package com.pocketfinancer.pipeline

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object SmsNotificationHelper {

    const val CHANNEL_ID = "sms_parsing_channel"
    private const val SUMMARY_NOTIFICATION_ID = 99999

    /**
     * Create the notification channel required for posting notifications on API 26+.
     */
    fun createNotificationChannel(context: Context) {
        val name = "SMS Transaction Sync"
        val descriptionText =
            "Displays stages of local transaction SMS parsing and sync alerts."
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Get a stable notification ID for one durable ingestion candidate.
     *
     * The opaque key includes connector provenance and the authoritative
     * provider message ID when available. Sender + timestamp is not sufficient:
     * legitimate provider rows may share both values.
     */
    fun getNotificationId(candidateKey: String): Int = candidateKey.hashCode()

    private fun getAppPendingIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        } ?: Intent()
        
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    private fun notifyIfPermitted(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        try {
            notificationManager.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked between the check and notify call.
        }
    }

    /**
     * Post a notification indicating that an SMS has been skipped because it is non-transactional.
     */
    fun showSkippedNotification(context: Context, candidateKey: String) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning) // fallback built-in icon
            .setContentTitle("SMS Skipped")
            .setContentText("This alert was not a supported transaction.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /**
     * Post/update an ongoing notification representing a stage of processing.
     */
    fun showProcessingNotification(
        context: Context,
        candidateKey: String,
        stageText: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Processing Transaction SMS")
            .setContentText(stageText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Cannot be swiped away
            .setAutoCancel(false)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /**
     * Replace an in-progress notification with a truthful prerequisite state.
     */
    fun showWaitingForModelNotification(
        context: Context,
        candidateKey: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Transaction alert saved securely")
            .setContentText(
                "Prepare the on-device model to process it. The alert will remain queued."
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /**
     * Replace an in-progress notification when a recoverable local error occurs.
     */
    fun showRetryNotification(
        context: Context,
        candidateKey: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Transaction alert queued")
            .setContentText("Local processing paused. Pocket Financer will retry.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /**
     * Post a notification indicating successful database sync.
     */
    fun showSuccessNotification(
        context: Context,
        candidateKey: String,
        amount: Double
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Transaction saved locally")
            .setContentText(
                "Saved a ₹${"%,.2f".format(amount)} transaction from this SMS."
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false) // Can be swiped away now
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /** Replace the in-progress notification after coordinator-owned persistence. */
    fun showSuccessNotification(
        context: Context,
        candidateKey: String,
        newlyInserted: Boolean
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                if (newlyInserted) "Transaction saved locally" else "Transaction already saved"
            )
            .setContentText(
                if (newlyInserted) {
                    "Saved a transaction from this SMS."
                } else {
                    "This SMS was already committed; no duplicate was created."
                }
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /**
     * Post a notification indicating processing failed.
     */
    fun showFailureNotification(
        context: Context,
        candidateKey: String,
        reason: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(candidateKey)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("SMS Sync Failed")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false) // Can be swiped away
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        notifyIfPermitted(context, notificationId, builder.build())
    }

    /**
     * Clear a processing notification when this worker no longer owns work.
     */
    fun cancelCandidateNotification(
        context: Context,
        candidateKey: String
    ) {
        try {
            NotificationManagerCompat.from(context).cancel(
                getNotificationId(candidateKey)
            )
        } catch (_: Exception) {
            // Notification cleanup must not change durable work settlement.
        }
    }

    /**
     * Removes the legacy heuristic backlog summary. Current automatic work
     * reports only the candidate it actually processed.
     */
    fun cancelLegacyUnsyncedSummaryNotification(context: Context) {
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } catch (e: Exception) {
            // Silently fail
        }
    }
}

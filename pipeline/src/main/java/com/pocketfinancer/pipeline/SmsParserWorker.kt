package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.repository.SmsIngestionRepository
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsScheduleResult
import com.pocketfinancer.sms.SmsWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MAX_OPERATIONAL_RETRY_ATTEMPTS = 3

/**
 * Processes one encrypted queued SMS candidate.
 *
 * WorkManager receives only [KEY_CANDIDATE_KEY]. Sender and body remain inside
 * the SQLCipher database until terminal rejection or until the transaction row
 * atomically takes ownership of that evidence.
 */
class SmsParserWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ParserWorkerEntryPoint {
        fun pipelineService(): PipelineService
        fun homeSyncDelegate(): HomeSyncDelegate
        fun smsIngestionRepository(): SmsIngestionRepository
        fun automaticProcessingPreferences(): AutomaticProcessingPreferences
        fun automaticSmsOperationGate(): AutomaticSmsOperationGate
        fun automaticSmsProcessingActivityStore():
            AutomaticSmsProcessingActivityStore
    }

    override suspend fun doWork(): Result {
        val candidateKey = inputData.getString(KEY_CANDIDATE_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.success()
        val claimToken = id.toString()

        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                ParserWorkerEntryPoint::class.java
            )
        } catch (error: Exception) {
            Log.e(TAG, "SMS worker dependency lookup failed", error)
            // No repository-backed settlement is possible without the graph.
            // Keep the encrypted evidence eligible for recovery after a
            // transient startup problem or a corrected app update.
            return Result.retry()
        }
        return protectSmsParserChain(
            onFailure = { error ->
                Log.e(
                    TAG,
                    "SMS worker could not establish durable candidate state",
                    error
                )
            },
            retryOrFinishChain = { Result.retry() }
        ) {
            processCandidateKey(
                entryPoint = entryPoint,
                candidateKey = candidateKey,
                claimToken = claimToken
            )
        }
    }

    private suspend fun processCandidateKey(
        entryPoint: ParserWorkerEntryPoint,
        candidateKey: String,
        claimToken: String
    ): Result {
        // Older builds posted an exact-looking backlog summary from a
        // truncated heuristic scan. It was neither complete nor proof that
        // those messages were transactions, so retire any surviving copy and
        // never recreate it from the automatic worker.
        SmsNotificationHelper.cancelLegacyUnsyncedSummaryNotification(
            applicationContext
        )
        val ingestionRepository = entryPoint.smsIngestionRepository()
        val candidate = ingestionRepository.get(candidateKey)
        if (candidate == null) {
            cancelMissingCandidateNotification {
                SmsNotificationHelper.cancelCandidateNotification(
                    applicationContext,
                    candidateKey
                )
            }
            return Result.success()
        }

        val appPreferences = applicationContext.getSharedPreferences(
            APP_SETTINGS,
            Context.MODE_PRIVATE
        )
        if (!isOnboardingCompleteForSmsWork(appPreferences)) {
            ingestionRepository.discardTerminal(candidateKey)
            return Result.success()
        }

        return entryPoint.automaticSmsOperationGate().withCandidate(
            origin = candidate.origin
        ) {
            processCandidateAfterAutomaticAdmission(
                entryPoint = entryPoint,
                appPreferences = appPreferences,
                candidate = candidate,
                claimToken = claimToken,
                ingestionRepository = ingestionRepository
            )
        }
    }

    private suspend fun processCandidateAfterAutomaticAdmission(
        entryPoint: ParserWorkerEntryPoint,
        appPreferences: SharedPreferences,
        candidate: QueuedSmsCandidate,
        claimToken: String,
        ingestionRepository: SmsIngestionRepository
    ): Result {
        // The candidate may have waited behind the one automatic operation
        // already running. Re-check the durable shell gate before it can claim.
        if (!isOnboardingCompleteForSmsWork(appPreferences)) {
            ingestionRepository.discardTerminal(candidate.candidateKey)
            return Result.success()
        }
        val automaticProcessingPreferences =
            entryPoint.automaticProcessingPreferences()
        val claimDecision = claimSmsCandidateForRun(
            candidate = candidate,
            claimToken = claimToken,
            automaticProcessingPreferences = automaticProcessingPreferences,
            ingestionRepository = ingestionRepository
        )
        if (claimDecision is SmsCandidateClaimDecision.AutomaticDisabled) {
            claimDecision.cancelDiscardedCandidateNotification(
                context = applicationContext,
                candidateKey = candidate.candidateKey
            )
            return Result.success()
        }
        val claimed =
            (claimDecision as SmsCandidateClaimDecision.Claimed).candidate
        if (claimed == null) {
            // Another run or a replacement owns the evidence. Retrying this
            // stale owner cannot make progress and can only create contention.
            return Result.success()
        }

        return withAutomaticSmsProcessingActivity(
            candidate = claimed,
            claimToken = claimToken,
            store = entryPoint.automaticSmsProcessingActivityStore()
        ) { automaticActivity ->
            try {
                processClaimedCandidate(
                    entryPoint = entryPoint,
                    preferences = appPreferences,
                    candidate = claimed,
                    claimToken = claimToken,
                    automaticProcessingPreferences =
                        automaticProcessingPreferences,
                    automaticActivity = automaticActivity
                )
            } catch (cancelled: CancellationException) {
                automaticActivity?.error(
                    "On-device processing was interrupted before completion."
                )
                rethrowAfterNonCancellableSettlement(cancelled) {
                    try {
                        val settlement = settleClaimedCandidateForRetry(
                            candidate = claimed,
                            claimToken = claimToken,
                            error = "Worker cancelled",
                            automaticProcessingPreferences =
                                automaticProcessingPreferences,
                            ingestionRepository = ingestionRepository
                        )
                        if (
                            settlement ==
                            SmsCandidateRetrySettlement.RELEASED_FOR_RETRY
                        ) {
                            SmsNotificationHelper.cancelCandidateNotification(
                                applicationContext,
                                claimed.candidateKey
                            )
                        } else {
                            cancelStaleTerminalNotificationIfCandidateAbsent(
                                ingestionRepository = ingestionRepository,
                                candidateKey = claimed.candidateKey
                            ) {
                                SmsNotificationHelper.cancelCandidateNotification(
                                    applicationContext,
                                    claimed.candidateKey
                                )
                            }
                        }
                    } catch (settlementFailure: Throwable) {
                        try {
                            cancelNotificationAfterRetrySettlementFailure(
                                ingestionRepository = ingestionRepository,
                                candidateKey = claimed.candidateKey,
                                claimToken = claimToken
                            ) {
                                SmsNotificationHelper.cancelCandidateNotification(
                                    applicationContext,
                                    claimed.candidateKey
                                )
                            }
                        } catch (cleanupFailure: Throwable) {
                            settlementFailure.addSuppressed(cleanupFailure)
                        }
                        throw settlementFailure
                    }
                }
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Encrypted SMS candidate processing failed: " +
                        error.javaClass.simpleName
                )
                retryClaimedOrDiscard(
                    ingestionRepository = ingestionRepository,
                    candidate = claimed,
                    claimToken = claimToken,
                    error = error.javaClass.simpleName,
                    automaticProcessingPreferences =
                        automaticProcessingPreferences,
                    automaticActivity = automaticActivity
                )
            }
        }
    }

    private suspend fun processClaimedCandidate(
        entryPoint: ParserWorkerEntryPoint,
        preferences: SharedPreferences,
        candidate: QueuedSmsCandidate,
        claimToken: String,
        automaticProcessingPreferences: AutomaticProcessingPreferences,
        automaticActivity: AutomaticSmsProcessingSession?
    ): Result {
        val ingestionRepository = entryPoint.smsIngestionRepository()
        val flowLease = entryPoint.homeSyncDelegate().tryEnterSmsWorkerFlow()
            ?: return retryClaimedOrDiscard(
                ingestionRepository = ingestionRepository,
                candidate = candidate,
                claimToken = claimToken,
                error = "Financial data maintenance owns admission",
                automaticProcessingPreferences = automaticProcessingPreferences,
                automaticActivity = automaticActivity
            )
        return try {
            processDirectlyInBackground(
                entryPoint = entryPoint,
                preferences = preferences,
                candidate = candidate,
                claimToken = claimToken,
                automaticProcessingPreferences = automaticProcessingPreferences,
                automaticActivity = automaticActivity
            )
        } finally {
            withContext(NonCancellable) { flowLease.release() }
        }
    }

    private suspend fun processDirectlyInBackground(
        entryPoint: ParserWorkerEntryPoint,
        preferences: SharedPreferences,
        candidate: QueuedSmsCandidate,
        claimToken: String,
        automaticProcessingPreferences: AutomaticProcessingPreferences,
        automaticActivity: AutomaticSmsProcessingSession?
    ): Result {
        val ingestionRepository = entryPoint.smsIngestionRepository()
        val sms = SmsReader.SmsMessage(
            address = candidate.sender,
            body = candidate.rawMessage,
            date = candidate.date,
            type = candidate.messageType,
            providerMessageId = candidate.sourceIdentity.providerMessageId,
            sourceTimestamp = candidate.sourceTimestamp
        )
        automaticActivity?.filtering()

        automaticActivity?.loadingModel()
        SmsNotificationHelper.showProcessingNotification(
            applicationContext,
            candidate.candidateKey,
            "Preparing on-device processing..."
        )

        // Reset may commit while the worker is waiting for flow admission.
        // Both the preference and durable claim must still be valid before
        // the coordinator can make a terminal storage decision.
        if (!isOnboardingCompleteForSmsWork(preferences)) {
            SmsNotificationHelper.cancelCandidateNotification(
                applicationContext,
                candidate.candidateKey
            )
            return Result.success()
        }
        if (!ingestionRepository.isClaimOwned(candidate.candidateKey, claimToken)) {
            automaticActivity?.error(
                "This automatic processing claim is no longer current."
            )
            cancelStaleTerminalNotificationIfCandidateAbsent(
                ingestionRepository = ingestionRepository,
                candidateKey = candidate.candidateKey
            ) {
                SmsNotificationHelper.cancelCandidateNotification(
                    applicationContext,
                    candidate.candidateKey
                )
            }
            return Result.success()
        }

        SmsNotificationHelper.showProcessingNotification(
            applicationContext,
            candidate.candidateKey,
            "Analyzing transaction content..."
        )
        return when (
            val processing = entryPoint.pipelineService()
                .processSingle(
                    sms = sms,
                    observer = automaticActivity,
                    trigger = "realtime"
                )
        ) {
                is PipelineService.ProcessingResult.Saved -> {
                    val newlyInserted = !processing.alreadyCommitted
                    automaticActivity?.saved(newlyInserted)
                    val discarded = discardOwnedTerminalCandidate(
                        ingestionRepository = ingestionRepository,
                        candidateKey = candidate.candidateKey,
                        claimToken = claimToken
                    )
                    applyExactTerminalNotification(
                        settledOwnedClaim = discarded,
                        onOwned = {
                            SmsNotificationHelper.showSuccessNotification(
                                applicationContext,
                                candidate.candidateKey,
                                newlyInserted
                            )
                        },
                        onStale = {
                            cancelStaleTerminalNotificationIfCandidateAbsent(
                                ingestionRepository = ingestionRepository,
                                candidateKey = candidate.candidateKey
                            ) {
                                SmsNotificationHelper.cancelCandidateNotification(
                                    applicationContext,
                                    candidate.candidateKey
                                )
                            }
                        }
                    )
                    Result.success()
                }

                is PipelineService.ProcessingResult.Skipped -> {
                    if (processing.reason == PipelineService.SkipReason.RETAINED_FOR_REVIEW) {
                        automaticActivity?.error("Saved locally for review; no transaction was added.")
                    } else {
                        automaticActivity?.filteredOut()
                    }
                    val discarded = discardOwnedTerminalCandidate(
                        ingestionRepository = ingestionRepository,
                        candidateKey = candidate.candidateKey,
                        claimToken = claimToken
                    )
                    applyExactTerminalNotification(
                        settledOwnedClaim = discarded,
                        onOwned = {
                            if (processing.reason == PipelineService.SkipReason.RETAINED_FOR_REVIEW) {
                                SmsNotificationHelper.showFailureNotification(
                                    applicationContext,
                                    candidate.candidateKey,
                                    "Saved locally for review; no transaction was added."
                                )
                            } else {
                                SmsNotificationHelper.showSkippedNotification(
                                    applicationContext,
                                    candidate.candidateKey
                                )
                            }
                        },
                        onStale = {
                            cancelStaleTerminalNotificationIfCandidateAbsent(
                                ingestionRepository = ingestionRepository,
                                candidateKey = candidate.candidateKey
                            ) {
                                SmsNotificationHelper.cancelCandidateNotification(
                                    applicationContext,
                                    candidate.candidateKey
                                )
                            }
                        }
                    )
                    Result.success()
                }

                is PipelineService.ProcessingResult.Stopped ->
                    retryClaimedOrDiscard(
                        ingestionRepository,
                        candidate,
                        claimToken,
                        "On-device inference stopped",
                        automaticProcessingPreferences =
                            automaticProcessingPreferences,
                        automaticActivity = automaticActivity
                    )

                is PipelineService.ProcessingResult.AwaitingConfiguration -> {
                    automaticActivity?.error(
                        "Choose a primary currency in Settings before saved alerts are analyzed."
                    )
                    settleClaimedCandidateForRetry(
                        candidate = candidate,
                        claimToken = claimToken,
                        error = "Awaiting primary currency confirmation",
                        automaticProcessingPreferences = automaticProcessingPreferences,
                        ingestionRepository = ingestionRepository
                    )
                    SmsNotificationHelper.showFailureNotification(
                        applicationContext,
                        candidate.candidateKey,
                        "Choose a primary currency in Pocket Financer Settings."
                    )
                    Result.success()
                }

                is PipelineService.ProcessingResult.Failure ->
                    if (processing.retryable) {
                        retryClaimedOrDiscard(
                            ingestionRepository,
                            candidate,
                            claimToken,
                            "On-device extraction failed",
                            automaticProcessingPreferences =
                                automaticProcessingPreferences,
                            automaticActivity = automaticActivity
                        )
                    } else {
                        automaticActivity?.error(
                            "This alert could not be safely interpreted."
                        )
                        val discarded = discardOwnedTerminalCandidate(
                            ingestionRepository = ingestionRepository,
                            candidateKey = candidate.candidateKey,
                            claimToken = claimToken
                        )
                        if (discarded) {
                            SmsNotificationHelper.showFailureNotification(
                                applicationContext,
                                candidate.candidateKey,
                                "This alert could not be safely interpreted."
                            )
                        } else {
                            cancelStaleTerminalNotificationIfCandidateAbsent(
                                ingestionRepository = ingestionRepository,
                                candidateKey = candidate.candidateKey
                            ) {
                                SmsNotificationHelper.cancelCandidateNotification(
                                    applicationContext,
                                    candidate.candidateKey
                                )
                            }
                        }
                        Result.success()
                    }
        }
    }

    private suspend fun retryClaimedOrDiscard(
        ingestionRepository: SmsIngestionRepository,
        candidate: QueuedSmsCandidate,
        claimToken: String,
        error: String,
        automaticProcessingPreferences: AutomaticProcessingPreferences,
        retryMode: SmsCandidateRetryMode =
            SmsCandidateRetryMode.BOUNDED_OPERATIONAL,
        automaticActivity: AutomaticSmsProcessingSession? = null
    ): Result {
        if (!hasRetryBudget(retryMode, runAttemptCount)) {
            automaticActivity?.error(
                "Processing paused; the encrypted alert was preserved for recovery."
            )
            val preserved = ingestionRepository.releaseForRetry(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken,
                error = error
            )
            if (preserved) {
                SmsNotificationHelper.showFailureNotification(
                    applicationContext,
                    candidate.candidateKey,
                    "Processing paused. The alert remains encrypted for recovery."
                )
            } else {
                cancelStaleTerminalNotificationIfCandidateAbsent(
                    ingestionRepository = ingestionRepository,
                    candidateKey = candidate.candidateKey
                ) {
                    SmsNotificationHelper.cancelCandidateNotification(
                        applicationContext,
                        candidate.candidateKey
                    )
                }
            }
            return Result.success()
        }

        automaticActivity?.retrying(
            if (retryMode == SmsCandidateRetryMode.UNTIL_MODEL_PREPARED) {
                "Waiting for the on-device model to be prepared."
            } else {
                "On-device processing will be retried."
            }
        )

        val settlement = try {
            settleClaimedCandidateForRetry(
                candidate = candidate,
                claimToken = claimToken,
                error = error,
                automaticProcessingPreferences =
                    automaticProcessingPreferences,
                ingestionRepository = ingestionRepository,
                onReleasedForRetry = {
                    runCatching {
                        if (
                            retryMode ==
                            SmsCandidateRetryMode.UNTIL_MODEL_PREPARED
                        ) {
                            SmsNotificationHelper
                                .showWaitingForModelNotification(
                                    applicationContext,
                                    candidate.candidateKey
                                )
                        } else {
                            SmsNotificationHelper.showRetryNotification(
                                applicationContext,
                                candidate.candidateKey
                            )
                        }
                    }
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (settlementFailure: Exception) {
            automaticActivity?.error(
                "Could not safely schedule the next processing attempt."
            )
            try {
                withContext(NonCancellable) {
                    cancelNotificationAfterRetrySettlementFailure(
                        ingestionRepository = ingestionRepository,
                        candidateKey = candidate.candidateKey,
                        claimToken = claimToken
                    ) {
                        SmsNotificationHelper.cancelCandidateNotification(
                            applicationContext,
                            candidate.candidateKey
                        )
                    }
                }
            } catch (cleanupFailure: Throwable) {
                settlementFailure.addSuppressed(cleanupFailure)
            }
            throw settlementFailure
        }

        return when (settlement) {
            SmsCandidateRetrySettlement.RELEASED_FOR_RETRY -> Result.retry()

            SmsCandidateRetrySettlement.FINISHED -> {
                automaticActivity?.error(
                    "Automatic processing is no longer scheduled for this alert."
                )
                cancelStaleTerminalNotificationIfCandidateAbsent(
                    ingestionRepository = ingestionRepository,
                    candidateKey = candidate.candidateKey
                ) {
                    SmsNotificationHelper.cancelCandidateNotification(
                        applicationContext,
                        candidate.candidateKey
                    )
                }
                Result.success()
            }
        }
    }

    internal companion object {
        const val TAG = "SmsParserWorker"
        const val KEY_CANDIDATE_KEY = "candidate_key"
        const val WORK_TAG = "sms-parser-candidates"
        private const val APP_SETTINGS = ".app_settings"

        fun uniqueWorkName(candidateKey: String): String =
            "sms-parser-$candidateKey"
    }
}

/**
 * Process-wide boundary between durable pending work and the single automatic
 * SMS that is allowed to snapshot ON.
 *
 * WorkManager may execute different unique WorkSpecs concurrently. Keeping
 * waiters outside the claim means disabling automatic updates can delete them
 * while the one admitted candidate finishes. Manual candidates bypass this
 * gate and remain independently available.
 */
@Singleton
class AutomaticSmsOperationGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withCandidate(
        origin: SmsCandidateOrigin,
        block: suspend () -> T
    ): T =
        if (origin == SmsCandidateOrigin.AUTOMATIC) {
            mutex.withLock { block() }
        } else {
            block()
        }
}

/**
 * Encrypted admission followed by an opaque, idempotent WorkManager handoff.
 */
@Singleton
class SmsWorkSchedulerImpl @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val admissionGate: SmsWorkAdmissionGate,
    private val ingestionRepository: SmsIngestionRepository,
    private val automaticProcessingPreferences: AutomaticProcessingPreferences
) : SmsWorkScheduler {
    override suspend fun scheduleSmsParsing(
        message: SmsReader.SmsMessage
    ): SmsScheduleResult =
        automaticProcessingPreferences.withConsistencyBoundary { enabled ->
            if (!enabled) {
                discardPendingAutomaticCandidatesAndNotifications(
                    context = context,
                    admissionGate = admissionGate,
                    ingestionRepository = ingestionRepository
                )
                return@withConsistencyBoundary SmsScheduleResult.AUTOMATIC_DISABLED
            }

            val admission = ingestionRepository.admit(
                SmsIngestionRepository.NewCandidate(
                    sourceIdentity = message.sourceIdentity,
                    sender = message.address,
                    rawMessage = message.body,
                    date = message.date,
                    sourceTimestamp = message.sourceTimestamp,
                    messageType = message.type,
                    origin = SmsCandidateOrigin.AUTOMATIC
                )
            )
            if (admission is SmsIngestionRepository.AdmissionResult.AlreadySaved) {
                return@withConsistencyBoundary SmsScheduleResult.ALREADY_SAVED
            }

            val candidateKey = admission.candidateKey
            if (!enqueueCandidate(candidateKey)) {
                discardPendingAutomaticCandidatesAndNotifications(
                    context = context,
                    admissionGate = admissionGate,
                    ingestionRepository = ingestionRepository
                )
                return@withConsistencyBoundary SmsScheduleResult.ADMISSION_PAUSED
            }
            SmsScheduleResult.SCHEDULED
        }

    override suspend fun reconcilePendingAutomaticWork() {
        val candidateKeys =
            automaticProcessingPreferences.withConsistencyBoundary { enabled ->
                if (!enabled) {
                    discardPendingAutomaticCandidatesAndNotifications(
                        context = context,
                        admissionGate = admissionGate,
                        ingestionRepository = ingestionRepository
                    )
                    emptyList()
                } else {
                    ingestionRepository.pendingAutomaticCandidateKeys()
                }
            }
        if (candidateKeys.isEmpty()) {
            return
        }

        for (candidateKey in candidateKeys) {
            val shouldContinue =
                automaticProcessingPreferences.withConsistencyBoundary { enabled ->
                    if (!enabled) {
                        discardPendingAutomaticCandidatesAndNotifications(
                            context = context,
                            admissionGate = admissionGate,
                            ingestionRepository = ingestionRepository
                        )
                        false
                    } else {
                        // A destructive maintenance boundary owns admission.
                        // The encrypted row remains durable for the next
                        // reconciliation.
                        enqueueCandidate(candidateKey)
                    }
                }
            if (!shouldContinue) {
                return
            }
        }
    }

    private fun enqueueCandidate(candidateKey: String): Boolean {
        val request = OneTimeWorkRequestBuilder<SmsParserWorker>()
            .setInputData(
                workDataOf(SmsParserWorker.KEY_CANDIDATE_KEY to candidateKey)
            )
            .addTag(SmsParserWorker.WORK_TAG)
            .build()
        val admitted = admissionGate.enqueueIfOpen {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SmsParserWorker.uniqueWorkName(candidateKey),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
        return admitted
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsPipelineModule {
    @Binds
    @Singleton
    abstract fun bindSmsWorkScheduler(impl: SmsWorkSchedulerImpl): SmsWorkScheduler

    @Binds
    @Singleton
    abstract fun bindSmsWorkController(
        impl: WorkManagerSmsWorkController
    ): SmsWorkController
}

internal fun isOnboardingCompleteForSmsWork(
    preferences: SharedPreferences
): Boolean = preferences.getBoolean("onboarding_completed", false)

internal sealed interface SmsCandidateClaimDecision {
    data class AutomaticDisabled(
        val discardedCandidate: Boolean
    ) : SmsCandidateClaimDecision

    data class Claimed(
        val candidate: QueuedSmsCandidate?
    ) : SmsCandidateClaimDecision
}

internal suspend fun claimSmsCandidateForRun(
    candidate: QueuedSmsCandidate,
    claimToken: String,
    automaticProcessingPreferences: AutomaticProcessingPreferences,
    ingestionRepository: SmsIngestionRepository
): SmsCandidateClaimDecision {
    if (candidate.origin != SmsCandidateOrigin.AUTOMATIC) {
        return SmsCandidateClaimDecision.Claimed(
            ingestionRepository.claim(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
        )
    }

    return automaticProcessingPreferences.withConsistencyBoundary { enabled ->
        if (!enabled) {
            // OFF won before this invocation could establish a claim. Pending
            // evidence, or a same-WorkSpec claim left by process death, can be
            // removed without touching a replacement owner's claim.
            val discardedCandidate = ingestionRepository.discardAutomaticBeforeClaim(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
            SmsCandidateClaimDecision.AutomaticDisabled(
                discardedCandidate = discardedCandidate
            )
        } else {
            SmsCandidateClaimDecision.Claimed(
                ingestionRepository.claim(
                    candidateKey = candidate.candidateKey,
                    claimToken = claimToken
                )
            )
        }
    }
}

internal fun SmsCandidateClaimDecision.AutomaticDisabled
    .cancelDiscardedCandidateNotification(
        context: Context,
        candidateKey: String
    ) {
        if (discardedCandidate) {
            SmsNotificationHelper.cancelCandidateNotification(
                context = context,
                candidateKey = candidateKey
            )
        }
    }

internal suspend fun discardOwnedTerminalCandidate(
    ingestionRepository: SmsIngestionRepository,
    candidateKey: String,
    claimToken: String
): Boolean = ingestionRepository.discardClaimed(
    candidateKey = candidateKey,
    claimToken = claimToken
)

/** Posts terminal copy only when exact durable settlement still owned it. */
internal suspend fun applyExactTerminalNotification(
    settledOwnedClaim: Boolean,
    onOwned: suspend () -> Unit,
    onStale: suspend () -> Unit
) {
    if (settledOwnedClaim) {
        onOwned()
    } else {
        onStale()
    }
}

/**
 * Candidate-key notifications are shared by successive claim tokens. A stale
 * owner may cancel only after durable evidence is absent; any existing row can
 * already belong to a replacement or pending retry whose notification must be
 * preserved. Repository lookup failure therefore conservatively preserves it.
 */
internal suspend fun cancelStaleTerminalNotificationIfCandidateAbsent(
    ingestionRepository: SmsIngestionRepository,
    candidateKey: String,
    cancel: () -> Unit
): Boolean {
    val currentCandidate = try {
        ingestionRepository.get(candidateKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(
            SmsParserWorker.TAG,
            "Could not verify candidate notification ownership",
            error
        )
        return false
    }
    if (currentCandidate != null) return false
    cancel()
    return true
}

/**
 * A retry-settlement exception leaves the durable outcome uncertain. Cancel an
 * old ongoing notification only when the row vanished or still carries this
 * exact claim. A different claim and an unclaimed pending row can already be a
 * successor, so both are preserved. Lookup failure is likewise conservative.
 */
internal suspend fun cancelNotificationAfterRetrySettlementFailure(
    ingestionRepository: SmsIngestionRepository,
    candidateKey: String,
    claimToken: String,
    cancel: () -> Unit
): Boolean {
    val currentCandidate = try {
        ingestionRepository.get(candidateKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(
            SmsParserWorker.TAG,
            "Could not verify candidate notification ownership",
            error
        )
        return false
    }
    if (
        currentCandidate != null &&
        currentCandidate.claimToken != claimToken
    ) {
        return false
    }
    cancel()
    return true
}

/**
 * Settlement failures must not strand an ongoing notification. Cleanup runs
 * outside cancellation, while the original settlement failure remains the
 * observable error and any cleanup failure is retained only as suppressed.
 */
internal suspend fun <T> withNotificationCleanupOnSettlementFailure(
    settle: suspend () -> T,
    cleanup: suspend () -> Unit
): T = try {
    settle()
} catch (settlementFailure: Throwable) {
    try {
        withContext(NonCancellable) {
            cleanup()
        }
    } catch (cleanupFailure: Throwable) {
        settlementFailure.addSuppressed(cleanupFailure)
    }
    throw settlementFailure
}

/** Process-death recovery for work whose durable evidence is already gone. */
internal fun cancelMissingCandidateNotification(cancel: () -> Unit) {
    cancel()
}

internal enum class SmsCandidateRetryMode {
    BOUNDED_OPERATIONAL,
    UNTIL_MODEL_PREPARED
}

internal enum class SmsCandidateRetrySettlement {
    RELEASED_FOR_RETRY,
    FINISHED
}

internal fun hasRetryBudget(
    mode: SmsCandidateRetryMode,
    runAttemptCount: Int
): Boolean =
    mode == SmsCandidateRetryMode.UNTIL_MODEL_PREPARED ||
        runAttemptCount < MAX_OPERATIONAL_RETRY_ATTEMPTS

/**
 * Settles a claimed candidate without reopening automatic intake after OFF.
 *
 * Automatic retry settlement shares the preference boundary with admission
 * and disable cleanup. If OFF wins, this worker deletes only the claim it owns.
 * If retry release wins, a following disable cleanup observes and removes the
 * newly pending row. A stale owner always finishes instead of retrying.
 */
internal suspend fun settleClaimedCandidateForRetry(
    candidate: QueuedSmsCandidate,
    claimToken: String,
    error: String,
    automaticProcessingPreferences: AutomaticProcessingPreferences,
    ingestionRepository: SmsIngestionRepository,
    onReleasedForRetry: suspend () -> Unit = {}
): SmsCandidateRetrySettlement {
    suspend fun settleWhileEnabled(): SmsCandidateRetrySettlement =
        if (
            ingestionRepository.releaseForRetry(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken,
                error = error
            )
        ) {
            onReleasedForRetry()
            SmsCandidateRetrySettlement.RELEASED_FOR_RETRY
        } else {
            SmsCandidateRetrySettlement.FINISHED
        }

    if (candidate.origin != SmsCandidateOrigin.AUTOMATIC) {
        return settleWhileEnabled()
    }

    return automaticProcessingPreferences.withConsistencyBoundary { enabled ->
        if (enabled) {
            settleWhileEnabled()
        } else {
            ingestionRepository.discardClaimed(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
            SmsCandidateRetrySettlement.FINISHED
        }
    }
}

/**
 * Cancellation must not strand a claim simply because its coroutine was
 * cancelled. Settlement failures are attached for diagnostics, while the
 * original cancellation remains the result observed by WorkManager.
 */
internal suspend fun rethrowAfterNonCancellableSettlement(
    cancelled: CancellationException,
    settle: suspend () -> Unit
): Nothing {
    var settlementFailure: Throwable? = null
    withContext(NonCancellable) {
        try {
            settle()
        } catch (failure: Throwable) {
            settlementFailure = failure
        }
    }
    settlementFailure?.let(cancelled::addSuppressed)
    throw cancelled
}

internal suspend fun protectSmsParserChain(
    onFailure: (Exception) -> Unit,
    retryOrFinishChain: () -> androidx.work.ListenableWorker.Result,
    block: suspend () -> androidx.work.ListenableWorker.Result
): androidx.work.ListenableWorker.Result = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    runCatching { onFailure(error) }
    retryOrFinishChain()
}

package com.pocketfinancer.data.repository

import androidx.room.withTransaction
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.QueuedSmsCandidateDao
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.QueuedSmsCandidateEntity
import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/**
 * Owns the encrypted handoff between SMS admission, WorkManager, and ledger
 * persistence. All cross-table source checks execute in one Room transaction.
 */
@Singleton
class SmsIngestionRepository @Inject constructor(
    private val appDatabase: AppDatabase,
    private val transactionDao: TransactionDao,
    private val candidateDao: QueuedSmsCandidateDao,
    private val processingDao: SmsProcessingDao
) {
    sealed interface AdmissionResult {
        val candidateKey: String

        data class Admitted(
            override val candidateKey: String,
            val newlyCreated: Boolean
        ) : AdmissionResult

        data class AlreadySaved(
            val transactionId: String,
            override val candidateKey: String
        ) : AdmissionResult
    }

    data class NewCandidate(
        val sourceIdentity: SmsSourceIdentity,
        val sender: String,
        val rawMessage: String,
        val date: Long,
        val sourceTimestamp: Long = date,
        val messageType: Int,
        val origin: SmsCandidateOrigin
    )

    suspend fun admit(candidate: NewCandidate): AdmissionResult =
        appDatabase.withTransaction {
            val source = candidate.sourceIdentity
            val now = System.currentTimeMillis()
            processingDao.insertSource(
                AdmittedSmsSourceEntity(
                    id = source.opaqueCandidateKey,
                    sourceConnector = source.connector,
                    sourceMessageId = source.messageId,
                    sourceProviderMessageId = source.providerMessageId,
                    sourceFingerprint = source.fallbackFingerprint,
                    sourceAlternateFingerprint = source.alternateFingerprint,
                    sender = candidate.sender,
                    rawMessage = candidate.rawMessage,
                    sourceTimestamp = candidate.sourceTimestamp,
                    messageType = candidate.messageType,
                    origin = candidate.origin.persistedValue,
                    admissionReceiptId = UUID.nameUUIDFromBytes(
                        source.opaqueCandidateKey.toByteArray(Charsets.UTF_8)
                    ).toString(),
                    admittedAt = now,
                    retentionState = "admitted"
                )
            )
            val existingTransaction = transactionDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )
            if (existingTransaction != null) {
                transactionDao.preserveSourceMetadata(
                    transactionId = existingTransaction.id,
                    messageId = source.messageId,
                    providerMessageId = source.providerMessageId,
                    fingerprint = source.fallbackFingerprint,
                    alternateFingerprint = source.alternateFingerprint,
                    receivedDate = candidate.date
                )
                candidateDao.findBySource(
                    connector = source.connector,
                    messageId = source.messageId,
                    providerMessageId = source.providerMessageId,
                    fingerprint = source.fallbackFingerprint,
                    alternateFingerprint = source.alternateFingerprint
                )?.let { queued ->
                    candidateDao.deleteByKey(queued.candidateKey)
                }
                return@withTransaction AdmissionResult.AlreadySaved(
                    transactionId = existingTransaction.id,
                    candidateKey = source.opaqueCandidateKey
                )
            }

            val existingCandidate = candidateDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )
            if (existingCandidate != null) {
                candidateDao.preserveSourceMetadata(
                    candidateKey = existingCandidate.candidateKey,
                    messageId = source.messageId,
                    providerMessageId = source.providerMessageId,
                    fingerprint = source.fallbackFingerprint,
                    alternateFingerprint = source.alternateFingerprint,
                    date = candidate.date,
                    sourceTimestamp = candidate.sourceTimestamp,
                    updatedAt = now
                )
                return@withTransaction AdmissionResult.Admitted(
                    candidateKey = existingCandidate.candidateKey,
                    newlyCreated = false
                )
            }

            val entity = QueuedSmsCandidateEntity(
                candidateKey = source.opaqueCandidateKey,
                sourceConnector = source.connector,
                sourceMessageId = source.messageId,
                sourceFingerprint = source.fallbackFingerprint,
                sourceAlternateFingerprint = source.alternateFingerprint,
                sourceProviderMessageId = source.providerMessageId,
                sender = candidate.sender,
                rawMessage = candidate.rawMessage,
                date = candidate.date,
                sourceTimestamp = candidate.sourceTimestamp,
                messageType = candidate.messageType,
                origin = candidate.origin.persistedValue,
                createdAt = now,
                updatedAt = now
            )
            val rowId = candidateDao.insertIgnore(entity)
            if (rowId != -1L) {
                return@withTransaction AdmissionResult.Admitted(
                    candidateKey = entity.candidateKey,
                    newlyCreated = true
                )
            }

            val conflictingCandidate = candidateDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            ) ?: error(
                "Candidate source conflict did not resolve to a durable candidate"
            )
            candidateDao.preserveSourceMetadata(
                candidateKey = conflictingCandidate.candidateKey,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint,
                date = candidate.date,
                sourceTimestamp = candidate.sourceTimestamp,
                updatedAt = now
            )
            AdmissionResult.Admitted(
                candidateKey = conflictingCandidate.candidateKey,
                newlyCreated = false
            )
        }

    suspend fun get(candidateKey: String): QueuedSmsCandidate? =
        candidateDao.getByKey(candidateKey)?.toDomain()

    /**
     * Claims a candidate for one WorkManager run. The same work id may reclaim
     * after retry, and another run may recover a stale process-death claim.
     */
    suspend fun claim(
        candidateKey: String,
        claimToken: String,
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = DEFAULT_STALE_CLAIM_MS
    ): QueuedSmsCandidate? = appDatabase.withTransaction {
        val updated = candidateDao.claim(
            candidateKey = candidateKey,
            claimToken = claimToken,
            claimedAt = now,
            staleBefore = now - staleAfterMs
        )
        if (updated == 0) {
            null
        } else {
            candidateDao.getByKey(candidateKey)?.toDomain()
        }
    }

    suspend fun releaseForRetry(
        candidateKey: String,
        claimToken: String,
        error: String?
    ): Boolean = candidateDao.releaseForRetry(
        candidateKey = candidateKey,
        claimToken = claimToken,
        lastError = error?.take(MAX_ERROR_LENGTH),
        updatedAt = System.currentTimeMillis()
    ) > 0

    suspend fun isClaimOwned(candidateKey: String, claimToken: String): Boolean =
        candidateDao.isClaimOwned(candidateKey, claimToken)

    suspend fun discardClaimed(
        candidateKey: String,
        claimToken: String
    ): Boolean = candidateDao.deleteClaimed(
        candidateKey = candidateKey,
        claimToken = claimToken
    ) > 0

    suspend fun discardTerminal(candidateKey: String): Boolean =
        candidateDao.deleteByKey(candidateKey) > 0

    /**
     * Disabling automatic processing removes only work that has not started.
     * Claimed rows are the explicit per-operation snapshot and finish normally.
     */
    suspend fun discardPendingAutomatic(): Int =
        candidateDao.deletePendingAutomatic()

    suspend fun discardAutomaticBeforeClaim(
        candidateKey: String,
        claimToken: String
    ): Boolean = candidateDao.deleteAutomaticBeforeClaim(
        candidateKey = candidateKey,
        claimToken = claimToken
    ) > 0

    suspend fun pendingAutomaticCandidateKeys(): List<String> =
        candidateDao.getPendingAutomaticKeys()

    suspend fun pendingAutomaticCandidates(): List<QueuedSmsCandidate> =
        candidateDao.getPendingAutomaticCandidates().map { it.toDomain() }

    suspend fun pendingCount(): Int = candidateDao.count()

    private fun QueuedSmsCandidateEntity.toDomain(): QueuedSmsCandidate =
        QueuedSmsCandidate(
            candidateKey = candidateKey,
            sourceIdentity = SmsSourceIdentity(
                connector = sourceConnector,
                messageId = sourceMessageId,
                fallbackFingerprint = sourceFingerprint,
                providerMessageId = sourceProviderMessageId,
                alternateFingerprint = sourceAlternateFingerprint
            ),
            sender = sender,
            rawMessage = rawMessage,
            date = date,
            sourceTimestamp = sourceTimestamp,
            messageType = messageType,
            origin = SmsCandidateOrigin.fromPersisted(origin),
            claimToken = claimToken,
            attemptCount = attemptCount
        )

    companion object {
        const val DEFAULT_STALE_CLAIM_MS = 15 * 60 * 1_000L
        private const val MAX_ERROR_LENGTH = 512
    }
}

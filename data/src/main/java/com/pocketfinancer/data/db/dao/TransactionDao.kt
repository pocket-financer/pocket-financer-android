package com.pocketfinancer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pocketfinancer.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    /**
     * Compatibility name retained for existing callers. Inserts never replace
     * an already-owned source or its raw evidence.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity): Int

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllByDateDesc(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE date >= :startMs AND date <= :endMs ORDER BY date DESC")
    fun getByDateRange(startMs: Long, endMs: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY date DESC")
    fun getByType(type: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY date DESC")
    fun getByAccount(accountId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT SUM(amount) FROM transactions WHERE type = :type AND date >= :sinceMs")
    suspend fun sumByTypeSince(type: String, sinceMs: Long): Double?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE sourceId = :sourceId AND sourceEventId = :eventId LIMIT 1")
    suspend fun getBySourceEvent(sourceId: String, eventId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE sourceId = :sourceId ORDER BY date, id")
    suspend fun getAllBySource(sourceId: String): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE sourceConnector = :connector
          AND (
            sourceMessageId = :messageId
            OR (
                :providerMessageId IS NOT NULL
                AND sourceProviderMessageId = :providerMessageId
            )
            OR (
                (
                    :providerMessageId IS NULL
                    OR sourceProviderMessageId IS NULL
                )
                AND (
                    sourceFingerprint = :fingerprint
                    OR sourceAlternateFingerprint = :fingerprint
                    OR (
                        :alternateFingerprint IS NOT NULL
                        AND (
                            sourceFingerprint = :alternateFingerprint
                            OR sourceAlternateFingerprint =
                                :alternateFingerprint
                        )
                    )
                )
            )
          )
        ORDER BY
            CASE
                WHEN sourceMessageId = :messageId THEN 0
                WHEN :providerMessageId IS NOT NULL
                    AND sourceProviderMessageId = :providerMessageId THEN 1
                WHEN sourceProviderMessageId IS NULL THEN 2
                ELSE 3
            END,
            createdAt ASC,
            id ASC
        LIMIT 1
        """
    )
    suspend fun findBySource(
        connector: String,
        messageId: String,
        providerMessageId: String?,
        fingerprint: String,
        alternateFingerprint: String?
    ): TransactionEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM transactions
            WHERE sourceConnector = :connector
              AND (
                sourceMessageId = :messageId
                OR (
                    :providerMessageId IS NOT NULL
                    AND sourceProviderMessageId = :providerMessageId
                )
                OR (
                    (
                        :providerMessageId IS NULL
                        OR sourceProviderMessageId IS NULL
                    )
                    AND (
                        sourceFingerprint = :fingerprint
                        OR sourceAlternateFingerprint = :fingerprint
                        OR (
                            :alternateFingerprint IS NOT NULL
                            AND (
                                sourceFingerprint = :alternateFingerprint
                                OR sourceAlternateFingerprint =
                                    :alternateFingerprint
                            )
                        )
                    )
                )
              )
        )
        """
    )
    suspend fun existsBySource(
        connector: String,
        messageId: String,
        providerMessageId: String?,
        fingerprint: String,
        alternateFingerprint: String?
    ): Boolean

    @Query(
        """
        UPDATE transactions
        SET sourceMessageId =
                CASE
                    WHEN :providerMessageId IS NOT NULL
                        AND (
                            sourceProviderMessageId IS NULL
                            OR sourceProviderMessageId = :providerMessageId
                        )
                        THEN :messageId
                    ELSE sourceMessageId
                END,
            sourceProviderMessageId =
                COALESCE(sourceProviderMessageId, :providerMessageId),
            sourceAlternateFingerprint =
                COALESCE(
                    sourceAlternateFingerprint,
                    CASE
                        WHEN sourceFingerprint != :fingerprint
                            THEN :fingerprint
                        WHEN :alternateFingerprint IS NOT NULL
                            AND sourceFingerprint != :alternateFingerprint
                            THEN :alternateFingerprint
                        ELSE NULL
                    END
                ),
            date = CASE
                WHEN sourceProviderMessageId IS NULL
                    AND :providerMessageId IS NOT NULL
                    AND :receivedDate IS NOT NULL
                    THEN :receivedDate
                ELSE date
            END
        WHERE id = :transactionId
        """
    )
    suspend fun preserveSourceMetadata(
        transactionId: String,
        messageId: String,
        providerMessageId: String?,
        fingerprint: String,
        alternateFingerprint: String?,
        receivedDate: Long?
    )

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE sender = :sender AND date = :date)")
    suspend fun exists(sender: String, date: Long): Boolean

    @Query("UPDATE transactions SET accountId = :newAccountId WHERE accountId = :oldAccountId")
    suspend fun updateTransactionsAccount(oldAccountId: String, newAccountId: String)
}

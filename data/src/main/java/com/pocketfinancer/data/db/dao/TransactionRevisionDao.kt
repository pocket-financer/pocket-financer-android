package com.pocketfinancer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketfinancer.data.db.entity.AccountAliasEntity
import com.pocketfinancer.data.db.entity.LegacyTransactionSnapshotEntity
import com.pocketfinancer.data.db.entity.TransactionRevisionEntity

@Dao
interface TransactionRevisionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccountAlias(alias: AccountAliasEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: TransactionRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLegacySnapshot(snapshot: LegacyTransactionSnapshotEntity): Long

    @Query("SELECT * FROM transaction_revisions WHERE transactionId = :transactionId ORDER BY revision")
    suspend fun getHistory(transactionId: String): List<TransactionRevisionEntity>

    @Query(
        "SELECT * FROM transaction_revisions " +
            "WHERE transactionId = :transactionId AND isCurrentProjection = 1 LIMIT 1"
    )
    suspend fun getCurrent(transactionId: String): TransactionRevisionEntity?

    @Query("SELECT * FROM transaction_revisions WHERE id = :revisionId LIMIT 1")
    suspend fun getById(revisionId: String): TransactionRevisionEntity?

    @Query(
        "UPDATE transaction_revisions SET isCurrentProjection = 0 " +
            "WHERE transactionId = :transactionId AND isCurrentProjection = 1"
    )
    suspend fun clearCurrentProjection(transactionId: String): Int

    @Query(
        "SELECT * FROM account_aliases " +
            "WHERE normalizedAliasHash = :normalizedAliasHash " +
            "AND matchingScope = :matchingScope AND confirmedByUser = 1"
    )
    suspend fun findConfirmedAliases(
        normalizedAliasHash: String,
        matchingScope: String
    ): List<AccountAliasEntity>
}

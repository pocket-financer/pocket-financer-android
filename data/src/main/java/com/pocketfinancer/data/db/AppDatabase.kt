package com.pocketfinancer.data.db

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.QueuedSmsCandidateDao
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.dao.TransactionRevisionDao
import com.pocketfinancer.data.db.entity.AccountAliasEntity
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.LegacyTransactionSnapshotEntity
import com.pocketfinancer.data.db.entity.QueuedSmsCandidateEntity
import com.pocketfinancer.data.db.entity.SmsPersistenceDecisionEntity
import com.pocketfinancer.data.db.entity.SmsProcessingAnalysisEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseV2ExtensionEntity
import com.pocketfinancer.data.db.entity.SmsSelectorAttemptEntity
import com.pocketfinancer.data.db.entity.SmsSourceMetadataEventEntity
import com.pocketfinancer.data.db.entity.SmsTraceImportReceiptEntity
import com.pocketfinancer.data.db.entity.SmsUserFeedbackEventEntity
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.db.entity.TransactionRevisionEntity
import com.pocketfinancer.data.model.SmsSourceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room database with SQLCipher for AES-256 encryption at rest.
 *
 * Every byte written to the SQLite file is encrypted. Its random passphrase is
 * wrapped by Android Keystore-backed encrypted preferences; initialization
 * fails closed if that protection is unavailable.
 */
@Database(
    entities = [
        TransactionEntity::class,
        AccountEntity::class,
        QueuedSmsCandidateEntity::class,
        AdmittedSmsSourceEntity::class,
        SmsSourceMetadataEventEntity::class,
        SmsProcessingOperationEntity::class,
        SmsProcessingAnalysisEntity::class,
        SmsSelectorAttemptEntity::class,
        SmsProcessingTraceEventEntity::class,
        SmsReconstructedResultEntity::class,
        SmsPersistenceDecisionEntity::class,
        SmsReviewCaseEntity::class,
        SmsReviewCaseV2ExtensionEntity::class,
        SmsUserFeedbackEventEntity::class,
        TransactionRevisionEntity::class,
        AccountAliasEntity::class,
        LegacyTransactionSnapshotEntity::class,
        SmsTraceImportReceiptEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun queuedSmsCandidateDao(): QueuedSmsCandidateDao
    abstract fun smsProcessingDao(): SmsProcessingDao
    abstract fun transactionRevisionDao(): TransactionRevisionDao

    companion object {
        private const val DB_NAME = "pocketfinancer.db"
        private const val SQLCIPHER_KDF_ITERATIONS = 256_000

        fun getOrCreatePassphrase(context: Context): ByteArray {
            val encryptedPrefs = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    ".encrypted_db_secrets",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Encrypted local storage is unavailable because the " +
                        "Android Keystore could not protect its database key.",
                    error
                )
            }

            val plainPrefs = context.getSharedPreferences(
                ".db_secrets",
                Context.MODE_PRIVATE
            )
            val existingEncrypted =
                encryptedPrefs.getString("db_passphrase", null)
            if (existingEncrypted != null) {
                if (plainPrefs.contains("db_passphrase")) {
                    check(
                        plainPrefs.edit()
                            .remove("db_passphrase")
                            .commit()
                    ) {
                        "Could not erase the legacy plaintext database key."
                    }
                }
                return existingEncrypted.toByteArray(Charsets.UTF_8)
            }

            // Upgrade installations that previously fell back to plaintext:
            // first durably wrap the existing key, then erase that plaintext
            // copy. This keeps the existing encrypted database readable.
            val existingPlain = plainPrefs.getString("db_passphrase", null)
            if (existingPlain != null) {
                check(
                    encryptedPrefs.edit()
                        .putString("db_passphrase", existingPlain)
                        .commit()
                ) {
                    "Could not protect the existing database key."
                }
                check(plainPrefs.edit().remove("db_passphrase").commit()) {
                    "Could not erase the legacy plaintext database key."
                }
                return existingPlain.toByteArray(Charsets.UTF_8)
            }

            val random = ByteArray(32)
            java.security.SecureRandom().nextBytes(random)
            val hex = random.joinToString("") { "%02x".format(it) }
            check(
                encryptedPrefs.edit()
                    .putString("db_passphrase", hex)
                    .commit()
            ) {
                "Could not protect the new database key."
            }
            return hex.toByteArray(Charsets.UTF_8)
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmPromptEvalMs INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmEvalMs INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmNumTokens INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmModelName TEXT")
            }
        }

        /**
         * Adds durable source identity and the encrypted processing queue.
         *
         * V3 allowed exact duplicate rows. The migration keeps every row: the
         * first occurrence owns the canonical fallback identity, while later
         * exact occurrences receive stable synthetic identities derived from
         * their existing primary keys. A future inbox scan therefore converges
         * on the canonical row without deleting historical user data.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions_v4` (
                        `id` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `merchant` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `rawMessage` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `isEdited` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `slmPromptEvalMs` INTEGER,
                        `slmEvalMs` INTEGER,
                        `slmNumTokens` INTEGER,
                        `slmModelName` TEXT,
                        `sourceConnector` TEXT NOT NULL,
                        `sourceProviderMessageId` TEXT,
                        `sourceMessageId` TEXT NOT NULL,
                        `sourceFingerprint` TEXT NOT NULL,
                        `sourceAlternateFingerprint` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                val claimedFingerprints = mutableSetOf<String>()
                db.query(
                    """
                    SELECT *
                    FROM `transactions`
                    ORDER BY `createdAt` ASC, `id` ASC
                    """.trimIndent()
                ).use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val amountIndex = cursor.getColumnIndexOrThrow("amount")
                    val merchantIndex = cursor.getColumnIndexOrThrow("merchant")
                    val dateIndex = cursor.getColumnIndexOrThrow("date")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    val accountIdIndex = cursor.getColumnIndexOrThrow("accountId")
                    val rawMessageIndex = cursor.getColumnIndexOrThrow("rawMessage")
                    val senderIndex = cursor.getColumnIndexOrThrow("sender")
                    val isEditedIndex = cursor.getColumnIndexOrThrow("isEdited")
                    val createdAtIndex = cursor.getColumnIndexOrThrow("createdAt")
                    val updatedAtIndex = cursor.getColumnIndexOrThrow("updatedAt")
                    val promptEvalIndex = cursor.getColumnIndexOrThrow("slmPromptEvalMs")
                    val evalIndex = cursor.getColumnIndexOrThrow("slmEvalMs")
                    val tokenIndex = cursor.getColumnIndexOrThrow("slmNumTokens")
                    val modelNameIndex = cursor.getColumnIndexOrThrow("slmModelName")

                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex)
                        val date = cursor.getLong(dateIndex)
                        val sender = cursor.getString(senderIndex)
                        val rawMessage = cursor.getString(rawMessageIndex)
                        val canonical = SmsSourceIdentity.androidSms(
                            providerMessageId = null,
                            sender = sender,
                            body = rawMessage,
                            sourceTimestamp = date,
                            messageType = 1
                        )
                        val sourceIdentity =
                            if (claimedFingerprints.add(canonical.fallbackFingerprint)) {
                                canonical
                            } else {
                                SmsSourceIdentity.legacyDuplicate(canonical, id).also {
                                    check(claimedFingerprints.add(it.fallbackFingerprint)) {
                                        "Could not allocate legacy source identity for $id"
                                    }
                                }
                            }

                        val values = ContentValues().apply {
                            put("id", id)
                            put("amount", cursor.getDouble(amountIndex))
                            put("merchant", cursor.getString(merchantIndex))
                            put("date", date)
                            put("type", cursor.getString(typeIndex))
                            put("accountId", cursor.getString(accountIdIndex))
                            put("rawMessage", rawMessage)
                            put("sender", sender)
                            put("isEdited", cursor.getInt(isEditedIndex))
                            put("createdAt", cursor.getLong(createdAtIndex))
                            put("updatedAt", cursor.getLong(updatedAtIndex))
                            putNullableLong("slmPromptEvalMs", cursor, promptEvalIndex)
                            putNullableLong("slmEvalMs", cursor, evalIndex)
                            putNullableInt("slmNumTokens", cursor, tokenIndex)
                            putNullableString("slmModelName", cursor, modelNameIndex)
                            put("sourceConnector", sourceIdentity.connector)
                            putNull("sourceProviderMessageId")
                            put("sourceMessageId", sourceIdentity.messageId)
                            put(
                                "sourceFingerprint",
                                sourceIdentity.fallbackFingerprint
                            )
                            putNull("sourceAlternateFingerprint")
                        }
                        check(
                            db.insert(
                                "transactions_v4",
                                SQLiteDatabase.CONFLICT_ABORT,
                                values
                            ) != -1L
                        ) {
                            "Failed to preserve transaction $id during migration"
                        }
                    }
                }

                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_v4` RENAME TO `transactions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_accountId` " +
                        "ON `transactions` (`accountId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_date` " +
                        "ON `transactions` (`date`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_type` " +
                        "ON `transactions` (`type`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_sourceMessageId` " +
                        "ON `transactions` (`sourceConnector`, `sourceMessageId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_sourceFingerprint` " +
                        "ON `transactions` (`sourceConnector`, `sourceFingerprint`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queued_sms_candidates` (
                        `candidateKey` TEXT NOT NULL,
                        `sourceConnector` TEXT NOT NULL,
                        `sourceMessageId` TEXT NOT NULL,
                        `sourceFingerprint` TEXT NOT NULL,
                        `sourceAlternateFingerprint` TEXT,
                        `sourceProviderMessageId` TEXT,
                        `sender` TEXT NOT NULL,
                        `rawMessage` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `sourceTimestamp` INTEGER NOT NULL,
                        `messageType` INTEGER NOT NULL,
                        `origin` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `claimToken` TEXT,
                        `claimedAt` INTEGER,
                        `attemptCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`candidateKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_sourceMessageId` " +
                        "ON `queued_sms_candidates` (`sourceConnector`, `sourceMessageId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_sourceFingerprint` " +
                        "ON `queued_sms_candidates` (`sourceConnector`, `sourceFingerprint`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_origin_state` " +
                        "ON `queued_sms_candidates` (`origin`, `state`)"
                )
            }
        }

        /**
         * Makes connector message ids authoritative.
         *
         * A fallback message id already embeds the deterministic fingerprint,
         * so the unique message-id index continues to serialize concurrent
         * provider-less retries. The fingerprint itself must not be unique:
         * two provider rows can legitimately contain byte-for-byte identical
         * evidence while carrying different provider ids.
         *
         * Existing rows and candidate primary keys are deliberately left
         * untouched. In particular, a V4 candidate enriched after a broadcast
         * keeps the key already referenced by WorkManager.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DROP INDEX IF EXISTS " +
                        "`index_transactions_sourceConnector_sourceFingerprint`"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_sourceFingerprint` " +
                        "ON `transactions` (`sourceConnector`, `sourceFingerprint`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_" +
                        "sourceAlternateFingerprint` " +
                        "ON `transactions` " +
                        "(`sourceConnector`, `sourceAlternateFingerprint`)"
                )
                db.execSQL(
                    "DROP INDEX IF EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_" +
                        "sourceFingerprint`"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_" +
                        "sourceFingerprint` " +
                        "ON `queued_sms_candidates` " +
                        "(`sourceConnector`, `sourceFingerprint`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_" +
                        "sourceAlternateFingerprint` " +
                        "ON `queued_sms_candidates` " +
                        "(`sourceConnector`, `sourceAlternateFingerprint`)"
                )
            }
        }

        /**
         * Adds the append-only native SMS operation/review model without rewriting
         * legacy transaction truth. Historical Double amounts remain explicitly
         * inexact; only new or user-confirmed revisions may populate exact money.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceEventId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN exactMinorUnits INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN currencyCode TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN currencyScale INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN currencyProvenance TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN timestampProvenance TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN currentRevisionId TEXT")
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN projectionState TEXT " +
                        "NOT NULL DEFAULT 'legacy'"
                )
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN legacyPrecisionStatus TEXT " +
                        "NOT NULL DEFAULT 'legacy_double_original_precision_unknown'"
                )
                db.execSQL(
                    "DROP INDEX IF EXISTS " +
                        "`index_transactions_sourceConnector_sourceMessageId`"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_sourceMessageId_sourceEventId` " +
                        "ON `transactions` (`sourceConnector`, `sourceMessageId`, `sourceEventId`)"
                )

                createNativeSmsTables(db)
                backfillNativeSmsState(db)
            }
        }

        /** Adds duplicate fingerprints and permits detection of ambiguous owned aliases. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sms_reconstructed_results " +
                        "ADD COLUMN transactionFingerprint TEXT"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_sms_reconstructed_results_transactionFingerprint` " +
                        "ON `sms_reconstructed_results` (`transactionFingerprint`)"
                )
                db.execSQL(
                    "DROP INDEX IF EXISTS " +
                        "`index_account_aliases_normalizedAliasHash_matchingScope`"
                )
                db.execSQL(
                    "CREATE INDEX `index_account_aliases_normalizedAliasHash_matchingScope` " +
                        "ON `account_aliases` (`normalizedAliasHash`, `matchingScope`)"
                )
            }
        }

        /** Adds review-case/2 evidence without changing historical review-case/1 rows. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sms_review_case_v2_extensions` (
                        `reviewCaseId` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `contractVersion` TEXT NOT NULL,
                        `furthestStage` TEXT NOT NULL,
                        `analyzerSuggestionsJson` TEXT NOT NULL,
                        `fieldEvidenceJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`reviewCaseId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_sms_review_case_v2_extensions_operationId` " +
                        "ON `sms_review_case_v2_extensions` (`operationId`)"
                )
            }
        }

        private fun createNativeSmsTables(db: SupportSQLiteDatabase) {
            val statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `sms_admitted_sources` (
                    `id` TEXT NOT NULL,
                    `sourceConnector` TEXT NOT NULL,
                    `sourceMessageId` TEXT NOT NULL,
                    `sourceProviderMessageId` TEXT,
                    `sourceFingerprint` TEXT NOT NULL,
                    `sourceAlternateFingerprint` TEXT,
                    `sender` TEXT NOT NULL,
                    `rawMessage` TEXT NOT NULL,
                    `sourceTimestamp` INTEGER,
                    `messageType` INTEGER,
                    `origin` TEXT NOT NULL,
                    `admissionReceiptId` TEXT NOT NULL,
                    `admittedAt` INTEGER NOT NULL,
                    `retentionState` TEXT NOT NULL,
                    `deletionEpoch` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_source_metadata_events` (
                    `id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sequence` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_processing_operations` (
                    `id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `parentOperationId` TEXT,
                    `stableEventId` TEXT NOT NULL, `trigger` TEXT NOT NULL,
                    `configurationJson` TEXT NOT NULL, `configurationHash` TEXT NOT NULL,
                    `contractReleaseId` TEXT NOT NULL, `state` TEXT NOT NULL,
                    `transitionSequence` INTEGER NOT NULL, `ownerToken` TEXT,
                    `ownerGeneration` INTEGER NOT NULL, `claimExpiresAt` INTEGER,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                    `settledAt` INTEGER, `settlementReceiptJson` TEXT,
                    `deletionEpoch` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_processing_analyses` (
                    `id` TEXT NOT NULL, `operationId` TEXT NOT NULL, `analysisId` TEXT NOT NULL,
                    `contractVersion` TEXT NOT NULL, `sourceHash` TEXT NOT NULL,
                    `configurationHash` TEXT NOT NULL, `canonicalJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_selector_attempts` (
                    `id` TEXT NOT NULL, `operationId` TEXT NOT NULL, `attemptIndex` INTEGER NOT NULL,
                    `runtimeProfileJson` TEXT NOT NULL, `requestJson` TEXT NOT NULL,
                    `rawOutput` TEXT, `outputByteCount` INTEGER, `completion` TEXT NOT NULL,
                    `validatedSelectionJson` TEXT, `safeErrorCode` TEXT,
                    `startedAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_processing_trace_events` (
                    `id` TEXT NOT NULL, `operationId` TEXT NOT NULL, `sequence` INTEGER NOT NULL,
                    `occurredAt` INTEGER NOT NULL, `stage` TEXT NOT NULL, `status` TEXT NOT NULL,
                    `reasonCodesJson` TEXT NOT NULL, `detailJson` TEXT, `previousEventHash` TEXT,
                    `eventHash` TEXT NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_reconstructed_results` (
                    `id` TEXT NOT NULL, `operationId` TEXT NOT NULL,
                    `contractVersion` TEXT NOT NULL, `recognitionDecision` TEXT NOT NULL,
                    `semanticResultJson` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_persistence_decisions` (
                    `id` TEXT NOT NULL, `operationId` TEXT NOT NULL, `result` TEXT NOT NULL,
                    `primaryReason` TEXT NOT NULL, `checksJson` TEXT NOT NULL,
                    `accountResolutionJson` TEXT NOT NULL, `rolloutMode` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_review_cases` (
                    `id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `currentOperationId` TEXT NOT NULL,
                    `state` TEXT NOT NULL, `revision` INTEGER NOT NULL,
                    `reasonCodesJson` TEXT NOT NULL, `draftJson` TEXT,
                    `stableEventIdsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_user_feedback_events` (
                    `actionId` TEXT NOT NULL, `reviewCaseId` TEXT,
                    `operationId` TEXT, `transactionId` TEXT, `transactionRevisionId` TEXT,
                    `expectedReviewRevision` INTEGER NOT NULL,
                    `resultingReviewRevision` INTEGER NOT NULL, `action` TEXT NOT NULL,
                    `actorClass` TEXT NOT NULL, `actorIdHash` TEXT NOT NULL,
                    `correctionsJson` TEXT NOT NULL, `retryConfiguration` TEXT,
                    `canonicalLabelId` TEXT, `canonicalLabelRevision` INTEGER,
                    `previousEventHash` TEXT, `eventHash` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL, PRIMARY KEY(`actionId`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `transaction_revisions` (
                    `id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
                    `stableEventId` TEXT NOT NULL, `revision` INTEGER NOT NULL,
                    `previousRevisionId` TEXT, `operationId` TEXT, `feedbackActionId` TEXT,
                    `exactMinorUnits` INTEGER, `currencyCode` TEXT, `currencyScale` INTEGER,
                    `direction` TEXT, `merchant` TEXT, `accountId` TEXT, `occurredAt` INTEGER,
                    `provenance` TEXT NOT NULL, `isCurrentProjection` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `account_aliases` (
                    `id` TEXT NOT NULL, `accountId` TEXT NOT NULL,
                    `normalizedAliasHash` TEXT NOT NULL, `aliasKind` TEXT NOT NULL,
                    `matchingScope` TEXT NOT NULL, `confirmedByUser` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `legacy_transaction_snapshots` (
                    `transactionId` TEXT NOT NULL, `legacyAmount` REAL NOT NULL,
                    `merchant` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL,
                    `direction` TEXT NOT NULL, `accountId` TEXT NOT NULL,
                    `rawMessage` TEXT NOT NULL, `sender` TEXT NOT NULL, `wasEdited` INTEGER NOT NULL,
                    `originalEditHistoryKnown` INTEGER NOT NULL, `capturedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`transactionId`)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS `sms_trace_import_receipts` (
                    `transferId` TEXT NOT NULL, `manifestHash` TEXT NOT NULL,
                    `sourcePlatform` TEXT NOT NULL, `consentedAt` INTEGER NOT NULL,
                    `importedAt` INTEGER NOT NULL, `operationCount` INTEGER NOT NULL,
                    `provenance` TEXT NOT NULL, PRIMARY KEY(`transferId`)
                )
                """
            )
            statements.forEach { db.execSQL(it.trimIndent()) }
            createNativeSmsIndices(db)
        }

        private fun createNativeSmsIndices(db: SupportSQLiteDatabase) {
            val statements = listOf(
                "CREATE UNIQUE INDEX `index_sms_admitted_sources_sourceConnector_sourceMessageId` ON `sms_admitted_sources` (`sourceConnector`, `sourceMessageId`)",
                "CREATE INDEX `index_sms_admitted_sources_sourceConnector_sourceProviderMessageId` ON `sms_admitted_sources` (`sourceConnector`, `sourceProviderMessageId`)",
                "CREATE INDEX `index_sms_admitted_sources_retentionState` ON `sms_admitted_sources` (`retentionState`)",
                "CREATE UNIQUE INDEX `index_sms_source_metadata_events_sourceId_sequence` ON `sms_source_metadata_events` (`sourceId`, `sequence`)",
                "CREATE INDEX `index_sms_processing_operations_sourceId_createdAt` ON `sms_processing_operations` (`sourceId`, `createdAt`)",
                "CREATE INDEX `index_sms_processing_operations_state` ON `sms_processing_operations` (`state`)",
                "CREATE INDEX `index_sms_processing_operations_parentOperationId` ON `sms_processing_operations` (`parentOperationId`)",
                "CREATE UNIQUE INDEX `index_sms_processing_analyses_operationId` ON `sms_processing_analyses` (`operationId`)",
                "CREATE UNIQUE INDEX `index_sms_selector_attempts_operationId_attemptIndex` ON `sms_selector_attempts` (`operationId`, `attemptIndex`)",
                "CREATE UNIQUE INDEX `index_sms_processing_trace_events_operationId_sequence` ON `sms_processing_trace_events` (`operationId`, `sequence`)",
                "CREATE UNIQUE INDEX `index_sms_reconstructed_results_operationId` ON `sms_reconstructed_results` (`operationId`)",
                "CREATE UNIQUE INDEX `index_sms_persistence_decisions_operationId` ON `sms_persistence_decisions` (`operationId`)",
                "CREATE INDEX `index_sms_review_cases_sourceId` ON `sms_review_cases` (`sourceId`)",
                "CREATE INDEX `index_sms_review_cases_currentOperationId` ON `sms_review_cases` (`currentOperationId`)",
                "CREATE INDEX `index_sms_review_cases_state_updatedAt` ON `sms_review_cases` (`state`, `updatedAt`)",
                "CREATE UNIQUE INDEX `index_sms_user_feedback_events_reviewCaseId_resultingReviewRevision` ON `sms_user_feedback_events` (`reviewCaseId`, `resultingReviewRevision`)",
                "CREATE UNIQUE INDEX `index_sms_user_feedback_events_transactionId_resultingReviewRevision` ON `sms_user_feedback_events` (`transactionId`, `resultingReviewRevision`)",
                "CREATE UNIQUE INDEX `index_transaction_revisions_transactionId_revision` ON `transaction_revisions` (`transactionId`, `revision`)",
                "CREATE UNIQUE INDEX `index_transaction_revisions_sourceId_stableEventId_revision` ON `transaction_revisions` (`sourceId`, `stableEventId`, `revision`)",
                "CREATE INDEX `index_account_aliases_accountId` ON `account_aliases` (`accountId`)",
                "CREATE UNIQUE INDEX `index_account_aliases_normalizedAliasHash_matchingScope` ON `account_aliases` (`normalizedAliasHash`, `matchingScope`)"
            )
            statements.forEach { db.execSQL(it) }
        }

        private fun backfillNativeSmsState(db: SupportSQLiteDatabase) {
            val nowExpression = "CAST(strftime('%s','now') AS INTEGER) * 1000"
            val uuidV4Expression = "(" +
                "lower(hex(randomblob(4))) || '-' || " +
                "lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-8' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "lower(hex(randomblob(6))))"
            db.execSQL(
                """
                INSERT OR IGNORE INTO `legacy_transaction_snapshots`
                    (`transactionId`, `legacyAmount`, `merchant`, `occurredAt`, `direction`,
                     `accountId`, `rawMessage`, `sender`, `wasEdited`,
                     `originalEditHistoryKnown`, `capturedAt`)
                SELECT `id`, `amount`, `merchant`, `date`, `type`, `accountId`, `rawMessage`,
                       `sender`, `isEdited`, 0, $nowExpression
                FROM `transactions`
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `transaction_revisions`
                    (`id`, `transactionId`, `sourceId`, `stableEventId`, `revision`,
                     `previousRevisionId`, `operationId`, `feedbackActionId`, `exactMinorUnits`,
                     `currencyCode`, `currencyScale`, `direction`, `merchant`, `accountId`,
                     `occurredAt`, `provenance`, `isCurrentProjection`, `createdAt`)
                SELECT `id` || ':legacy', `id`, COALESCE(`sourceId`, `sourceMessageId`),
                       COALESCE(`sourceEventId`, `id`), 0, NULL, NULL, NULL, NULL, NULL, NULL,
                       `type`, `merchant`, `accountId`, `date`,
                       'legacy_current_state_original_history_unknown', 1, $nowExpression
                FROM `transactions`
                """.trimIndent()
            )
            db.execSQL("UPDATE transactions SET currentRevisionId = id || ':legacy'")
            db.execSQL(
                """
                INSERT OR IGNORE INTO `sms_admitted_sources`
                    (`id`, `sourceConnector`, `sourceMessageId`, `sourceProviderMessageId`,
                     `sourceFingerprint`, `sourceAlternateFingerprint`, `sender`, `rawMessage`,
                     `sourceTimestamp`, `messageType`, `origin`, `admissionReceiptId`,
                     `admittedAt`, `retentionState`, `deletionEpoch`)
                SELECT `candidateKey`, `sourceConnector`, `sourceMessageId`,
                       `sourceProviderMessageId`, `sourceFingerprint`,
                       `sourceAlternateFingerprint`, `sender`, `rawMessage`, `sourceTimestamp`,
                       `messageType`, `origin`, `candidateKey`, `createdAt`, 'retained', 0
                FROM `queued_sms_candidates`
                """.trimIndent()
            )
            // Preserve queued work as a durable, explicitly unconfigured
            // operation. Startup recovery may attach a fresh immutable
            // configuration snapshot, but migration must not invent one.
            db.execSQL(
                """
                INSERT OR IGNORE INTO `sms_processing_operations`
                    (`id`, `sourceId`, `parentOperationId`, `stableEventId`, `trigger`,
                     `configurationJson`, `configurationHash`, `contractReleaseId`, `state`,
                     `transitionSequence`, `ownerToken`, `ownerGeneration`, `claimExpiresAt`,
                     `createdAt`, `updatedAt`, `settledAt`, `settlementReceiptJson`,
                     `deletionEpoch`)
                SELECT $uuidV4Expression, `candidateKey`, NULL,
                       $uuidV4Expression, `origin`, '{}', 'legacy-unconfigured',
                       'native-integration-v1', 'awaiting_configuration', 0, NULL, 0, NULL,
                       `createdAt`, `updatedAt`, NULL, NULL, 0
                FROM `queued_sms_candidates`
                """.trimIndent()
            )
        }

        private fun ContentValues.putNullableLong(
            column: String,
            cursor: android.database.Cursor,
            index: Int
        ) {
            if (cursor.isNull(index)) putNull(column) else put(column, cursor.getLong(index))
        }

        private fun ContentValues.putNullableInt(
            column: String,
            cursor: android.database.Cursor,
            index: Int
        ) {
            if (cursor.isNull(index)) putNull(column) else put(column, cursor.getInt(index))
        }

        private fun ContentValues.putNullableString(
            column: String,
            cursor: android.database.Cursor,
            index: Int
        ) {
            if (cursor.isNull(index)) putNull(column) else put(column, cursor.getString(index))
        }
    }

    /**
     * Module-level provider for Hilt.
     */
    @Singleton
    class Factory @Inject constructor(
        @ApplicationContext private val context: Context
    ) {
        fun create(): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = getOrCreatePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)

            val builder = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )

            return builder.build()
        }
    }
}

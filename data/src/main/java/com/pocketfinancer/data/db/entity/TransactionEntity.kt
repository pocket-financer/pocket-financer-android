package com.pocketfinancer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.pocketfinancer.data.model.SmsSourceIdentity

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["date"]),
        Index(value = ["type"]),
        Index(
            value = ["sourceConnector", "sourceMessageId", "sourceEventId"],
            unique = true
        ),
        Index(
            value = ["sourceConnector", "sourceFingerprint"],
            unique = false
        ),
        Index(value = ["sourceConnector", "sourceAlternateFingerprint"])
    ]
)
data class TransactionEntity(
    @PrimaryKey
    val id: String,                     // UUID generated at insert time
    val amount: Double,
    val merchant: String,
    val date: Long,                     // epoch millis (SMS arrival timestamp)
    val type: String,                   // "debit" | "credit"
    val accountId: String,              // foreign key to accounts table
    val rawMessage: String,             // original SMS body (encrypted at rest)
    val sender: String,                 // SMS sender address (e.g. "AX-HDFCBK")
    val isEdited: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val slmPromptEvalMs: Long? = null,
    val slmEvalMs: Long? = null,
    val slmNumTokens: Int? = null,
    val slmModelName: String? = null,
    val sourceConnector: String = SmsSourceIdentity.ANDROID_SMS_CONNECTOR,
    val sourceProviderMessageId: String? = null,
    val sourceMessageId: String = SmsSourceIdentity.androidSms(
        providerMessageId = sourceProviderMessageId,
        sender = sender,
        body = rawMessage,
        sourceTimestamp = date,
        messageType = 1
    ).messageId,
    val sourceFingerprint: String = SmsSourceIdentity.androidSms(
        providerMessageId = sourceProviderMessageId,
        sender = sender,
        body = rawMessage,
        sourceTimestamp = date,
        messageType = 1
    ).fallbackFingerprint,
    val sourceAlternateFingerprint: String? = null,
    val sourceId: String? = null,
    val sourceEventId: String? = null,
    val exactMinorUnits: Long? = null,
    val currencyCode: String? = null,
    val currencyScale: Int? = null,
    val currencyProvenance: String? = null,
    val timestampProvenance: String? = null,
    val currentRevisionId: String? = null,
    @ColumnInfo(defaultValue = "'legacy'")
    val projectionState: String = "legacy",
    @ColumnInfo(defaultValue = "'legacy_double_original_precision_unknown'")
    val legacyPrecisionStatus: String = "legacy_double_original_precision_unknown"
)

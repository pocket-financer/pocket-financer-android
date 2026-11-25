package com.pocketfinancer.ui.smsprocessing

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator-only audit. Default instrumentation runs skip production app data. */
@RunWith(AndroidJUnit4::class)
class SmsSyntheticRuntimeAuditTest {
    @Test
    fun inspectSyntheticEmulatorState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("smsSyntheticAudit") == "true")
        val database = AppDatabase.Factory(
            instrumentation.targetContext.applicationContext
        ).create()
        try {
            inspectDatabase(database, arguments, instrumentation)
        } finally {
            database.close()
        }
    }

    private suspend fun inspectDatabase(
        database: AppDatabase,
        arguments: Bundle,
        instrumentation: android.app.Instrumentation,
    ) {
        if (arguments.getString("seedHdfc1234Account") == "true") {
            val name = "HDFC Bank A/c XX1234"
            if (database.accountDao().findByName(name) == null) {
                database.accountDao().insert(
                    AccountEntity(
                        id = UUID.nameUUIDFromBytes(
                            "synthetic-emulator-hdfc-1234".toByteArray()
                        ).toString(),
                        name = name,
                        bank = "HDFC Bank",
                        type = "synthetic-test"
                    )
                )
            }
        }

        val sql = database.openHelper.readableDatabase
        val operationStates = mutableListOf<String>()
        sql.query(
            "SELECT state, COUNT(*) FROM sms_processing_operations GROUP BY state ORDER BY state"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                operationStates += "${cursor.getString(0)}:${cursor.getInt(1)}"
            }
        }
        fun grouped(query: String): String = sql.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("${cursor.getString(0)}:${cursor.getInt(1)}")
                }
            }.joinToString(",")
        }
        fun count(table: String): Int = sql.query("SELECT COUNT(*) FROM $table").use {
            cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
        val partialFields = mutableMapOf<String, Int>()
        sql.query("SELECT fieldEvidenceJson FROM sms_review_case_v2_extensions")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val fields = JSONArray(cursor.getString(0))
                    for (index in 0 until fields.length()) {
                        val field = fields.getJSONObject(index)
                        val category = listOf(
                            field.getString("origin"),
                            field.getString("field"),
                            field.getString("validation_state")
                        ).joinToString("/")
                        partialFields[category] = (partialFields[category] ?: 0) + 1
                    }
                }
            }
        val summary = listOf(
            "operations=${operationStates.joinToString(",")}",
            "triggers=${grouped("SELECT trigger, COUNT(*) FROM sms_processing_operations GROUP BY trigger ORDER BY trigger")}",
            "linkedRetries=${count("sms_processing_operations AS child " +
                "JOIN sms_processing_operations AS parent ON parent.id = child.parentOperationId " +
                "WHERE child.trigger = 'retry' AND child.sourceId = parent.sourceId " +
                "AND child.stableEventId = parent.stableEventId")}",
            "attempts=${count("sms_selector_attempts")}",
            "attemptCompletions=${grouped("SELECT completion, COUNT(*) FROM sms_selector_attempts GROUP BY completion ORDER BY completion")}",
            "reviews=${count("sms_review_cases")}",
            "reviewStates=${grouped("SELECT state, COUNT(*) FROM sms_review_cases GROUP BY state ORDER BY state")}",
            "partialFields=${partialFields.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }}",
            "outputDiagnostics=${outputDiagnostics(database)}",
            "reviewReasons=${grouped("SELECT reasonCodesJson, COUNT(*) FROM sms_review_cases GROUP BY reasonCodesJson ORDER BY reasonCodesJson")}",
            "transactions=${count("transactions")}",
            "queued=${count("queued_sms_candidates")}",
            "accounts=${count("accounts")}",
        ).joinToString(" ")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", summary) })
    }

    /**
     * Diagnose only aggregate synthetic output shape and scalar grounding.
     * Raw messages, generated strings, source IDs, and per-row facts stay local.
     */
    private fun outputDiagnostics(database: AppDatabase): String {
        val counts = mutableMapOf<String, Int>()
        fun count(category: String) {
            counts[category] = (counts[category] ?: 0) + 1
        }
        database.openHelper.readableDatabase.query(
            """
            SELECT attempt.rawOutput, source.rawMessage
            FROM sms_selector_attempts AS attempt
            JOIN sms_processing_operations AS operation ON operation.id = attempt.operationId
            JOIN sms_admitted_sources AS source ON source.id = operation.sourceId
            WHERE attempt.rawOutput IS NOT NULL
            """.trimIndent()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0)
                val source = cursor.getString(1)
                val root = runCatching { JSONObject(raw) }.getOrNull()
                if (root == null) {
                    count("json/unreadable")
                    count(if (raw.trimEnd().endsWith("}")) "json/closed" else "json/open")
                    count(
                        when {
                            raw.length < 512 -> "json/short"
                            raw.length < 1024 -> "json/medium"
                            else -> "json/long"
                        }
                    )
                    continue
                }
                count("json/readable")
                if (root.optString("decision") != "posted") continue
                val sourceScalars = source.codePointCount(0, source.length)
                for (field in listOf("amount", "direction", "account", "counterparty")) {
                    val span = root.optJSONObject(field)?.optJSONObject("evidence") ?: continue
                    val start = span.optInt("start_scalar", -1)
                    val end = span.optInt("end_scalar", -1)
                    val evidenceText = span.optString("text")
                    if (start < 0 || end <= start || end > sourceScalars) {
                        count("$field/bounds")
                        continue
                    }
                    val startUtf16 = source.offsetByCodePoints(0, start)
                    val endUtf16 = source.offsetByCodePoints(0, end)
                    if (source.substring(startUtf16, endUtf16) == evidenceText) {
                        count("$field/exact")
                        continue
                    }
                    val foundUtf16 = source.indexOf(evidenceText)
                    if (evidenceText.isEmpty() || foundUtf16 < 0) {
                        count("$field/text_absent")
                        continue
                    }
                    val foundScalar = source.codePointCount(0, foundUtf16)
                    val delta = start - foundScalar
                    count(
                        when (delta) {
                            -1, 1 -> "$field/offset_one"
                            0 -> "$field/length_or_other"
                            else -> "$field/offset_other"
                        }
                    )
                }
            }
        }
        return counts.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
    }

}

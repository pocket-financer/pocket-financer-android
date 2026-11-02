package com.pocketfinancer.pipeline.sms

import java.io.File
import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test

class StructuralSmsAnalyzerParityTest {
    @Test
    fun `all frozen vectors match canonical analysis selector input and triage`() {
        val root = JSONObject(fixtureFile().readText())
        val vectors = root.getJSONArray("vectors")
        val analyzer = StructuralSmsAnalyzer()
        val triage = SmsTriageEvaluator()

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val id = vector.getString("id")
            val source = vector.getString("source")
            val configurationHash = vector.getString("operation_config_hash")
            val operationId = vector.getString("operation_id")
            val operation = operation(operationId, configurationHash)

            val analysis = analyzer.analyze(source, operation)
            assertEquals(vector.getString("expected_analysis_json"), analysis.canonicalJson(), id)
            assertEquals(
                vector.getString("expected_selector_input_json"),
                groundedSelectorPayload(source, analysis),
                id
            )

            val expectedTriage = vector.getJSONObject("expected_triage")
            val actualTriage = triage.evaluate(analysis)
            assertEquals(
                expectedTriage.getString("disposition"),
                actualTriage.disposition.name.lowercase(),
                id
            )
            assertEquals(
                expectedTriage.getString("selector_action"),
                actualTriage.selectorAction.name.lowercase(),
                id
            )
            assertEquals(
                expectedTriage.getJSONArray("reason_codes").toStringList(),
                actualTriage.reasonCodes,
                id
            )
        }
    }

    private fun operation(operationId: String, configurationHash: String): SmsOperationSnapshot {
        val configuration = SmsOperationConfiguration(
            operationId = operationId,
            parentOperationId = null,
            sourceId = "synthetic-source",
            sourceRefHash = "b".repeat(64),
            trigger = "diagnostic",
            createdAtEpochMs = 1_700_000_000_000,
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            sourceTimestampEpochMs = 1_700_000_000_000,
            sourceTimestampProvenance = "acquisition_supplied_message_time",
            admissionTimestampEpochMs = 1_700_000_000_000,
            timezoneId = "UTC",
            releaseManifestHash = "c".repeat(64),
            currencyAssetHash = "d".repeat(64),
            profileAssetHashes = emptyMap(),
            selectorEligible = true,
            selectorIneligibilityReason = null,
            selectorModelId = "test-selector",
            selectorModelHash = null,
            selectorRuntimeVersion = "test-runtime",
            osVersion = "test-os",
            deviceCohort = "test-device",
            promptHash = "e".repeat(64)
        )
        return SmsOperationSnapshot(
            operationId = operationId,
            parentOperationId = null,
            stableEventId = "synthetic-event",
            configuration = configuration,
            configurationJson = "{}",
            configurationHash = configurationHash
        )
    }

    private fun fixtureFile(): File {
        val candidates = listOf(
            File("src/main/assets/sms_processing/golden-parity-v1.json"),
            File("pipeline/src/main/assets/sms_processing/golden-parity-v1.json")
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("golden-parity-v1.json is unavailable from ${File(".").absolutePath}")
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}

package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.SmsProcessingStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject

class StructuralSmsAnalyzerTest {
    private val analyzer = StructuralSmsAnalyzer()

    @Test
    fun `clause segmenter splits punctuation newlines and contrastive conjunctions with unicode spans`() {
        assertTrue(StructuralClauseSegmenter.split("").isEmpty())

        val text = "Alert \uD83D\uDCB3 ₹120.00 debited, however INR 45.00 refunded; ok.\nAvl bal ₹500.00"
        val clauses = StructuralClauseSegmenter.split(text)

        assertEquals(4, clauses.size)
        assertEquals("cl0", clauses[0].id)
        assertEquals("Alert \uD83D\uDCB3 ₹120.00 debited,", clauses[0].evidence.text)
        assertEquals("INR 45.00 refunded;", clauses[1].evidence.text)
        assertEquals("ok.", clauses[2].evidence.text)
        assertEquals("Avl bal ₹500.00", clauses[3].evidence.text)

        // Emoji is 1 Unicode scalar (2 UTF-16 code units, 4 UTF-8 bytes) and ₹ is 3 UTF-8 bytes.
        assertEquals(
            text.codePointCount(0, clauses[0].evidence.text.length),
            clauses[0].evidence.endCodePoint
        )
        assertEquals(
            clauses[0].evidence.text.toByteArray(Charsets.UTF_8).size,
            clauses[0].evidence.endUtf8
        )
    }

    @Test
    fun `structural view normalizes NFKC casefold whitespace and maps back to exact source spans`() {
        val source = "  Rs.\u00A01,250.50   DEBITED\tfrom A/c XX4321  "
        val view = SmsStructuralView(source)

        assertEquals(" rs. 1,250.50 debited from a/c xx4321 ", view.normalized)
        val match = view.findAll(Regex("debited")).single()
        val evidence = assertNotNull(match.evidence())
        assertEquals("DEBITED", evidence.text)
        assertEquals("DEBITED", source.substring(source.offsetByCodePoints(0, evidence.startCodePoint), source.offsetByCodePoints(0, evidence.endCodePoint)))
    }

    @Test
    fun `extracts amount direction accounts counterparty and explicit absence candidates`() {
        val body = "INR 1,250.50 debited from A/c XX4321 via UPI to merchant@upi at DEMO COFFEE. Card ending 8899."
        val analysis = analyzer.analyze(body, snapshot(body))

        val amounts = analysis.candidates.filter { it.kind == SmsCandidateKind.AMOUNT }
        assertEquals(1, amounts.size)
        assertEquals("125050", amounts.single().value["minor_units"])
        assertEquals("INR", amounts.single().value["currency"])
        assertEquals("explicit_code", amounts.single().value["currency_provenance"])

        val directions = analysis.candidates.filter { it.kind == SmsCandidateKind.DIRECTION }
        assertEquals(1, directions.size)
        assertEquals("debit", directions.single().value["direction"])
        assertEquals(1, analysis.completedEventCount)

        val accounts = analysis.candidates.filter {
            it.kind == SmsCandidateKind.ACCOUNT && !it.explicitAbsence
        }
        assertEquals(setOf("bank_account", "vpa", "card"), accounts.map { it.value["account_type"] }.toSet())
        assertTrue(accounts.any { it.value["identifier"] == "XX4321" })
        assertTrue(accounts.any { it.value["identifier"] == "merchant@upi" })
        assertTrue(accounts.any { it.value["identifier"] == "8899" })

        val counterparties = analysis.candidates.filter {
            it.kind == SmsCandidateKind.COUNTERPARTY && !it.explicitAbsence
        }
        assertTrue(counterparties.any { it.value["surface"] == "DEMO COFFEE" })

        // Explicit absence candidates are always present for account and counterparty.
        assertEquals(1, analysis.candidates.count { it.kind == SmsCandidateKind.ACCOUNT && it.explicitAbsence })
        assertEquals(1, analysis.candidates.count { it.kind == SmsCandidateKind.COUNTERPARTY && it.explicitAbsence })
        assertTrue("amount_candidate_present" in analysis.reasonCodes)
        assertTrue("completed_direction_candidate_present" in analysis.reasonCodes)

        val firstClauseAnnotation = analysis.clauseAnnotations.first()
        assertTrue("completed" in firstClauseAnnotation.states)
        assertTrue(firstClauseAnnotation.financialFamilies.any { it.family == "upi_transfer" })
    }

    @Test
    fun `filters stop-word counterparties and blocks directions in negated pending or hold clauses`() {
        val body = "INR 500.00 was not debited from your account; INR 200.00 will be debited tomorrow. " +
            "Authorization hold of INR 999.00 on card 1234. INR 75.00 spent at ACME STORE."
        val analysis = analyzer.analyze(body, snapshot(body))

        val directions = analysis.candidates.filter { it.kind == SmsCandidateKind.DIRECTION }
        assertEquals(1, directions.size)
        assertEquals("spent", directions.single().evidence?.text)
        assertEquals(1, analysis.completedEventCount)

        val cueKinds = analysis.cues.map { it.kind }.toSet()
        assertTrue("negation" in cueKinds)
        assertTrue("pending" in cueKinds)
        assertTrue("authorization_hold" in cueKinds)

        val counterparties = analysis.candidates.filter {
            it.kind == SmsCandidateKind.COUNTERPARTY && !it.explicitAbsence
        }
        assertFalse(counterparties.any { it.value["surface"].equals("your account", ignoreCase = true) })
        assertTrue(counterparties.any { it.value["surface"] == "ACME STORE" })
    }

    @Test
    fun `produces deterministic analysis id canonical json and grounded selector payload`() {
        val body = "₹450 credited to A/c 9876 from SALARY PAYROLL"
        val snap = snapshot(body)
        val first = analyzer.analyze(body, snap)
        val second = analyzer.analyze(body, snap)

        assertEquals(first.analysisId, second.analysisId)
        assertEquals(first.canonicalJson(), second.canonicalJson())

        val other = analyzer.analyze("₹451 credited to A/c 9876 from SALARY PAYROLL", snap)
        assertNotEquals(first.analysisId, other.analysisId)

        val json = JSONObject(first.canonicalJson())
        assertEquals("INR", json.getString("primary_currency"))
        assertEquals("core-en+india", json.getString("profile_id"))
        assertEquals(
            "pocketfinancer.structural-sms-analyzer/2",
            json.getJSONObject("metadata").getString("analyzer_behavior_version")
        )
        assertTrue(json.getJSONObject("metadata").getBoolean("input_valid"))

        val selectorPayload = JSONObject(groundedSelectorPayload(body, first))
        assertEquals(
            "pocketfinancer.grounded-candidate-selector-input/1",
            selectorPayload.getString("contract")
        )
        assertEquals(first.analysisId, selectorPayload.getString("analysis_id"))
        assertEquals(body, selectorPayload.getString("message"))
        assertTrue(selectorPayload.getJSONArray("candidates").length() >= 4)
    }

    private fun snapshot(body: String) = SmsOperationSnapshot(
        operationId = "11111111-1111-4111-8111-111111111111",
        parentOperationId = null,
        stableEventId = "22222222-2222-4222-8222-222222222222",
        configuration = SmsOperationConfiguration(
            operationId = "11111111-1111-4111-8111-111111111111",
            parentOperationId = null,
            sourceId = "source-1",
            sourceRefHash = SmsProcessingStore.sha256("source-1"),
            trigger = "realtime",
            createdAtEpochMs = 1_700_000_000_000L,
            admissionTimestampEpochMs = 1_700_000_000_000L,
            receivedTimestampEpochMs = 1_700_000_000_000L,
            receivedTimestampProvenance = "platform_received",
            timezoneId = "UTC",
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            releaseManifestHash = SmsContractAssets.MANIFEST_SHA256,
            currencyAssetHash = "c".repeat(64),
            profileAssetHashes = mapOf("core-en" to "d".repeat(64)),
            extractorEligible = true,
            extractorIneligibilityReason = null,
            modelIdentifier = "qwen-test",
            modelFileSha256 = "a".repeat(64),
            modelIdentityKind = "file_sha256",
            runtimeVersion = "llama.cpp-jni",
            osVersion = "14",
            deviceCohort = "Pixel 9",
            promptHash = "e".repeat(64),
            grammarHash = "f".repeat(64),
            grammarEnabled = false,
            validationProfileHash = "0".repeat(64)
        ),
        configurationJson = "{}",
        configurationHash = SmsProcessingStore.sha256(body)
    )
}

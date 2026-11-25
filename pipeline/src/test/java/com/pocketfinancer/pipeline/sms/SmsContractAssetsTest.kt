package com.pocketfinancer.pipeline.sms

import android.content.Context
import android.content.res.AssetManager
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.repository.SmsProcessingStore
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SmsContractAssetsTest {
    private fun assetsRoot(): File = listOf(
        File("src/main/assets"),
        File("pipeline/src/main/assets")
    ).firstOrNull(File::isDirectory) ?: error("pipeline assets dir not found")

    private fun mockContext(
        overridePath: String? = null,
        overrideBytes: ByteArray? = null
    ): Context {
        val root = assetsRoot()
        val assetManager = mockk<AssetManager>()
        every { assetManager.open(any()) } answers {
            val path = firstArg<String>()
            if (overridePath != null && overrideBytes != null && path == overridePath) {
                ByteArrayInputStream(overrideBytes)
            } else {
                File(root, path).inputStream()
            }
        }
        val context = mockk<Context>()
        every { context.assets } returns assetManager
        return context
    }

    @Test
    fun `verifies unified contract manifest and all LF android assets`() {
        val context = mockContext()
        val binding = SmsContractAssets.verify(context.assets)

        assertEquals(SmsContractAssets.RELEASE_ID, binding.releaseId)
        assertEquals(SmsContractAssets.MANIFEST_SHA256, binding.manifestSha256)
        assertEquals(300_000L, binding.duplicateWindowMs)
        assertEquals(
            SmsContractAssets.EXPECTED_ANDROID_ARTIFACT_COUNT,
            binding.artifactsByPath.size
        )
        assertTrue(SmsContractAssets.readExtractorPrompt(context.assets).isNotBlank())
        assertTrue(SmsContractAssets.readExtractorGrammar(context.assets).contains("root ::="))
    }

    @Test
    fun `verify fails closed on CRLF line endings or tampered contract asset`() {
        val root = assetsRoot()
        val promptPath = "${SmsContractAssets.ASSET_ROOT}/${SmsContractAssets.PROMPT_PATH}"
        val originalBytes = File(root, promptPath).readBytes()
        val crlfBytes = originalBytes.toString(Charsets.UTF_8)
            .replace("\n", "\r\n")
            .toByteArray(Charsets.UTF_8)

        val crlfContext = mockContext(overridePath = promptPath, overrideBytes = crlfBytes)
        assertFailsWith<SmsContractAssetIntegrityException> {
            SmsContractAssets.verify(crlfContext.assets)
        }

        val tamperedBytes = (originalBytes.toString(Charsets.UTF_8) + "\n# tampered")
            .toByteArray(Charsets.UTF_8)
        val tamperedContext = mockContext(overridePath = promptPath, overrideBytes = tamperedBytes)
        assertFailsWith<SmsContractAssetIntegrityException> {
            SmsContractAssets.verify(tamperedContext.assets)
        }
    }

    @Test
    fun `eligible file-backed model identity requires 64-char lowercase sha256`() {
        assertFailsWith<IllegalArgumentException> { SmsModelIdentity(true, "model", null) }
        assertFailsWith<IllegalArgumentException> {
            SmsModelIdentity(true, "model", "a".repeat(63))
        }
        assertFailsWith<IllegalArgumentException> {
            SmsModelIdentity(true, "model", "A".repeat(64))
        }
        val valid = SmsModelIdentity(true, "model", "a".repeat(64))
        assertTrue(valid.eligible)
        val ineligible = SmsModelIdentity(false, null, null)
        assertNull(ineligible.modelFileSha256)
    }

    @Test
    fun `currency profile registry parses valid scales and rejects invalid or overflowing inputs`() {
        val inr = CurrencyProfileRegistry.parse("1,23,456.7", "inr", "explicit_code")
        assertNotNull(inr)
        assertEquals(12_345_670L, inr.minorUnits)
        assertEquals("INR", inr.currency)
        assertEquals(2, inr.scale)

        val jpy = CurrencyProfileRegistry.parse("5,000", "JPY", "explicit_code")
        assertNotNull(jpy)
        assertEquals(5_000L, jpy.minorUnits)
        assertEquals(0, jpy.scale)

        assertNull(CurrencyProfileRegistry.parse("500.5", "JPY", "explicit_code"))
        assertNull(CurrencyProfileRegistry.parse("12.345", "USD", "explicit_code"))
        assertNull(CurrencyProfileRegistry.parse("0.00", "INR", "explicit_code"))
        assertNull(CurrencyProfileRegistry.parse("-10.00", "INR", "explicit_code"))
        assertNull(CurrencyProfileRegistry.parse("100.00", "ZZZ", "explicit_code"))
        assertNull(CurrencyProfileRegistry.parse("999999999999999999999", "INR", "explicit_code"))
    }

    @Test
    fun `snapshot factory encodes and decodes stored ready snapshot faithfully`() = runTest {
        val context = mockContext()
        val store = mockk<SmsProcessingStore>(relaxed = true)
        val factory = SmsOperationSnapshotFactory(context, store)

        val ref = AdmittedMessageRef(
            sourceId = "source-id-1",
            admissionReceiptId = "receipt-1",
            sourceDigest = "a".repeat(64)
        )
        val created = factory.create(
            source = ref,
            trigger = "realtime",
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            receivedTimestampEpochMs = 1_700_000_000_000L,
            receivedTimestampProvenance = "platform_received",
            admissionTimestampEpochMs = 1_700_000_000_000L,
            modelIdentity = SmsModelIdentity(false, null, null),
            runtimeVersion = "llama.cpp-jni",
            deviceCohort = "Pixel 9",
            now = 1_700_000_000_500L,
            grammarEnabled = true
        )

        val storedReady = SmsProcessingOperationEntity(
            id = created.operationId,
            sourceId = ref.sourceId,
            parentOperationId = created.parentOperationId,
            stableEventId = created.stableEventId,
            trigger = "realtime",
            configurationJson = created.configurationJson,
            configurationHash = created.configurationHash,
            contractReleaseId = SmsContractAssets.RELEASE_ID,
            state = "ready",
            transitionSequence = 0,
            ownerToken = null,
            ownerGeneration = 0,
            claimExpiresAt = null,
            createdAt = 1_700_000_000_500L,
            updatedAt = 1_700_000_000_500L,
            settledAt = null,
            settlementReceiptJson = null,
            deletionEpoch = 0
        )

        val decoded = factory.decode(storedReady)
        assertNotNull(decoded)
        assertEquals(created.operationId, decoded.operationId)
        assertEquals(created.configuration, decoded.configuration)
        assertEquals(created.configurationHash, decoded.configurationHash)
        assertTrue(decoded.configuration.grammarEnabled)
        assertEquals("direct_json", decoded.configuration.wireTransport)

        assertNull(factory.decode(storedReady.copy(configurationHash = "0".repeat(64))))
    }
}

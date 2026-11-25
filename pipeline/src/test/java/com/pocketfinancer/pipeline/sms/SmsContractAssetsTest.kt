package com.pocketfinancer.pipeline.sms

import android.content.Context
import android.content.res.AssetManager
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.repository.SmsProcessingStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SmsContractAssetsTest {
    private fun assetsRoot(): File = listOf(
        File("src/main/assets"),
        File("pipeline/src/main/assets")
    ).firstOrNull(File::isDirectory) ?: error("pipeline assets dir not found")

    private fun mockContext(): Context {
        val root = assetsRoot()
        val assetManager = mockk<AssetManager>()
        every { assetManager.open(any()) } answers {
            File(root, firstArg<String>()).inputStream()
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

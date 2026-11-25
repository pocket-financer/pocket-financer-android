package com.pocketfinancer.pipeline.sms

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verify APK bytes, since JVM-only tests cannot catch Windows asset packaging drift. */
@RunWith(AndroidJUnit4::class)
class PackagedSmsContractTest {
    @Test fun smsPipelineBundleRetainsManifestPathsAndBytes() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

        val binding = SmsContractAssets.verify(assets)

        assertEquals(SmsContractAssets.RELEASE_ID, binding.releaseId)
        assertEquals(SmsContractAssets.MANIFEST_SHA256, binding.manifestSha256)
        assertEquals(SmsContractAssets.DUPLICATE_WINDOW_MS, binding.duplicateWindowMs)
        assertEquals(
            "operation_release_incompatible",
            SmsContractAssetIntegrityException.REASON_CODE
        )
        assertEquals(
            SmsContractAssets.EXPECTED_ANDROID_ARTIFACT_COUNT,
            binding.artifactsByPath.size
        )
        assertTrue(SmsContractAssets.readExtractorPrompt(assets).isNotBlank())
        assertTrue(SmsContractAssets.readExtractorGrammar(assets).contains("root ::="))
    }
}

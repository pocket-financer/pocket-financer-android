package com.pocketfinancer.pipeline.sms

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSmsV4SourceAssetsTest {
    @Test fun nativeV4SourceBundleMatchesEveryFrozenManifestHash() {
        val root = listOf(
            File("src/main/assets/sms_processing/native-integration-v4"),
            File("pipeline/src/main/assets/sms_processing/native-integration-v4")
        ).firstOrNull(File::isDirectory) ?: error("Native v4 bundle unavailable")
        val bytes = File(root, "configs/sms_processing/contracts/releases/native-integration-v4.json").readBytes()
        assertEquals(NativeSmsV4Assets.MANIFEST_SHA256, sha256(bytes))
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(NativeSmsV4Assets.RELEASE_ID, manifest.getString("release_id"))
        assertFalse(manifest.getBoolean("automatic_persistence_enabled"))
        val artifacts = manifest.getJSONArray("artifacts")
        assertEquals(44, artifacts.length())
        repeat(artifacts.length()) { index ->
            val artifact = artifacts.getJSONObject(index)
            assertTrue(artifact.getString("path").startsWith("configs/sms_processing/") ||
                artifact.getString("path").startsWith("tests/sms_processing/"))
            assertEquals(artifact.getString("sha256"), sha256(File(root, artifact.getString("path")).readBytes()))
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

package com.pocketfinancer.pipeline.sms

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSmsV5SourceAssetsTest {
    @Test fun automaticSourceBundleMatchesEveryFrozenManifestHash() {
        val root = listOf(
            File("src/main/assets/sms_processing/native-integration-v5"),
            File("pipeline/src/main/assets/sms_processing/native-integration-v5")
        ).firstOrNull(File::isDirectory) ?: error("Automatic native bundle unavailable")
        val bytes = File(
            root,
            "configs/sms_processing/contracts/releases/native-integration-v5.json"
        ).readBytes()
        assertEquals(NativeSmsV5Assets.MANIFEST_SHA256, sha256(bytes))
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(NativeSmsV5Assets.RELEASE_ID, manifest.getString("release_id"))
        assertTrue(manifest.getBoolean("automatic_persistence_enabled"))
        val artifacts = manifest.getJSONArray("artifacts")
        assertEquals(52, artifacts.length())
        repeat(artifacts.length()) { index ->
            val artifact = artifacts.getJSONObject(index)
            val path = artifact.getString("path")
            assertTrue(
                path.startsWith("configs/sms_processing/") ||
                    path.startsWith("tests/sms_processing/")
            )
            assertEquals(artifact.getString("sha256"), sha256(File(root, path).readBytes()))
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

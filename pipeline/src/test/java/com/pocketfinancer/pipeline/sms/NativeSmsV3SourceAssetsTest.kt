package com.pocketfinancer.pipeline.sms

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSmsV3SourceAssetsTest {
    @Test fun nativeV3SourceBundleMatchesEveryFrozenManifestHash() {
        val root = assetRoot()
        val manifestFile = File(
            root,
            "configs/sms_processing/contracts/releases/native-integration-v3.json"
        )
        val manifestBytes = manifestFile.readBytes()
        assertEquals(NativeSmsV3Assets.MANIFEST_SHA256, sha256(manifestBytes))

        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        assertEquals(NativeSmsV3Assets.RELEASE_ID, manifest.getString("release_id"))
        assertEquals("frozen_for_native_implementation", manifest.getString("status"))
        assertFalse(manifest.getBoolean("automatic_persistence_enabled"))
        val artifacts = manifest.getJSONArray("artifacts")
        assertEquals(NativeSmsV3Assets.EXPECTED_ARTIFACT_COUNT, artifacts.length())

        val contracts = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        repeat(artifacts.length()) { index ->
            val artifact = artifacts.getJSONObject(index)
            val contract = artifact.getString("contract")
            val path = artifact.getString("path")
            assertTrue("Duplicate contract: $contract", contracts.add(contract))
            assertTrue("Duplicate path: $path", paths.add(path))
            assertEquals(
                "Frozen source asset changed: $path",
                artifact.getString("sha256"),
                sha256(File(root, path).readBytes())
            )
        }
    }

    @Test fun historicalV2ManifestRemainsUnchanged() {
        val manifest = legacyAsset("manifest.json")
        assertEquals(
            "637013f0988a20eb070e10b07f68ddf9172b847262024a50676c90022234019d",
            sha256(manifest.readBytes())
        )
        assertEquals(
            "native-integration-v2",
            JSONObject(manifest.readText()).getString("release_id")
        )
    }

    private fun assetRoot(): File {
        val candidates = listOf(
            File("src/main/assets/sms_processing/native-integration-v3"),
            File("pipeline/src/main/assets/sms_processing/native-integration-v3")
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Native v3 asset bundle is unavailable from ${File(".").absolutePath}")
    }

    private fun legacyAsset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/sms_processing/$name"),
            File("pipeline/src/main/assets/sms_processing/$name")
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("$name is unavailable from ${File(".").absolutePath}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

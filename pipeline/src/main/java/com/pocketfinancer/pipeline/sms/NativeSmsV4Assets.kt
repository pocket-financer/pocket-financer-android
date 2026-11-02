package com.pocketfinancer.pipeline.sms

import android.content.res.AssetManager
import java.security.MessageDigest
import org.json.JSONObject

/** Frozen successor bundle verifier. v1/v2/v3 remain independently readable. */
object NativeSmsV4Assets {
    const val RELEASE_ID = "native-integration-v4"
    const val MANIFEST_SHA256 = "0d3bf18f91d0a197c7bb56b5e082fd2851ce072f854452a9647c52d45b3433d8"
    const val ASSET_ROOT = "sms_processing/native-integration-v4"
    const val MANIFEST_PATH = "$ASSET_ROOT/configs/sms_processing/contracts/releases/native-integration-v4.json"
    const val PROCESSING_CONFIG_SHA256 = "257405f8cff35db2131dc2325388cc9845cb1e62775b5df1b45c321908642406"
    const val MANIFEST_SCHEMA_SHA256 = "ae86ae96dd65df7c1be9a4478b825e1c035d61bbbfda32aa1023da19e92b7b30"

    fun verify(assetManager: AssetManager): NativeSmsV3AssetBinding = try {
        val bytes = assetManager.open(MANIFEST_PATH).use { it.readBytes() }
        check(sha256(bytes) == MANIFEST_SHA256)
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        check(manifest.getString("release_id") == RELEASE_ID)
        check(!manifest.getBoolean("automatic_persistence_enabled"))
        val artifacts = manifest.getJSONArray("artifacts")
        val bindings = buildMap {
            repeat(artifacts.length()) { index ->
                val item = artifacts.getJSONObject(index)
                val contract = item.getString("contract")
                val path = item.getString("path")
                val hash = item.getString("sha256")
                check(path.startsWith("configs/sms_processing/") || path.startsWith("tests/sms_processing/"))
                check(!path.contains("..") && hash.matches(Regex("[0-9a-f]{64}")))
                check(put(contract, NativeSmsV3Artifact(contract, path, hash)) == null)
                check(sha256(assetManager.open("$ASSET_ROOT/$path").use { it.readBytes() }) == hash)
            }
        }
        check(bindings["pocketfinancer.processing-config/4"]?.sha256 == PROCESSING_CONFIG_SHA256)
        NativeSmsV3AssetBinding(RELEASE_ID, MANIFEST_SHA256, bindings)
    } catch (error: Throwable) {
        throw NativeSmsV3AssetIntegrityException(error)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

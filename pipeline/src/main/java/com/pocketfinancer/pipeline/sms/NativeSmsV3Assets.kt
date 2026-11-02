package com.pocketfinancer.pipeline.sms

import android.content.res.AssetManager
import java.security.MessageDigest
import org.json.JSONObject

data class NativeSmsV3AssetBinding(
    val releaseId: String,
    val manifestSha256: String,
    val artifactsByContract: Map<String, NativeSmsV3Artifact>
)

data class NativeSmsV3Artifact(
    val contract: String,
    val path: String,
    val sha256: String
)

class NativeSmsV3AssetIntegrityException(cause: Throwable? = null) :
    IllegalStateException(REASON_CODE, cause) {
    companion object {
        const val REASON_CODE = "configuration_integrity"
    }
}

/**
 * Verifies the exact frozen v3 release before a future v3 operation is admitted.
 * Historical v1/v2 assets remain in their existing locations and are not routed here.
 */
object NativeSmsV3Assets {
    const val RELEASE_ID = "native-integration-v3"
    const val MANIFEST_SHA256 =
        "61609c3336374c8b96b1e36cb90b5af01039ceecefcfc1b0091fd9359804436b"
    const val ASSET_ROOT = "sms_processing/native-integration-v3"
    const val MANIFEST_PATH =
        "$ASSET_ROOT/configs/sms_processing/contracts/releases/native-integration-v3.json"
    const val EXPECTED_ARTIFACT_COUNT = 44

    private val requiredProductionContracts = setOf(
        "pocketfinancer.sms-analysis/2",
        "pocketfinancer.supported-currencies/1",
        "pocketfinancer.analyzer-profile/1:core-en",
        "pocketfinancer.analyzer-profile/1:india",
        "pocketfinancer.persistence-policy/1",
        "pocketfinancer.timestamp-policy/1",
        "pocketfinancer.sms-extractor-input/1",
        "pocketfinancer.sms-extractor/1",
        "pocketfinancer.extractor-validation-profile/1",
        "pocketfinancer.extractor-prompt/1",
        "pocketfinancer.extractor-grammar/1",
        "pocketfinancer.processing-config/3",
        "pocketfinancer.processing-result/3",
        "pocketfinancer.processing-trace/3",
        "pocketfinancer.reason-code-registry/2",
        "pocketfinancer.account-resolution-profile/1",
        "pocketfinancer.review-case/1",
        "pocketfinancer.user-feedback/3",
        "pocketfinancer.canonical-label/2"
    )

    fun verify(assetManager: AssetManager): NativeSmsV3AssetBinding = try {
        val manifestBytes = assetManager.open(MANIFEST_PATH).use { it.readBytes() }
        check(sha256(manifestBytes) == MANIFEST_SHA256)
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        check(manifest.getString("contract") == "pocketfinancer.contract-release-manifest/1")
        check(manifest.getString("release_id") == RELEASE_ID)
        check(manifest.getString("status") == "frozen_for_native_implementation")
        check(!manifest.getBoolean("automatic_persistence_enabled"))

        val runtime = manifest.getJSONObject("runtime_policy")
        check(runtime.getInt("automatic_retry_limit") == 3)
        check(runtime.getLong("claim_heartbeat_ms") == 15_000L)
        check(runtime.getLong("claim_lease_ms") == 120_000L)
        check(runtime.getString("generation_mode") == "DIRECT_NON_THINKING")
        check(runtime.getString("decoding") == "greedy")
        check(runtime.getInt("answer_token_limit") == 512)
        check(runtime.getInt("raw_output_utf8_byte_limit") == 16_384)
        check(runtime.getLong("parser_deadline_ms") == 0L)

        val artifacts = manifest.getJSONArray("artifacts")
        check(artifacts.length() == EXPECTED_ARTIFACT_COUNT)
        val byContract = buildMap {
            repeat(artifacts.length()) { index ->
                val item = artifacts.getJSONObject(index)
                val artifact = NativeSmsV3Artifact(
                    contract = item.getString("contract"),
                    path = item.getString("path"),
                    sha256 = item.getString("sha256")
                )
                check(artifact.contract.isNotBlank())
                check(isSafeRelativePath(artifact.path))
                check(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
                check(put(artifact.contract, artifact) == null)
                val packaged = assetManager.open("$ASSET_ROOT/${artifact.path}")
                    .use { it.readBytes() }
                check(sha256(packaged) == artifact.sha256)
            }
        }
        check(byContract.keys.containsAll(requiredProductionContracts))
        NativeSmsV3AssetBinding(RELEASE_ID, MANIFEST_SHA256, byContract)
    } catch (error: NativeSmsV3AssetIntegrityException) {
        throw error
    } catch (error: Throwable) {
        throw NativeSmsV3AssetIntegrityException(error)
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.startsWith('/') || path.startsWith('\\')) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || '\\' in it }) return false
        return path.startsWith("configs/sms_processing/") ||
            path.startsWith("tests/sms_processing/golden/")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

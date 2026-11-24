package com.pocketfinancer.pipeline.sms

import android.content.res.AssetManager
import com.pocketfinancer.data.repository.SmsProcessingStore
import java.security.MessageDigest
import org.json.JSONObject

data class SmsContractArtifact(
    val contract: String,
    val path: String,
    val sha256: String
)

data class SmsContractAssetBinding(
    val releaseId: String,
    val manifestSha256: String,
    val duplicateWindowMs: Long,
    val artifactsByPath: Map<String, SmsContractArtifact>
) {
    fun sha256ForPath(path: String): String = artifactsByPath[path]?.sha256
        ?: error("Missing packaged contract asset: $path")
}

class SmsContractAssetIntegrityException(cause: Throwable? = null) :
    IllegalStateException(REASON_CODE, cause) {
    companion object {
        const val REASON_CODE = "operation_release_incompatible"
    }
}

/**
 * Verifies and loads the unified `pocketfinancer.sms-pipeline/1` contract assets
 * synchronized from `pocket-financer-intelligence`.
 */
object SmsContractAssets {
    const val ASSET_ROOT = "sms_processing"
    const val MANIFEST_CONTRACT = "pocketfinancer.contract-release-manifest/1"
    const val RELEASE_ID = "pocketfinancer.sms-pipeline/1"
    const val PERSISTENCE_POLICY_CONTRACT = "pocketfinancer.persistence-policy/1"
    const val PROCESSING_CONFIG_CONTRACT = "pocketfinancer.sms-pipeline/1"
    const val REVIEW_CASE_CONTRACT = "pocketfinancer.review-case/2"
    const val PROCESSING_RESULT_CONTRACT = "pocketfinancer.processing-result/3"
    const val PROCESSING_TRACE_CONTRACT = "pocketfinancer.processing-trace/3"
    const val DUPLICATE_WINDOW_MS = 300_000L

    const val PROMPT_PATH = "prompts/sms-extractor.txt"
    const val GRAMMAR_PATH = "grammars/sms-extractor.gbnf"
    const val CURRENCY_PROFILE_PATH = "profiles/currency.json"
    const val ACCOUNT_RESOLUTION_PROFILE_PATH = "profiles/account-resolution.json"
    const val EXTRACTOR_VALIDATION_PROFILE_PATH = "profiles/extractor-validation.json"
    const val PERSISTENCE_POLICY_PROFILE_PATH = "profiles/persistence-policy.json"
    const val REASON_CODES_PROFILE_PATH = "profiles/reason-codes.json"
    const val PROFILE_CORE_EN_PATH = "profiles/core-en.json"
    const val PROFILE_INDIA_PATH = "profiles/india.json"

    const val MANIFEST_SHA256 =
        "4890f6f905f83fd53604bcf0dbbb5935c913777a517ad55e8e3aa0c81915ebd0"
    const val EXPECTED_MANIFEST_ARTIFACT_COUNT = 19
    const val EXPECTED_ANDROID_ARTIFACT_COUNT = 17

    private val IOS_ONLY_PATHS = setOf(
        "prompts/apple-fm-sms-extractor.txt",
        "schemas/apple-fm-flat.schema.json"
    )

    private val EXPECTED_ANDROID_SHA256_BY_PATH = mapOf(
        "grammars/sms-extractor.gbnf" to
            "11672e2b7ce54dff57ea061572f8c0bfa37c12b066b4d0aa4f13756afce0507d",
        "profiles/account-resolution.json" to
            "a1e0f1ccba9ea377e30ba22f79d219dc0b598a9d5c0135e20f05d16008367687",
        "profiles/core-en.json" to
            "c9f7b95ce70528d4b564458e320a8788fdf2076ffb55d1d80e80687c48d48b52",
        "profiles/currency.json" to
            "cb5d991a5ade283f6b2406e4427a2fd1bf5468f67ab6f573f6eb97b4c5919c78",
        "profiles/extractor-validation.json" to
            "e9677dd72e992539a730a009c260bd7c9893cfba643eb8ce6e7f56c8c404daff",
        "profiles/india.json" to
            "1cb1a4d7431bb9fe141aa90d45bb3820a115942123668eb4efd6522a73ed0156",
        "profiles/persistence-policy.json" to
            "8dd1415ad1d238112aec5a9b25d2c0a52ac9280bc9307133c9c9df28129ace5f",
        "profiles/reason-codes.json" to
            "27b1b8692db08491775e3b331a63d7a8dc1b81e42622e789284e4b121e66aaab",
        "prompts/sms-extractor.txt" to
            "2903d1b1a31327f7980322d4825820723af6fc1964b6b95f395cc9f258ae4dbc",
        "schemas/processing-config.schema.json" to
            "d3c5afe1c1228e3d6ad79f3b6643bf0d819ec4c5b0bce5bf74fe9139ad1112bc",
        "schemas/processing-result.schema.json" to
            "0768bcb68dbae58e25d726467b108f6ba1f06267e1efbbc0fe37bff1b5dbf369",
        "schemas/processing-trace.schema.json" to
            "8d7c5ceebe19c465c3a5984f8c7253b6829d19a46a3e70452d54518b9d6b1c4c",
        "schemas/review-case.schema.json" to
            "50f65a5ef03155d764b33e2f645d4fd3447015b240768d5567f53f504308de88",
        "schemas/sms-analysis.schema.json" to
            "359b32c0ea9055aec2aaa2ab475dbc3e1316113f260cd4c5b8af48c6258c1ffe",
        "schemas/sms-extractor-input.schema.json" to
            "ad0d4addff4b201fdaa7dc6265db3bff0f378dad2dea91b2309605d6fdbc8cc4",
        "schemas/sms-extractor.schema.json" to
            "4e7b2b05c26f85e27c28c482f5323c5278aeb76cfbf5d8e3e382d24db4458267",
        "schemas/user-feedback.schema.json" to
            "8e902e1111f1f5c15fa8487895f83035e33a5048b50252a9c557e24f449d4fc2"
    )

    fun verify(assetManager: AssetManager): SmsContractAssetBinding = try {
        val manifestBytes = readLfAssetBytes(assetManager, "$ASSET_ROOT/release-manifest.json")
        check(sha256(manifestBytes) == MANIFEST_SHA256)
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        check(manifest.getString("contract") == MANIFEST_CONTRACT)
        check(manifest.getString("release_id") == RELEASE_ID)
        val duplicateWindowMs = manifest.getLong("duplicate_window_ms")
        check(duplicateWindowMs == DUPLICATE_WINDOW_MS)
        val routingPolicy = manifest.getJSONObject("routing_policy")
        check(routingPolicy.getString("policy_contract") == PERSISTENCE_POLICY_CONTRACT)
        check(routingPolicy.getString("processing_config_contract") == PROCESSING_CONFIG_CONTRACT)
        check(routingPolicy.getString("review_contract") == REVIEW_CASE_CONTRACT)
        check(routingPolicy.getString("processing_result_contract") == PROCESSING_RESULT_CONTRACT)

        val entries = manifest.getJSONArray("artifacts")
        check(entries.length() == EXPECTED_MANIFEST_ARTIFACT_COUNT)
        val byPath = linkedMapOf<String, SmsContractArtifact>().apply {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val path = entry.getString("path")
                val contract = entry.getString("contract")
                val expectedHash = entry.getString("sha256")
                if (path in IOS_ONLY_PATHS) continue
                check(EXPECTED_ANDROID_SHA256_BY_PATH[path] == expectedHash) {
                    "Pinned contract asset digest mismatch for $path"
                }
                val assetBytes = readLfAssetBytes(assetManager, "$ASSET_ROOT/$path")
                check(sha256(assetBytes) == expectedHash) {
                    "Packaged contract asset digest mismatch for $path"
                }
                check(put(path, SmsContractArtifact(contract, path, expectedHash)) == null)
            }
        }
        check(byPath.size == EXPECTED_ANDROID_ARTIFACT_COUNT)
        check(byPath.keys == EXPECTED_ANDROID_SHA256_BY_PATH.keys)
        SmsContractAssetBinding(
            releaseId = RELEASE_ID,
            manifestSha256 = MANIFEST_SHA256,
            duplicateWindowMs = duplicateWindowMs,
            artifactsByPath = byPath
        )
    } catch (error: SmsContractAssetIntegrityException) {
        throw error
    } catch (error: Exception) {
        throw SmsContractAssetIntegrityException(error)
    }

    fun readExtractorPrompt(assetManager: AssetManager): String =
        readLfAssetText(assetManager, "$ASSET_ROOT/$PROMPT_PATH")

    fun readExtractorGrammar(assetManager: AssetManager): String =
        readLfAssetText(assetManager, "$ASSET_ROOT/$GRAMMAR_PATH")

    fun readProfileJson(assetManager: AssetManager, relativePath: String): String =
        readLfAssetText(assetManager, "$ASSET_ROOT/$relativePath")

    private fun readLfAssetText(assetManager: AssetManager, fullPath: String): String =
        readLfAssetBytes(assetManager, fullPath).toString(Charsets.UTF_8)

    private fun readLfAssetBytes(assetManager: AssetManager, fullPath: String): ByteArray =
        assetManager.open(fullPath).use { it.readBytes() }.also { bytes ->
            check(bytes.none { byte -> byte == '\r'.code.toByte() }) {
                "CRLF line ending detected in contract asset: $fullPath"
            }
        }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    internal fun sha256(text: String): String = SmsProcessingStore.sha256(text)
}

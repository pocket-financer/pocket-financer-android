package com.pocketfinancer.pipeline.sms

import android.content.res.AssetManager
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Hash-bound automatic-routing release. Earlier native bundles remain
 * independently readable so a stored operation always keeps its own policy.
 */
object NativeSmsV5Assets {
    const val RELEASE_ID = "native-integration-v5"
    const val MANIFEST_SHA256 =
        "971ac758729916e8dfdd8ea165d4e8344b5d3ab6e4d190473c8e91e2541c5f6d"
    const val ASSET_ROOT = "sms_processing/native-integration-v5"
    const val MANIFEST_PATH =
        "$ASSET_ROOT/configs/sms_processing/contracts/releases/native-integration-v5.json"
    const val PROCESSING_CONFIG_SHA256 =
        "1a0f51bc2226fb4255fc4f22c8778a4f7f1200d8c25f377b3b35acdff08c6bca"
    const val REVIEW_CASE_SHA256 =
        "50f65a5ef03155d764b33e2f645d4fd3447015b240768d5567f53f504308de88"
    const val REASON_REGISTRY_SHA256 =
        "50f1dbdf5a5725d0ee7ae895b966309be4431f5370f244e1dedc22447a498228"
    const val PERSISTENCE_POLICY_SHA256 =
        "9c98c4f8bb715f38acf95fa8f3c18b541664fbfdde6fa879d1d1105ba2f873d6"
    const val MANIFEST_SCHEMA_SHA256 =
        "5339f5b45c7618069858b41fee72047adef30cc01e4408cd36a12a106cbbd2ff"
    const val ROUTING_GOLDEN_SHA256 =
        "e403f05b3a0fa3ee88b528892412d53374f1218dedd2a88d31857e6c5fa0d378"

    fun verify(assetManager: AssetManager): NativeSmsV3AssetBinding = try {
        val bytes = assetManager.open(MANIFEST_PATH).use { it.readBytes() }
        check(sha256(bytes) == MANIFEST_SHA256)
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        check(manifest.getString("release_id") == RELEASE_ID)
        check(manifest.getBoolean("automatic_persistence_enabled"))
        val compatibility = manifest.getJSONObject("compatibility")
        check(compatibility.getString("stored_operation_behavior") == "original_release")
        val frozen = compatibility.getJSONArray("frozen_release_ids")
        check((0 until frozen.length()).map(frozen::getString) == listOf(
            "native-integration-v1",
            "native-integration-v2",
            "native-integration-v3",
            "native-integration-v4"
        ))
        val routing = manifest.getJSONObject("routing_policy")
        check(routing.getString("complete_posted") == "transactions")
        check(routing.getString("exception") == "review")
        check(routing.getString("duplicate") == "review")
        check(routing.getString("valid_none") == "no_transaction")
        check(routing.getString("retry") == "new_operation_with_parent_operation_id")
        check(routing.getString("transaction_write") == "atomic_with_operation_settlement")
        check(routing.getString("processing_config_contract") == "pocketfinancer.processing-config/5")
        check(routing.getString("review_contract") == "pocketfinancer.review-case/2")
        check(routing.getString("policy_contract") == "pocketfinancer.persistence-policy/2")

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
        check(bindings["pocketfinancer.processing-config/5"]?.sha256 == PROCESSING_CONFIG_SHA256)
        check(bindings["pocketfinancer.review-case/2"]?.sha256 == REVIEW_CASE_SHA256)
        check(bindings["pocketfinancer.reason-code-registry/3"]?.sha256 == REASON_REGISTRY_SHA256)
        check(bindings["pocketfinancer.persistence-policy/2"]?.sha256 == PERSISTENCE_POLICY_SHA256)
        check(bindings["pocketfinancer.contract-release-manifest-schema/5"]?.sha256 == MANIFEST_SCHEMA_SHA256)
        check(bindings["pocketfinancer.native-routing-golden/2"]?.sha256 == ROUTING_GOLDEN_SHA256)
        NativeSmsV3AssetBinding(RELEASE_ID, MANIFEST_SHA256, bindings)
    } catch (error: Throwable) {
        throw NativeSmsV3AssetIntegrityException(error)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

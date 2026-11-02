package com.pocketfinancer.pipeline.sms

import android.content.Context
import android.os.Build
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.repository.SmsProcessingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class SmsOperationSnapshotFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SmsProcessingStore
) {
    suspend fun create(
        source: AdmittedMessageRef,
        trigger: String,
        primaryCurrency: String,
        enabledProfiles: List<String>,
        sourceTimestampEpochMs: Long?,
        sourceTimestampProvenance: String,
        admissionTimestampEpochMs: Long,
        selectorModelId: String?,
        selectorModelHash: String?,
        selectorRuntimeVersion: String,
        deviceCohort: String,
        stableEventId: String? = null,
        parentOperationId: String? = null,
        now: Long = System.currentTimeMillis()
    ): SmsOperationSnapshot {
        require(trigger in TRIGGERS)
        require(primaryCurrency in CurrencyProfileRegistry.scales)
        require(enabledProfiles.isNotEmpty() && enabledProfiles.distinct().size == enabledProfiles.size)
        require((sourceTimestampEpochMs == null) == (sourceTimestampProvenance == "unknown"))
        val operationId = UUID.randomUUID().toString()
        val resolvedStableEventId = stableEventId ?: UUID.randomUUID().toString()
        require(
            UUID.fromString(resolvedStableEventId).toString() ==
                resolvedStableEventId.lowercase()
        )
        val manifestHash = assetHash("sms_processing/manifest.json")
        val currencyHash = checkedAssetHash(
            "sms_processing/currency-v1.json",
            CURRENCY_HASH
        )
        val promptHash = checkedAssetHash(
            "sms_processing/selector-prompt-v1.txt",
            PROMPT_HASH
        )
        val eligible = selectorModelId != null
        val profileHashes = enabledProfiles.associateWith { profile ->
            val expected = PROFILE_HASHES[profile]
                ?: error("Unsupported SMS analyzer profile")
            checkedAssetHash("sms_processing/profile-$profile-v1.json", expected)
        }
        val configuration = SmsOperationConfiguration(
            operationId = operationId,
            parentOperationId = parentOperationId,
            sourceId = source.sourceId,
            sourceRefHash = SmsProcessingStore.sha256(source.sourceId),
            trigger = trigger,
            createdAtEpochMs = now,
            primaryCurrency = primaryCurrency,
            enabledProfiles = enabledProfiles,
            sourceTimestampEpochMs = sourceTimestampEpochMs,
            sourceTimestampProvenance = sourceTimestampProvenance,
            admissionTimestampEpochMs = admissionTimestampEpochMs,
            timezoneId = TimeZone.getDefault().id,
            releaseManifestHash = manifestHash,
            currencyAssetHash = currencyHash,
            profileAssetHashes = profileHashes,
            selectorEligible = eligible,
            selectorIneligibilityReason = if (eligible) null else "runtime_unavailable",
            selectorModelId = selectorModelId ?: "unavailable",
            selectorModelHash = selectorModelHash,
            selectorRuntimeVersion = selectorRuntimeVersion,
            osVersion = Build.VERSION.RELEASE,
            deviceCohort = deviceCohort,
            promptHash = promptHash
        )
        val payload = payload(configuration)
        val configurationHash = SmsProcessingStore.sha256(CanonicalAndroidJson.stringify(payload))
        payload.put("config_hash", configurationHash)
        val configurationJson = CanonicalAndroidJson.stringify(payload)
        store.createOperation(
            SmsProcessingOperationEntity(
                id = operationId,
                sourceId = source.sourceId,
                parentOperationId = parentOperationId,
                stableEventId = resolvedStableEventId,
                trigger = trigger,
                configurationJson = configurationJson,
                configurationHash = configurationHash,
                contractReleaseId = configuration.releaseId,
                state = "ready",
                transitionSequence = 0,
                ownerToken = null,
                ownerGeneration = 0,
                claimExpiresAt = null,
                createdAt = now,
                updatedAt = now,
                settledAt = null,
                settlementReceiptJson = null,
                deletionEpoch = 0
            )
        )
        return SmsOperationSnapshot(
            operationId,
            parentOperationId,
            resolvedStableEventId,
            configuration,
            configurationJson,
            configurationHash
        )
    }

    internal fun configurationMatches(snapshot: SmsOperationSnapshot): Boolean = runCatching {
        val expectedPayload = payload(snapshot.configuration)
        val expectedHash = SmsProcessingStore.sha256(
            CanonicalAndroidJson.stringify(expectedPayload)
        )
        if (expectedHash != snapshot.configurationHash) return@runCatching false
        expectedPayload.put("config_hash", expectedHash)
        CanonicalAndroidJson.stringify(expectedPayload) == snapshot.configurationJson
    }.getOrDefault(false)

    private fun payload(value: SmsOperationConfiguration): JSONObject = JSONObject()
        .put("contract", value.contract)
        .put("operation_id", value.operationId)
        .put("parent_operation_id", value.parentOperationId ?: JSONObject.NULL)
        .put("source_ref_hash", value.sourceRefHash)
        .put("trigger", value.trigger)
        .put("created_at_epoch_ms", value.createdAtEpochMs)
        .put("admission_epoch_ms", value.admissionTimestampEpochMs)
        .put(
            "contract_release",
            JSONObject()
                .put("release_id", value.releaseId)
                .put("manifest_sha256", value.releaseManifestHash)
        )
        .put(
            "analyzer",
            JSONObject()
                .put("behavior_version", "pocketfinancer.structural-sms-analyzer/2")
                .put("unicode_behavior_version", "14.0.0-per-code-point-nfkc-casefold")
                .put("currency_asset_sha256", value.currencyAssetHash)
                .put(
                    "profile_assets",
                    JSONArray(
                        value.enabledProfiles.map { profile ->
                            JSONObject()
                                .put("asset_id", profile)
                                .put("sha256", value.profileAssetHashes.getValue(profile))
                        }
                    )
                )
        )
        .put(
            "currency_context",
            JSONObject()
                .put("primary_currency", value.primaryCurrency)
                .put("enabled_profile_ids", JSONArray(value.enabledProfiles))
        )
        .put(
            "source_timestamp",
            JSONObject()
                .put("epoch_ms", value.sourceTimestampEpochMs ?: JSONObject.NULL)
                .put("provenance", value.sourceTimestampProvenance)
                .put("timezone_id", value.timezoneId)
                .put("policy_version", "pocketfinancer.timestamp-policy/1")
        )
        .put(
            "selector",
            JSONObject()
                .put("eligible", value.selectorEligible)
                .put("ineligibility_reason", value.selectorIneligibilityReason ?: JSONObject.NULL)
                .put("model_identifier", if (value.selectorEligible) value.selectorModelId else JSONObject.NULL)
                .put("model_file_sha256", value.selectorModelHash ?: JSONObject.NULL)
                .put("runtime_version", value.selectorRuntimeVersion)
                .put("os_version", value.osVersion)
                .put("device_cohort", value.deviceCohort)
                .put("prompt_version", "pocketfinancer.selector-prompt/1")
                .put("prompt_sha256", value.promptHash)
                .put("validation_profile", "pocketfinancer.selector-validation-profile/3")
                .put("generation_mode", value.generationMode)
                .put("decoding", value.decoding)
                .put("answer_token_limit", value.answerTokenLimit)
                .put("raw_output_utf8_byte_limit", value.rawOutputByteLimit)
                .put("parser_deadline_ms", value.parserDeadlineMs)
        )
        .put(
            "persistence_policy",
            JSONObject()
                .put("version", "pocketfinancer.persistence-policy/1")
                .put("rollout_mode", value.rolloutMode)
        )

    private fun assetHash(path: String): String = context.assets.open(path)
        .bufferedReader(Charsets.UTF_8)
        .use { SmsProcessingStore.sha256(it.readText()) }

    private fun checkedAssetHash(path: String, expected: String): String =
        assetHash(path).also { actual ->
            check(actual == expected) { "Pinned SMS processing asset hash mismatch" }
        }

    private companion object {
        val TRIGGERS = setOf(
            "realtime", "historical", "manual", "retry", "diagnostic", "app_intent",
            "background_recovery"
        )
        const val CURRENCY_HASH = "cb5d991a5ade283f6b2406e4427a2fd1bf5468f67ab6f573f6eb97b4c5919c78"
        const val PROMPT_HASH = "3da47ec16b074ddcf2c03526dd968f9f1f668f9bfbed69bc01c4e2b5c613b940"
        val PROFILE_HASHES = mapOf(
            "core-en" to "c9f7b95ce70528d4b564458e320a8788fdf2076ffb55d1d80e80687c48d48b52",
            "india" to "1cb1a4d7431bb9fe141aa90d45bb3820a115942123668eb4efd6522a73ed0156"
        )
    }
}

internal object CanonicalAndroidJson {
    fun stringify(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { key -> "${JSONObject.quote(key)}:${stringify(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]", separator = ","
        ) { stringify(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported canonical JSON value: ${value::class.java.simpleName}")
    }
}

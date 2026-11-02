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

data class SmsV4OperationConfiguration(
    val contract: String = "pocketfinancer.processing-config/4",
    val releaseId: String = NativeSmsV4Assets.RELEASE_ID,
    val operationId: String,
    val parentOperationId: String?,
    val sourceId: String,
    val sourceRefHash: String,
    val trigger: String,
    val createdAtEpochMs: Long,
    val admissionTimestampEpochMs: Long,
    val receivedTimestampEpochMs: Long,
    val receivedTimestampProvenance: String,
    val timezoneId: String,
    val primaryCurrency: String,
    val enabledProfiles: List<String>,
    val releaseManifestHash: String,
    val currencyAssetHash: String,
    val profileAssetHashes: Map<String, String>,
    val extractorEligible: Boolean,
    val extractorIneligibilityReason: String?,
    val modelIdentifier: String?,
    val modelFileSha256: String?,
    val modelIdentityKind: String,
    val runtimeVersion: String,
    val osVersion: String,
    val deviceCohort: String,
    val promptHash: String,
    val grammarHash: String,
    val validationProfileHash: String,
    val rolloutMode: String = "review_only"
)

data class SmsV4OperationSnapshot(
    val operationId: String,
    val parentOperationId: String?,
    val stableEventId: String,
    val configuration: SmsV4OperationConfiguration,
    val configurationJson: String,
    val configurationHash: String
)

@Singleton
class SmsV4OperationSnapshotFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SmsProcessingStore
) {
    private val release by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeSmsV4Assets.verify(context.assets)
    }

    suspend fun create(
        source: AdmittedMessageRef,
        trigger: String,
        primaryCurrency: String,
        enabledProfiles: List<String>,
        receivedTimestampEpochMs: Long,
        receivedTimestampProvenance: String,
        admissionTimestampEpochMs: Long,
        modelIdentity: SmsV4ModelIdentity,
        runtimeVersion: String,
        deviceCohort: String,
        stableEventId: String? = null,
        parentOperationId: String? = null,
        now: Long = System.currentTimeMillis()
    ): SmsV4OperationSnapshot {
        require(trigger in TRIGGERS)
        require(primaryCurrency in CurrencyProfileRegistry.scales)
        require(enabledProfiles.isNotEmpty() && enabledProfiles.distinct().size == enabledProfiles.size)
        require(receivedTimestampEpochMs >= 0L)
        require(receivedTimestampProvenance in RECEIVED_PROVENANCE)
        require(admissionTimestampEpochMs >= 0L)
        val operationId = UUID.randomUUID().toString()
        val resolvedStableEventId = stableEventId ?: UUID.randomUUID().toString()
        require(UUID.fromString(resolvedStableEventId).toString() == resolvedStableEventId.lowercase())

        val binding = release
        fun hash(contract: String): String = binding.artifactsByContract[contract]?.sha256
            ?: error("Missing frozen v4 asset: $contract")
        val profileHashes = enabledProfiles.associateWith { profile ->
            hash("pocketfinancer.analyzer-profile/1:$profile")
        }
        val configuration = SmsV4OperationConfiguration(
            operationId = operationId,
            parentOperationId = parentOperationId,
            sourceId = source.sourceId,
            sourceRefHash = SmsProcessingStore.sha256(source.sourceId),
            trigger = trigger,
            createdAtEpochMs = now,
            admissionTimestampEpochMs = admissionTimestampEpochMs,
            receivedTimestampEpochMs = receivedTimestampEpochMs,
            receivedTimestampProvenance = receivedTimestampProvenance,
            timezoneId = TimeZone.getDefault().id,
            primaryCurrency = primaryCurrency,
            enabledProfiles = enabledProfiles,
            releaseManifestHash = binding.manifestSha256,
            currencyAssetHash = hash("pocketfinancer.supported-currencies/1"),
            profileAssetHashes = profileHashes,
            extractorEligible = modelIdentity.eligible,
            extractorIneligibilityReason = if (modelIdentity.eligible) null else "runtime_unavailable",
            modelIdentifier = modelIdentity.modelIdentifier,
            modelFileSha256 = modelIdentity.modelFileSha256,
            modelIdentityKind = modelIdentity.kind,
            runtimeVersion = runtimeVersion,
            osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" },
            deviceCohort = deviceCohort,
            promptHash = hash("pocketfinancer.extractor-prompt/1"),
            grammarHash = hash("pocketfinancer.extractor-grammar/1"),
            validationProfileHash = hash("pocketfinancer.extractor-validation-profile/1")
        )
        val payload = payload(configuration)
        val hash = SmsProcessingStore.sha256(CanonicalAndroidJson.stringify(payload))
        payload.put("config_hash", hash)
        val json = CanonicalAndroidJson.stringify(payload)
        store.createOperation(
            SmsProcessingOperationEntity(
                id = operationId,
                sourceId = source.sourceId,
                parentOperationId = parentOperationId,
                stableEventId = resolvedStableEventId,
                trigger = trigger,
                configurationJson = json,
                configurationHash = hash,
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
        return SmsV4OperationSnapshot(
            operationId, parentOperationId, resolvedStableEventId,
            configuration, json, hash
        )
    }

    internal fun configurationMatches(snapshot: SmsV4OperationSnapshot): Boolean = runCatching {
        val expected = payload(snapshot.configuration)
        val hash = SmsProcessingStore.sha256(CanonicalAndroidJson.stringify(expected))
        if (hash != snapshot.configurationHash) return@runCatching false
        expected.put("config_hash", hash)
        CanonicalAndroidJson.stringify(expected) == snapshot.configurationJson
    }.getOrDefault(false)

    private fun payload(value: SmsV4OperationConfiguration): JSONObject = JSONObject()
        .put("contract", value.contract)
        .put("operation_id", value.operationId)
        .put("parent_operation_id", value.parentOperationId ?: JSONObject.NULL)
        .put("source_ref_hash", value.sourceRefHash)
        .put("trigger", value.trigger)
        .put("created_at_epoch_ms", value.createdAtEpochMs)
        .put("admission_epoch_ms", value.admissionTimestampEpochMs)
        .put("contract_release", JSONObject()
            .put("release_id", value.releaseId)
            .put("manifest_sha256", value.releaseManifestHash))
        .put("analyzer", JSONObject()
            .put("behavior_version", "pocketfinancer.structural-sms-analyzer/2")
            .put("unicode_behavior_version", "14.0.0-per-code-point-nfkc-casefold")
            .put("currency_asset_sha256", value.currencyAssetHash)
            .put("profile_assets", JSONArray(value.enabledProfiles.map { profile ->
                JSONObject().put("asset_id", profile)
                    .put("sha256", value.profileAssetHashes.getValue(profile))
            })))
        .put("currency_context", JSONObject()
            .put("primary_currency", value.primaryCurrency)
            .put("enabled_profile_ids", JSONArray(value.enabledProfiles)))
        .put("received_timestamp", JSONObject()
            .put("epoch_ms", value.receivedTimestampEpochMs)
            .put("provenance", value.receivedTimestampProvenance)
            .put("timezone_id", value.timezoneId)
            .put("read_only", true))
        .put("extractor", JSONObject()
            .put("eligible", value.extractorEligible)
            .put("ineligibility_reason", value.extractorIneligibilityReason ?: JSONObject.NULL)
            .put("model_identifier", value.modelIdentifier ?: JSONObject.NULL)
            .put("model_file_sha256", value.modelFileSha256 ?: JSONObject.NULL)
            .put("model_identity_kind", value.modelIdentityKind)
            .put("runtime_version", value.runtimeVersion)
            .put("os_version", value.osVersion)
            .put("device_cohort", value.deviceCohort)
            .put("prompt_sha256", value.promptHash)
            .put("grammar_sha256", value.grammarHash)
            .put("validation_profile_sha256", value.validationProfileHash)
            .put("prompt_version", "pocketfinancer.extractor-prompt/1")
            .put("validation_profile", "pocketfinancer.extractor-validation-profile/1")
            .put("grammar_version", "pocketfinancer.extractor-grammar/1")
            .put("generation_mode", "DIRECT_NON_THINKING")
            .put("decoding", "greedy")
            .put("answer_token_limit", 512)
            .put("raw_output_utf8_byte_limit", 16_384)
            .put("parser_deadline_ms", 0))
        .put("persistence_policy", JSONObject()
            .put("version", "pocketfinancer.persistence-policy/1")
            .put("rollout_mode", value.rolloutMode))

    private companion object {
        val TRIGGERS = setOf(
            "realtime", "historical", "manual", "retry", "diagnostic", "app_intent",
            "background_recovery"
        )
        val RECEIVED_PROVENANCE = setOf(
            "platform_received", "acquisition_supplied_message_time"
        )
    }
}

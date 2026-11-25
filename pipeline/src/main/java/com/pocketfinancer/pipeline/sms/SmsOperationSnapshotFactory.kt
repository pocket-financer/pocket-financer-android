package com.pocketfinancer.pipeline.sms

import android.content.Context
import android.os.Build
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

data class SmsOperationConfiguration(
    val contract: String = SmsContractAssets.PROCESSING_CONFIG_CONTRACT,
    val releaseId: String = SmsContractAssets.RELEASE_ID,
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
    val grammarEnabled: Boolean = false,
    val validationProfileHash: String,
    val wireTransport: String = "direct_json",
    val rolloutMode: String = "automatic"
)

data class SmsOperationSnapshot(
    val operationId: String,
    val parentOperationId: String?,
    val stableEventId: String,
    val configuration: SmsOperationConfiguration,
    val configurationJson: String,
    val configurationHash: String,
    val admissionClaim: SmsOperationClaim? = null
)

@Singleton
class SmsOperationSnapshotFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SmsProcessingStore
) {
    private val contractBinding by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SmsContractAssets.verify(context.assets)
    }

    suspend fun create(
        source: AdmittedMessageRef,
        trigger: String,
        primaryCurrency: String,
        enabledProfiles: List<String>,
        receivedTimestampEpochMs: Long,
        receivedTimestampProvenance: String,
        admissionTimestampEpochMs: Long,
        modelIdentity: SmsModelIdentity,
        runtimeVersion: String,
        deviceCohort: String,
        stableEventId: String? = null,
        parentOperationId: String? = null,
        now: Long = System.currentTimeMillis(),
        grammarEnabled: Boolean = false
    ): SmsOperationSnapshot {
        require(trigger in TRIGGERS)
        require(primaryCurrency in CurrencyProfileRegistry.scales)
        require(
            enabledProfiles.isNotEmpty() &&
                enabledProfiles.distinct().size == enabledProfiles.size
        )
        require(receivedTimestampEpochMs >= 0L)
        require(receivedTimestampProvenance in RECEIVED_PROVENANCE)
        require(admissionTimestampEpochMs >= 0L && now >= admissionTimestampEpochMs)
        require(trigger != "retry" || parentOperationId != null)
        val operationId = UUID.randomUUID().toString()
        require(parentOperationId == null || parentOperationId != operationId)
        val resolvedStableEventId = stableEventId ?: UUID.randomUUID().toString()
        require(
            UUID.fromString(resolvedStableEventId).toString() == resolvedStableEventId.lowercase()
        )

        val binding = contractBinding
        val profileHashes = enabledProfiles.associateWith { profile ->
            binding.sha256ForPath("profiles/$profile.json")
        }
        val configuration = SmsOperationConfiguration(
            contract = SmsContractAssets.PROCESSING_CONFIG_CONTRACT,
            releaseId = binding.releaseId,
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
            currencyAssetHash = binding.sha256ForPath(SmsContractAssets.CURRENCY_PROFILE_PATH),
            profileAssetHashes = profileHashes,
            extractorEligible = modelIdentity.eligible,
            extractorIneligibilityReason =
                if (modelIdentity.eligible) null else "runtime_unavailable",
            modelIdentifier = modelIdentity.modelIdentifier,
            modelFileSha256 = modelIdentity.modelFileSha256,
            modelIdentityKind = modelIdentity.kind,
            runtimeVersion = runtimeVersion,
            osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            deviceCohort = deviceCohort,
            promptHash = binding.sha256ForPath(SmsContractAssets.PROMPT_PATH),
            grammarHash = binding.sha256ForPath(SmsContractAssets.GRAMMAR_PATH),
            grammarEnabled = grammarEnabled,
            validationProfileHash =
                binding.sha256ForPath(SmsContractAssets.EXTRACTOR_VALIDATION_PROFILE_PATH)
        )
        val payload = payload(configuration)
        val configurationHash = SmsProcessingStore.sha256(CanonicalAndroidJson.stringify(payload))
        payload.put("config_hash", configurationHash)
        val configurationJson = CanonicalAndroidJson.stringify(payload)
        val storedOperation = SmsProcessingOperationEntity(
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
        val admissionClaim = if (trigger == "retry") {
            store.createRetryOperationAndClaim(storedOperation)
        } else {
            store.createOperation(storedOperation)
            null
        }
        return SmsOperationSnapshot(
            operationId = operationId,
            parentOperationId = parentOperationId,
            stableEventId = resolvedStableEventId,
            configuration = configuration,
            configurationJson = configurationJson,
            configurationHash = configurationHash,
            admissionClaim = admissionClaim
        )
    }

    /**
     * Decodes a persisted frozen operation entity (such as a stored `ready` snapshot during
     * startup recovery or original-configuration inspection) and verifies its canonical hash.
     */
    fun decode(entity: SmsProcessingOperationEntity): SmsOperationSnapshot? = runCatching {
        if (entity.contractReleaseId != SmsContractAssets.RELEASE_ID) return@runCatching null
        val json = JSONObject(entity.configurationJson)
        if (json.optString("config_hash") != entity.configurationHash) return@runCatching null
        if (json.optString("contract") != SmsContractAssets.PROCESSING_CONFIG_CONTRACT) {
            return@runCatching null
        }

        val release = json.getJSONObject("contract_release")
        val releaseId = release.getString("release_id")
        if (releaseId != SmsContractAssets.RELEASE_ID) return@runCatching null

        val operationId = json.getString("operation_id")
        if (operationId != entity.id) return@runCatching null
        val parentOperationId = json.optString("parent_operation_id")
            .takeIf { !json.isNull("parent_operation_id") && it.isNotBlank() }
        if (parentOperationId != entity.parentOperationId) return@runCatching null
        val sourceRefHash = json.getString("source_ref_hash")
        if (sourceRefHash != SmsProcessingStore.sha256(entity.sourceId)) return@runCatching null

        val analyzer = json.getJSONObject("analyzer")
        val profileAssetsJson = analyzer.getJSONArray("profile_assets")
        val profileHashes = linkedMapOf<String, String>()
        for (i in 0 until profileAssetsJson.length()) {
            val item = profileAssetsJson.getJSONObject(i)
            profileHashes[item.getString("asset_id")] = item.getString("sha256")
        }

        val currencyContext = json.getJSONObject("currency_context")
        val enabledProfilesJson = currencyContext.getJSONArray("enabled_profile_ids")
        val enabledProfiles = (0 until enabledProfilesJson.length()).map {
            enabledProfilesJson.getString(it)
        }

        val received = json.getJSONObject("received_timestamp")
        val extractor = json.getJSONObject("extractor")
        val persistencePolicy = json.getJSONObject("persistence_policy")
        if (
            persistencePolicy.optString("version") !=
            SmsContractAssets.PERSISTENCE_POLICY_CONTRACT
        ) {
            return@runCatching null
        }

        val configuration = SmsOperationConfiguration(
            contract = json.getString("contract"),
            releaseId = releaseId,
            operationId = operationId,
            parentOperationId = parentOperationId,
            sourceId = entity.sourceId,
            sourceRefHash = sourceRefHash,
            trigger = json.getString("trigger"),
            createdAtEpochMs = json.getLong("created_at_epoch_ms"),
            admissionTimestampEpochMs = json.getLong("admission_epoch_ms"),
            receivedTimestampEpochMs = received.getLong("epoch_ms"),
            receivedTimestampProvenance = received.getString("provenance"),
            timezoneId = received.getString("timezone_id"),
            primaryCurrency = currencyContext.getString("primary_currency"),
            enabledProfiles = enabledProfiles,
            releaseManifestHash = release.getString("manifest_sha256"),
            currencyAssetHash = analyzer.getString("currency_asset_sha256"),
            profileAssetHashes = profileHashes,
            extractorEligible = extractor.getBoolean("eligible"),
            extractorIneligibilityReason = extractor.optString("ineligibility_reason")
                .takeIf { !extractor.isNull("ineligibility_reason") && it.isNotBlank() },
            modelIdentifier = extractor.optString("model_identifier")
                .takeIf { !extractor.isNull("model_identifier") && it.isNotBlank() },
            modelFileSha256 = extractor.optString("model_file_sha256")
                .takeIf { !extractor.isNull("model_file_sha256") && it.isNotBlank() },
            modelIdentityKind = extractor.getString("model_identity_kind"),
            runtimeVersion = extractor.getString("runtime_version"),
            osVersion = extractor.getString("os_version"),
            deviceCohort = extractor.getString("device_cohort"),
            promptHash = extractor.getString("prompt_sha256"),
            grammarHash = extractor.getString("grammar_sha256"),
            grammarEnabled = extractor.getBoolean("grammar_enabled"),
            validationProfileHash = extractor.getString("validation_profile_sha256"),
            wireTransport = extractor.getString("wire_transport"),
            rolloutMode = persistencePolicy.getString("rollout_mode")
        )
        val snapshot = SmsOperationSnapshot(
            operationId = entity.id,
            parentOperationId = entity.parentOperationId,
            stableEventId = entity.stableEventId,
            configuration = configuration,
            configurationJson = entity.configurationJson,
            configurationHash = entity.configurationHash,
            admissionClaim = null
        )
        snapshot.takeIf(::configurationMatches)
    }.getOrNull()

    internal fun configurationMatches(snapshot: SmsOperationSnapshot): Boolean = runCatching {
        val expected = payload(snapshot.configuration)
        val hash = SmsProcessingStore.sha256(CanonicalAndroidJson.stringify(expected))
        if (hash != snapshot.configurationHash) return@runCatching false
        expected.put("config_hash", hash)
        CanonicalAndroidJson.stringify(expected) == snapshot.configurationJson
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
                            JSONObject().put("asset_id", profile)
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
            "received_timestamp",
            JSONObject()
                .put("epoch_ms", value.receivedTimestampEpochMs)
                .put("provenance", value.receivedTimestampProvenance)
                .put("timezone_id", value.timezoneId)
                .put("read_only", true)
        )
        .put(
            "extractor",
            JSONObject()
                .put("eligible", value.extractorEligible)
                .put(
                    "ineligibility_reason",
                    value.extractorIneligibilityReason ?: JSONObject.NULL
                )
                .put("model_identifier", value.modelIdentifier ?: JSONObject.NULL)
                .put("model_file_sha256", value.modelFileSha256 ?: JSONObject.NULL)
                .put("model_identity_kind", value.modelIdentityKind)
                .put("runtime_version", value.runtimeVersion)
                .put("os_version", value.osVersion)
                .put("device_cohort", value.deviceCohort)
                .put("prompt_sha256", value.promptHash)
                .put("grammar_sha256", value.grammarHash)
                .put("grammar_enabled", value.grammarEnabled)
                .put("validation_profile_sha256", value.validationProfileHash)
                .put("prompt_version", "pocketfinancer.extractor-prompt/1")
                .put("validation_profile", "pocketfinancer.extractor-validation-profile/1")
                .put("grammar_version", "pocketfinancer.extractor-grammar/1")
                .put("generation_mode", "DIRECT_NON_THINKING")
                .put("decoding", "greedy")
                .put("answer_token_limit", 512)
                .put("raw_output_utf8_byte_limit", 16_384)
                .put("parser_deadline_ms", 0)
                .put("wire_transport", value.wireTransport)
        )
        .put(
            "persistence_policy",
            JSONObject()
                .put("version", SmsContractAssets.PERSISTENCE_POLICY_CONTRACT)
                .put("rollout_mode", value.rolloutMode)
        )

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

internal object CanonicalAndroidJson {
    fun stringify(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { key -> "${JSONObject.quote(key)}:${stringify(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { stringify(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported canonical JSON value: ${value::class.java.simpleName}")
    }
}

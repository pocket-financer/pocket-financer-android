package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.GroundedAccountResolution
import com.pocketfinancer.data.repository.GroundedAccountResolver
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.CandidateSelectorRuntimeProfile
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.withLease
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Direct-extractor successor. Analyzer output is advisory and cannot skip inference. */
@Singleton
class DefaultSmsV4ProcessingCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SmsProcessingStore,
    private val runtime: SlmRuntime,
    private val modelStorage: SlmModelStorage,
    private val deviceCapabilities: DeviceCapabilities,
    private val extractor: DefaultDirectSmsExtractor,
    private val accountResolver: GroundedAccountResolver,
    private val snapshotFactory: SmsV4OperationSnapshotFactory
) {
    private val analyzer = StructuralSmsAnalyzer()

    suspend fun process(
        source: AdmittedMessageRef,
        operation: SmsV4OperationSnapshot,
        observer: SmsProcessingObserver = SmsProcessingObserver.None
    ): SmsProcessingOutcome = processInternal(source, operation, observer, null)

    suspend fun processUsingLease(
        source: AdmittedMessageRef,
        operation: SmsV4OperationSnapshot,
        lease: SlmLease,
        observer: SmsProcessingObserver = SmsProcessingObserver.None
    ): SmsProcessingOutcome = processInternal(source, operation, observer, lease)

    fun currentModelIdentity(): SmsV4ModelIdentity = identityFor(resolveCurrentModel())
    fun modelIdentityForLease(lease: SlmLease): SmsV4ModelIdentity = identityFor(lease.model)

    private suspend fun processInternal(
        source: AdmittedMessageRef,
        operation: SmsV4OperationSnapshot,
        observer: SmsProcessingObserver,
        preferredLease: SlmLease?
    ): SmsProcessingOutcome {
        if (!operationIsValid(source, operation)) {
            return retainWithoutClaim(operation, listOf("persistence_configuration_hash_mismatch"))
        }
        settledOutcome(operation)?.let { return it }
        val claim = try {
            store.claim(operation.operationId, System.currentTimeMillis())
        } catch (_: Exception) {
            return retainWithoutClaim(operation, listOf("persistence_claim_ownership_invalid"))
        }
        return supervisorScope {
            val heartbeat = launchHeartbeat(claim)
            try {
                processOwned(source, operation, claim, observer, preferredLease)
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    val stopped = store.requestStop(operation.operationId, System.currentTimeMillis())
                    SmsProcessingOutcome.Stopped(
                        operation.operationId, stopped.reviewCaseId,
                        if (stopped.committed) "already_committed" else "processing_cancelled"
                    )
                }
            } catch (_: Exception) {
                retainWithoutClaim(operation, listOf("operation_interrupted"))
            } finally {
                heartbeat.cancel()
            }
        }
    }

    private fun operationIsValid(
        source: AdmittedMessageRef,
        operation: SmsV4OperationSnapshot
    ): Boolean = source.sourceId == operation.configuration.sourceId &&
        SmsProcessingStore.sha256(source.sourceId) == operation.configuration.sourceRefHash &&
        operation.operationId == operation.configuration.operationId &&
        operation.parentOperationId == operation.configuration.parentOperationId &&
        operation.configuration.contract == "pocketfinancer.processing-config/4" &&
        operation.configuration.releaseId == NativeSmsV4Assets.RELEASE_ID &&
        operation.configuration.releaseManifestHash == NativeSmsV4Assets.MANIFEST_SHA256 &&
        operation.configuration.modelIdentityKind == "file_sha256" &&
        operation.configuration.rolloutMode == "review_only" &&
        snapshotFactory.configurationMatches(operation)

    private suspend fun processOwned(
        source: AdmittedMessageRef,
        operation: SmsV4OperationSnapshot,
        initialClaim: SmsOperationClaim,
        observer: SmsProcessingObserver,
        preferredLease: SlmLease?
    ): SmsProcessingOutcome {
        var claim = initialClaim
        emit(claim, "claim", "completed", emptyList(), observer)
        val evidence = store.sourceEvidence(source.sourceId)
        if (evidence.admissionReceiptId != source.admissionReceiptId ||
            SmsProcessingStore.sha256(evidence.body) != source.sourceDigest
        ) return retainOwned(
            claim, operation, listOf("persistence_configuration_hash_mismatch"), observer
        )

        val analysis = analyzer.analyze(evidence.body, operation)
        store.recordAnalysis(
            claim, analysis.analysisId, analysis.contract, analysis.sourceHash,
            analysis.configurationHash, analysis.canonicalJson(), System.currentTimeMillis()
        )
        store.transition(claim, "claimed", "analyzed", System.currentTimeMillis())
        emit(claim, "analysis", "completed", analysis.reasonCodes, observer)
        store.transition(claim, "analyzed", "triaged", System.currentTimeMillis())
        emit(claim, "triage", "completed", analysis.reasonCodes, observer)

        if (!operation.configuration.extractorEligible) return retainOwned(
            claim, operation,
            listOf(operation.configuration.extractorIneligibilityReason ?: "runtime_unavailable"),
            observer
        )
        val model = preferredLease?.model ?: resolveCurrentModel() ?: return retainOwned(
            claim, operation, listOf("runtime_unavailable"), observer
        )
        val currentIdentity = identityFor(model)
        if (!currentIdentity.eligible ||
            currentIdentity.modelIdentifier != operation.configuration.modelIdentifier ||
            currentIdentity.modelFileSha256 != operation.configuration.modelFileSha256
        ) return retainOwned(claim, operation, listOf("runtime_ineligible"), observer)

        val request = DirectSmsExtractorRequest(
            evidence.body,
            SmsV4ProcessingJson.senderFamily(evidence.sender),
            operation.configuration.primaryCurrency,
            operation.configuration.enabledProfiles,
            SmsV4ProcessingJson.advisoryEvidence(analysis),
            readV4Asset("configs/sms_processing/prompts/sms-extractor-v1.txt"),
            readV4Asset("configs/sms_processing/grammars/sms-extractor-v1.gbnf")
        )
        val profile = CandidateSelectorRuntimeProfile()
        store.transition(claim, "triaged", "selector_running", System.currentTimeMillis())
        emit(claim, "selector_execution", "running", emptyList(), observer)
        val startedAt = System.currentTimeMillis()
        val runtimeResult = runExtractor(preferredLease, model, request)
        if (runtimeResult.completion == "interrupted") {
            recordAttempt(claim, profile, request.payload(), runtimeResult, null, startedAt)
            emit(claim, "selector_execution", "interrupted", listOf("operation_interrupted"), observer)
            throw CancellationException("operation interrupted")
        }
        val raw = runtimeResult.rawOutput
        if (raw == null) {
            recordAttempt(claim, profile, request.payload(), runtimeResult, null, startedAt)
            val reason = runtimeResult.safeErrorCode ?: "runtime_unavailable"
            emit(claim, "selector_execution", "failed", listOf(reason), observer)
            return retainOwned(claim, operation, listOf(reason), observer)
        }
        emit(claim, "selector_execution", "completed", emptyList(), observer)
        val parsed = try {
            SmsExtractorValidator.validate(raw, evidence.body, profile.rawOutputByteLimit)
        } catch (error: SmsExtractorValidationException) {
            recordAttempt(claim, profile, request.payload(), runtimeResult, null, startedAt, error.reasonCode)
            emit(claim, "selector_validation", "failed", listOf(error.reasonCode), observer)
            return retainOwned(claim, operation, listOf(error.reasonCode), observer)
        }
        val normalized = try {
            (parsed as? SmsExtractorResult.Posted)?.let {
                SmsExtractorNormalizer.normalize(
                    it, operation.configuration.primaryCurrency,
                    operation.configuration.enabledProfiles
                )
            }
        } catch (error: SmsExtractorValidationException) {
            recordAttempt(claim, profile, request.payload(), runtimeResult, null, startedAt, error.reasonCode)
            emit(claim, "selector_validation", "failed", listOf(error.reasonCode), observer)
            return retainOwned(claim, operation, listOf(error.reasonCode), observer)
        }
        recordAttempt(
            claim, profile, request.payload(), runtimeResult,
            SmsV4ProcessingJson.validated(parsed), startedAt
        )
        store.transition(claim, "selector_running", "selector_recorded", System.currentTimeMillis())
        emit(claim, "selector_validation", "completed", emptyList(), observer)
        store.transition(claim, "selector_recorded", "validated", System.currentTimeMillis())
        claim = store.heartbeat(claim, System.currentTimeMillis())
        return when (parsed) {
            SmsExtractorResult.None -> settleNotPosted(claim, operation, observer)
            SmsExtractorResult.Abstain -> settleAbstain(claim, operation, observer)
            is SmsExtractorResult.Posted -> settlePosted(claim, operation, normalized!!, observer)
        }
    }

    private suspend fun runExtractor(
        preferredLease: SlmLease?,
        model: SlmModelSpec,
        request: DirectSmsExtractorRequest
    ): DirectCandidateSelectorResult = try {
        if (preferredLease != null) extractor.extract(preferredLease, request) else
            runtime.withLease(SlmRuntimeOwner.SMS_WORKER, model) { extractor.extract(it, request) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DirectCandidateSelectorResult(null, "failed", "runtime_unavailable", model, null, null)
    }

    private suspend fun settleNotPosted(
        claim: SmsOperationClaim,
        operation: SmsV4OperationSnapshot,
        observer: SmsProcessingObserver
    ): SmsProcessingOutcome {
        val reasons = listOf("extractor_none")
        val accountReason = "account_resolution_unresolved"
        val gate = SmsV4ProcessingJson.gate(false, accountReason, "clear")
        val account = SmsV4ProcessingJson.account(GroundedAccountResolution.Missing, "")
        val duplicate = SmsV4ProcessingJson.duplicate(
            "clear", operation.configuration.sourceId, operation.stableEventId, null
        )
        recordResult(
            claim, operation, "none", "not_posted", null, account, duplicate, gate, reasons
        )
        store.recordGateDecision(
            claim, "not_posted", "persistence_not_posted",
            SmsV4ProcessingJson.checksJson(false, accountReason, "clear"),
            CanonicalAndroidJson.stringify(account), "review_only",
            System.currentTimeMillis()
        )
        emit(claim, "persistence_gate", "completed", reasons, observer)
        emit(claim, "settlement", "completed", reasons, observer)
        store.settleDiscarded(claim, reasons.single(), System.currentTimeMillis())
        return SmsProcessingOutcome.TerminallyDiscarded(operation.operationId, reasons.single())
    }

    private suspend fun settleAbstain(
        claim: SmsOperationClaim,
        operation: SmsV4OperationSnapshot,
        observer: SmsProcessingObserver
    ): SmsProcessingOutcome {
        val reasons = listOf("extractor_abstained")
        recordResult(claim, operation, "abstain", "review", null, null, null, null, reasons)
        return retainOwned(claim, operation, reasons, observer)
    }

    private suspend fun settlePosted(
        claim: SmsOperationClaim,
        operation: SmsV4OperationSnapshot,
        normalized: NormalizedSmsExtraction,
        observer: SmsProcessingObserver
    ): SmsProcessingOutcome {
        val resolution = accountResolver.resolve(normalized.accountReference)
        emit(
            claim, "account_resolution", "completed",
            SmsV4ProcessingJson.accountReasons(resolution), observer
        )
        val accountId = (resolution as? GroundedAccountResolution.UniquelyResolved)?.accountId
        val fingerprint = SmsProcessingStore.sha256(
            "${normalized.minorUnits}\u0000${normalized.currency}\u0000${normalized.direction}\u0000" +
                "${accountId.orEmpty()}\u0000${operation.configuration.receivedTimestampEpochMs}"
        )
        val matches = store.duplicateMatchCounts(
            operation.operationId, operation.configuration.sourceId,
            operation.stableEventId, fingerprint
        )
        val duplicateStatus = when {
            matches.persistedSourceEvents > 0 -> "already_persisted"
            matches.matchingSourceEvents > 0 || matches.matchingTransactionFingerprints > 0 ->
                "possible_duplicate"
            else -> "clear"
        }
        val reasons = buildList {
            addAll(SmsV4ProcessingJson.accountReasons(resolution))
            when (duplicateStatus) {
                "already_persisted" -> add("duplicate_already_persisted")
                "clear" -> Unit
                else -> add("duplicate_possible")
            }
            add("persistence_blocked_by_rollout_mode")
        }.distinct().sorted()
        val accountJson = SmsV4ProcessingJson.account(resolution, normalized.accountReference)
        val duplicateJson = SmsV4ProcessingJson.duplicate(
            duplicateStatus, operation.configuration.sourceId,
            operation.stableEventId, fingerprint
        )
        val accountReason = SmsV4ProcessingJson.accountReasons(resolution).firstOrNull()
        val gate = SmsV4ProcessingJson.gate(true, accountReason, duplicateStatus)
        val gateResult = gate.getString("result")
        val gateReason = gate.getString("primary_reason")
        recordResult(
            claim, operation, "posted",
            if (gateResult == "blocked_by_mode") "blocked" else "review",
            SmsV4ProcessingJson.semantic(normalized),
            accountJson, duplicateJson, gate, reasons, fingerprint
        )
        emit(claim, "reconstruction", "completed", emptyList(), observer)
        store.recordGateDecision(
            claim, gateResult, gateReason,
            SmsV4ProcessingJson.checksJson(true, accountReason, duplicateStatus),
            CanonicalAndroidJson.stringify(accountJson), "review_only", System.currentTimeMillis()
        )
        emit(claim, "persistence_gate", "completed", reasons, observer)
        return retainOwned(claim, operation, reasons, observer)
    }

    private suspend fun recordResult(
        claim: SmsOperationClaim,
        operation: SmsV4OperationSnapshot,
        decision: String,
        status: String,
        semantic: JSONObject?,
        account: JSONObject?,
        duplicate: JSONObject?,
        gate: JSONObject?,
        reasons: List<String>,
        fingerprint: String? = null
    ) {
        val result = SmsV4ProcessingJson.result(
            status, decision, semantic, operation, account, duplicate, gate, reasons
        )
        store.recordReconstruction(
            claim, result, System.currentTimeMillis(),
            contractVersion = "pocketfinancer.processing-result/3",
            recognitionDecision = decision,
            transactionFingerprint = fingerprint
        )
        store.transition(claim, "validated", "reconstructed", System.currentTimeMillis())
    }

    private suspend fun recordAttempt(
        claim: SmsOperationClaim,
        profile: CandidateSelectorRuntimeProfile,
        requestJson: String,
        result: DirectCandidateSelectorResult,
        validated: String?,
        startedAt: Long,
        overrideError: String? = null
    ) = store.recordSelectorAttempt(
        claim = claim,
        runtimeProfileJson = runtimeProfileJson(profile),
        requestJson = requestJson,
        rawOutput = result.rawOutput,
        completion = if (overrideError == null) result.completion else "invalid",
        validatedSelectionJson = validated,
        safeErrorCode = overrideError ?: result.safeErrorCode,
        startedAt = startedAt,
        completedAt = System.currentTimeMillis()
    )

    private fun runtimeProfileJson(value: CandidateSelectorRuntimeProfile): String =
        CanonicalAndroidJson.stringify(JSONObject()
            .put("generation_mode", value.generationMode)
            .put("decoding", value.decoding)
            .put("answer_token_limit", value.answerTokenLimit)
            .put("raw_output_utf8_byte_limit", value.rawOutputByteLimit)
            .put("parser_deadline_ms", value.deadlineMs))

    private fun resolveCurrentModel(): SlmModelSpec? {
        val device = deviceCapabilities.assessDevice()
        val tier = resolveActiveSlmTier(context, modelStorage.modelDirectory, device) ?: return null
        val file = modelStorage.modelFile(tier.modelFile)
        if (!file.isFile || file.length() <= 0L) return null
        return SlmModelSpec(
            tier.id, file.canonicalPath,
            "${tier.modelFile}:${file.length()}:${file.lastModified()}",
            3072, 0, 0, device.cpu?.hasFp16 ?: false
        )
    }

    private fun identityFor(model: SlmModelSpec?): SmsV4ModelIdentity {
        if (model == null) return SmsV4ModelIdentity(false, null, null)
        val file = File(model.modelPath)
        if (!file.isFile || file.length() <= 0L) {
            return SmsV4ModelIdentity(false, model.modelId, null)
        }
        return runCatching {
            SmsV4ModelIdentity(true, model.modelId, sha256File(file))
        }.getOrElse { SmsV4ModelIdentity(false, model.modelId, null) }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readV4Asset(path: String): String = context.assets
        .open("${NativeSmsV4Assets.ASSET_ROOT}/$path")
        .bufferedReader().use { it.readText() }

    private fun kotlinx.coroutines.CoroutineScope.launchHeartbeat(
        claim: SmsOperationClaim
    ): Job = launch {
        var current = claim
        while (true) {
            delay(SmsProcessingStore.CLAIM_HEARTBEAT_MS)
            current = store.heartbeat(current, System.currentTimeMillis())
        }
    }

    private suspend fun emit(
        claim: SmsOperationClaim,
        stage: String,
        status: String,
        reasons: List<String>,
        observer: SmsProcessingObserver
    ) {
        val safeReasons = reasons.distinct().sorted()
        val receipt = store.appendTrace(
            claim, stage, status, safeReasons, null, System.currentTimeMillis()
        )
        runCatching {
            observer.onEvent(SmsProcessingObserverEvent(
                claim.operationId, receipt.sequence, stage, status, safeReasons
            ))
        }
    }

    private suspend fun retainOwned(
        claim: SmsOperationClaim,
        operation: SmsV4OperationSnapshot,
        reasons: List<String>,
        observer: SmsProcessingObserver
    ): SmsProcessingOutcome {
        val safe = reasons.ifEmpty { listOf("persistence_triage_requires_review") }
            .distinct().sorted()
        emit(claim, "settlement", "retained", safe, observer)
        val reviewId = store.retainForReview(claim, safe, System.currentTimeMillis())
        return SmsProcessingOutcome.RetainedForReview(operation.operationId, reviewId, safe)
    }

    private suspend fun retainWithoutClaim(
        operation: SmsV4OperationSnapshot,
        reasons: List<String>
    ): SmsProcessingOutcome {
        settledOutcome(operation)?.let { return it }
        val reason = reasons.firstOrNull() ?: "operation_interrupted"
        val reviewId = store.retainUnownedForReview(
            operation.operationId, reason, System.currentTimeMillis()
        )
        return if (reviewId != null) {
            SmsProcessingOutcome.RetainedForReview(operation.operationId, reviewId, reasons)
        } else SmsProcessingOutcome.RetryableFailure(
            operation.operationId, null, reason, "foreground_or_model_availability"
        )
    }

    private suspend fun settledOutcome(
        operation: SmsV4OperationSnapshot
    ): SmsProcessingOutcome? {
        val settled = store.settledReceipt(operation.operationId) ?: return null
        return when (settled.first) {
            "discarded" -> SmsProcessingOutcome.TerminallyDiscarded(
                operation.operationId, "already_discarded"
            )
            "persisted" -> SmsProcessingOutcome.Persisted(
                operation.operationId, emptyList(), true
            )
            "retain_review" -> SmsProcessingOutcome.RetainedForReview(
                operation.operationId,
                store.reviewCaseIdForOperation(operation.operationId) ?: return null,
                listOf("persistence_triage_requires_review")
            )
            else -> null
        }
    }
}

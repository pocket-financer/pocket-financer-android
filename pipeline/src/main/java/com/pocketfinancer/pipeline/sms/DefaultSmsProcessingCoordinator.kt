package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.GroundedAccountResolution
import com.pocketfinancer.data.repository.GroundedAccountResolver
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.CandidateSelectorRuntimeProfile
import com.pocketfinancer.inference.DefaultDirectCandidateSelector
import com.pocketfinancer.inference.DirectCandidateSelectorRequest
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.withLease
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class DefaultSmsProcessingCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SmsProcessingStore,
    private val runtime: SlmRuntime,
    private val modelStorage: SlmModelStorage,
    private val deviceCapabilities: DeviceCapabilities,
    private val selector: DefaultDirectCandidateSelector,
    private val accountResolver: GroundedAccountResolver,
    private val snapshotFactory: SmsOperationSnapshotFactory
) : SmsProcessingCoordinator {
    private val analyzer = StructuralSmsAnalyzer()
    private val triageEvaluator = SmsTriageEvaluator()
    private val validator = GroundedSelectorValidator()
    private val reconstructor = SemanticReconstructor()
    private val gate = AutomaticPersistenceGate()

    override suspend fun process(
        source: AdmittedMessageRef,
        operation: SmsOperationSnapshot,
        observer: SmsProcessingObserver
    ): SmsProcessingOutcome = processInternal(source, operation, observer, null)

    /**
     * Uses a lease already owned by a foreground or worker batch. This keeps
     * the coordinator as the only SMS decision path without trying to acquire
     * a second runtime lease while the caller deliberately keeps one resident.
     */
    suspend fun processUsingLease(
        source: AdmittedMessageRef,
        operation: SmsOperationSnapshot,
        lease: com.pocketfinancer.inference.SlmLease,
        observer: SmsProcessingObserver = SmsProcessingObserver.None
    ): SmsProcessingOutcome = processInternal(source, operation, observer, lease)

    private suspend fun processInternal(
        source: AdmittedMessageRef,
        operation: SmsOperationSnapshot,
        observer: SmsProcessingObserver,
        preferredLease: com.pocketfinancer.inference.SlmLease?
    ): SmsProcessingOutcome {
        if (
            source.sourceId != operation.configuration.sourceId ||
            SmsProcessingStore.sha256(source.sourceId) !=
                operation.configuration.sourceRefHash ||
            operation.operationId != operation.configuration.operationId ||
            operation.parentOperationId != operation.configuration.parentOperationId ||
            operation.configuration.generationMode != "DIRECT_NON_THINKING" ||
            operation.configuration.decoding != "greedy" ||
            operation.configuration.answerTokenLimit != 512 ||
            operation.configuration.rawOutputByteLimit != 16_384 ||
            operation.configuration.parserDeadlineMs != 0L ||
            operation.configuration.rolloutMode !in setOf("shadow", "review_only") ||
            !snapshotFactory.configurationMatches(operation)
        ) {
            // configurationHash covers the payload before its self-describing hash field;
            // equality here would indicate the wrong hashing boundary was used.
            return retainWithoutClaim(
                operation,
                listOf("persistence_configuration_hash_mismatch")
            )
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
                    val stopped = store.requestStop(
                        operation.operationId,
                        System.currentTimeMillis()
                    )
                    SmsProcessingOutcome.Stopped(
                        operation.operationId,
                        reviewCaseId = stopped.reviewCaseId,
                        reason = if (stopped.committed) {
                            "already_committed"
                        } else {
                            "processing_cancelled"
                        }
                    )
                }
            } catch (_: Exception) {
                store.reviewCaseIdForOperation(operation.operationId)?.let { reviewCaseId ->
                    SmsProcessingOutcome.Stopped(
                        operation.operationId,
                        reviewCaseId,
                        "operation_interrupted"
                    )
                } ?: retainWithoutClaim(
                    operation,
                    listOf("operation_interrupted")
                )
            } finally {
                heartbeat.cancel()
            }
        }
    }

    private suspend fun processOwned(
        source: AdmittedMessageRef,
        operation: SmsOperationSnapshot,
        claim: com.pocketfinancer.data.repository.SmsOperationClaim,
        observer: SmsProcessingObserver,
        preferredLease: com.pocketfinancer.inference.SlmLease?
    ): SmsProcessingOutcome {
        emit(claim, "claim", "completed", emptyList(), observer)
        val evidence = store.sourceEvidence(source.sourceId)
        if (
            evidence.admissionReceiptId != source.admissionReceiptId ||
            SmsProcessingStore.sha256(evidence.body) != source.sourceDigest
        ) {
            return retainOwned(
                claim,
                operation,
                listOf("persistence_configuration_hash_mismatch"),
                observer
            )
        }
        val analysis = analyzer.analyze(evidence.body, operation)
        store.recordAnalysis(
            claim,
            analysis.analysisId,
            analysis.contract,
            analysis.sourceHash,
            analysis.configurationHash,
            analysisJson(analysis),
            System.currentTimeMillis()
        )
        store.transition(claim, "claimed", "analyzed", System.currentTimeMillis())
        emit(claim, "analysis", "completed", analysis.reasonCodes, observer)
        val triage = triageEvaluator.evaluate(analysis)
        store.transition(claim, "analyzed", "triaged", System.currentTimeMillis())
        emit(claim, "triage", "completed", triage.reasonCodes, observer)

        if (triage.disposition == SmsStorageDisposition.DISCARD) {
            val reason = triage.reasonCodes.firstOrNull { it.startsWith("discard_") }
                ?: "discard_unambiguous_standalone_non_event"
            emit(claim, "settlement", "completed", listOf(reason), observer)
            store.settleDiscarded(claim, reason, System.currentTimeMillis())
            return SmsProcessingOutcome.TerminallyDiscarded(operation.operationId, reason)
        }
        val shouldSelect = triage.selectorAction != SmsSelectorAction.SKIP
        if (!shouldSelect || !operation.configuration.selectorEligible) {
            val reasons = triage.reasonCodes + if (!operation.configuration.selectorEligible) {
                listOf(operation.configuration.selectorIneligibilityReason ?: "selector_runtime_ineligible")
            } else {
                emptyList()
            }
            return retainOwned(claim, operation, reasons.distinct().sorted(), observer)
        }

        val modelSpec = preferredLease?.model ?: resolveModel(operation)
            ?: return retainOwned(
                claim, operation, listOf("runtime_unavailable"), observer
            )
        if (modelSpec.modelId != operation.configuration.selectorModelId) {
            return retainOwned(
                claim, operation, listOf("runtime_ineligible"), observer
            )
        }
        val selectorPayload = selectorPayload(evidence.body, analysis)
        val prompt = readAsset("sms_processing/selector-prompt-v1.txt")
        val grammar = readAsset("sms_processing/selector-output-v1.gbnf")
        val profile = CandidateSelectorRuntimeProfile()
        store.transition(claim, "triaged", "selector_running", System.currentTimeMillis())
        emit(claim, "selector_execution", "running", emptyList(), observer)
        val startedAt = System.currentTimeMillis()
        val result = try {
            if (preferredLease != null) {
                selector.select(
                    preferredLease,
                    DirectCandidateSelectorRequest(prompt, selectorPayload, grammar, profile)
                )
            } else {
                runtime.withLease(SlmRuntimeOwner.SMS_WORKER, modelSpec) { lease ->
                    selector.select(
                        lease,
                        DirectCandidateSelectorRequest(prompt, selectorPayload, grammar, profile)
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            store.recordSelectorAttempt(
                claim,
                runtimeProfileJson(profile),
                selectorPayload,
                null,
                "failed",
                null,
                "runtime_unavailable",
                startedAt,
                System.currentTimeMillis()
            )
            emit(
                claim,
                "selector_execution",
                "failed",
                listOf("runtime_unavailable"),
                observer
            )
            return retainOwned(
                claim, operation, listOf("runtime_unavailable"), observer
            )
        }
        val raw = result.rawOutput
        if (raw == null) {
            store.recordSelectorAttempt(
                claim,
                runtimeProfileJson(profile),
                selectorPayload,
                null,
                result.completion,
                null,
                result.safeErrorCode,
                startedAt,
                System.currentTimeMillis()
            )
            emit(
                claim,
                "selector_execution",
                if (result.completion == "interrupted") "interrupted" else "failed",
                listOf(result.safeErrorCode ?: "selector_output_unavailable"),
                observer
            )
            return retainOwned(
                claim,
                operation,
                listOf(result.safeErrorCode ?: "selector_output_unavailable"),
                observer
            )
        }
        emit(claim, "selector_execution", "completed", emptyList(), observer)
        val selection = try {
            validator.validate(raw, analysis, profile.rawOutputByteLimit)
        } catch (error: GroundedSelectorValidationException) {
            store.recordSelectorAttempt(
                claim,
                runtimeProfileJson(profile),
                selectorPayload,
                raw,
                "invalid",
                null,
                error.reasonCode,
                startedAt,
                System.currentTimeMillis()
            )
            emit(
                claim,
                "selector_validation",
                "failed",
                listOf(error.reasonCode),
                observer
            )
            return retainOwned(claim, operation, listOf(error.reasonCode), observer)
        }
        store.recordSelectorAttempt(
            claim,
            runtimeProfileJson(profile),
            selectorPayload,
            raw,
            "complete",
            selectionJson(selection),
            null,
            startedAt,
            System.currentTimeMillis()
        )
        store.transition(claim, "selector_running", "selector_recorded", System.currentTimeMillis())
        emit(claim, "selector_validation", "completed", emptyList(), observer)
        store.transition(claim, "selector_recorded", "validated", System.currentTimeMillis())

        val reconstructed = if (selection.decision == SelectorDecision.POSTED) {
            reconstructor.reconstruct(selection, analysis, operation).also {
                store.recordReconstruction(claim, reconstructionJson(it), System.currentTimeMillis())
                store.transition(claim, "validated", "reconstructed", System.currentTimeMillis())
                emit(claim, "reconstruction", "completed", emptyList(), observer)
            }
        } else {
            null
        }
        val accountResolution = accountResolver.resolve(reconstructed?.accountEvidence)
        emit(claim, "account_resolution", "completed", emptyList(), observer)
        val gateClaim = store.heartbeat(claim, System.currentTimeMillis())
        val decision = gate.evaluate(
            analysis,
            triage,
            selection,
            reconstructed,
            accountResolution,
            operation,
            claimOwnershipCurrent = true
        )
        store.recordGateDecision(
            gateClaim,
            decision.result.wireValue,
            decision.primaryReason,
            checksJson(decision.checks),
            accountResolutionJson(accountResolution),
            operation.configuration.rolloutMode,
            System.currentTimeMillis()
        )
        emit(
            gateClaim,
            "persistence_gate",
            "completed",
            listOf(decision.primaryReason),
            observer
        )
        return retainOwned(
            gateClaim,
            operation,
            (triage.reasonCodes + decision.primaryReason).distinct().sorted(),
            observer
        )
    }

    override suspend fun requestStop(operationId: String): SmsProcessingStopReceipt {
        val receipt = store.requestStop(operationId, System.currentTimeMillis())
        return SmsProcessingStopReceipt(
            receipt.operationId,
            receipt.reviewCaseId,
            receipt.state,
            receipt.committed
        )
    }

    fun currentSelectorModelId(): String? {
        val device = deviceCapabilities.assessDevice()
        return resolveActiveSlmTier(context, modelStorage.modelDirectory, device)?.id
    }

    private fun kotlinx.coroutines.CoroutineScope.launchHeartbeat(
        claim: com.pocketfinancer.data.repository.SmsOperationClaim
    ): Job = launch {
        var renewable = claim
        while (true) {
            delay(SmsProcessingStore.CLAIM_HEARTBEAT_MS)
            renewable = store.heartbeat(renewable, System.currentTimeMillis())
        }
    }

    private suspend fun retainOwned(
        claim: com.pocketfinancer.data.repository.SmsOperationClaim,
        operation: SmsOperationSnapshot,
        reasons: List<String>,
        observer: SmsProcessingObserver
    ): SmsProcessingOutcome {
        val safeReasons = reasons.ifEmpty {
            listOf("persistence_triage_requires_review")
        }.distinct().sorted()
        emit(claim, "settlement", "retained", safeReasons, observer)
        val reviewId = store.retainForReview(claim, safeReasons, System.currentTimeMillis())
        return SmsProcessingOutcome.RetainedForReview(operation.operationId, reviewId, safeReasons)
    }

    private suspend fun retainWithoutClaim(
        operation: SmsOperationSnapshot,
        reasons: List<String>
    ): SmsProcessingOutcome {
        val settled = settledOutcome(operation)
        if (settled != null) return settled
        val reason = reasons.firstOrNull() ?: "operation_interrupted"
        val reviewCaseId = store.retainUnownedForReview(
            operation.operationId,
            reason,
            System.currentTimeMillis()
        )
        return if (reviewCaseId != null) {
            SmsProcessingOutcome.RetainedForReview(
                operation.operationId,
                reviewCaseId,
                reasons.ifEmpty { listOf(reason) }
            )
        } else {
            SmsProcessingOutcome.RetryableFailure(
                operation.operationId,
                null,
                reason,
                "foreground_or_model_availability"
            )
        }
    }

    private suspend fun settledOutcome(operation: SmsOperationSnapshot): SmsProcessingOutcome? {
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
                store.reviewCaseIdForOperation(operation.operationId)
                    ?: return null,
                listOf("persistence_triage_requires_review")
            )
            else -> null
        }
    }

    private suspend fun emit(
        claim: com.pocketfinancer.data.repository.SmsOperationClaim,
        stage: String,
        status: String,
        reasons: List<String>,
        observer: SmsProcessingObserver
    ) {
        val receipt = store.appendTrace(
            claim, stage, status, reasons.distinct(), null, System.currentTimeMillis()
        )
        runCatching {
            observer.onEvent(
                SmsProcessingObserverEvent(
                    claim.operationId, receipt.sequence, stage, status, reasons
                )
            )
        }
    }

    private fun resolveModel(operation: SmsOperationSnapshot): SlmModelSpec? {
        val device = deviceCapabilities.assessDevice()
        val tier = resolveActiveSlmTier(context, modelStorage.modelDirectory, device) ?: return null
        if (tier.id != operation.configuration.selectorModelId) return null
        val file = modelStorage.modelFile(tier.modelFile)
        if (!file.exists() || file.length() == 0L) return null
        return SlmModelSpec(
            tier.id,
            file.canonicalPath,
            "${tier.modelFile}:${file.length()}:${file.lastModified()}",
            3072,
            0,
            0,
            device.cpu?.hasFp16 ?: false
        )
    }

    private fun selectorPayload(source: String, analysis: SmsAnalysis): String {
        return groundedSelectorPayload(source, analysis)
    }

    private fun analysisJson(value: SmsAnalysis): String = value.canonicalJson()

    private fun selectionJson(value: GroundedSelectorResult): String = when (value.decision) {
        SelectorDecision.NONE -> "{\"decision\":\"none\"}"
        SelectorDecision.ABSTAIN -> "{\"decision\":\"abstain\"}"
        SelectorDecision.POSTED -> value.posted!!.let {
            CanonicalAndroidJson.stringify(
                JSONObject()
                    .put("decision", "posted")
                    .put("amount", it.amountCandidateId)
                    .put("direction", it.directionCandidateId)
                    .put("account", it.accountCandidateId)
                    .put("counterparty", it.counterpartyCandidateId)
            )
        }
    }

    private fun reconstructionJson(value: ReconstructedSmsTransaction): String =
        CanonicalAndroidJson.stringify(
            JSONObject()
                .put("analysis_id", value.analysisId)
                .put("stable_event_id", value.stableEventId)
                .put("minor_units", value.minorUnits)
                .put("currency", value.currency)
                .put("currency_scale", value.currencyScale)
                .put("currency_provenance", value.currencyProvenance)
                .put("direction", value.direction)
                .put("account_evidence", value.accountEvidence ?: JSONObject.NULL)
                .put("counterparty_evidence", value.counterpartyEvidence ?: JSONObject.NULL)
                .put("occurred_at_epoch_ms", value.occurredAtEpochMs ?: JSONObject.NULL)
                .put("timestamp_provenance", value.timestampProvenance)
        )

    private fun checksJson(values: List<PersistenceGateCheck>): String =
        CanonicalAndroidJson.stringify(
            JSONArray(values.map { value ->
                JSONObject()
                    .put("check", value.check)
                    .put("passed", value.passed)
                    .put("reason_code", value.reasonCode ?: JSONObject.NULL)
            })
        )

    private fun accountResolutionJson(value: GroundedAccountResolution): String =
        CanonicalAndroidJson.stringify(
            when (value) {
                GroundedAccountResolution.Missing -> JSONObject().put("result", "missing")
                GroundedAccountResolution.Unresolved -> JSONObject().put("result", "unresolved")
                is GroundedAccountResolution.Ambiguous -> JSONObject()
                    .put("result", "ambiguous")
                    .put("account_ids", JSONArray(value.accountIds))
                is GroundedAccountResolution.UniquelyResolved -> JSONObject()
                    .put("result", "unique")
                    .put("account_id", value.accountId)
                    .put("matched_alias_hash", value.matchedAliasHash)
                    .put("provenance", value.provenance)
            }
        )

    private fun runtimeProfileJson(value: CandidateSelectorRuntimeProfile): String =
        CanonicalAndroidJson.stringify(
            JSONObject()
                .put("generation_mode", value.generationMode)
                .put("decoding", value.decoding)
                .put("answer_token_limit", value.answerTokenLimit)
                .put("raw_output_utf8_byte_limit", value.rawOutputByteLimit)
                .put("parser_deadline_ms", value.deadlineMs)
        )

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use {
        it.readText()
    }

}

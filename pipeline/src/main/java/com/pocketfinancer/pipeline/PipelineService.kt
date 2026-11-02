package com.pocketfinancer.pipeline

import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.pipeline.sms.AdmittedMessageRef
import com.pocketfinancer.pipeline.sms.DefaultSmsProcessingCoordinator
import com.pocketfinancer.pipeline.sms.DefaultSmsV4ProcessingCoordinator
import com.pocketfinancer.pipeline.sms.DefaultSmsV5ProcessingCoordinator
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsV4ModelIdentity
import com.pocketfinancer.pipeline.sms.SmsV4OperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsV5OperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsProcessingOutcome
import com.pocketfinancer.pipeline.sms.SmsProcessingObserver
import com.pocketfinancer.pipeline.sms.SmsProcessingTransientEvent
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Admits one SMS and routes it through the grounded processing coordinator. */
@Singleton
class PipelineService @Inject constructor(
    private val smsProcessingStore: SmsProcessingStore,
    private val snapshotFactory: SmsOperationSnapshotFactory,
    private val processingConfiguration: ProcessingConfigurationRepository,
    private val smsProcessingCoordinator: DefaultSmsProcessingCoordinator,
    private val v4SnapshotFactory: SmsV4OperationSnapshotFactory,
    private val v4Coordinator: DefaultSmsV4ProcessingCoordinator,
    private val v5SnapshotFactory: SmsV5OperationSnapshotFactory,
    private val v5Coordinator: DefaultSmsV5ProcessingCoordinator,
    private val slmProcessingPreferences: SlmProcessingPreferences
) {
    private val _pipelineState = MutableStateFlow<PipelineStep?>(null)
    val pipelineState: StateFlow<PipelineStep?> = _pipelineState.asStateFlow()

    data class PipelineStep(
        val stage: Stage,
        val message: String,
        val progress: Int = 0,
        val total: Int = 0,
        val perf: SlmPerformanceData? = null
    )

    enum class Stage {
        EXTRACTING, EXTRACTED, SKIPPED, SAVED, ERROR
    }

    enum class SkipReason {
        NOT_TRANSACTION,
        EXTRACTION_REJECTED,
        RETAINED_FOR_REVIEW
    }

    /**
     * Receives detailed progress for one [processSingle] invocation.
     *
     * This is deliberately run-local rather than another shared state holder:
     * concurrent callers can render their own SMS without observing events
     * from unrelated background work. Observer failures are always isolated
     * from filtering, inference, and persistence.
     */
    fun interface ProcessingObserver {
        fun onEvent(event: ProcessingEvent)
    }

    sealed interface ProcessingEvent {
        data class GroundedStage(
            val stage: String,
            val status: String,
            val reasonCodes: List<String>
        ) : ProcessingEvent

        data object DeterministicFilterStarted : ProcessingEvent

        data object DeterministicFilterRejected : ProcessingEvent

        data object DeterministicFilterPassed : ProcessingEvent

        data class InferenceStarted(
            val model: SlmModelSpec,
            val grammarEnabled: Boolean,
            val answerTokenBudget: Int
        ) : ProcessingEvent

        data class JsonTokenDelta(
            val delta: String,
            val cumulativeStructuredOutput: String
        ) : ProcessingEvent

        /**
         * Keeps the exact terminal runtime result while exposing the metadata
         * needed by progress UIs without requiring every consumer to remap it.
         */
        data class InferenceCompleted(
            val result: SlmExtractionResult
        ) : ProcessingEvent {
            val json: String?
                get() = (result as? SlmExtractionResult.Success)?.json

            val perf: SlmPerformanceData?
                get() = when (result) {
                    is SlmExtractionResult.Success -> result.perf
                    is SlmExtractionResult.Null -> result.perf
                    is SlmExtractionResult.Error,
                    is SlmExtractionResult.Stopped -> null
                }

            val cache: SlmCacheDiagnostics?
                get() = when (result) {
                    is SlmExtractionResult.Success -> result.cache
                    is SlmExtractionResult.Null -> result.cache
                    is SlmExtractionResult.Error,
                    is SlmExtractionResult.Stopped -> null
                }

            val model: SlmModelSpec
                get() = result.model
        }

        /** Emitted after cancellation is checked, at the durable handoff. */
        data object PersistenceStarted : ProcessingEvent
    }

    /**
     * A typed pipeline outcome keeps "not a transaction", cancellation, and
     * operational failure distinct for WorkManager retry and UI reporting.
     */
    sealed interface ProcessingResult {
        data class Skipped(val reason: SkipReason) : ProcessingResult

        data class Saved(
            val transactionIds: List<String>,
            val alreadyCommitted: Boolean
        ) : ProcessingResult

        data object AwaitingConfiguration : ProcessingResult

        data object Stopped : ProcessingResult

        data class Failure(
            val message: String,
            val retryable: Boolean
        ) : ProcessingResult
    }

    /**
     * Process exactly one SMS using an already-owned residency lease. The
     * lease is passed into the coordinator so foreground batches never attempt
     * a nested runtime acquisition.
     */
    suspend fun processSingle(
        sms: SmsReader.SmsMessage,
        lease: SlmLease,
        observer: ProcessingObserver? = null,
        trigger: String = "manual"
    ): ProcessingResult {
        return processThroughV5Coordinator(
            sms = sms,
            lease = lease,
            trigger = trigger,
            store = smsProcessingStore,
            configurationRepository = processingConfiguration,
            observer = observer
        )
    }

    /**
     * Process one durable source without requiring the caller to own model
     * residency. The coordinator resolves the configured model and retains a
     * visible review case when the selector cannot safely run.
     */
    suspend fun processSingle(
        sms: SmsReader.SmsMessage,
        observer: ProcessingObserver? = null,
        trigger: String = "manual"
    ): ProcessingResult {
        return processThroughV5Coordinator(
            sms = sms,
            lease = null,
            trigger = trigger,
            store = smsProcessingStore,
            configurationRepository = processingConfiguration,
            observer = observer
        )
    }

    suspend fun retryReview(reviewCaseId: String, configurationMode: String): ProcessingResult {
        require(configurationMode in setOf("original", "current"))
        val retry = smsProcessingStore.retryContext(reviewCaseId)
        val previousConfiguration = JSONObject(retry.operation.configurationJson)
        val previousCurrencyContext = previousConfiguration.getJSONObject("currency_context")
        val previousContract = previousConfiguration.getString("contract")
        val currency = if (configurationMode == "original") {
            previousCurrencyContext.getString("primary_currency")
        } else {
            processingConfiguration.confirmedPrimaryCurrency()
                ?: return ProcessingResult.AwaitingConfiguration
        }
        val profiles = if (configurationMode == "original") {
            previousCurrencyContext.getJSONArray("enabled_profile_ids").let { values ->
                (0 until values.length()).map { values.getString(it) }
            }
        } else {
            processingConfiguration.enabledProfiles(currency)
        }
        val source = retry.source
        val reference = AdmittedMessageRef(
            source.id,
            source.admissionReceiptId,
            SmsProcessingStore.sha256(source.rawMessage)
        )
        if (configurationMode == "original" && previousContract == "pocketfinancer.processing-config/2") {
            val selector = previousConfiguration.getJSONObject("selector")
            val snapshot = snapshotFactory.create(
                source = reference,
                trigger = "retry",
                primaryCurrency = currency,
                enabledProfiles = profiles,
                sourceTimestampEpochMs = source.sourceTimestamp,
                sourceTimestampProvenance = if (source.sourceTimestamp == null) "unknown" else
                    "acquisition_supplied_message_time",
                admissionTimestampEpochMs = source.admittedAt,
                selectorModelId = selector.optString("model_identifier")
                    .takeIf { it.isNotBlank() && it != "null" },
                selectorModelHash = selector.optString("model_file_sha256")
                    .takeIf { it.matches(Regex("[0-9a-f]{64}")) },
                selectorRuntimeVersion = selector.optString("runtime_version", "llama.cpp-jni"),
                deviceCohort = android.os.Build.MODEL?.takeIf { it.isNotBlank() }
                    ?: "android-device",
                stableEventId = retry.operation.stableEventId,
                parentOperationId = retry.operation.id
            )
            return mapOutcome(smsProcessingCoordinator.process(reference, snapshot))
        }
        val retryOriginalV4 = configurationMode == "original" &&
            previousContract == "pocketfinancer.processing-config/4"
        val retryOriginalV5 = configurationMode == "original" &&
            previousContract == "pocketfinancer.processing-config/5"
        val retryOriginalV6 = configurationMode == "original" &&
            previousContract == "pocketfinancer.processing-config/6"
        if (configurationMode == "original" &&
            !retryOriginalV4 && !retryOriginalV5 && !retryOriginalV6
        ) {
            return ProcessingResult.Failure(
                "The original frozen configuration is retained but has no executable native adapter; retry with current configuration.",
                retryable = false
            )
        }
        val identity = if (configurationMode == "original") {
            val extractor = previousConfiguration.getJSONObject("extractor")
            SmsV4ModelIdentity(
                extractor.getBoolean("eligible"),
                extractor.optString("model_identifier").takeIf { it.isNotBlank() && it != "null" },
                extractor.optString("model_file_sha256").takeIf {
                    it.matches(Regex("[0-9a-f]{64}"))
                }
            )
        } else v5Coordinator.currentModelIdentity()
        val received = if (configurationMode == "original") {
            previousConfiguration.getJSONObject("received_timestamp")
        } else null
        val receivedAt = received?.getLong("epoch_ms")
            ?: source.sourceTimestamp ?: source.admittedAt
        val receivedProvenance = received?.getString("provenance")
            ?: if (source.sourceTimestamp == null) "platform_received" else
                "acquisition_supplied_message_time"
        val deviceCohort = android.os.Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "android-device"
        if (retryOriginalV4) {
            val snapshot = v4SnapshotFactory.create(
                source = reference,
                trigger = "retry",
                primaryCurrency = currency,
                enabledProfiles = profiles,
                receivedTimestampEpochMs = receivedAt,
                receivedTimestampProvenance = receivedProvenance,
                admissionTimestampEpochMs = source.admittedAt,
                modelIdentity = identity,
                runtimeVersion = "llama.cpp-jni",
                deviceCohort = deviceCohort,
                stableEventId = retry.operation.stableEventId,
                parentOperationId = retry.operation.id
            )
            return mapOutcome(v4Coordinator.process(reference, snapshot))
        }
        val grammarEnabled = when {
            retryOriginalV5 -> null
            retryOriginalV6 -> previousConfiguration
                .getJSONObject("extractor").getBoolean("grammar_enabled")
            else -> slmProcessingPreferences.gbnfGrammarEnabled.value
        }
        val snapshot = v5SnapshotFactory.create(
            source = reference,
            trigger = "retry",
            primaryCurrency = currency,
            enabledProfiles = profiles,
            receivedTimestampEpochMs = receivedAt,
            receivedTimestampProvenance = receivedProvenance,
            admissionTimestampEpochMs = source.admittedAt,
            modelIdentity = identity,
            runtimeVersion = "llama.cpp-jni",
            deviceCohort = deviceCohort,
            stableEventId = retry.operation.stableEventId,
            parentOperationId = retry.operation.id,
            grammarEnabled = grammarEnabled
        )
        return mapOutcome(v5Coordinator.process(reference, snapshot))
    }

    private suspend fun processThroughV5Coordinator(
        sms: SmsReader.SmsMessage,
        lease: SlmLease?,
        trigger: String,
        store: SmsProcessingStore,
        configurationRepository: ProcessingConfigurationRepository,
        observer: ProcessingObserver?
    ): ProcessingResult {
        val sourceIdentity = sms.sourceIdentity
        val sourceId = sourceIdentity.opaqueCandidateKey
        val receiptId = UUID.nameUUIDFromBytes(sourceId.toByteArray(Charsets.UTF_8)).toString()
        val now = System.currentTimeMillis()
        store.admitSource(
            AdmittedSmsSourceEntity(
                id = sourceId,
                sourceConnector = sourceIdentity.connector,
                sourceMessageId = sourceIdentity.messageId,
                sourceProviderMessageId = sourceIdentity.providerMessageId,
                sourceFingerprint = sourceIdentity.fallbackFingerprint,
                sourceAlternateFingerprint = sourceIdentity.alternateFingerprint,
                sender = sms.address,
                rawMessage = sms.body,
                sourceTimestamp = sms.sourceTimestamp,
                messageType = sms.type,
                origin = trigger,
                admissionReceiptId = receiptId,
                admittedAt = now,
                retentionState = "admitted"
            )
        )
        val currency = configurationRepository.confirmedPrimaryCurrency()
            ?: return ProcessingResult.AwaitingConfiguration
        val reference = AdmittedMessageRef(
            sourceId, receiptId, SmsProcessingStore.sha256(sms.body)
        )
        val snapshot = v5SnapshotFactory.create(
            source = reference,
            trigger = trigger,
            primaryCurrency = currency,
            enabledProfiles = configurationRepository.enabledProfiles(currency),
            receivedTimestampEpochMs = sms.sourceTimestamp ?: now,
            receivedTimestampProvenance = if (sms.sourceTimestamp == null) {
                "platform_received"
            } else "acquisition_supplied_message_time",
            admissionTimestampEpochMs = now,
            modelIdentity = lease?.let(v5Coordinator::modelIdentityForLease)
                ?: v5Coordinator.currentModelIdentity(),
            runtimeVersion = "llama.cpp-jni",
            deviceCohort = android.os.Build.MODEL?.takeIf { it.isNotBlank() }
                ?: "android-device",
            now = now,
            grammarEnabled = slmProcessingPreferences.gbnfGrammarEnabled.value
        )
        val automaticObserver = SmsProcessingObserver { event ->
            when (val transient = event.transient) {
                is SmsProcessingTransientEvent.InferenceStarted -> runCatching {
                    observer?.onEvent(ProcessingEvent.InferenceStarted(
                        transient.model,
                        transient.grammarEnabled,
                        transient.answerTokenBudget
                    ))
                }
                is SmsProcessingTransientEvent.DecodedToken -> runCatching {
                    observer?.onEvent(ProcessingEvent.JsonTokenDelta(
                        transient.delta,
                        transient.cumulativeStructuredOutput
                    ))
                }
                is SmsProcessingTransientEvent.InferenceCompleted -> runCatching {
                    val runtimeResult = transient.result
                    val rawOutput = runtimeResult.rawOutput
                    val result = when {
                        rawOutput != null -> SlmExtractionResult.Success(
                            rawOutput,
                            runtimeResult.performance,
                            runtimeResult.model,
                            runtimeResult.cache ?: SlmCacheDiagnostics()
                        )
                        runtimeResult.completion == "interrupted" ->
                            SlmExtractionResult.Stopped(runtimeResult.model)
                        runtimeResult.safeErrorCode == "runtime_output_truncated" ->
                            SlmExtractionResult.Null(
                                runtimeResult.model,
                                runtimeResult.performance,
                                runtimeResult.cache ?: SlmCacheDiagnostics()
                            )
                        else -> SlmExtractionResult.Error(
                            runtimeResult.safeErrorCode ?: "runtime_failure",
                            runtimeResult.model
                        )
                    }
                    observer?.onEvent(ProcessingEvent.InferenceCompleted(result))
                }
                null -> {
                    when (event.stage) {
                        "analysis", "triage" ->
                            emit(Stage.EXTRACTING, "Preparing advisory evidence")
                        "selector_execution" ->
                            emit(Stage.EXTRACTING, "Extracting transaction fields")
                        "selector_validation", "account_resolution", "reconstruction",
                        "persistence_gate" ->
                            emit(Stage.EXTRACTING, "Validating grounded transaction")
                        "settlement" -> when (event.status) {
                            "running" -> {
                                emit(Stage.EXTRACTING, "Saving transaction")
                                runCatching {
                                    observer?.onEvent(ProcessingEvent.PersistenceStarted)
                                }
                            }
                            "retained" -> emit(Stage.SKIPPED, "Saved for review")
                        }
                    }
                    runCatching {
                        observer?.onEvent(ProcessingEvent.GroundedStage(
                            event.stage, event.status, event.reasonCodes.toList()
                        ))
                    }
                }
            }
        }
        val outcome = if (lease != null) {
            v5Coordinator.processUsingLease(reference, snapshot, lease, automaticObserver)
        } else v5Coordinator.process(reference, snapshot, automaticObserver)
        return mapOutcome(outcome).also { result ->
            when (result) {
                is ProcessingResult.Saved -> emit(Stage.SAVED, "Transaction saved")
                is ProcessingResult.Skipped -> emit(
                    Stage.SKIPPED,
                    if (result.reason == SkipReason.RETAINED_FOR_REVIEW) {
                        "Saved for review"
                    } else "No transaction created"
                )
                is ProcessingResult.Failure -> emit(Stage.ERROR, result.message)
                ProcessingResult.AwaitingConfiguration ->
                    emit(Stage.ERROR, "Primary currency is not configured")
                ProcessingResult.Stopped -> emit(Stage.SKIPPED, "Processing stopped")
            }
        }
    }

    private suspend fun processThroughV4Coordinator(
        sms: SmsReader.SmsMessage,
        lease: SlmLease?,
        trigger: String,
        store: SmsProcessingStore,
        configurationRepository: ProcessingConfigurationRepository,
        observer: ProcessingObserver?
    ): ProcessingResult {
        val sourceIdentity = sms.sourceIdentity
        val sourceId = sourceIdentity.opaqueCandidateKey
        val receiptId = UUID.nameUUIDFromBytes(sourceId.toByteArray(Charsets.UTF_8)).toString()
        val now = System.currentTimeMillis()
        store.admitSource(
            AdmittedSmsSourceEntity(
                id = sourceId,
                sourceConnector = sourceIdentity.connector,
                sourceMessageId = sourceIdentity.messageId,
                sourceProviderMessageId = sourceIdentity.providerMessageId,
                sourceFingerprint = sourceIdentity.fallbackFingerprint,
                sourceAlternateFingerprint = sourceIdentity.alternateFingerprint,
                sender = sms.address,
                rawMessage = sms.body,
                sourceTimestamp = sms.sourceTimestamp,
                messageType = sms.type,
                origin = trigger,
                admissionReceiptId = receiptId,
                admittedAt = now,
                retentionState = "admitted"
            )
        )
        val currency = configurationRepository.confirmedPrimaryCurrency()
            ?: return ProcessingResult.AwaitingConfiguration
        val reference = AdmittedMessageRef(
            sourceId, receiptId, SmsProcessingStore.sha256(sms.body)
        )
        val snapshot = v4SnapshotFactory.create(
            source = reference,
            trigger = trigger,
            primaryCurrency = currency,
            enabledProfiles = configurationRepository.enabledProfiles(currency),
            receivedTimestampEpochMs = sms.sourceTimestamp ?: now,
            receivedTimestampProvenance = if (sms.sourceTimestamp == null) {
                "platform_received"
            } else "acquisition_supplied_message_time",
            admissionTimestampEpochMs = now,
            modelIdentity = lease?.let(v4Coordinator::modelIdentityForLease)
                ?: v4Coordinator.currentModelIdentity(),
            runtimeVersion = "llama.cpp-jni",
            deviceCohort = android.os.Build.MODEL?.takeIf { it.isNotBlank() }
                ?: "android-device",
            now = now
        )
        val v4Observer = SmsProcessingObserver { event ->
            when (event.stage) {
                "analysis", "triage" -> emit(Stage.EXTRACTING, "Preparing advisory evidence")
                "selector_execution" -> emit(Stage.EXTRACTING, "Extracting transaction fields")
                "settlement" -> emit(Stage.SKIPPED, "Saved for review")
            }
            runCatching {
                observer?.onEvent(ProcessingEvent.GroundedStage(
                    event.stage, event.status, event.reasonCodes.toList()
                ))
            }
        }
        val outcome = if (lease != null) {
            v4Coordinator.processUsingLease(reference, snapshot, lease, v4Observer)
        } else v4Coordinator.process(reference, snapshot, v4Observer)
        return mapOutcome(outcome)
    }

    private suspend fun processThroughGroundedCoordinator(
        sms: SmsReader.SmsMessage,
        lease: SlmLease?,
        trigger: String,
        store: SmsProcessingStore,
        snapshotFactory: SmsOperationSnapshotFactory,
        configurationRepository: ProcessingConfigurationRepository,
        coordinator: DefaultSmsProcessingCoordinator,
        observer: ProcessingObserver?
    ): ProcessingResult {
        val sourceIdentity = sms.sourceIdentity
        val sourceId = sourceIdentity.opaqueCandidateKey
        val receiptId = UUID.nameUUIDFromBytes(sourceId.toByteArray(Charsets.UTF_8)).toString()
        val now = System.currentTimeMillis()
        store.admitSource(
            AdmittedSmsSourceEntity(
                id = sourceId,
                sourceConnector = sourceIdentity.connector,
                sourceMessageId = sourceIdentity.messageId,
                sourceProviderMessageId = sourceIdentity.providerMessageId,
                sourceFingerprint = sourceIdentity.fallbackFingerprint,
                sourceAlternateFingerprint = sourceIdentity.alternateFingerprint,
                sender = sms.address,
                rawMessage = sms.body,
                sourceTimestamp = sms.sourceTimestamp,
                messageType = sms.type,
                origin = trigger,
                admissionReceiptId = receiptId,
                admittedAt = now,
                retentionState = "admitted"
            )
        )
        val currency = configurationRepository.confirmedPrimaryCurrency()
            ?: return ProcessingResult.AwaitingConfiguration
        val reference = AdmittedMessageRef(
            sourceId,
            receiptId,
            SmsProcessingStore.sha256(sms.body)
        )
        val snapshot = snapshotFactory.create(
            source = reference,
            trigger = trigger,
            primaryCurrency = currency,
            enabledProfiles = configurationRepository.enabledProfiles(currency),
            sourceTimestampEpochMs = sms.sourceTimestamp,
            sourceTimestampProvenance = "acquisition_supplied_message_time",
            admissionTimestampEpochMs = now,
            selectorModelId = lease?.model?.modelId ?: coordinator.currentSelectorModelId(),
            selectorModelHash = null,
            selectorRuntimeVersion = "llama.cpp-jni",
            deviceCohort = android.os.Build.MODEL
                ?.takeIf { it.isNotBlank() }
                ?: "android-device",
            now = now
        )
        val groundedObserver = SmsProcessingObserver { event ->
            when (event.stage) {
                "analysis", "triage" -> emit(Stage.EXTRACTING, "Analyzing grounded evidence")
                "selector_execution" -> emit(Stage.EXTRACTING, "Selecting grounded candidates")
                "settlement" -> emit(Stage.SKIPPED, "Saved for review")
            }
            runCatching {
                observer?.onEvent(
                    ProcessingEvent.GroundedStage(
                        event.stage,
                        event.status,
                        event.reasonCodes.toList()
                    )
                )
            }
        }
        val outcome = if (lease != null) {
            coordinator.processUsingLease(reference, snapshot, lease, groundedObserver)
        } else {
            coordinator.process(reference, snapshot, groundedObserver)
        }
        return mapOutcome(outcome)
    }

    private fun mapOutcome(outcome: SmsProcessingOutcome): ProcessingResult = when (outcome) {
        is SmsProcessingOutcome.TerminallyDiscarded ->
            ProcessingResult.Skipped(SkipReason.NOT_TRANSACTION)
        is SmsProcessingOutcome.RetainedForReview ->
            ProcessingResult.Skipped(SkipReason.RETAINED_FOR_REVIEW)
        is SmsProcessingOutcome.Persisted -> ProcessingResult.Saved(
            outcome.transactionIds,
            outcome.alreadyCommitted
        )
        is SmsProcessingOutcome.RetryableFailure -> ProcessingResult.Failure(
            "Saved for retry: ${outcome.reason}", retryable = true
        )
        is SmsProcessingOutcome.Stopped -> ProcessingResult.Stopped
    }

    private fun emit(
        stage: Stage,
        message: String,
        perf: SlmPerformanceData? = null
    ) {
        _pipelineState.value = PipelineStep(
            stage = stage,
            message = message,
            perf = perf
        )
    }

}

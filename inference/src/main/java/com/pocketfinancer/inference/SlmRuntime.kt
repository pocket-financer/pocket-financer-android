package com.pocketfinancer.inference

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exact identity and native configuration of a model residency.
 *
 * [modelPath] is normalized when this value is created so two callers referring
 * to the same file cannot accidentally create two native model identities.
 */
class SlmModelSpec(
    val modelId: String,
    modelPath: String,
    val artifactRevision: String = "",
    val contextSize: Int = 3072,
    val gpuLayers: Int = 0,
    val numThreads: Int = 0,
    val hasFp16: Boolean = false
) {
    val modelPath: String = canonicalPath(modelPath)

    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(this.modelPath.isNotBlank()) { "modelPath must not be blank" }
        require(contextSize > 0) { "contextSize must be positive" }
        require(gpuLayers >= 0) { "gpuLayers must not be negative" }
        require(numThreads >= 0) { "numThreads must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is SlmModelSpec &&
            modelId == other.modelId &&
            modelPath == other.modelPath &&
            artifactRevision == other.artifactRevision &&
            contextSize == other.contextSize &&
            gpuLayers == other.gpuLayers &&
            numThreads == other.numThreads &&
            hasFp16 == other.hasFp16

    override fun hashCode(): Int {
        var result = modelId.hashCode()
        result = 31 * result + modelPath.hashCode()
        result = 31 * result + artifactRevision.hashCode()
        result = 31 * result + contextSize
        result = 31 * result + gpuLayers
        result = 31 * result + numThreads
        result = 31 * result + hasFp16.hashCode()
        return result
    }

    override fun toString(): String =
        "SlmModelSpec(modelId=$modelId, modelPath=$modelPath, " +
            "artifactRevision=$artifactRevision, contextSize=$contextSize, " +
            "gpuLayers=$gpuLayers, numThreads=$numThreads, hasFp16=$hasFp16)"

    private companion object {
        fun canonicalPath(path: String): String = try {
            File(path).canonicalFile.absolutePath
        } catch (_: Exception) {
            File(path).absoluteFile.normalize().path
        }
    }
}

@JvmInline
value class SlmRuntimeOwner(val value: String) {
    init {
        require(value.isNotBlank()) { "Runtime owner must not be blank" }
    }

    companion object {
        val SELECTED_MODEL = SlmRuntimeOwner("selected-model")
        val HOME_SYNC = SlmRuntimeOwner("home-sync")
        val ONBOARDING = SlmRuntimeOwner("onboarding")
        val MODEL_UPGRADE = SlmRuntimeOwner("model-upgrade")
        val SMS_WORKER = SlmRuntimeOwner("sms-worker")
        val LEDGER_EDIT = SlmRuntimeOwner("ledger-edit")
        val SETTINGS_TEST = SlmRuntimeOwner("settings-test")
        val SETTINGS_MANUAL = SlmRuntimeOwner("settings-manual")
    }
}

data class SlmChatMessage(
    val role: String,
    val content: String
)

fun interface SlmTokenCallback {
    fun onToken(token: String)
}

/**
 * Immutable direct-generation request. In particular [grammar] is captured by
 * the caller once and is never re-read by the runtime. There is intentionally
 * no thinking/scratch channel in this production API.
 */
data class SlmExtractionRequest(
    val messages: List<SlmChatMessage>,
    val fallbackPrompt: String,
    val staticPrefix: String? = null,
    val grammar: String? = null,
    val answerTokens: Int = 256,
    val jsonCallback: SlmTokenCallback? = null
) {
    init {
        require(messages.isNotEmpty()) { "At least one chat message is required" }
        require(answerTokens > 0) { "answerTokens must be positive" }
    }
}

data class SlmPerformanceData(
    val tLoadMs: Long,
    val tPromptEvalMs: Long,
    val tEvalMs: Long,
    val nTokens: Int
) {
    val tokensPerSecond: Double
        get() = if (tEvalMs > 0) nTokens.toDouble() / (tEvalMs / 1_000.0) else 0.0
}

data class SlmCacheDiagnostics(
    val attempted: Boolean = false,
    val hit: Boolean = false,
    val sessionFile: String? = null,
    val prefixTokens: Int = 0
)

sealed interface SlmExtractionResult {
    val model: SlmModelSpec

    data class Success(
        val json: String,
        val perf: SlmPerformanceData? = null,
        override val model: SlmModelSpec,
        val cache: SlmCacheDiagnostics = SlmCacheDiagnostics()
    ) : SlmExtractionResult

    data class Null(
        override val model: SlmModelSpec,
        val perf: SlmPerformanceData? = null,
        val cache: SlmCacheDiagnostics = SlmCacheDiagnostics()
    ) : SlmExtractionResult

    data class Error(
        val message: String,
        override val model: SlmModelSpec
    ) : SlmExtractionResult

    data class Stopped(
        override val model: SlmModelSpec
    ) : SlmExtractionResult
}

enum class SlmRuntimePhase {
    UNLOADED,
    LOADING,
    READY,
    RUNNING,
    STOPPING,
    UNLOADING,
    MAINTENANCE,
    ERROR
}

enum class SlmOperationKind {
    LOAD,
    EXTRACT,
    TOKENIZE,
    UNLOAD
}

data class SlmActiveOperation(
    val requestId: Long,
    val kind: SlmOperationKind,
    val owner: SlmRuntimeOwner?,
    val model: SlmModelSpec
)

sealed interface SlmPendingAction {
    data class ModelChange(val model: SlmModelSpec) : SlmPendingAction
    data object Unload : SlmPendingAction
    data class Maintenance(val owner: SlmRuntimeOwner) : SlmPendingAction
}

data class SlmRuntimeState(
    val phase: SlmRuntimePhase = SlmRuntimePhase.UNLOADED,
    val loadedModel: SlmModelSpec? = null,
    val activeOperation: SlmActiveOperation? = null,
    val queueDepth: Int = 0,
    val leaseCount: Int = 0,
    val leasesByOwner: Map<SlmRuntimeOwner, Int> = emptyMap(),
    val pinnedModels: Map<SlmRuntimeOwner, SlmModelSpec> = emptyMap(),
    val pendingAction: SlmPendingAction? = null,
    val lastError: String? = null
) {
    val isBusy: Boolean
        get() = activeOperation != null || queueDepth > 0 ||
            phase == SlmRuntimePhase.LOADING ||
            phase == SlmRuntimePhase.UNLOADING ||
            phase == SlmRuntimePhase.STOPPING ||
            phase == SlmRuntimePhase.MAINTENANCE
}

interface SlmLease {
    val owner: SlmRuntimeOwner
    val model: SlmModelSpec
    val isReleased: Boolean

    suspend fun extract(request: SlmExtractionRequest): SlmExtractionResult

    suspend fun countTokens(text: String, addSpecial: Boolean = true): Int?

    /** Idempotent. A released lease can no longer submit work. */
    suspend fun release()
}

interface SlmMaintenanceLease {
    val owner: SlmRuntimeOwner

    /** Idempotent. New runtime requests remain blocked until release. */
    suspend fun release()
}

/**
 * The only production access path to the process-wide native SLM runtime.
 */
interface SlmRuntime {
    val state: StateFlow<SlmRuntimeState>

    suspend fun acquire(owner: SlmRuntimeOwner, spec: SlmModelSpec): SlmLease

    /**
     * Establish or replace a process-lifetime residency owner. Equal model
     * requests coalesce; a different model waits for existing scoped leases.
     */
    suspend fun pin(owner: SlmRuntimeOwner, spec: SlmModelSpec)

    /**
     * Releases only this owner's pin. Once invoked, this mutation is committed
     * even if the caller is concurrently cancelled. Physical unload is
     * deferred until safe.
     */
    suspend fun unpin(owner: SlmRuntimeOwner)

    /**
     * Atomically enters a destructive-maintenance window only when the runtime
     * is quiescent. [pinsToRelease] are removed only when acquisition succeeds.
     */
    suspend fun tryAcquireMaintenance(
        owner: SlmRuntimeOwner,
        pinsToRelease: Set<SlmRuntimeOwner> = emptySet()
    ): SlmMaintenanceLease?
}

class SlmModelResidencyException(message: String) : IllegalStateException(message)

suspend inline fun <T> SlmRuntime.withLease(
    owner: SlmRuntimeOwner,
    spec: SlmModelSpec,
    block: suspend (SlmLease) -> T
): T {
    val lease = acquire(owner, spec)
    return try {
        block(lease)
    } finally {
        withContext(NonCancellable) {
            lease.release()
        }
    }
}

package com.pocketfinancer.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Raw JNI owner. This type is intentionally module-internal: production
 * callers must go through [SlmRuntime].
 *
 * Apart from [stop], every method is invoked from the coordinator's single
 * native lane. Native operation IDs make the one concurrent control call
 * request-scoped, so a stale cancellation cannot stop a later request.
 */
internal class LlamaEngine(
    context: Context,
    private val storage: SlmModelStorage = DefaultSlmModelStorage(context)
) {
    @Volatile
    private var modelHandle: Long = 0
    private var loadedSpec: SlmModelSpec? = null

    fun loadModel(spec: SlmModelSpec): Result<Unit> = runCatching {
        check(modelHandle == 0L) {
            "The coordinator must unload the resident model before loading another"
        }
        val handle = nativeLoadModel(
            path = spec.modelPath,
            nCtx = spec.contextSize,
            nGpuLayers = spec.gpuLayers,
            nThreads = spec.numThreads,
            hasFp16 = spec.hasFp16
        )
        check(handle != 0L) { "Failed to load model: ${spec.modelPath}" }
        modelHandle = handle
        loadedSpec = spec
    }

    /**
     * Native code rejects unload while an operation is active. The coordinator
     * treats false as a lifecycle invariant failure and never clears the handle.
     */
    fun unloadModel(): Boolean {
        val handle = modelHandle
        if (handle == 0L) {
            loadedSpec = null
            return true
        }
        if (!nativeUnloadModel(handle)) {
            return false
        }
        modelHandle = 0
        loadedSpec = null
        return true
    }

    fun inferForExtraction(
        operationId: Long,
        spec: SlmModelSpec,
        request: SlmExtractionRequest,
        afterOperationStarted: () -> Unit = {}
    ): SlmExtractionResult {
        val handle = modelHandle
        if (handle == 0L || loadedSpec != spec) {
            return SlmExtractionResult.Error("Requested model is not loaded", spec)
        }
        if (!nativeBeginOperation(handle, operationId)) {
            return SlmExtractionResult.Error("Native runtime is already busy", spec)
        }

        return try {
            afterOperationStarted()
            val renderedPrompt = applyChatTemplate(
                handle = handle,
                operationId = operationId,
                messages = request.messages
            ) ?: throw IllegalStateException("non_thinking_template_unavailable")

            if (wasStopped(handle, operationId)) {
                return SlmExtractionResult.Stopped(spec)
            }

            val prepared = prepareSessionPrefix(
                handle = handle,
                operationId = operationId,
                spec = spec,
                prompt = renderedPrompt,
                staticPrefix = request.staticPrefix
            )
            if (wasStopped(handle, operationId)) {
                return SlmExtractionResult.Stopped(spec)
            }

            inferDirect(
                handle = handle,
                operationId = operationId,
                spec = spec,
                request = request,
                prepared = prepared
            )
        } catch (failure: Throwable) {
            if (wasStopped(handle, operationId)) {
                SlmExtractionResult.Stopped(spec)
            } else {
                SlmExtractionResult.Error(
                    failure.message ?: "Unknown inference error",
                    spec
                )
            }
        } finally {
            nativeEndOperation(handle, operationId)
        }
    }

    fun countTokens(
        operationId: Long,
        text: String,
        addSpecial: Boolean,
        afterOperationStarted: () -> Unit = {}
    ): Int? {
        val handle = modelHandle
        if (handle == 0L || !nativeBeginOperation(handle, operationId)) {
            return null
        }
        return try {
            afterOperationStarted()
            nativeTokenize(handle, operationId, text, addSpecial)?.size
        } finally {
            nativeEndOperation(handle, operationId)
        }
    }

    fun stop(operationId: Long): Boolean {
        val handle = modelHandle
        return handle != 0L && nativeStop(handle, operationId)
    }

    private fun inferDirect(
        handle: Long,
        operationId: Long,
        spec: SlmModelSpec,
        request: SlmExtractionRequest,
        prepared: PreparedPrompt
    ): SlmExtractionResult {
        val answer = nativeCompletion(
            handle = handle,
            operationId = operationId,
            prompt = prepared.remainingPrompt,
            grammar = request.grammar,
            nPredict = request.answerTokens,
            temperature = 0.0f,
            stop = null,
            keepCache = prepared.keepCache,
            callback = request.jsonCallback,
            requireJsonObjectStart = true
        )
        return extractionResult(
            handle = handle,
            operationId = operationId,
            spec = spec,
            answer = answer,
            cache = prepared.diagnostics
        )
    }

    private fun extractionResult(
        handle: Long,
        operationId: Long,
        spec: SlmModelSpec,
        answer: String,
        cache: SlmCacheDiagnostics
    ): SlmExtractionResult {
        if (wasStopped(handle, operationId)) {
            return SlmExtractionResult.Stopped(spec)
        }
        if (answer.isEmpty()) {
            return SlmExtractionResult.Error("JSON decode produced empty output", spec)
        }

        val perf = getPerformanceData(handle, operationId)
        val trimmed = answer.trim()
        return if (trimmed == "null") {
            SlmExtractionResult.Null(spec, perf, cache)
        } else {
            SlmExtractionResult.Success(trimmed, perf, spec, cache)
        }
    }

    /**
     * Tokenization, session load/prefill/save, and stale-session cleanup all
     * occur inside the same request-scoped native operation as completion.
     */
    private fun prepareSessionPrefix(
        handle: Long,
        operationId: Long,
        spec: SlmModelSpec,
        prompt: String,
        staticPrefix: String?
    ): PreparedPrompt {
        if (staticPrefix == null) {
            return PreparedPrompt(prompt, keepCache = false)
        }
        val splitIndex = prompt.indexOf(staticPrefix)
        if (splitIndex < 0) {
            return PreparedPrompt(prompt, keepCache = false)
        }

        val prefix = prompt.substring(0, splitIndex + staticPrefix.length)
        val suffix = prompt.substring(splitIndex + staticPrefix.length)
        val prefixHash = storage.sha256(prefix)
        val sessionFile = storage.sessionFile(spec, prefixHash)
        val prefixTokens = nativeTokenize(
            handle,
            operationId,
            prefix,
            true
        ) ?: return PreparedPrompt(
            remainingPrompt = prompt,
            keepCache = false,
            diagnostics = SlmCacheDiagnostics(attempted = true)
        )

        if (wasStopped(handle, operationId)) {
            return PreparedPrompt(
                remainingPrompt = prompt,
                keepCache = false,
                diagnostics = SlmCacheDiagnostics(
                    attempted = true,
                    sessionFile = sessionFile.absolutePath,
                    prefixTokens = prefixTokens.size
                )
            )
        }

        var cacheHit = false
        if (sessionFile.exists()) {
            val loadedTokenIds = IntArray(prefixTokens.size)
            val loadedTokens = nativeLoadSession(
                handle,
                operationId,
                sessionFile.absolutePath,
                loadedTokenIds
            )
            cacheHit = loadedTokens == prefixTokens.size &&
                loadedTokenIds.contentEquals(prefixTokens)
        }

        if (!cacheHit && !wasStopped(handle, operationId)) {
            nativeCompletion(
                handle = handle,
                operationId = operationId,
                prompt = prefix,
                grammar = null,
                nPredict = 0,
                temperature = 0.0f,
                stop = null,
                keepCache = false,
                callback = null,
                requireJsonObjectStart = false
            )
            if (!wasStopped(handle, operationId)) {
                storage.deleteStaleSessions(spec, prefixHash)
                nativeSaveSession(
                    handle,
                    operationId,
                    sessionFile.absolutePath,
                    prefixTokens
                )
            }
        }

        return PreparedPrompt(
            remainingPrompt = suffix,
            keepCache = true,
            diagnostics = SlmCacheDiagnostics(
                attempted = true,
                hit = cacheHit,
                sessionFile = sessionFile.absolutePath,
                prefixTokens = prefixTokens.size
            )
        )
    }

    private fun applyChatTemplate(
        handle: Long,
        operationId: Long,
        messages: List<SlmChatMessage>
    ): String? {
        val json = JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().apply {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        }
        return nativeApplyChatTemplate(
            handle,
            operationId,
            json.toString(),
            true
        ).ifEmpty { null }
    }

    private fun getPerformanceData(
        handle: Long,
        operationId: Long
    ): SlmPerformanceData? {
        val json = nativeGetPerfData(handle, operationId) ?: return null
        return try {
            val value = JSONObject(json)
            if (!value.keys().hasNext()) {
                null
            } else {
                SlmPerformanceData(
                    tLoadMs = value.optLong("t_load_ms", 0),
                    tPromptEvalMs = value.optLong("t_p_eval_ms", 0),
                    tEvalMs = value.optLong("t_eval_ms", 0),
                    nTokens = value.optInt("n_tokens", 0)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun wasStopped(handle: Long, operationId: Long): Boolean =
        nativeWasStopped(handle, operationId)

    // Kept module-internal for focused storage/hash unit tests.
    fun computeSha256(input: String): String = storage.sha256(input)

    fun getModelStorageDir(): File = storage.modelDirectory

    fun getSessionFile(hash: String): File =
        storage.sessionFile(
            loadedSpec?.let { File(it.modelPath).name } ?: MODEL_FILENAME,
            hash
        )

    fun deleteStaleSessions(activeHash: String?) {
        storage.deleteStaleSessions(
            loadedSpec?.let { File(it.modelPath).name } ?: MODEL_FILENAME,
            activeHash
        )
    }

    private data class PreparedPrompt(
        val remainingPrompt: String,
        val keepCache: Boolean,
        val diagnostics: SlmCacheDiagnostics = SlmCacheDiagnostics()
    )

    companion object {
        const val MODEL_FILENAME = "qwen3-1.7b-Q8_0.gguf"

        init {
            try {
                System.loadLibrary("pocketfinancer_llm")
            } catch (_: UnsatisfiedLinkError) {
                // Expected in local JVM tests. Production load reports failure.
            }
        }
    }

    private external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nGpuLayers: Int,
        nThreads: Int,
        hasFp16: Boolean
    ): Long

    private external fun nativeBeginOperation(handle: Long, operationId: Long): Boolean

    private external fun nativeEndOperation(handle: Long, operationId: Long): Boolean

    private external fun nativeWasStopped(handle: Long, operationId: Long): Boolean

    private external fun nativeCompletion(
        handle: Long,
        operationId: Long,
        prompt: String,
        grammar: String?,
        nPredict: Int,
        temperature: Float,
        stop: String?,
        keepCache: Boolean,
        callback: SlmTokenCallback?,
        requireJsonObjectStart: Boolean
    ): String

    private external fun nativeApplyChatTemplate(
        handle: Long,
        operationId: Long,
        messages: String,
        addAssistantPrefix: Boolean
    ): String

    private external fun nativeGetPerfData(handle: Long, operationId: Long): String?

    private external fun nativeStop(handle: Long, operationId: Long): Boolean

    private external fun nativeUnloadModel(handle: Long): Boolean

    private external fun nativeTokenize(
        handle: Long,
        operationId: Long,
        text: String,
        addSpecial: Boolean
    ): IntArray?

    private external fun nativeSaveSession(
        handle: Long,
        operationId: Long,
        path: String,
        tokens: IntArray
    ): Boolean

    private external fun nativeLoadSession(
        handle: Long,
        operationId: Long,
        path: String,
        tokensOut: IntArray
    ): Int
}

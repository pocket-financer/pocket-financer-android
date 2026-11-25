#include <jni.h>
#include <vector>
#include <algorithm>
#include <string>
#include <cstring>
#include <cstdlib>
#include <atomic>
#include <mutex>
#include <thread>
#include <utility>
#include <exception>

#include "llama.h"
#include "chat.h"
#include "log.h"

// ── Per-model state ─────────────────────────────────────────────────────────

struct ModelInstance {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    const llama_vocab *vocab = nullptr;
    // Exactly one coordinator-owned native operation may mutate ctx/KV/perf.
    // stop_operation is request-scoped so a stale cancel cannot affect the
    // next operation that reuses this ModelInstance.
    std::atomic<jlong> active_operation{0};
    std::atomic<jlong> stop_operation{0};
    std::atomic<jlong> pending_stop_operation{0};
    int n_past = 0;
};

// ── JNI lifecycle (one-time, when .so is loaded) ───────────────────────────
// llama_backend_init/free must be called exactly once per process lifetime.
// JNI_OnLoad/OnUnload guarantees that.

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM */*vm*/, void */*reserved*/) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM */*vm*/, void */*reserved*/) {
    llama_backend_free();
}

// ── Helpers ─────────────────────────────────────────────────────────────────

static jlong ptr_to_jlong(void *p) {
    return reinterpret_cast<jlong>(p);
}

static ModelInstance *jlong_to_instance(jlong handle) {
    return reinterpret_cast<ModelInstance *>(handle);
}

static bool operation_matches(ModelInstance *inst, jlong operation_id) {
    return inst &&
        operation_id != 0 &&
        inst->active_operation.load(std::memory_order_acquire) == operation_id;
}

static bool operation_stopped(ModelInstance *inst, jlong operation_id) {
    return operation_matches(inst, operation_id) &&
        inst->stop_operation.load(std::memory_order_acquire) == operation_id;
}

/** Optimal thread count: use available cores, capped at 4 for thermal safety. */
static int get_optimal_thread_count() {
    int cores = (int)std::thread::hardware_concurrency();
    return std::max(1, std::min(cores, 4));
}

// ── Log callback (routes llama.cpp logs to Android logcat) ──────────────────

static auto log_callback = [](ggml_log_level level, const char * text, void * /*user_data*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_print(prio, "llama.cpp", "%s", text);
};

// ── nativeLoadModel ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeLoadModel(
    JNIEnv *env, jclass /*clazz*/,
    jstring jpath, jint n_ctx, jint n_gpu_layers, jint n_threads, jboolean has_fp16) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    // Register log callback once (safe to call multiple times, only first takes)
    llama_log_set(log_callback, nullptr);

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.use_mmap     = false;  // mmap from private storage can fail silently on Android

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (!model) {
        LOG_ERR("nativeLoadModel: failed to load model\n");
        return 0;
    }

    // Context params
    int optimal_threads = (n_threads > 0) ? (int)n_threads : get_optimal_thread_count();
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx        = n_ctx;
    ctx_params.n_batch      = 512;
    ctx_params.n_ubatch     = 256;   // smaller physical batch = lower peak memory
    ctx_params.n_threads    = optimal_threads;
    ctx_params.n_threads_batch = optimal_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_params.type_k = has_fp16 ? GGML_TYPE_F16 : GGML_TYPE_Q8_0;
    ctx_params.type_v = has_fp16 ? GGML_TYPE_F16 : GGML_TYPE_Q8_0;
    ctx_params.no_perf = false;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOG_ERR("nativeLoadModel: failed to create context\n");
        llama_model_free(model);
        return 0;
    }

    auto *inst = new ModelInstance();
    inst->model = model;
    inst->ctx   = ctx;
    inst->vocab = llama_model_get_vocab(model);
    inst->active_operation.store(0);
    inst->stop_operation.store(0);
    inst->pending_stop_operation.store(0);

    LOG_INF("nativeLoadModel: loaded model, ctx=%d, threads=%d, gpu_layers=%d\n",
            n_ctx, optimal_threads, n_gpu_layers);
    return ptr_to_jlong(inst);
}

// ── Request-scoped operation lifecycle ─────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeBeginOperation(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle, jlong operation_id) {
    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx || operation_id == 0) {
        return JNI_FALSE;
    }

    jlong expected = 0;
    if (!inst->active_operation.compare_exchange_strong(
            expected,
            operation_id,
            std::memory_order_acq_rel,
            std::memory_order_acquire)) {
        return JNI_FALSE;
    }

    // Reset cancellation and performance exactly once for the complete
    // template/tokenize/session/prefill/generation/perf operation.
    const jlong pending_stop =
        inst->pending_stop_operation.exchange(0, std::memory_order_acq_rel);
    if (pending_stop == operation_id) {
        inst->stop_operation.store(operation_id, std::memory_order_release);
    }
    llama_perf_context_reset(inst->ctx);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeEndOperation(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle, jlong operation_id) {
    auto *inst = jlong_to_instance(handle);
    if (!operation_matches(inst, operation_id)) {
        return JNI_FALSE;
    }
    jlong expected = operation_id;
    return inst->active_operation.compare_exchange_strong(
        expected,
        0,
        std::memory_order_acq_rel,
        std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeWasStopped(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle, jlong operation_id) {
    return operation_stopped(jlong_to_instance(handle), operation_id)
        ? JNI_TRUE
        : JNI_FALSE;
}

// ── nativeCompletion ────────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeCompletion(
    JNIEnv *env, jclass /*clazz*/,
    jlong handle,
    jlong operation_id,
    jstring jprompt,
    jstring jgrammar,
    jint    n_predict,
    jfloat  temperature,
    jstring jstop,
    jboolean jkeep_cache,
    jobject jcallback,
    jboolean jrequire_json_object_start) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx || !operation_matches(inst, operation_id)) {
        return env->NewStringUTF("");
    }

    const char *prompt  = env->GetStringUTFChars(jprompt, nullptr);
    const char *grammar = jgrammar ? env->GetStringUTFChars(jgrammar, nullptr) : nullptr;
    const char *stop    = jstop    ? env->GetStringUTFChars(jstop, nullptr)    : nullptr;

    if (!prompt) return env->NewStringUTF("");

    // Tokenize prompt
    bool add_special = !jkeep_cache;
    int n_tokens = -llama_tokenize(inst->vocab, prompt, (int)strlen(prompt), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(inst->vocab, prompt, (int)strlen(prompt), tokens.data(), n_tokens, add_special, true);

    if (tokens.empty()) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        if (grammar) env->ReleaseStringUTFChars(jgrammar, grammar);
        if (stop)    env->ReleaseStringUTFChars(jstop, stop);
        return env->NewStringUTF("");
    }

    int n_ctx = llama_n_ctx(inst->ctx);

    if (!jkeep_cache) {
        // Clear KV cache for sequence 0 before starting this execution
        llama_memory_seq_rm(llama_get_memory(inst->ctx), 0, -1, -1);
        inst->n_past = 0;
    } else if (inst->n_past + n_tokens > n_ctx) {
        // Fallback if cache + new tokens exceeds context size
        __android_log_print(ANDROID_LOG_WARN, "PocketFinancer",
                            "nativeCompletion: Cache + prompt (%d + %d) exceeds context size (%d). Resetting cache.",
                            inst->n_past, n_tokens, n_ctx);
        llama_memory_seq_rm(llama_get_memory(inst->ctx), 0, -1, -1);
        inst->n_past = 0;
    }

    // KV cache eviction: if prompt exceeds context, truncate it from the start
    if (n_tokens > n_ctx) {
        int keep_space = std::min(n_ctx, 128);
        int keep = n_ctx - keep_space;
        int to_delete = n_tokens - keep;
        if (to_delete > 0) {
            __android_log_print(ANDROID_LOG_WARN, "PocketFinancer",
                                "nativeCompletion: prompt size (%d) exceeds context size (%d). Truncating oldest %d tokens.",
                                n_tokens, n_ctx, to_delete);
            tokens.erase(tokens.begin(), tokens.begin() + to_delete);
            n_tokens = (int)tokens.size();
        }
    }

    // Result buffer
    std::string result;
    bool json_started = false;
    bool mode_violation = false;

    // Look up callback method if provided
    jmethodID on_token_method = nullptr;
    if (jcallback) {
        jclass callback_class = env->GetObjectClass(jcallback);
        on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(callback_class);
    }

    __android_log_print(ANDROID_LOG_INFO, "PocketFinancer",
                        "nativeCompletion: jkeep_cache=%d, n_tokens=%d, inst->n_past=%d, kv_min=%d, kv_max=%d",
                        jkeep_cache, n_tokens, inst->n_past,
                        llama_memory_seq_pos_min(llama_get_memory(inst->ctx), 0),
                        llama_memory_seq_pos_max(llama_get_memory(inst->ctx), 0));

    // Prefill prompt (chunked by n_batch to prevent crashes when prompt size > n_batch)
    int n_batch = llama_n_batch(inst->ctx);
    bool prefill_failed = false;
    for (int i = 0; i < n_tokens; i += n_batch) {
        if (operation_stopped(inst, operation_id)) {
            prefill_failed = true;
            break;
        }
        int n_eval = std::min(n_tokens - i, n_batch);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (int j = 0; j < n_eval; j++) {
            batch.token[j] = tokens[i + j];
            batch.pos[j] = inst->n_past + i + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == n_tokens - 1);
        }
        int decode_res = llama_decode(inst->ctx, batch);
        if (decode_res != 0) {
            LOG_ERR("nativeCompletion: prefill decode failed at chunk %d\n", i);
            llama_batch_free(batch);
            prefill_failed = true;
            break;
        }
        llama_batch_free(batch);
        if (operation_stopped(inst, operation_id)) {
            prefill_failed = true;
            break;
        }
    }

    if (prefill_failed) {
        env->ReleaseStringUTFChars(jprompt, prompt);
        if (grammar) env->ReleaseStringUTFChars(jgrammar, grammar);
        if (stop)    env->ReleaseStringUTFChars(jstop, stop);
        return env->NewStringUTF(result.c_str());
    }

    inst->n_past += n_tokens;

    {
        // ── Sampler ──
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        sparams.no_perf = true;

        llama_sampler *smpl = llama_sampler_chain_init(sparams);

        if (grammar && strlen(grammar) > 0) {
            // Constrain logits with grammar, then sample greedily
            llama_sampler_chain_add(smpl, llama_sampler_init_grammar(
                inst->vocab, grammar, "root"));
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            // Greedy (deterministic) sampling with temperature
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
            if (temperature > 0.0f) {
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
            }
        }

        // Allocate batch of size 1 for decode loop
        llama_batch batch_single = llama_batch_init(1, 0, 1);
        batch_single.n_tokens = 1;
        batch_single.n_seq_id[0] = 1;
        batch_single.seq_id[0][0] = 0;
        batch_single.logits[0] = true;

        // ── Decode loop ──
        for (int i = 0; i < n_predict; i++) {
            if (operation_stopped(inst, operation_id)) {
                break;
            }

            // Context size limit safety check
            if (inst->n_past >= n_ctx) {
                __android_log_print(ANDROID_LOG_WARN, "PocketFinancer",
                                    "nativeCompletion: context size limit reached (%d/%d), stopping generation.",
                                    inst->n_past, n_ctx);
                break;
            }

            llama_token new_token = llama_sampler_sample(smpl, inst->ctx, -1);

            if (llama_vocab_is_eog(inst->vocab, new_token)) {
                break;
            }

            char buf[256];
            int n = llama_token_to_piece(inst->vocab, new_token, buf, sizeof(buf), 0, true);
            if (n > 0) {
                std::string piece(buf, n);
                if (jrequire_json_object_start && !json_started) {
                    const size_t first = piece.find_first_not_of(" \t\r\n");
                    if (first == std::string::npos) {
                        piece.clear();
                    } else if (piece[first] != '{') {
                        mode_violation = true;
                        break;
                    } else {
                        json_started = true;
                        piece.erase(0, first);
                    }
                }
                if (!piece.empty()) {
                    result.append(piece);
                    // No decoded thought prefix reaches the callback.
                    if (jcallback && on_token_method) {
                        jstring jtoken_piece = env->NewStringUTF(piece.c_str());
                        env->CallVoidMethod(jcallback, on_token_method, jtoken_piece);
                        env->DeleteLocalRef(jtoken_piece);
                    }
                }
            }

            // Check stop string
            if (stop && strlen(stop) > 0 && result.find(stop) != std::string::npos) {
                break;
            }

            // Decode single token
            batch_single.token[0] = new_token;
            batch_single.pos[0] = inst->n_past;

            if (llama_decode(inst->ctx, batch_single) != 0) {
                LOG_ERR("nativeCompletion: single token decode failed\n");
                break;
            }
            inst->n_past++;
            if (operation_stopped(inst, operation_id)) {
                break;
            }
        }

        llama_batch_free(batch_single);
        llama_sampler_free(smpl);
    }

    env->ReleaseStringUTFChars(jprompt, prompt);
    if (grammar) env->ReleaseStringUTFChars(jgrammar, grammar);
    if (stop)    env->ReleaseStringUTFChars(jstop, stop);

    if (mode_violation) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception) {
            env->ThrowNew(exception, "runtime_mode_violation");
            env->DeleteLocalRef(exception);
        }
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

// ── nativeApplyChatTemplate ─────────────────────────────────────────────────

/**
 * Simple lightweight JSON array of objects parser for chat messages.
 * Input format: [{"role":"...","content":"..."},...]
 * Returns number of messages parsed, or 0 on failure.
 * Messages are allocated at the given pointer (must hold up to 16 messages).
 */
static int parse_chat_messages_json(const char *json, llama_chat_message *out, int max_msgs) {
    int count = 0;
    const char *p = json;

    // Skip whitespace and opening bracket
    while (*p && *p != '[') { if (*p > ' ') break; p++; }
    if (*p != '[') return 0;
    p++;

    while (*p && count < max_msgs) {
        // Skip whitespace and opening brace
        while (*p && *p != '{') { if (*p == ']') return count; p++; }
        if (*p != '{') break;
        p++;

        const char *role_val = nullptr;
        const char *content_val = nullptr;
        int role_len = 0, content_len = 0;

        // Parse key-value pairs
        while (*p && *p != '}') {
            // Skip whitespace
            while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;

            // Find quoted key
            if (*p != '"') { p++; continue; }
            p++; // skip opening quote

            const char *key_start = p;
            while (*p && *p != '"') p++;
            if (*p != '"') return count;
            int key_len = (int)(p - key_start);
            p++; // skip closing quote

            // Skip colon
            while (*p && *p != ':') p++;
            if (*p == ':') p++;
            while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;

            // Parse string value
            if (*p == '"') {
                p++; // skip opening quote
                const char *val_start = p;
                while (*p && *p != '"') {
                    if (*p == '\\') p++; // skip escaped char
                    p++;
                }
                int val_len = (int)(p - val_start);
                if (*p == '"') p++; // skip closing quote

                if (key_len == 4 && strncmp(key_start, "role", 4) == 0) {
                    role_val = val_start;
                    role_len = val_len;
                } else if (key_len == 7 && strncmp(key_start, "content", 7) == 0) {
                    content_val = val_start;
                    content_len = val_len;
                }
            }

            // Skip comma
            while (*p && *p != ',' && *p != '}') p++;
            if (*p == ',') p++;
        }
        if (*p == '}') p++;

        if (role_val && content_val && count < max_msgs) {
            // Allocate and copy strings
            char *role_copy = (char *)malloc(role_len + 1);
            char *content_copy = (char *)malloc(content_len + 1);
            if (role_copy && content_copy) {
                memcpy(role_copy, role_val, role_len);
                role_copy[role_len] = '\0';
                // Unescape JSON escapes in content (simple \n handling)
                int ci = 0;
                for (int i = 0; i < content_len; i++) {
                    if (content_val[i] == '\\' && i + 1 < content_len) {
                        if (content_val[i + 1] == 'n') { content_copy[ci++] = '\n'; i++; }
                        else if (content_val[i + 1] == '"') { content_copy[ci++] = '"'; i++; }
                        else if (content_val[i + 1] == '\\') { content_copy[ci++] = '\\'; i++; }
                        else { content_copy[ci++] = content_val[i]; }
                    } else {
                        content_copy[ci++] = content_val[i];
                    }
                }
                content_copy[ci] = '\0';

                out[count].role = role_copy;
                out[count].content = content_copy;
                count++;
            } else {
                free(role_copy);
                free(content_copy);
            }
        }

        // Skip comma
        while (*p && *p != ',' && *p != ']') p++;
        if (*p == ',') p++;
    }

    return count;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeApplyChatTemplate(
    JNIEnv *env, jclass /*clazz*/,
    jlong handle, jlong operation_id, jstring jmessages,
    jboolean add_assistant_prefix) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->model || !operation_matches(inst, operation_id)) {
        return env->NewStringUTF("");
    }

    const char *messages_json = env->GetStringUTFChars(jmessages, nullptr);
    if (!messages_json) return env->NewStringUTF("");

    // Calculate n_msg by counting '{' in JSON (safe upper bound)
    int n_msg_est = 0;
    for (const char *cp = messages_json; *cp; cp++) {
        if (*cp == '{') n_msg_est++;
    }

    if (n_msg_est == 0) {
        env->ReleaseStringUTFChars(jmessages, messages_json);
        return env->NewStringUTF("");
    }

    // Allocate storage for messages
    std::vector<llama_chat_message> msgs(n_msg_est);

    // Parse JSON messages into llama_chat_message array
    int n_msgs = parse_chat_messages_json(messages_json, msgs.data(), n_msg_est);

    env->ReleaseStringUTFChars(jmessages, messages_json);

    if (n_msgs == 0) {
        LOG_ERR("nativeApplyChatTemplate: failed to parse chat messages JSON\n");
        return env->NewStringUTF("");
    }

    // Copy messages before releasing the JNI parser's malloc-backed strings.
    std::vector<common_chat_msg> chat_messages;
    chat_messages.reserve(n_msgs);
    for (int i = 0; i < n_msgs; i++) {
        common_chat_msg message;
        message.role = msgs[i].role;
        message.content = msgs[i].content;
        chat_messages.push_back(std::move(message));
        free((void *)msgs[i].role);
        free((void *)msgs[i].content);
    }

    try {
        auto templates = common_chat_templates_init(inst->model, "");
        common_chat_templates_inputs inputs;
        inputs.messages = std::move(chat_messages);
        inputs.add_generation_prompt = (bool)add_assistant_prefix;
        inputs.use_jinja = true;
        inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_NONE;
        inputs.enable_thinking = false;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_NONE;
        inputs.force_pure_content = true;
        const std::string rendered =
            common_chat_templates_apply(templates.get(), inputs).prompt;
        return env->NewStringUTF(rendered.c_str());
    } catch (const std::exception &) {
        LOG_ERR("nativeApplyChatTemplate: non-thinking template rendering failed\n");
        return env->NewStringUTF("");
    }
}

// ── nativeGetPerfData ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeGetPerfData(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jlong operation_id) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx || !operation_matches(inst, operation_id)) {
        return env->NewStringUTF("{}");
    }

    const auto pd = llama_perf_context(inst->ctx);

    char json[512];
    int n = snprintf(json, sizeof(json),
        "{\"t_load_ms\":%lld,\"t_p_eval_ms\":%lld,\"t_eval_ms\":%lld,\"n_tokens\":%d}",
        (long long)pd.t_load_ms,
        (long long)pd.t_p_eval_ms,
        (long long)pd.t_eval_ms,
        pd.n_eval);

    return env->NewStringUTF(json);
}

// ── nativeStop ──────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeStop(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle, jlong operation_id) {
    auto *inst = jlong_to_instance(handle);
    if (!inst || operation_id == 0) {
        return JNI_FALSE;
    }

    jlong active = inst->active_operation.load(std::memory_order_acquire);
    if (active == operation_id) {
        inst->stop_operation.store(operation_id, std::memory_order_release);
        return JNI_TRUE;
    }
    if (active != 0) {
        return JNI_FALSE;
    }

    // The actor may issue stop after dispatching the native job but just before
    // nativeBeginOperation executes. Publish a request-scoped pending stop and
    // then re-check active to close that race.
    inst->pending_stop_operation.store(operation_id, std::memory_order_release);
    active = inst->active_operation.load(std::memory_order_acquire);
    if (active == operation_id) {
        inst->stop_operation.store(operation_id, std::memory_order_release);
        jlong expected = operation_id;
        inst->pending_stop_operation.compare_exchange_strong(
            expected, 0, std::memory_order_acq_rel, std::memory_order_acquire);
        return JNI_TRUE;
    }
    if (active != 0) {
        jlong expected = operation_id;
        inst->pending_stop_operation.compare_exchange_strong(
            expected, 0, std::memory_order_acq_rel, std::memory_order_acquire);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// ── nativeUnloadModel ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeUnloadModel(
    JNIEnv */*env*/, jclass /*clazz*/, jlong handle) {
    auto *inst = jlong_to_instance(handle);
    if (!inst) return JNI_TRUE;
    if (inst->active_operation.load(std::memory_order_acquire) != 0) {
        return JNI_FALSE;
    }

    if (inst->ctx) {
        llama_free(inst->ctx);
        inst->ctx = nullptr;
    }
    if (inst->model) {
        llama_model_free(inst->model);
        inst->model = nullptr;
    }
    // NOTE: llama_backend_free is NOT called here — it's handled in JNI_OnUnload
    delete inst;
    return JNI_TRUE;
}

// ── nativeGetModelSize ──────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeGetModelSize(
    JNIEnv *env, jclass /*clazz*/, jstring jpath) {

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;

    llama_model_params mparams = llama_model_default_params();
    auto *model = llama_model_load_from_file(path, mparams);
    jlong size = -1;
    if (model) {
        size = (jlong)llama_model_size(model);
        llama_model_free(model);
    }
    env->ReleaseStringUTFChars(jpath, path);
    return size;
}

// ── nativeTokenize ─────────────────────────────────────────────────────────

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeTokenize(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jlong operation_id,
    jstring jtext, jboolean add_special) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->vocab || !operation_matches(inst, operation_id)) {
        return nullptr;
    }

    const char *text = env->GetStringUTFChars(jtext, nullptr);
    if (!text) return nullptr;

    int n_tokens = -llama_tokenize(inst->vocab, text, (int)strlen(text), nullptr, 0, add_special, true);
    if (n_tokens < 0) {
        env->ReleaseStringUTFChars(jtext, text);
        return nullptr;
    }

    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(inst->vocab, text, (int)strlen(text), tokens.data(), n_tokens, add_special, true);

    jintArray result = env->NewIntArray(n_tokens);
    jint *result_ptr = env->GetIntArrayElements(result, nullptr);
    for (int i = 0; i < n_tokens; ++i) {
        result_ptr[i] = static_cast<jint>(tokens[i]);
    }
    env->ReleaseIntArrayElements(result, result_ptr, 0);

    env->ReleaseStringUTFChars(jtext, text);
    return result;
}

// ── nativeSaveSession ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeSaveSession(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jlong operation_id,
    jstring jpath, jintArray jtokens) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx || !operation_matches(inst, operation_id)) {
        return JNI_FALSE;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;

    jsize n_tokens = env->GetArrayLength(jtokens);
    jint *tokens_ptr = env->GetIntArrayElements(jtokens, nullptr);

    std::vector<llama_token> tokens(n_tokens);
    for (jsize i = 0; i < n_tokens; ++i) {
        tokens[i] = static_cast<llama_token>(tokens_ptr[i]);
    }
    env->ReleaseIntArrayElements(jtokens, tokens_ptr, JNI_ABORT);

    bool ok = llama_state_save_file(inst->ctx, path, tokens.data(), tokens.size());

    __android_log_print(ANDROID_LOG_INFO, "pocketfinancer_llm",
                        "nativeSaveSession: saved session to %s (status=%d, tokens=%d)", path, ok, n_tokens);

    env->ReleaseStringUTFChars(jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativeLoadSession ───────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketfinancer_inference_LlamaEngine_nativeLoadSession(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jlong operation_id,
    jstring jpath, jintArray jtokens_out) {

    auto *inst = jlong_to_instance(handle);
    if (!inst || !inst->ctx || !operation_matches(inst, operation_id)) {
        return -1;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;

    jsize max_tokens = env->GetArrayLength(jtokens_out);
    std::vector<llama_token> tokens(max_tokens);
    size_t n_tokens_loaded = 0;

    // Clear KV cache for sequence 0 before loading session to prevent interleaving corruption
    llama_memory_seq_rm(llama_get_memory(inst->ctx), 0, -1, -1);
    inst->n_past = 0;

    bool ok = llama_state_load_file(inst->ctx, path, tokens.data(), tokens.size(), &n_tokens_loaded);
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, "pocketfinancer_llm",
                            "nativeLoadSession: failed to load session from %s", path);
        env->ReleaseStringUTFChars(jpath, path);
        return -1;
    }

    // Copy tokens back to dynamic buffer
    jint *tokens_ptr = env->GetIntArrayElements(jtokens_out, nullptr);
    for (size_t i = 0; i < n_tokens_loaded && i < static_cast<size_t>(max_tokens); ++i) {
        tokens_ptr[i] = static_cast<jint>(tokens[i]);
    }
    env->ReleaseIntArrayElements(jtokens_out, tokens_ptr, 0);

    // Update context pointer position
    inst->n_past = static_cast<int>(n_tokens_loaded);

    __android_log_print(ANDROID_LOG_INFO, "pocketfinancer_llm",
                        "nativeLoadSession: loaded session from %s (tokens=%zu, n_past=%d)",
                        path, n_tokens_loaded, inst->n_past);

    env->ReleaseStringUTFChars(jpath, path);
    return static_cast<jint>(n_tokens_loaded);
}


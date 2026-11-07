#include <jni.h>
#include "llama.h"
#include <chrono>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr int32_t kPromptBatchTokens = 512;
constexpr int32_t kMaxThreads = 6;
constexpr int32_t kTopK = 20;
constexpr float   kTopP = 0.8f;
constexpr float   kTemperature = 0.2f;
constexpr uint32_t kSamplerSeed = 0x4D554553U; // 'MUES'

std::once_flag g_backend_once;

void ensure_backend_initialized() {
    std::call_once(g_backend_once, []() { llama_backend_init(); });
}

void throw_java(JNIEnv * env, const char * class_name, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

struct QwenNativeSession {
    llama_model *   model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    int32_t         n_ctx   = 0;

    ~QwenNativeSession() {
        if (sampler != nullptr) {
            llama_sampler_free(sampler);
            sampler = nullptr;
        }
        if (ctx != nullptr) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
    }
};

bool tokenize_utf8(
    JNIEnv * env,
    const llama_vocab * vocab,
    const char * text,
    int32_t text_len,
    std::vector<llama_token> & out
) {
    int32_t cap = std::max<int32_t>(text_len + 16, 64);
    out.resize(static_cast<size_t>(cap));
    int32_t n = llama_tokenize(vocab, text, text_len, out.data(), cap, /*add_special=*/false, /*parse_special=*/true);
    if (n < 0) {
        cap = -n;
        out.resize(static_cast<size_t>(cap));
        n = llama_tokenize(vocab, text, text_len, out.data(), cap, /*add_special=*/false, /*parse_special=*/true);
    }
    if (n < 0) {
        throw_java(env, "java/io/IOException", "Failed to tokenize prompt");
        return false;
    }
    out.resize(static_cast<size_t>(n));
    return true;
}

/** Invokes Kotlin `() -> Boolean` on the current attached JNI caller thread only. */
bool should_cancel(JNIEnv * env, jobject callback, jmethodID invoke_mid) {
    if (callback == nullptr || invoke_mid == nullptr) return false;
    jobject boxed = env->CallObjectMethod(callback, invoke_mid);
    if (env->ExceptionCheck()) return true;
    if (boxed == nullptr) return false;
    jclass bool_cls = env->GetObjectClass(boxed);
    jmethodID val_mid = env->GetMethodID(bool_cls, "booleanValue", "()Z");
    jboolean cancelled = (val_mid != nullptr) ? env->CallBooleanMethod(boxed, val_mid) : JNI_FALSE;
    env->DeleteLocalRef(bool_cls);
    env->DeleteLocalRef(boxed);
    return cancelled == JNI_TRUE || env->ExceptionCheck();
}

/** MethodID pair for invoking a Kotlin `(Int) -> Unit` callback. */
struct TokenCallback {
    jobject   object  = nullptr;
    jmethodID invoke  = nullptr;
    jmethodID valueOf = nullptr; // Integer.valueOf(I)
};

TokenCallback bind_token_callback(JNIEnv * env, jobject callback) {
    TokenCallback cb;
    if (callback == nullptr) return cb;
    jclass fn_cls = env->GetObjectClass(callback);
    cb.invoke = env->GetMethodID(fn_cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(fn_cls);
    if (cb.invoke == nullptr) return cb;
    jclass integer_cls = env->FindClass("java/lang/Integer");
    if (integer_cls != nullptr) {
        cb.valueOf = env->GetStaticMethodID(integer_cls, "valueOf", "(I)Ljava/lang/Integer;");
        env->DeleteLocalRef(integer_cls);
    }
    cb.object = callback;
    return cb;
}

/**
 * Reports cumulative generated tokens on the JNI caller thread, throttled to
 * once per second (the caller emits the final count at completion). Returns
 * false when the JVM callback threw, leaving the pending exception set.
 */
bool emit_tokens(JNIEnv * env, const TokenCallback & cb, jint generated) {
    if (cb.object == nullptr || cb.invoke == nullptr || cb.valueOf == nullptr) return true;
    jclass integer_cls = env->FindClass("java/lang/Integer");
    if (integer_cls == nullptr) return false;
    jobject boxed = env->CallStaticObjectMethod(integer_cls, cb.valueOf, generated);
    env->DeleteLocalRef(integer_cls);
    if (env->ExceptionCheck()) return false;
    jobject result = env->CallObjectMethod(cb.object, cb.invoke, boxed);
    if (result != nullptr) env->DeleteLocalRef(result);
    env->DeleteLocalRef(boxed);
    return !env->ExceptionCheck();
}

using SteadyClock = std::chrono::steady_clock;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phequals7_muesli_summaries_LocalQwenRuntime_nativeOpen(
    JNIEnv * env,
    jclass /*clazz*/,
    jstring model_path_j,
    jint context_tokens,
    jint threads
) {
    if (model_path_j == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "modelPath is null");
        return 0;
    }
    ensure_backend_initialized();

    const char * path_chars = env->GetStringUTFChars(model_path_j, nullptr);
    if (path_chars == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not read modelPath");
        return 0;
    }
    std::string model_path(path_chars);
    env->ReleaseStringUTFChars(model_path_j, path_chars);

    auto session = std::make_unique<QwenNativeSession>();
    const int32_t n_ctx = std::max<int32_t>(context_tokens, 512);
    const int32_t n_threads = std::clamp<int32_t>(threads, 1, kMaxThreads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    session->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (session->model == nullptr) {
        throw_java(env, "java/io/IOException", ("Failed to load GGUF model: " + model_path).c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = static_cast<uint32_t>(kPromptBatchTokens);
    cparams.n_ubatch = static_cast<uint32_t>(kPromptBatchTokens);
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;

    session->ctx = llama_init_from_model(session->model, cparams);
    if (session->ctx == nullptr) {
        throw_java(env, "java/io/IOException", "Failed to initialize llama context");
        return 0;
    }
    session->n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    session->sampler = llama_sampler_chain_init(sparams);
    if (session->sampler == nullptr) {
        throw_java(env, "java/io/IOException", "Failed to initialize sampler chain");
        return 0;
    }
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(kTopK));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(kTopP, 1));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(kTemperature));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(kSamplerSeed));

    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_phequals7_muesli_summaries_LocalQwenRuntime_nativeTokenCount(
    JNIEnv * env,
    jclass /*clazz*/,
    jlong handle,
    jbyteArray prompt_utf8
) {
    auto * session = reinterpret_cast<QwenNativeSession *>(handle);
    if (session == nullptr || session->model == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Qwen session is closed");
        return 0;
    }
    if (prompt_utf8 == nullptr) return 0;

    const jsize len = env->GetArrayLength(prompt_utf8);
    if (len <= 0) return 0;

    std::vector<char> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(prompt_utf8, 0, len, reinterpret_cast<jbyte *>(buf.data()));

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    std::vector<llama_token> tokens;
    if (!tokenize_utf8(env, vocab, buf.data(), static_cast<int32_t>(len), tokens)) return 0;
    return static_cast<jint>(tokens.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_phequals7_muesli_summaries_LocalQwenRuntime_nativeGenerate(
    JNIEnv * env,
    jclass /*clazz*/,
    jlong handle,
    jbyteArray prompt_utf8,
    jint max_tokens,
    jobject should_cancel_cb,
    jobject on_tokens_cb
) {
    auto * session = reinterpret_cast<QwenNativeSession *>(handle);
    if (session == nullptr || session->model == nullptr || session->ctx == nullptr || session->sampler == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Qwen session is closed");
        return nullptr;
    }
    if (prompt_utf8 == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "promptUtf8 is null");
        return nullptr;
    }
    const int32_t max_new = std::max<int32_t>(max_tokens, 1);

    jmethodID invoke_mid = nullptr;
    if (should_cancel_cb != nullptr) {
        jclass fn_cls = env->GetObjectClass(should_cancel_cb);
        invoke_mid = env->GetMethodID(fn_cls, "invoke", "()Ljava/lang/Object;");
        env->DeleteLocalRef(fn_cls);
    }
    const TokenCallback token_cb = bind_token_callback(env, on_tokens_cb);
    if (env->ExceptionCheck()) return nullptr;

    // Observe cancellation before the (potentially slow) prompt tokenization.
    if (should_cancel(env, should_cancel_cb, invoke_mid)) {
        if (!env->ExceptionCheck()) {
            throw_java(env, "java/util/concurrent/CancellationException", "Generation cancelled");
        }
        return nullptr;
    }

    const jsize len = env->GetArrayLength(prompt_utf8);
    std::vector<char> prompt_bytes(static_cast<size_t>(std::max<jsize>(len, 1)));
    if (len > 0) {
        env->GetByteArrayRegion(prompt_utf8, 0, len, reinterpret_cast<jbyte *>(prompt_bytes.data()));
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    std::vector<llama_token> prompt_tokens;
    if (!tokenize_utf8(env, vocab, prompt_bytes.data(), static_cast<int32_t>(len), prompt_tokens)) {
        return nullptr;
    }
    if (prompt_tokens.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt tokenized to 0 tokens");
        return nullptr;
    }
    if (static_cast<int32_t>(prompt_tokens.size()) + max_new > session->n_ctx) {
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt + maxTokens exceeds context window");
        return nullptr;
    }

    // Reset KV cache and sampler state before each completion.
    llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);
    llama_sampler_reset(session->sampler);

    // Monotonic 120s no-progress watchdog: every completed prompt batch and
    // every sampled token advance `last_progress`; checkpoints re-check it.
    constexpr std::chrono::seconds kStallTimeout{120};
    auto last_progress = SteadyClock::now();
    auto last_emit = SteadyClock::now() - std::chrono::seconds(1);
    auto stall_check = [&](bool throw_on_stall = true) {
        if (SteadyClock::now() - last_progress > kStallTimeout) {
            if (throw_on_stall && !env->ExceptionCheck()) {
                throw_java(
                    env, "java/io/IOException",
                    "Summary generation stalled. Try again or choose another model/provider.");
            }
            return false;
        }
        return true;
    };

    // Decode the prompt in 512-token batches, checking cancellation between batches.
    const int32_t total_prompt = static_cast<int32_t>(prompt_tokens.size());
    for (int32_t offset = 0; offset < total_prompt; offset += kPromptBatchTokens) {
        if (should_cancel(env, should_cancel_cb, invoke_mid) || !stall_check()) {
            if (!env->ExceptionCheck()) {
                throw_java(env, "java/util/concurrent/CancellationException", "Generation cancelled");
            }
            return nullptr;
        }
        const int32_t count = std::min<int32_t>(kPromptBatchTokens, total_prompt - offset);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, count);
        const int32_t rc = llama_decode(session->ctx, batch);
        if (rc != 0) {
            throw_java(env, "java/io/IOException", "llama_decode failed on prompt batch");
            return nullptr;
        }
        if (!stall_check()) return nullptr;
        if (!emit_tokens(env, token_cb, 0)) return nullptr;
        last_progress = SteadyClock::now();
    }

    std::string output_bytes;
    output_bytes.reserve(2048);
    char piece_buf[256];
    int32_t generated = 0;

    for (int32_t step = 0; step < max_new; ++step) {
        if (should_cancel(env, should_cancel_cb, invoke_mid) || !stall_check()) {
            if (!env->ExceptionCheck()) {
                throw_java(env, "java/util/concurrent/CancellationException", "Generation cancelled");
            }
            return nullptr;
        }

        llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        int32_t piece_len = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, /*special=*/false);
        if (piece_len < 0) {
            std::vector<char> large(static_cast<size_t>(-piece_len));
            piece_len = llama_token_to_piece(vocab, token, large.data(), static_cast<int32_t>(large.size()), 0, /*special=*/false);
            if (piece_len > 0) output_bytes.append(large.data(), static_cast<size_t>(piece_len));
        } else if (piece_len > 0) {
            output_bytes.append(piece_buf, static_cast<size_t>(piece_len));
        }

        ++generated;
        last_progress = SteadyClock::now();
        const auto now = SteadyClock::now();
        if (token_cb.object != nullptr && now - last_emit >= std::chrono::seconds(1)) {
            last_emit = now;
            if (!emit_tokens(env, token_cb, generated)) return nullptr;
        }

        if (step + 1 < max_new) {
            llama_batch next_batch = llama_batch_get_one(&token, 1);
            if (llama_decode(session->ctx, next_batch) != 0) {
                throw_java(env, "java/io/IOException", "llama_decode failed during token generation");
                return nullptr;
            }
        }
    }

    // Final cumulative count always reaches the caller exactly once at completion.
    if (token_cb.object != nullptr && !emit_tokens(env, token_cb, generated)) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(output_bytes.size()));
    if (result == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate output byte array");
        return nullptr;
    }
    if (!output_bytes.empty()) {
        env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(output_bytes.size()),
            reinterpret_cast<const jbyte *>(output_bytes.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_phequals7_muesli_summaries_LocalQwenRuntime_nativeClose(
    JNIEnv * /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto * session = reinterpret_cast<QwenNativeSession *>(handle);
    delete session;
}

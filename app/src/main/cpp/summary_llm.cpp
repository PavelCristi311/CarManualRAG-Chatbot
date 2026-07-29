#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "AtlasSummary";
constexpr int CONTEXT_TOKENS = 2048;
constexpr int MAX_OUTPUT_TOKENS = 192;

std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
int g_threads = 1;
int g_system_tokens = 0;

using Clock = std::chrono::steady_clock;

struct ModelTimings {
    int64_t prompt_formatting = 0;
    int64_t tokenization = 0;
    int64_t prefill = 0;
    int64_t generation = 0;
    int64_t streaming = 0;
    int64_t finalization = 0;
};

// Converts a monotonic clock interval to Java's nanosecond timing unit.
int64_t elapsed_nanos(Clock::time_point started) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            Clock::now() - started).count();
}

// Sends one complete native timing sample to the Java accumulator.
void report_timings(
        JNIEnv * env,
        jobject callback,
        jmethodID on_timing,
        const ModelTimings & timings) {
    env->CallVoidMethod(
            callback,
            on_timing,
            static_cast<jlong>(timings.prompt_formatting),
            static_cast<jlong>(timings.tokenization),
            static_cast<jlong>(timings.prefill),
            static_cast<jlong>(timings.generation),
            static_cast<jlong>(timings.streaming),
            static_cast<jlong>(timings.finalization));
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// Copies a nullable Java UTF-8 string into memory owned by native code.
std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string output(chars);
    env->ReleaseStringUTFChars(value, chars);
    return output;
}

// Tokenizes with the model vocabulary and returns an empty vector on failure.
std::vector<llama_token> tokenize(
        const llama_vocab * vocab,
        const std::string & text,
        bool add_bos) {
    int count = -llama_tokenize(
            vocab, text.c_str(), text.size(), nullptr, 0, add_bos, true);
    if (count <= 0) return {};
    std::vector<llama_token> tokens(count);
    int actual = llama_tokenize(
            vocab,
            text.c_str(),
            text.size(),
            tokens.data(),
            tokens.size(),
            add_bos,
            true);
    if (actual < 0) return {};
    tokens.resize(actual);
    return tokens;
}

// Prevents a truncated model continuation from reaching the user.
bool is_complete_sentence(const std::string & text) {
    size_t end = text.find_last_not_of(" \n\r\t");
    if (end == std::string::npos) return false;
    char last = text[end];
    return last == '.' || last == '!' || last == '?';
}

// Counts terminal punctuation for the early-stop completeness heuristic.
int sentence_count(const std::string & text) {
    return std::count_if(
            text.begin(),
            text.end(),
            [](char value) {
                return value == '.' || value == '!' || value == '?';
            });
}

// Wraps dynamic request data in Qwen's ChatML user turn.
std::string format_prompt(
        const std::string & question, const std::string & context) {
    return "<|im_start|>user\nQuestion: " + question
            + "\nOwner's-manual excerpts:\n" + context
            + "\nCover every required point above. "
            + "Answer using only these excerpts."
            + "<|im_end|>\n<|im_start|>assistant\n";
}
}

extern "C" JNIEXPORT jboolean JNICALL
// Loads Qwen once and evaluates the system prompt into a reusable KV prefix.
Java_com_atlas_manualassistant_CompanionSummarizer_nativeLoad(
        JNIEnv * env,
        jclass,
        jstring model_path,
        jint threads,
        jstring system_prompt_value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) return JNI_TRUE;
    llama_backend_init();
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    std::string path = from_jstring(env, model_path);
    g_model = llama_model_load_from_file(path.c_str(), params);
    g_threads = std::max(1, static_cast<int>(threads));
    if (g_model == nullptr) return JNI_FALSE;

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = CONTEXT_TOKENS;
    context_params.n_batch = CONTEXT_TOKENS;
    context_params.n_threads = g_threads;
    context_params.n_threads_batch = g_threads;
    context_params.no_perf = true;
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    std::string system = "<|im_start|>system\n"
            + from_jstring(env, system_prompt_value)
            + "<|im_end|>\n";
    std::vector<llama_token> system_tokens = tokenize(
            llama_model_get_vocab(g_model), system, true);
    if (system_tokens.empty()
            || llama_decode(
                    g_context,
                    llama_batch_get_one(
                            system_tokens.data(), system_tokens.size())) != 0) {
        llama_free(g_context);
        llama_model_free(g_model);
        g_context = nullptr;
        g_model = nullptr;
        return JNI_FALSE;
    }
    g_system_tokens = system_tokens.size();
    __android_log_print(
            ANDROID_LOG_DEBUG,
            TAG,
            "warm system cache tokens=%d threads=%d",
            g_system_tokens,
            g_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
// Reuses the warm prefix, streams greedy tokens, and returns only complete text.
Java_com_atlas_manualassistant_CompanionSummarizer_nativeSummarize(
        JNIEnv * env,
        jclass,
        jstring question_value,
        jstring context_value,
        jobject callback,
        jobject timing_callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr
            || g_context == nullptr
            || callback == nullptr
            || timing_callback == nullptr) {
        return env->NewStringUTF("");
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(
            callback_class, "onToken", "(Ljava/lang/String;)V");
    jclass timing_class = env->GetObjectClass(timing_callback);
    jmethodID on_timing = env->GetMethodID(
            timing_class, "onTiming", "(JJJJJJ)V");
    if (on_token == nullptr || on_timing == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(callback_class);
        env->DeleteLocalRef(timing_class);
        return env->NewStringUTF("");
    }

    ModelTimings timings;
    auto phase_started = Clock::now();
    llama_memory_seq_rm(
            llama_get_memory(g_context), 0, g_system_tokens, -1);
    std::string prompt = format_prompt(
            from_jstring(env, question_value),
            from_jstring(env, context_value));
    timings.prompt_formatting = elapsed_nanos(phase_started);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    phase_started = Clock::now();
    std::vector<llama_token> tokens = tokenize(vocab, prompt, false);
    timings.tokenization = elapsed_nanos(phase_started);
    if (tokens.empty()
            || g_system_tokens + tokens.size()
                    + MAX_OUTPUT_TOKENS >= CONTEXT_TOKENS) {
        report_timings(env, timing_callback, on_timing, timings);
        env->DeleteLocalRef(callback_class);
        env->DeleteLocalRef(timing_class);
        return env->NewStringUTF("");
    }

    auto sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    phase_started = Clock::now();
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_context, batch) != 0) {
        timings.prefill = elapsed_nanos(phase_started);
        llama_sampler_free(sampler);
        report_timings(env, timing_callback, on_timing, timings);
        env->DeleteLocalRef(callback_class);
        env->DeleteLocalRef(timing_class);
        return env->NewStringUTF("");
    }
    timings.prefill = elapsed_nanos(phase_started);

    std::string generated_output;
    int generated = 0;
    while (generated < MAX_OUTPUT_TOKENS) {
        phase_started = Clock::now();
        llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            timings.generation += elapsed_nanos(phase_started);
            break;
        }
        char piece[256];
        int size = llama_token_to_piece(
                vocab, token, piece, sizeof(piece), 0, true);
        if (size < 0) {
            timings.generation += elapsed_nanos(phase_started);
            generated_output.clear();
            break;
        }
        generated_output.append(piece, size);
        generated++;
        timings.generation += elapsed_nanos(phase_started);

        phase_started = Clock::now();
        jstring java_piece = env->NewStringUTF(
                std::string(piece, size).c_str());
        env->CallVoidMethod(callback, on_token, java_piece);
        env->DeleteLocalRef(java_piece);
        timings.streaming += elapsed_nanos(phase_started);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            generated_output.clear();
            break;
        }
        if (generated >= 40
                && sentence_count(generated_output) >= 2
                && is_complete_sentence(generated_output)) {
            break;
        }

        phase_started = Clock::now();
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_context, batch) != 0) {
            timings.generation += elapsed_nanos(phase_started);
            generated_output.clear();
            break;
        }
        timings.generation += elapsed_nanos(phase_started);
    }

    phase_started = Clock::now();
    __android_log_print(
            ANDROID_LOG_DEBUG,
            TAG,
            "summary prompt=%zu tokens=%d complete=%d",
            tokens.size(),
            generated,
            is_complete_sentence(generated_output));
    std::string output;
    if (is_complete_sentence(generated_output)) {
        output = generated_output;
    }
    llama_sampler_free(sampler);
    timings.finalization = elapsed_nanos(phase_started);
    report_timings(env, timing_callback, on_timing, timings);
    env->DeleteLocalRef(callback_class);
    env->DeleteLocalRef(timing_class);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
// Releases process-wide llama.cpp state under the same serialization lock.
Java_com_atlas_manualassistant_CompanionSummarizer_nativeClose(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

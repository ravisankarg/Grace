#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr const char * TAG = "GraceEmbedding";
std::once_flag backend_once;
std::mutex mutex;
llama_model * model = nullptr;
llama_context * context = nullptr;
int threads = 4;

void ensure_backend() { llama_backend_init(); }

void close_model() {
    if (context) { llama_free(context); context = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}

bool open_model(const char * path) {
    std::call_once(backend_once, ensure_backend);
    close_model();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    model = llama_model_load_from_file(path, params);
    threads = std::clamp<int>(sysconf(_SC_NPROCESSORS_ONLN), 2, 8);
    __android_log_print(ANDROID_LOG_INFO, TAG, "EmbeddingGemma context: %d CPU threads", threads);
    if (!model) __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not load model from %s", path);
    if (!model) return false;
    auto context_params = llama_context_default_params();
    // The offline index uses 512-word passages. A passage can tokenize beyond
    // 512 tokens, so retain the model's 2,048-token context for exact
    // laptop/phone compatibility. Queries are normally far shorter.
    context_params.n_ctx = 2048; context_params.n_batch = 2048; context_params.n_ubatch = 2048;
    context_params.n_threads = threads; context_params.n_threads_batch = threads;
    context_params.embeddings = true; context_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    context_params.offload_kqv = false;
    context = llama_init_from_model(model, context_params);
    if (!context) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not create embedding context");
        close_model();
    }
    return context != nullptr;
}

std::vector<float> embed(const char * text) {
    if (!model || !context || !text || !*text) return {};
    const auto * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(1024);
    int count = llama_tokenize(vocab, text, static_cast<int32_t>(strlen(text)), tokens.data(), tokens.size(), true, false);
    if (count < 0) { tokens.resize(-count); count = llama_tokenize(vocab, text, static_cast<int32_t>(strlen(text)), tokens.data(), tokens.size(), true, false); }
    if (count <= 0) return {};
    tokens.resize(std::min(count, 2048));

    // Keep the context resident for the whole indexing run. Recreating it for
    // every source passage dominates CPU time on a phone.
    llama_memory_clear(llama_get_memory(context), false);
    auto batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    const int result = llama_encode(context, batch);
    float * values = result == 0 ? llama_get_embeddings_seq(context, 0) : nullptr;
    const int dimension = llama_model_n_embd(model);
    std::vector<float> output;
    if (values && dimension > 0) output.assign(values, values + dimension);
    return output;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravi_grace_EmbeddingNative_open(JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(mutex);
    const char * raw = env->GetStringUTFChars(path, nullptr);
    const bool success = open_model(raw);
    env->ReleaseStringUTFChars(path, raw);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ravi_grace_EmbeddingNative_embed(JNIEnv * env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(mutex);
    const char * raw = env->GetStringUTFChars(text, nullptr);
    const auto values = embed(raw);
    env->ReleaseStringUTFChars(text, raw);
    if (values.empty()) return nullptr;
    auto result = env->NewFloatArray(static_cast<jsize>(values.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravi_grace_EmbeddingNative_close(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(mutex); close_model();
}

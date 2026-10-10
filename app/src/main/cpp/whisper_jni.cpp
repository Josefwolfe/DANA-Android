#include <jni.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"

namespace {
std::mutex whisper_mutex;
whisper_context *ctx = nullptr;
std::string current_model;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dana_ai_WhisperNative_nativeInitialize(JNIEnv *env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char *utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return JNI_FALSE;
    const std::string name(utf);
    env->ReleaseStringUTFChars(path, utf);
    std::lock_guard<std::mutex> guard(whisper_mutex);
    if (ctx && current_model == name) return JNI_TRUE;
    auto params = whisper_context_default_params();
    auto *loaded = whisper_init_from_file_with_params(name.c_str(), params);
    if (!loaded) return JNI_FALSE;
    if (ctx) whisper_free(ctx);
    ctx = loaded;
    current_model = name;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dana_ai_WhisperNative_nativeTranscribe(JNIEnv *env, jobject, jfloatArray samples) {
    if (!samples) return env->NewStringUTF("");
    const jsize size = env->GetArrayLength(samples);
    if (size <= 0 || size > 16000 * 15) return env->NewStringUTF("");
    std::vector<float> pcm(static_cast<size_t>(size));
    env->GetFloatArrayRegion(samples, 0, size, pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    std::lock_guard<std::mutex> guard(whisper_mutex);
    if (!ctx) return env->NewStringUTF("");
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "fa";
    params.no_context = true;
    params.single_segment = true;
    params.n_threads = std::max(1, std::min(4, static_cast<int>(std::thread::hardware_concurrency())));
    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0)
        return env->NewStringUTF("");
    std::string text;
    const int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; ++i) {
        const char *s = whisper_full_get_segment_text(ctx, i);
        if (s) text += s;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_dana_ai_WhisperNative_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(whisper_mutex);
    if (ctx) whisper_free(ctx);
    ctx = nullptr;
    current_model.clear();
}

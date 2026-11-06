#include <jni.h>

#include <string>
#include <thread>

#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_dev_aravind_assistant_WhisperCpp_nativeInit(JNIEnv *env, jobject, jstring model) {
    const char *path = env->GetStringUTFChars(model, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_aravind_assistant_WhisperCpp_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jstring prompt,
        jint threads) {
    auto *context = reinterpret_cast<whisper_context *>(handle);
    if (context == nullptr) return env->NewStringUTF("");

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *hint = env->GetStringUTFChars(prompt, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.language = lang;
    params.detect_language = false;
    params.n_threads = threads;
    params.initial_prompt = hint;
    params.greedy.best_of = 3;

    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    const jsize count = env->GetArrayLength(samples);
    const int status = whisper_full(context, params, audio, count);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseStringUTFChars(prompt, hint);
    if (status != 0) return env->NewStringUTF("");

    std::string text;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(context, i);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_aravind_assistant_WhisperCpp_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

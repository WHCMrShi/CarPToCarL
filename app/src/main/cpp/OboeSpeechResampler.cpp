#include <algorithm>
#include <cmath>
#include <cstdint>
#include <jni.h>
#include <memory>
#include <new>
#include <vector>

#include "oboe/MultiChannelResampler.h"

namespace {
using oboe::resampler::MultiChannelResampler;
constexpr int kOutputRate = 48000;

struct Resampler {
    Resampler(int rate, int count)
            : channels(count), input(rate / 5 * count),
              filter(MultiChannelResampler::make(1, rate, kOutputRate,
                      MultiChannelResampler::Quality::Best)) {}

    const int channels;
    std::vector<int16_t> input;
    std::unique_ptr<MultiChannelResampler> filter;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_shaonialife_carptocar_audio_OboeResamplerNative_create(
        JNIEnv *, jobject, jint inputRate, jint channels) {
    if (inputRate < 8000 || inputRate > 192000 || (channels != 1 && channels != 2)) return 0;
    try {
        return reinterpret_cast<jlong>(new Resampler(inputRate, channels));
    } catch (const std::bad_alloc &) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_shaonialife_carptocar_audio_OboeResamplerNative_process(
        JNIEnv *env, jobject, jlong handle, jbyteArray pcm, jobject outputBuffer) {
    auto *resampler = reinterpret_cast<Resampler *>(handle);
    auto *output = static_cast<int16_t *>(env->GetDirectBufferAddress(outputBuffer));
    const jint bytes = env->GetArrayLength(pcm);
    const jlong capacity = env->GetDirectBufferCapacity(outputBuffer) / sizeof(int16_t);
    const auto samples = static_cast<size_t>(bytes) / sizeof(int16_t);
    if (!resampler || !output || bytes % sizeof(int16_t) != 0 ||
            samples % resampler->channels != 0 || samples > resampler->input.size()) {
        return -1;
    }

    // Android's supported ABIs store PCM16LE samples in native byte order.
    const auto &input = resampler->input;
    env->GetByteArrayRegion(pcm, 0, bytes, reinterpret_cast<jbyte *>(resampler->input.data()));
    if (env->ExceptionCheck()) return -1;

    jint written = 0;
    for (size_t i = 0; i < samples; i += resampler->channels) {
        const float sample = resampler->channels == 1 ? input[i] : (input[i] + input[i + 1]) * 0.5f;
        resampler->filter->writeNextFrame(&sample);
        while (!resampler->filter->isWriteNeeded()) {
            if (written >= capacity) return -1;
            float value;
            resampler->filter->readNextFrame(&value);
            output[written++] = static_cast<int16_t>(std::clamp(std::lround(value), -32768L, 32767L));
        }
    }
    return written;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_shaonialife_carptocar_audio_OboeResamplerNative_destroy(
        JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Resampler *>(handle);
}

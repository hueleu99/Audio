#include <jni.h>
#include <string>
#include "AudioBufferPool.h"
#include <memory>
#include "Audio2Storage.h"#include "Audio2Storage.h"

static Audio2Storage* audio2Storage = nullptr;
std::unique_ptr<AudioBufferPool> gBufferPool;

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_audiodetector_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_MainActivity_nativeInit(JNIEnv *env, jobject thiz, jint sample_rate,
                                                       jint channels) {
    gBufferPool = std::make_unique<AudioBufferPool>(sample_rate, channels);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_MainActivity_nativePush(JNIEnv *env, jobject thiz, jshortArray pcm,
                                                       jint length, jlong pts_us) {
    jshort* pcmData = env->GetShortArrayElements(pcm, nullptr);
    gBufferPool->push(pcmData, length, pts_us);
    env->ReleaseShortArrayElements(pcm, pcmData, 0);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_MainActivity_nativeFlush(JNIEnv *env, jobject thiz) {
    if(gBufferPool)
        gBufferPool->flush();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_AudioDetector_nativeInit(JNIEnv *env, jobject thiz, jint sample_rate,
                                                        jint channels) {
    if (!audio2Storage)
        audio2Storage = new Audio2Storage();
    gBufferPool = std::make_unique<AudioBufferPool>(sample_rate, channels);
    gBufferPool->setAudio2Storage(audio2Storage);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_AudioDetector_nativePush(JNIEnv *env, jobject thiz, jshortArray pcm,
                                                        jint length, jlong pts_us) {
    jshort* pcmData = env->GetShortArrayElements(pcm, nullptr);
    gBufferPool->push(pcmData, length, pts_us);
    env->ReleaseShortArrayElements(pcm, pcmData, 0);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_AudioDetector_nativeFlush(JNIEnv *env, jobject thiz) {
    if(gBufferPool)
        gBufferPool->flush();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_example_audiodetector_AudioDetector_nativePushBG(JNIEnv *env, jobject thiz,
                                                          jshortArray pcm, jint length, jlong pts_us) {
    jshort* pcmData = env->GetShortArrayElements(pcm, nullptr);
    audio2Storage->append(pcmData,length, pts_us);
    env->ReleaseShortArrayElements(pcm, pcmData, 0);
}
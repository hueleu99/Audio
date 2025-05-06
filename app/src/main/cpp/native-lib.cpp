#include <jni.h>
#include <string>
#include "AudioBufferPool.h"
#include <memory>

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
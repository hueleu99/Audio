package com.example.audiodetector;

import android.media.MediaCodec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioDetector {
    // Used to load the 'audiodetector' library on application startup.
    static {
        System.loadLibrary("audiodetector");
    }

    public native void nativeInit(int sampleRate, int channels);
    public native void nativePush(short[] pcm, int length, long ptsUs);
    public native void nativeFlush();
    public native void nativePushBG(short[] pcm,int length, long ptsUs);

    public void onPcmDataFromDecoder(ByteBuffer buffer, MediaCodec.BufferInfo info, boolean isFG) {
        short[] pcm = new short[info.size / 2];
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        if(isFG){
            nativePush(pcm, pcm.length, info.presentationTimeUs);
        } else {
            nativePushBG(pcm,  pcm.length, info.presentationTimeUs);
        }

    }



    public void init(int sampleRate, int channels) {
        nativeInit(sampleRate, channels);
    }

    public void flush(){
        nativeFlush();
    }
}

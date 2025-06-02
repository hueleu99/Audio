package com.example.audiodetector;

import android.media.MediaCodec;
import android.media.MediaFormat;

import androidx.annotation.NonNull;

public class AudioCallback extends MediaCodec.Callback {
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

    }

    @Override
    public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

    }
}

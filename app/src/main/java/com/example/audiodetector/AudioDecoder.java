package com.example.audiodetector;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

public class AudioDecoder extends MediaCodec.Callback {
    private static final String TAG = "AudioDecoder";
    private MediaExtractor extractor;
    private boolean isOutputEOS;
    private boolean isFG;
    AudioDetector audioDetector;

    AudioDecoder(MediaExtractor extractor, boolean isFG, AudioDetector audioDetector) {
        this.extractor = extractor;
        this.isFG = isFG;
        this.audioDetector = audioDetector;
    }

    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
        ByteBuffer inputBuffer = codec.getInputBuffer(index);
        assert inputBuffer != null;
        int sampleSize = extractor.readSampleData(inputBuffer, 0);
        if (sampleSize >= 0) {
            long presentationTimeUs = extractor.getSampleTime();
            codec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, 0);
            extractor.advance();
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            //isOutputEOS = true;
            Log.d(TAG, "Output EOS received");
            if (isFG) {
                audioDetector.flush();
            }
            codec.stop();
            codec.release();
            extractor.release();
            return;
        }

        ByteBuffer buffer = codec.getOutputBuffer(index);
        if (buffer != null) {
            if (isFG) {
                audioDetector.onPcmDataFromDecoder(buffer, info, true);
            } else {
                audioDetector.onPcmDataFromDecoder(buffer, info, false);
            }
            // Log.i(TAG, "onOutputBufferAvailable: ");
        }

        // TODO: Xử lý dữ liệu giải mã tại đây (phát audio, render video, ...)
        codec.releaseOutputBuffer(index, false);
    }

    @Override
    public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
        Log.e("Decode", "Codec error", e);
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
        Log.d("Decode", "Output format changed: " + format);
    }
}


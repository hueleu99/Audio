//package com.example.audiodetector;
//
//import android.media.MediaCodec;
//import android.media.MediaExtractor;
//import android.media.MediaFormat;
//import android.util.Log;
//
//import androidx.annotation.NonNull;
//
//import java.nio.ByteBuffer;
//
//public class VideoCallback extends MediaCodec.Callback {
//    private boolean isEOS = false;
//    MediaExtractor extractor;
//    MediaCodec encoder;
//    VideoCallback(MediaExtractor extractor, MediaCodec encoder){
//        this.extractor = extractor;
//        this.encoder = encoder;
//    }
//    @Override
//    public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
//        if (isEOS) return;
//        ByteBuffer inputBuffer = codec.getInputBuffer(index);
//        if (inputBuffer != null) {
//            int sampleSize = extractor.readSampleData(inputBuffer, 0);
//            if (sampleSize < 0) {
//                codec.queueInputBuffer(index, 0, 0, 0,
//                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//                isEOS = true;
//            } else {
//                long pts = extractor.getSampleTime();
//                codec.queueInputBuffer(index, 0, sampleSize, pts, 0);
//                extractor.advance();
//            }
//        }
//    }
//
//    @Override
//    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
//                                        @NonNull MediaCodec.BufferInfo info) {
//        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
//        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
//            codec.releaseOutputBuffer(index, false);
//            return;
//        }
//
//        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//            drainEncoder(true); // gửi tín hiệu kết thúc cho encoder
//            stopAll();
//            return;
//        }
//
//        // ⚠️ Đây là nơi bạn có thể xử lý YUV frame từ outputBuffer nếu cần
//
//        // Gửi frame này sang encoder
//        feedEncoder(outputBuffer, info);
//
//        codec.releaseOutputBuffer(index, false);
//    }
//
//    @Override
//    public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
//        Log.e("Transcode", "Decode error: " + e.getMessage());
//    }
//
//    @Override
//    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
//        // Ignore
//    }
//
//    private void feedEncoder(ByteBuffer decodedBuffer, MediaCodec.BufferInfo info) {
//        int inputBufferIndex = encoder.dequeueInputBuffer(10000);
//        if (inputBufferIndex >= 0) {
//            ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
//            if (inputBuffer != null) {
//                inputBuffer.clear();
//                inputBuffer.put(decodedBuffer);
//                encoder.queueInputBuffer(inputBufferIndex, 0, info.size,
//                        info.presentationTimeUs, info.flags);
//            }
//        }
//
//        drainEncoder(false);
//    }
//
//    private void drainEncoder(boolean endOfStream) {
//        if (endOfStream) {
//            int inputBufferIndex = encoder.dequeueInputBuffer(10000);
//            if (inputBufferIndex >= 0) {
//                encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
//                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//            }
//        }
//
//        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
//        while (true) {
//            int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
//            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                break;
//            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
//                if (muxerStarted)
//                    throw new RuntimeException("Format changed twice");
//                MediaFormat newFormat = encoder.getOutputFormat();
//                outputTrackIndex = muxer.addTrack(newFormat);
//                muxer.start();
//                muxerStarted = true;
//            } else if (outputBufferIndex >= 0) {
//                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);
//                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
//                    bufferInfo.size = 0;
//                }
//
//                if (bufferInfo.size != 0 && encodedData != null && muxerStarted) {
//                    encodedData.position(bufferInfo.offset);
//                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
//                    muxer.writeSampleData(outputTrackIndex, encodedData, bufferInfo);
//                }
//
//                encoder.releaseOutputBuffer(outputBufferIndex, false);
//                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
//                    break;
//            }
//        }
//    }
//
//    private void stopAll() {
//        try {
//            decoder.stop();
//            decoder.release();
//            encoder.stop();
//            encoder.release();
//            if (muxerStarted)
//                muxer.stop();
//            muxer.release();
//            extractor.release();
//            Log.d("Transcode", "Hoàn tất encode lại video.");
//        } catch (Exception e) {
//            Log.e("Transcode", "Lỗi khi đóng codec/muxer: " + e.getMessage());
//        }
//    }
//}

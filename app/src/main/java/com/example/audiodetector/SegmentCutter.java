package com.example.audiodetector;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SegmentCutter {

    private static class EncodedFrame {
        public ByteBuffer buffer;
        public MediaCodec.BufferInfo info;

        public EncodedFrame(ByteBuffer buffer, MediaCodec.BufferInfo info) {
            this.buffer = buffer;
            this.info = info;
        }
    }

    private final Queue<EncodedFrame> audioDecodedQueue = new ConcurrentLinkedQueue<>();
    private final Queue<EncodedFrame> videoDecodedQueue = new ConcurrentLinkedQueue<>();
    private final MediaCodec audioDecoder;
    private final MediaCodec audioEncoder;
    private final MediaCodec videoDecoder;
    private final MediaCodec videoEncoder;
    private final MediaMuxer muxer;
    private int audioTrackIndex = -1;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;

    public SegmentCutter(MediaCodec audioDecoder, MediaCodec audioEncoder,
                         MediaCodec videoDecoder, MediaCodec videoEncoder,
                         MediaMuxer muxer) {
        this.audioDecoder = audioDecoder;
        this.audioEncoder = audioEncoder;
        this.videoDecoder = videoDecoder;
        this.videoEncoder = videoEncoder;
        this.muxer = muxer;
        Log.i("hueleu", "SegmentCutter: ");

        setupAudioCallbacks();
        setupVideoCallbacks();
    }

    public void start() {
        audioDecoder.start();
        audioEncoder.start();
        videoDecoder.start();
        videoEncoder.start();
    }
    public void stop() {
        audioDecoder.stop();
        audioEncoder.stop();
        videoDecoder.stop();
        videoEncoder.stop();
    }

    private void setupAudioCallbacks() {
        audioDecoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                Log.i("hueleu", "onInputBufferAvailable: " + index);
                // Fill input buffer from audio MediaExtractor elsewhere.
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (info.size > 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null) {
                        ByteBuffer copiedBuffer = ByteBuffer.allocate(info.size);
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        copiedBuffer.put(outputBuffer);
                        copiedBuffer.flip();

                        MediaCodec.BufferInfo copiedInfo = new MediaCodec.BufferInfo();
                        copiedInfo.set(0, info.size, info.presentationTimeUs, info.flags);
                        audioDecodedQueue.add(new EncodedFrame(copiedBuffer, copiedInfo));
                    }
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (!muxerStarted) {
                    audioTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }
        });

        audioEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                EncodedFrame frame = audioDecodedQueue.poll();
                if (frame == null) return;

                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frame.buffer);
                    codec.queueInputBuffer(index, 0, frame.info.size, frame.info.presentationTimeUs, frame.info.flags);
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (muxerStarted && info.size > 0) {
                    ByteBuffer encodedBuffer = codec.getOutputBuffer(index);
                    if (encodedBuffer != null) {
                        encodedBuffer.position(info.offset);
                        encodedBuffer.limit(info.offset + info.size);
                        muxer.writeSampleData(audioTrackIndex, encodedBuffer, info);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (!muxerStarted) {
                    audioTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }
        });
    }

    private void setupVideoCallbacks() {
        videoDecoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                // Fill input buffer from video MediaExtractor elsewhere.
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (info.size > 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null) {
                        ByteBuffer copiedBuffer = ByteBuffer.allocate(info.size);
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        copiedBuffer.put(outputBuffer);
                        copiedBuffer.flip();

                        MediaCodec.BufferInfo copiedInfo = new MediaCodec.BufferInfo();
                        copiedInfo.set(0, info.size, info.presentationTimeUs, info.flags);
                        videoDecodedQueue.add(new EncodedFrame(copiedBuffer, copiedInfo));
                    }
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (!muxerStarted) {
                    videoTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }
        });

        videoEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                EncodedFrame frame = videoDecodedQueue.poll();
                if (frame == null) return;

                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frame.buffer);
                    codec.queueInputBuffer(index, 0, frame.info.size, frame.info.presentationTimeUs, frame.info.flags);
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (muxerStarted && info.size > 0) {
                    ByteBuffer encodedBuffer = codec.getOutputBuffer(index);
                    if (encodedBuffer != null) {
                        encodedBuffer.position(info.offset);
                        encodedBuffer.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrackIndex, encodedBuffer, info);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (!muxerStarted) {
                    videoTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }
        });
    }

    private synchronized void startMuxerIfReady() {
        if (audioTrackIndex != -1 && videoTrackIndex != -1 && !muxerStarted) {
            muxer.start();
            muxerStarted = true;
        }
    }
}


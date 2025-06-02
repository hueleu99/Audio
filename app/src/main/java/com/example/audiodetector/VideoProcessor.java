package com.example.audiodetector;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class VideoProcessor {
    private static final String TAG = "VideoProcessor";
    private static final long TIMEOUT_US = 10000;

    public interface ProgressCallback {
        void onProgress(String message);
    }

    public void process(FileDescriptor inputFile, File outputFile, ProgressCallback callback) throws IOException {
        if (callback != null) callback.onProgress("Initializing...");

        // Setup extractor
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputFile);

        // Find tracks
        int audioTrackIndex = -1;
        int videoTrackIndex = -1;
        MediaFormat audioFormat = null;
        MediaFormat videoFormat = null;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i;
                audioFormat = format;
            } else if (mime.startsWith("video/")) {
                videoTrackIndex = i;
                videoFormat = format;
            }
        }

        if (audioTrackIndex == -1 || videoTrackIndex == -1) {
            throw new IOException("Could not find audio or video track");
        }

        // Create queues for decoded frames
        BlockingQueue<FrameData> decodedFrames = new LinkedBlockingQueue<>();

        // Start decoders
        if (callback != null) callback.onProgress("Starting decoders...");
        AudioDecoder audioDecoder = new AudioDecoder(extractor, audioTrackIndex, decodedFrames);
        VideoDecoder videoDecoder = new VideoDecoder(extractor, videoTrackIndex, decodedFrames);

        audioDecoder.start();
        videoDecoder.start();

        // Wait for decoders to finish
        try {
            audioDecoder.join();
            videoDecoder.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "Decoding interrupted", e);
        }

        extractor.release();

        // Collect all frames
        if (callback != null) callback.onProgress("Collecting frames...");
        List<FrameData> allFrames = new ArrayList<>();
        decodedFrames.drainTo(allFrames);

        Collections.sort(allFrames, Comparator.comparingLong(frame -> frame.pts));

        // Define segments to remove (in microseconds)
        List<Segment> segmentsToRemove = new ArrayList<>();
        segmentsToRemove.add(new Segment(1_000_000, 2_000_000)); // Remove 1-2 seconds
        segmentsToRemove.add(new Segment(3_000_000, 4_000_000)); // Remove 3-4 seconds

        // Remove audio segments and corresponding video frames
        if (callback != null) callback.onProgress("Removing segments...");
        List<FrameData> processedFrames = removeSegments(allFrames, segmentsToRemove);

        // Encode processed frames
        if (callback != null) callback.onProgress("Encoding output...");
        Encoder encoder = new Encoder(outputFile, audioFormat, videoFormat);
        encoder.encode(processedFrames, callback);

        if (callback != null) callback.onProgress("Processing complete!");
    }

    private static class Segment {
        long start;
        long end;

        Segment(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private List<FrameData> removeSegments(List<FrameData> frames, List<Segment> segments) {
        List<FrameData> result = new ArrayList<>();
        List<Long> audioRemovedPts = new ArrayList<>();

        // First pass: Remove audio segments and record removed PTS
        for (FrameData frame : frames) {
            if (frame.isAudio) {
                boolean shouldRemove = false;
                for (Segment segment : segments) {
                    if (frame.pts >= segment.start && frame.pts < segment.end) {
                        shouldRemove = true;
                        audioRemovedPts.add(frame.pts);
                        break;
                    }
                }
                if (!shouldRemove) {
                    result.add(frame);
                }
            } else {
                result.add(frame);
            }
        }

        // Second pass: Remove video frames corresponding to removed audio PTS
        List<FrameData> finalResult = new ArrayList<>();
        for (FrameData frame : result) {
            if (!frame.isAudio) {
                boolean shouldRemove = false;
                for (Long pts : audioRemovedPts) {
                    if (Math.abs(frame.pts - pts) < 50_000) { // 50ms tolerance
                        shouldRemove = true;
                        break;
                    }
                }
                if (!shouldRemove) {
                    finalResult.add(frame);
                }
            } else {
                finalResult.add(frame);
            }
        }

        return finalResult;
    }

    // Base decoder class using MediaCodec callback
    private abstract static class AsyncDecoder extends Thread {
        protected final MediaExtractor extractor;
        protected final int trackIndex;
        protected final BlockingQueue<FrameData> outputQueue;
        protected MediaCodec codec;
        protected boolean mIsAudio;

        AsyncDecoder(MediaExtractor extractor, int trackIndex, BlockingQueue<FrameData> outputQueue, boolean isAudio) {
            this.extractor = extractor;
            this.trackIndex = trackIndex;
            this.outputQueue = outputQueue;
            this.mIsAudio = isAudio;
        }

        @Override
        public void run() {
            try {
                extractor.selectTrack(trackIndex);
                MediaFormat format = extractor.getTrackFormat(trackIndex);
                String mime = format.getString(MediaFormat.KEY_MIME);

                codec = MediaCodec.createDecoderByType(mime);
                codec.setCallback(new MediaCodec.Callback() {
                    @Override
                    public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                        try {
                            ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);

                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufferId, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inputBufferId, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Input buffer error", e);
                        }
                    }

                    @Override
                    public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId,
                                                        MediaCodec.BufferInfo bufferInfo) {
                        try {
                            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                codec.stop();
                                return;
                            }

                            ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                ByteBuffer frameData = ByteBuffer.allocateDirect(bufferInfo.size);
                                outputBuffer.position(bufferInfo.offset);
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                frameData.put(outputBuffer);
                                frameData.flip();

                                outputQueue.offer(new FrameData(
                                        frameData,
                                        bufferInfo.presentationTimeUs,
                                        mIsAudio
                                ), 100, TimeUnit.MILLISECONDS);
                            }
                            codec.releaseOutputBuffer(outputBufferId, false);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Output queue interrupted", e);
                        } catch (Exception e) {
                            Log.e(TAG, "Output buffer error", e);
                        }
                    }

                    @Override
                    public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                        Log.e(TAG, "Codec error", e);
                    }

                    @Override
                    public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                        // Handle format change if needed
                    }
                });

                codec.configure(format, null, null, 0);
                codec.start();

                // Wait for EOS
                while (!Thread.interrupted()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Decoder error", e);
            } finally {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            }
        }
    }

    // Audio Decoder with callback
    private static class AudioDecoder extends AsyncDecoder {
        AudioDecoder(MediaExtractor extractor, int trackIndex, BlockingQueue<FrameData> outputQueue) {
            super(extractor, trackIndex, outputQueue, true);
        }

        // Inner class to identify audio frames
        //private static class InnerCallback extends MediaCodec.Callback {}
    }

    // Video Decoder with callback
    private static class VideoDecoder extends AsyncDecoder {
        VideoDecoder(MediaExtractor extractor, int trackIndex, BlockingQueue<FrameData> outputQueue) {
            super(extractor, trackIndex, outputQueue, false);
        }
    }

    // Encoder using MediaCodec callback
    private static class Encoder {
        private final MediaMuxer muxer;
        private final MediaFormat audioFormat;
        private final MediaFormat videoFormat;
        private MediaCodec audioEncoder;
        private MediaCodec videoEncoder;
        private int audioTrackIndex = -1;
        private int videoTrackIndex = -1;
        private boolean muxerStarted = false;
        private final BlockingQueue<FrameData> encodeQueue = new LinkedBlockingQueue<>();
        private volatile boolean encodingComplete = false;

        Encoder(File outputFile, MediaFormat audioFormat, MediaFormat videoFormat) throws IOException {
            this.muxer = new MediaMuxer(outputFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            this.audioFormat = audioFormat;
            this.videoFormat = videoFormat;
        }

        void encode(List<FrameData> frames, ProgressCallback callback) {
            // Add all frames to queue
            encodeQueue.addAll(frames);

            try {
                // Create encoders
                audioEncoder = createEncoder(audioFormat, true);
                videoEncoder = createEncoder(videoFormat, false);

                // Start encoders
                audioEncoder.start();
                videoEncoder.start();

                // Process frames until complete
                while (!encodingComplete) {
                    FrameData frame = encodeQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;

                    MediaCodec encoder = frame.isAudio ? audioEncoder : videoEncoder;
                    int inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                        inputBuffer.put(frame.data);
                        encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                frame.data.remaining(),
                                frame.pts,
                                0
                        );
                    }

                    // Check for end condition
                    if (encodeQueue.isEmpty() && !encodingComplete) {
                        encodingComplete = true;
                        signalEndOfStream(audioEncoder);
                        signalEndOfStream(videoEncoder);
                    }

                    if (callback != null) {
                        int progress = (int) ((1.0 - (double) encodeQueue.size() / frames.size()) * 100);
                        callback.onProgress("Encoding: " + progress + "%");
                    }
                }

                // Release resources
                audioEncoder.stop();
                videoEncoder.stop();
                audioEncoder.release();
                videoEncoder.release();
                muxer.stop();
                muxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Encoding error", e);
            }
        }

        private MediaCodec createEncoder(MediaFormat format, boolean isAudio) throws IOException {
            String mime = format.getString(MediaFormat.KEY_MIME);
            MediaCodec encoder = MediaCodec.createEncoderByType(mime);

            encoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int inputBufferId) {
                    // Handled in main encode loop
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int outputBufferId,
                                                    MediaCodec.BufferInfo bufferInfo) {
                    try {
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return;
                        }

                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            mc.releaseOutputBuffer(outputBufferId, false);
                            return;
                        }

                        ByteBuffer outputBuffer = mc.getOutputBuffer(outputBufferId);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // Add track if needed
                            if (!muxerStarted) {
                                synchronized (muxer) {
                                    if (audioTrackIndex == -1 || videoTrackIndex == -1) {
                                        mc.releaseOutputBuffer(outputBufferId, false);
                                        return;
                                    }
                                    muxer.start();
                                    muxerStarted = true;
                                }
                            }

                            // Write to muxer
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            muxer.writeSampleData(
                                    isAudio ? audioTrackIndex : videoTrackIndex,
                                    outputBuffer,
                                    bufferInfo
                            );
                        }
                        mc.releaseOutputBuffer(outputBufferId, false);
                    } catch (Exception e) {
                        Log.e(TAG, "Muxer error", e);
                    }
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    if (isAudio) {
                        audioTrackIndex = muxer.addTrack(format);
                    } else {
                        videoTrackIndex = muxer.addTrack(format);
                    }
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    Log.e(TAG, "Encoder error", e);
                }
            });

            // Configure encoder
            if (isAudio) {
                // Preserve audio settings
                format.setInteger(MediaFormat.KEY_BIT_RATE,
                        audioFormat.getInteger(MediaFormat.KEY_BIT_RATE));
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT,
                        audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE,
                        audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        audioFormat.getInteger(MediaFormat.KEY_AAC_PROFILE));
            } else {
                // Preserve video settings
                format.setInteger(MediaFormat.KEY_BIT_RATE,
                        videoFormat.getInteger(MediaFormat.KEY_BIT_RATE));
                format.setInteger(MediaFormat.KEY_FRAME_RATE,
                        videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
                        videoFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL));
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        videoFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT));
            }

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return encoder;
        }

        private void signalEndOfStream(MediaCodec encoder) {
            int inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                );
            }
        }
    }
}
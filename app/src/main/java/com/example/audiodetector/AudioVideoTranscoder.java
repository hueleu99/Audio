package com.example.audiodetector;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioVideoTranscoder {
    //region Các thành phần MediaCodec
    private MediaExtractor videoExtractor;
    private MediaExtractor audioExtractor;
    private MediaCodec videoDecoder;
    private MediaCodec videoEncoder;
    private MediaCodec audioDecoder;
    private MediaCodec audioEncoder;
    private MediaMuxer muxer;
    //endregion

    //region Surface
    private Surface inputSurface; // Surface cho video encoder
    private SurfaceView outputSurfaceView; // Surface hiển thị video
    //endregion

    //region Trạng thái
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean audioEOS = new AtomicBoolean(false);
    private final AtomicBoolean videoEOS = new AtomicBoolean(false);
    private final Object muxerLock = new Object();
    private int audioTrackIndex = -1;
    private int videoTrackIndex = -1;
    private boolean isMuxerStarted = false;

    // Kích thước buffer tối đa (adjust theo nhu cầu)
    private static final int MAX_PCM_BUFFER_SIZE = 48000 * 2 * 2 / 1024;;
    private final BlockingQueue<PcmData> pcmQueue = new ArrayBlockingQueue<>(MAX_PCM_BUFFER_SIZE);
    private final AtomicBoolean isAudioDecodingFinished = new AtomicBoolean(false);
    //endregion

    //region Khởi tạo
    public void startTranscoding(Context context,
                                 String inputFilePath,
                                 String outputFilePath,
                                 SurfaceView displaySurface) throws IOException {
        if (isRunning.get()) {
            throw new IllegalStateException("Đang chạy");
        }
        Log.i("VideoDecoder", "out: " + outputFilePath);

        isRunning.set(true);
        outputSurfaceView = displaySurface;

        // 1. Khởi tạo Extractor
        initExtractors(inputFilePath);

        // 2. Khởi tạo và cấu hình codec
        initVideoCodecs();
        initAudioCodecs();

        // 3. Khởi tạo Muxer

        muxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // 4. Bắt đầu chạy
        videoDecoder.start();
        videoEncoder.start();
        audioDecoder.start();
        audioEncoder.start();
    }
    //endregion

    //region Khởi tạo Extractor
    private void initExtractors(String filePath) throws IOException {
        videoExtractor = new MediaExtractor();
        videoExtractor.setDataSource(filePath);
        int videoTrackIndex = selectTrack(videoExtractor, "video/");
        videoExtractor.selectTrack(videoTrackIndex);

        audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(filePath);
        int audioTrackIndex = selectTrack(audioExtractor, "audio/");
        audioExtractor.selectTrack(audioTrackIndex);
    }

    private int selectTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
    //endregion

    //region Khởi tạo Video Codec
    private void initVideoCodecs() throws IOException {
        // 1. Lấy thông tin video từ extractor
        MediaFormat inputFormat = videoExtractor.getTrackFormat(
                selectTrack(videoExtractor, "video/")
        );

        // 2. Tạo video decoder
        videoDecoder = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME)
        );
        videoDecoder.setCallback(new VideoDecoderCallback());
        videoDecoder.configure(
                inputFormat,
                outputSurfaceView.getHolder().getSurface(), // Surface hiển thị
                null,
                0
        );

        // 3. Tạo video encoder
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat outputFormat = createVideoFormat(inputFormat);
        videoEncoder.setCallback(new VideoEncoderCallback());
        videoEncoder.configure(
                outputFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );
        inputSurface = videoEncoder.createInputSurface();

    }

    private MediaFormat createVideoFormat(MediaFormat inputFormat) {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                inputFormat.getInteger(MediaFormat.KEY_WIDTH),
                inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        );
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        );
        return format;
    }
    //endregion

    //region Khởi tạo Audio Codec
    private void initAudioCodecs() throws IOException {
        // 1. Lấy thông tin audio từ extractor
        MediaFormat inputFormat = audioExtractor.getTrackFormat(
                selectTrack(audioExtractor, "audio/")
        );

        // 2. Tạo audio decoder
        audioDecoder = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME)
        );
        audioDecoder.setCallback(new AudioDecoderCallback());
        audioDecoder.configure(inputFormat, null, null, 0);

        // 3. Tạo audio encoder
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat outputFormat = createAudioFormat(inputFormat);
        audioEncoder.setCallback(new AudioEncoderCallback());
        audioEncoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private MediaFormat createAudioFormat(MediaFormat inputFormat) {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        );
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
        );
        return format;
    }
    //endregion

    //region Video Callbacks
    private class VideoDecoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            if (!isRunning.get()) return;

            try {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) {
                    codec.queueInputBuffer(index, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    return;
                }

                int sampleSize = videoExtractor.readSampleData(buffer, 0);
                if (sampleSize >= 0) {
                    long pts = videoExtractor.getSampleTime();
                    codec.queueInputBuffer(index, 0, sampleSize, pts, 0);
                    videoExtractor.advance();
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception e) {
                Log.e("VideoDecoder", "Lỗi input buffer: " + e.getMessage());
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index,
                                            MediaCodec.BufferInfo info) {
            if (!isRunning.get()) return;

            try {
                codec.releaseOutputBuffer(index, info.size > 0);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    videoEOS.set(true);
                    checkCompletion();
                }
            } catch (Exception e) {
                Log.e("VideoDecoder", "Lỗi output buffer: " + e.getMessage());
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e("VideoDecoder", "Lỗi: " + e.getMessage());
            stopTranscoding();
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            // Không cần xử lý với video decoder
        }
    }

    private class VideoEncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            // Không cần xử lý khi dùng Surface
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index,
                                            MediaCodec.BufferInfo info) {
            if (!isRunning.get()) return;

            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    synchronized (muxerLock) {
                        if (isMuxerStarted) {
                            muxer.writeSampleData(videoTrackIndex, buffer, info);
                        }
                    }
                }

                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    videoEOS.set(true);
                    Log.i("hueleu", "onOutputBufferAvailable: encode video");
                    checkCompletion();
                }
            } catch (Exception e) {
                Log.e("VideoEncoder", "Lỗi output buffer: " + e.getMessage());
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e("VideoEncoder", "Lỗi: " + e.getMessage());
            stopTranscoding();
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            synchronized (muxerLock) {
                if (!isMuxerStarted) {
                    videoTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }
        }
    }
    //endregion

    //region Audio Callbacks
    private class AudioDecoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            if (!isRunning.get()) return;

            try {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) {
                    codec.queueInputBuffer(index, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    return;
                }

                int sampleSize = audioExtractor.readSampleData(buffer, 0);
                if (sampleSize >= 0) {
                    long pts = audioExtractor.getSampleTime();
                    codec.queueInputBuffer(index, 0, sampleSize, pts, 0);
                    audioExtractor.advance();
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception e) {
                Log.e("AudioDecoder", "Lỗi input buffer: " + e.getMessage());
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index,
                                            MediaCodec.BufferInfo info) {

            if (!isRunning.get()) return;

            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    // Sao chép dữ liệu PCM
                    byte[] pcmData = new byte[info.size];
                    buffer.get(pcmData);
                    buffer.rewind();

                    // Đưa vào hàng đợi (block nếu queue đầy)
                    try {
                        pcmQueue.put(new PcmData(pcmData, info.presentationTimeUs, info.size));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isAudioDecodingFinished.set(true);
                    checkCompletion();
                }
            } catch (Exception e) {
                Log.e("AudioDecoder", "Output buffer error: " + e.getMessage());
            }
        }

        private void feedAudioEncoder(byte[] pcmData, MediaCodec.BufferInfo info) {
            // Triển khai đưa dữ liệu PCM sang encoder
            // (Cần quản lý buffer và presentation time)
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e("AudioDecoder", "Lỗi: " + e.getMessage());
            stopTranscoding();
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            // Cập nhật thông số audio nếu cần
        }
    }

    private class AudioEncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            if (!isRunning.get()) return;

            try {
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) return;

                // Lấy dữ liệu PCM từ decoder
                PcmData pcmData = getNextPcmData();
                if (pcmData != null) {
                    buffer.put(pcmData.data);
                    codec.queueInputBuffer(
                            index,
                            0,
                            pcmData.data.length,
                            pcmData.presentationTimeUs,
                            0
                    );
                } else if (isAudioDecodingFinished.get()) {
                    codec.queueInputBuffer(
                            index,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    );
                }
            } catch (Exception e) {
                Log.e("AudioEncoder", "Lỗi input buffer: " + e.getMessage());
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index,
                                            MediaCodec.BufferInfo info) {
            if (!isRunning.get()) return;

            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    synchronized (muxerLock) {
                        if (isMuxerStarted) {
                            muxer.writeSampleData(audioTrackIndex, buffer, info);
                        }
                    }
                }

                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    audioEOS.set(true);
                    checkCompletion();
                }
            } catch (Exception e) {
                Log.e("AudioEncoder", "Lỗi output buffer: " + e.getMessage());
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e("AudioEncoder", "Lỗi: " + e.getMessage());
            stopTranscoding();
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            synchronized (muxerLock) {
                if (!isMuxerStarted) {
                    audioTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }
        }
    }
    //endregion

    private PcmData getNextPcmData() {
        try {
            // Chờ tối đa 100ms để tránh block vô hạn
            PcmData data = pcmQueue.poll(100, TimeUnit.MILLISECONDS);

            if (data != null) {
                return data;
            } else if (isAudioDecodingFinished.get() && pcmQueue.isEmpty()) {
                // Trả về null khi đã hết dữ liệu
                return null;
            }

            // Trường hợp timeout nhưng vẫn còn dữ liệu
            return new PcmData(new byte[0], 0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static class PcmData {
        public final byte[] data;
        public final long presentationTimeUs;
        public final int size;

        public PcmData(byte[] data, long presentationTimeUs, int size) {
            this.data = data;
            this.presentationTimeUs = presentationTimeUs;
            this.size = size;
        }
    }

    //region Tiện ích
    private void startMuxerIfReady() {
        synchronized (muxerLock) {
            if (!isMuxerStarted && videoTrackIndex != -1 && audioTrackIndex != -1) {
                muxer.start();
                isMuxerStarted = true;
            }
        }
    }

    private void checkCompletion() {
        if (audioEOS.get() && videoEOS.get()) {
            stopTranscoding();
        }
    }

    public void stopTranscoding() {
        if (!isRunning.getAndSet(false)) return;

        releaseResources();
    }

    private void releaseResources() {
        // Release theo thứ tự ngược với khởi tạo
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder.release();
        }

        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
        }

        if (audioDecoder != null) {
            audioDecoder.stop();
            audioDecoder.release();
        }

        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder.release();
        }

        if (inputSurface != null) {
            inputSurface.release();
        }

        synchronized (muxerLock) {
            if (muxer != null && isMuxerStarted) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e("Transcoder", "Lỗi release muxer: " + e.getMessage());
                }
            }
        }

        if (videoExtractor != null) {
            videoExtractor.release();
        }

        if (audioExtractor != null) {
            audioExtractor.release();
        }

        pcmQueue.clear();
        isAudioDecodingFinished.set(false);
    }
    //endregion
}
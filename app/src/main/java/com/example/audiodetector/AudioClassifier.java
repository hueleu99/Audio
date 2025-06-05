package com.example.audiodetector;

import static androidx.core.content.ContentProviderCompat.requireContext;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.lite.Interpreter;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioClassifier {
    String TAG = "hueleu";

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int REQUIRED_INPUT_SIZE = 15600;
    private static final int STEP_SIZE = 7680;
    private static final Logger log = LogManager.getLogger(AudioClassifier.class);


    private List<Segment> segments = new ArrayList<>();
    private float currentTime = 0f;
    private final float frameDurationSec = 0.48f;  // mỗi frame YAMNet tương ứng 0.48s
    private String lastLabel = null;
    private float lastStartTime = 0f;
    private float lastConfidence = 0f;
    private final List<Float> buffer = new ArrayList<>();
    private final Context context;
    Map<String, List<Segment>> allResults = new HashMap<>();

    private int audioTrackIndex = -1;
    private int videoTrackIndex = -1;

    private MediaExtractor audioExtractor;
    private MediaExtractor extractor;

    private MediaMuxer muxer;

    private int muxerVideoTrackIndex = -1;
    private int muxerAudioTrackIndex = -1;
    private boolean muxerStarted = false;
    MediaCodec videoDecoder, videoEncoder;
    private int outputTrackIndex = -1;

    private Surface outputSurface; // hoặc null nếu xử lý YUV

    private boolean videoEncoderDone = false;
    private boolean audioEncoderDone = false;

    private MediaCodec audioDecoder, audioEncoder;

    private MediaCodec decoder;
    private MediaCodec encoder;
    private Surface inputSurface;
    boolean encoderStarted = false;

    private int muxerTrackIndex = -1;

    //List<Segment> segments = new ArrayList<>();
    Segment currentSegment = null;
    Segment pendingShortSegment = null;

    String OUTPUT_PATH = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString() + "/output.mp4";

    public AudioClassifier(Context context) throws IOException {
        this.context = context;
    }


    public void classifyFile(Uri uriPathPath) throws IOException {
        reset();
        ParcelFileDescriptor pfd_fg = null;
        try {
            pfd_fg = context.getContentResolver().openFileDescriptor(uriPathPath, "r");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        assert pfd_fg != null;

        ParcelFileDescriptor finalPfd_fg = pfd_fg;
        new Thread(() -> {
            try {
                start(finalPfd_fg.getFileDescriptor());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

    }

    public void start(FileDescriptor INPUT_PATH) throws IOException {
        Log.i(TAG, "start: videoTrackIndex " + videoTrackIndex + " audioTrackIndex " + audioTrackIndex);
        extractor = new MediaExtractor();
        extractor.setDataSource(INPUT_PATH);
        Log.i(TAG, "after setDataSource: ");
        // Select tracks
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.i(TAG, "start:  after getFormat");

            assert mime != null;
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i;
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i;
            }
        }

        Log.i(TAG, "start: videoTrackIndex " + videoTrackIndex + " audioTrackIndex " + audioTrackIndex);

        if (videoTrackIndex != -1) {
            Log.i(TAG, "start: ");
            setupVideoPipeline();
        }

        if (audioTrackIndex != -1) {
            setupAudioPipeline();
        }
        muxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void setupVideoPipeline() throws IOException {
        extractor.selectTrack(videoTrackIndex);
        MediaFormat videoFormat = extractor.getTrackFormat(videoTrackIndex);
        Log.i(TAG, "setupVideoPipeline: ");

        videoDecoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
        videoDecoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer == null) return;

                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                long presentationTimeUs = extractor.getSampleTime();

                if (sampleSize >= 0) {
                    Log.i(TAG, "onInputBufferAvailable: decoder");
                    codec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, extractor.getSampleFlags());
                    extractor.advance();
                } else {
                    Log.i(TAG, "onInputBufferAvailable: decoder end");
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                boolean isEndOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                codec.releaseOutputBuffer(index, true); // render to inputSurface of encoder

                if (isEndOfStream) {
                    if (encoderStarted) {
                        Log.d(TAG, "Decoder reached end of stream");
                        videoEncoder.signalEndOfInputStream(); // ✅ Rất quan trọng
                    } else {
                        Log.w(TAG, "Encoder chưa start nhưng cố gọi signalEndOfInputStream!");
                    }

                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            }
        });

        setupVideoEncoder(videoFormat);

        videoDecoder.configure(videoFormat, inputSurface, null, 0);
        videoDecoder.start();
    }

    private void setupVideoEncoder(MediaFormat inputFormat) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc",
                inputFormat.getInteger(MediaFormat.KEY_WIDTH),
                inputFormat.getInteger(MediaFormat.KEY_HEIGHT));
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        videoEncoder = MediaCodec.createEncoderByType("video/avc");
        videoEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {

                ByteBuffer buffer = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                synchronized (muxerLock) {
                    if (info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(muxerVideoTrackIndex, buffer, info);
                    }
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Video encode complete");
                    videoEOS.set(true);
                    checkAndRelease();
                }
                codec.releaseOutputBuffer(index, false);
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

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            }
        });

        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();
        encoderStarted = true;
    }

    boolean audioDecoderEOS = false;
    // Queue để chuyển PCM data giữa decoder và encoder
    private BlockingQueue<PcmData> pcmQueue = new LinkedBlockingQueue<>();

    private static class PcmData {
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        PcmData(ByteBuffer data, MediaCodec.BufferInfo info) {
            this.data = data;
            this.info = new MediaCodec.BufferInfo();
            this.info.set(info.offset, info.size, info.presentationTimeUs, info.flags);
        }
    }
    private AtomicBoolean audioEOS = new AtomicBoolean(false);
    private AtomicBoolean videoEOS = new AtomicBoolean(false);

    boolean isReleased = false;

    private void checkAndRelease() {
        if (audioEOS.get() && videoEOS.get()) {
            releaseResources();
        }
    }

    private void setupAudioPipeline() throws IOException {
        Log.i(TAG, "setupAudioPipeline: ");
        extractor.selectTrack(audioTrackIndex);
        MediaFormat audioFormat = extractor.getTrackFormat(audioTrackIndex);

        audioDecoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
        audioDecoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                if (codec == null || isReleased) { // Kiểm tra biến trạng thái
                    return;
                }
                if(audioDecoderEOS) return;

                ByteBuffer buffer = codec.getInputBuffer(index);
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize >= 0) {
                    long time = extractor.getSampleTime();
                    codec.queueInputBuffer(index, 0, sampleSize, time, extractor.getSampleFlags());
                    extractor.advance();
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    audioDecoderEOS = true;
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // Gửi EOS tới encoder
                    Log.i(TAG, "Gửi EOS tới encoder: ");
                    pcmQueue.offer(new PcmData(null, info));
                }

                if (info.size > 0) {
                    ByteBuffer outputBuffer = audioDecoder.getOutputBuffer(index);
                    // Sao chép dữ liệu PCM để chuyển sang encoder
                    ByteBuffer pcmCopy = ByteBuffer.allocateDirect(info.size);
                    pcmCopy.put(outputBuffer);
                    pcmCopy.flip();
                    Log.i(TAG, " pcmQueue.offer: ");

                    pcmQueue.offer(new PcmData(pcmCopy, info));
                }

                audioDecoder.releaseOutputBuffer(index, false);
            }

            @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) {}
            @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {}
        });

        audioDecoder.configure(audioFormat, null, null, 0);
        audioDecoder.start();

        setupAudioEncoder(audioFormat);
    }

    private void startMuxerIfReady() {
        if (videoTrackIndex != -1 && audioTrackIndex != -1) {
            muxer.start();
            isMuxerStarted = true;
        }
    }
    boolean audioDecoderStared = false;
    boolean encoderEOS = false;
    private final Object muxerLock = new Object();
    boolean isMuxerStarted = false;

    private void setupAudioEncoder(MediaFormat inputFormat) throws IOException {

        Log.i(TAG, "setupAudioEncoder: ");

        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm",
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
        audioEncoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(index);

                if (info.size > 0) {
                    synchronized (muxerLock) {
                        if (!isMuxerStarted) return; // Chờ format

                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        muxer.writeSampleData(outputTrackIndex, outputBuffer, info);
                    }
                }

                audioEncoder.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    releaseResources(); // Kết thúc quá trình
                    Log.i(TAG, "releaseResources: ");
                }
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                synchronized (muxerLock) {
                    audioTrackIndex = muxer.addTrack(format);
                    startMuxerIfReady();
                }
            }

            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                if (encoderEOS) return;

                try {
                    PcmData pcmData = pcmQueue.poll(100, TimeUnit.MILLISECONDS);

                    if (pcmData == null) {
                        Log.i(TAG, " Chưa có dữ liệu: ");
                        return; // Chưa có dữ liệu

                    }

                    MediaCodec.BufferInfo info = pcmData.info;
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(index);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        audioEncoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        );
                        audioEOS.set(true);
                        checkAndRelease();
                    } else {
                        Log.i(TAG, "encode pcm data input: ");
                        inputBuffer.clear();
                        inputBuffer.put(pcmData.data);
                        audioEncoder.queueInputBuffer(
                                index, 0, info.size, info.presentationTimeUs, 0
                        );
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "onInputBufferAvailable: Thread.currentThread().interrupt()");
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            }
        });

        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();


        Log.i(TAG, "setupAudioEncoder: started");
    }
    private void releaseResources() {
        audioDecoder.stop();
        audioDecoder.release();
        audioEncoder.stop();
        audioEncoder.release();
        extractor.release();

        synchronized (muxerLock) {
            if (isMuxerStarted) {
                muxer.stop();
                muxer.release();
            }
        }
    }

    private void initMuxer() {
        try {
            File file = new File(OUTPUT_PATH);
            if (file.exists()) file.delete();
            muxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            throw new RuntimeException("Muxer init failed", e);
        }
    }

    private void tryStartMuxer() {
        if (muxer != null && muxerVideoTrackIndex != -1 && muxerAudioTrackIndex != -1) {
            muxer.start();
            muxerStarted = true;
            Log.d(TAG, "Muxer started");
        }
    }

    private void tryStopMuxer() {
        if (videoEncoderDone && audioEncoderDone && muxer != null) {
            try {
                muxer.stop();
                muxer.release();
                Log.d(TAG, "Muxer stopped and released");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping muxer", e);
            }
        }
    }


/*    public void startTranscoding(FileDescriptor INPUT_PATH) throws IOException {
        Log.i(TAG, "startTranscoding: ");
        extractor = new MediaExtractor();
        extractor.setDataSource(INPUT_PATH);

        // Tìm video track
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrackIndex = i;
                extractor.selectTrack(videoTrackIndex);
                setupEncoder(format);         // 🟢 Gọi trước để tạo encoder + inputSurface
                setupDecoder(format);         // 🟢 Giờ đã có surface để cấu hình decoder
                break;
            }
        }

        if (videoTrackIndex == -1) {
            throw new RuntimeException("Không tìm thấy track video!");
        }
    }*/

/*    private void setupDecoder(MediaFormat format) throws IOException {
        Log.i(TAG, "setupDecoder: ");
        String mime = format.getString(MediaFormat.KEY_MIME);
        decoder = MediaCodec.createDecoderByType(mime);

        decoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer == null) return;

                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                long presentationTimeUs = extractor.getSampleTime();

                if (sampleSize >= 0) {
                    Log.i(TAG, "onInputBufferAvailable: decoder");
                    codec.queueInputBuffer(index, 0, sampleSize, presentationTimeUs, extractor.getSampleFlags());
                    extractor.advance();
                } else {
                    Log.i(TAG, "onInputBufferAvailable: decoder end");
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                boolean isEndOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                codec.releaseOutputBuffer(index, true); // render to inputSurface of encoder

                if (isEndOfStream) {
                    if(encoderStarted){
                        Log.d(TAG, "Decoder reached end of stream");
                        encoder.signalEndOfInputStream(); // ✅ Rất quan trọng
                    }else {
                        Log.w(TAG, "Encoder chưa start nhưng cố gọi signalEndOfInputStream!");
                    }

                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "Decoder error", e);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                Log.i(TAG, "onOutputFormatChanged: ");
            }
        });
        decoder.configure(format, inputSurface, null, 0);  // 🔁 dùng surface đã có
        decoder.start();
    }*/

/*    private void setupEncoder(MediaFormat inputFormat) throws IOException {
        Log.i(TAG, "setupEncoder: ");
        int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

        MediaFormat encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        encoder = MediaCodec.createEncoderByType("video/avc");

        encoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                Log.i(TAG, "onInputBufferAvailable: encoder");
                // Không dùng vì sử dụng Surface
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.i(TAG, "BUFFER_FLAG_CODEC_CONFIG: ");
                    codec.releaseOutputBuffer(index, false);
                    return;
                }

                ByteBuffer encodedData = codec.getOutputBuffer(index);
                if (encodedData != null && info.size > 0) {
                    if (!muxerStarted) {
                        throw new IllegalStateException("Muxer chưa bắt đầu!");
                    }
                    Log.i(TAG, "onOutputBufferAvailable: encoder");
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    muxer.writeSampleData(muxerTrackIndex, encodedData, info);
                }

                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Encode complete");
                    releaseAll();
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, "Encoder error", e);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                try {
                    muxer = new MediaMuxer(OUTPUT_PATH, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    muxerTrackIndex = muxer.addTrack(format);
                    muxer.start();
                    muxerStarted = true;
                    Log.d(TAG, "Muxer started");
                } catch (IOException e) {
                    throw new RuntimeException("Lỗi khi khởi tạo muxer", e);
                }
            }
        });
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();
        encoderStarted = true;



    }*/

    private void releaseAll() {
        try {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            if (extractor != null) {
                extractor.release();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                muxer.release();
            }
            Log.d(TAG, "Đã giải phóng toàn bộ");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reset() {
        lastLabel = null;
        segments = new ArrayList<>();
        lastStartTime = 0f;
        lastConfidence = 0f;
        currentTime = 0f;
        buffer.clear();
    }
}

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

public class AudioClassifier {

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
    private MediaExtractor videoExtractor;

    private MediaCodec audioEncoder;
    private MediaCodec videoEncoder;
    private MediaMuxer muxer;

    private Surface inputSurface;
    private int muxerVideoTrackIndex = -1;
    private int muxerAudioTrackIndex = -1;
    private boolean muxerStarted = false;
    MediaCodec videoDecoder;
    private int outputTrackIndex = -1;

    private Surface outputSurface; // hoặc null nếu xử lý YUV


    //List<Segment> segments = new ArrayList<>();
    Segment currentSegment = null;
    Segment pendingShortSegment = null;

    public AudioClassifier(Context context) throws IOException {
        this.context = context;
    }


    public Map<String, List<Segment>> getAllResults() {
        return allResults;
    }

    public void classifyFile(Uri uriPathPath) throws IOException {
        reset();
        ParcelFileDescriptor pfd_fg = null;
        try {
            pfd_fg = context.getContentResolver().openFileDescriptor(uriPathPath, "r");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        try {
            assert pfd_fg != null;
            setupExtractors(pfd_fg.getFileDescriptor());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

//        //create audio decoder
//        MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrackIndex);
//        String mineAudio = audioFormat.getString(MediaFormat.KEY_MIME);
//        assert mineAudio != null;
//        MediaCodec audioDecoder = MediaCodec.createDecoderByType(mineAudio);
//        audioDecoder.setCallback(new AudioCallback());
//        audioDecoder.configure(audioFormat, null, null, 0);
//        audioDecoder.start();
//
//        //create audio encoder
//        int BIT_RATE = 128000;
//        int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
//        int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
//        int maxInputSize = channelCount*sampleRate*2;
//        MediaFormat outputAudioFormat = MediaFormat.createAudioFormat(mineAudio, sampleRate, channelCount);
//        outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
//        outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
//        outputAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize);
//
//        audioEncoder = MediaCodec.createEncoderByType(mineAudio);
//        audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//        audioEncoder.start();

        //create video decoder
        MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
        String mimeVideo = videoFormat.getString(MediaFormat.KEY_MIME);
        assert mimeVideo != null;


        //create video encoder
        int frameRate = videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
        int width = videoFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int I_FRAME_INTERVAL = 10;
        String MIME_TYPE = "video/avc";
        MediaFormat outputVideoFormat = MediaFormat.createVideoFormat(mimeVideo, width, height);

        outputVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        outputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoEncoder.createInputSurface();
        videoEncoder.start();

        videoDecoder = MediaCodec.createDecoderByType(mimeVideo);
        //videoDecoder.setCallback(new VideoCallback());
        videoDecoder.configure(videoFormat, inputSurface, null, 0);
        videoDecoder.start();

        //create muxer
        String outputPath =  Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString() + "/output.mp4";
        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        process();
        release();

    }

    private void setupExtractors(FileDescriptor inputPath) throws IOException {
        videoExtractor = new MediaExtractor();
        videoExtractor.setDataSource(inputPath);

        audioExtractor = new MediaExtractor();
        audioExtractor.setDataSource(inputPath);

        for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
            MediaFormat format = videoExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i;
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i;
            }
        }

        if (videoTrackIndex == -1) throw new RuntimeException("No video track found");

        videoExtractor.selectTrack(videoTrackIndex);
        if (audioTrackIndex >= 0) audioExtractor.selectTrack(audioTrackIndex);
    }

    private void process() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean isExtractorEOS = false;
        boolean isDecoderEOS = false;
        boolean isEncoderEOS = false;
        boolean signaledEndOfStream = false;

        long lastRenderTimeUs = -2_000_000; // Đảm bảo frame đầu được render
        long dropIntervalUs = 2_000_000;    // Drop frame nếu cách frame trước < 2s

        while (!isEncoderEOS) {
            // 1. Feed decoder
            if (!isExtractorEOS) {
                int inputIndex = videoDecoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = videoDecoder.getInputBuffer(inputIndex);
                    int sampleSize = videoExtractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        videoDecoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isExtractorEOS = true;
                    } else {
                        long sampleTime = videoExtractor.getSampleTime();
                        videoDecoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                        videoExtractor.advance();
                    }
                }
            }

            // 2. Drain decoder
            boolean decoderOutputAvailable = true;
            while (decoderOutputAvailable) {
                int outputIndex = videoDecoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    decoderOutputAvailable = false;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Bỏ qua, không cần xử lý
                } else if (outputIndex >= 0) {
                    long presentationTimeUs = bufferInfo.presentationTimeUs;

                    // Quyết định có nên render frame hay drop
                    boolean shouldRender = (presentationTimeUs - lastRenderTimeUs >= dropIntervalUs);
                    if (shouldRender) {
                        videoDecoder.releaseOutputBuffer(outputIndex, true); // render
                        lastRenderTimeUs = presentationTimeUs;
                    } else {
                        videoDecoder.releaseOutputBuffer(outputIndex, false); // drop
                    }

                    // Gửi EOS tới encoder nếu decoder kết thúc
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true;
                        if (!signaledEndOfStream) {
                            videoEncoder.signalEndOfInputStream();
                            signaledEndOfStream = true;
                        }
                    }
                }
            }

            // 3. Drain encoder
            boolean encoderOutputAvailable = true;
            while (encoderOutputAvailable) {
                int outputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    encoderOutputAvailable = false;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        Log.w("hueleu", "Format changed again, ignoring.");
                        continue;
                    }
                    MediaFormat newFormat = videoEncoder.getOutputFormat();
                    outputTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer encodedBuffer = videoEncoder.getOutputBuffer(outputIndex);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0; // Codec config không cần ghi
                    }

                    if (bufferInfo.size > 0 && muxerStarted) {
                        encodedBuffer.position(bufferInfo.offset);
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(outputTrackIndex, encodedBuffer, bufferInfo);
                    }

                    videoEncoder.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEncoderEOS = true;
                        break;
                    }
                }
            }
        }
    }


    private void release() {
        Log.i("hueleu", "release: ");
        if (videoExtractor != null) videoExtractor.release();
        if (videoDecoder != null) videoDecoder.stop();
        if (videoDecoder != null) videoDecoder.release();
        if (videoEncoder != null) videoEncoder.stop();
        if (videoEncoder != null) videoEncoder.release();
        if (muxer != null) muxer.stop();
        if (muxer != null) muxer.release();
    }



    private String mapLabelToGroup(String label) {
        String lower = label.toLowerCase();
        if (lower.contains("music") || lower.contains("instrument") || lower.contains("guitar") ||
                lower.contains("violin") || lower.contains("piano") || lower.contains("drum") || lower.contains("timpani")) {
            return "Music";
        } else if (lower.contains("speech") || lower.contains("talking") || lower.contains("conversation")) {
            return "Speech";
        } else if (lower.contains("nature") || lower.contains("wind") || lower.contains("rain") ||
                lower.contains("water") || lower.contains("bird") || lower.contains("animal")) {
            return "Nature";
        } else {
            return label;  // giữ nguyên nếu không thuộc nhóm nào
        }
    }

    private void processPCMData(byte[] pcmBytes, MediaFormat format) {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int numSamples = pcmBytes.length / 2;

        float[] monoFloat = new float[numSamples / channels];

        ByteBuffer buffer16 = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0, j = 0; i < numSamples; i += channels, j++) {
            short sample = buffer16.getShort(i * 2); // Chỉ lấy kênh 0
            monoFloat[j] = sample / 32768f;
        }

        float[] resampled = resampleIfNeeded(monoFloat, sampleRate);
        feedToYamnet(resampled);
    }

    private float[] resampleIfNeeded(float[] input, int originalRate) {
        if (originalRate == TARGET_SAMPLE_RATE) return input;

        int newLength = (int) ((long) input.length * TARGET_SAMPLE_RATE / originalRate);
        float[] output = new float[newLength];

        for (int i = 0; i < newLength; i++) {
            float pos = ((float) i * originalRate) / TARGET_SAMPLE_RATE;
            int idx = (int) pos;
            float frac = pos - idx;

            if (idx + 1 < input.length)
                output[i] = input[idx] * (1 - frac) + input[idx + 1] * frac;
            else
                output[i] = input[idx];
        }
        return output;
    }

    private void feedToYamnet(float[] samples) {
        for (float f : samples) buffer.add(f);

        while (buffer.size() >= REQUIRED_INPUT_SIZE) {
            float[] input = new float[REQUIRED_INPUT_SIZE];
            for (int i = 0; i < REQUIRED_INPUT_SIZE; i++) {
                input[i] = buffer.get(i);
            }


            // Slide 0.48s
            buffer.subList(0, STEP_SIZE).clear();
        }
    }


    private void handleLabelChange(String newLabel) {
        float time = currentTime;

        if (currentSegment == null) {
            currentSegment = new Segment(newLabel, time, time + frameDurationSec, lastConfidence);
            return;
        }

        if (newLabel.equals(currentSegment.label)) {
            // Tiếp tục cùng nhãn → mở rộng segment
            currentSegment.endTimeSec = time + frameDurationSec;

            // Nếu có nhãn ngắn xen giữa trước đó cùng nhãn này → gộp luôn
            if (pendingShortSegment != null) {
                currentSegment.endTimeSec = pendingShortSegment.endTimeSec;
                pendingShortSegment = null;
            }
        } else {
            float segmentDuration = currentSegment.endTimeSec - currentSegment.startTimeSec;

            if (segmentDuration >= 1.0f) {
                segments.add(currentSegment);
            }

            // Nếu nhãn mới khác → tạm giữ lại nếu ngắn
            if (segmentDuration < 1.0f) {
                // Bỏ qua nhãn ngắn nếu không có gì phía sau để gộp
                pendingShortSegment = currentSegment;
            }

            currentSegment = new Segment(newLabel, time, time + frameDurationSec, lastConfidence);
        }
    }


    private int argMax(float[] arr) {
        int maxIdx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[maxIdx]) maxIdx = i;
        }
        return maxIdx;
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

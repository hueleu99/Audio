package com.example.audiodetector;

import static androidx.core.content.ContentProviderCompat.requireContext;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

public class AudioClassifier {

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int REQUIRED_INPUT_SIZE = 15600;
    private static final int STEP_SIZE = 7680;


    private  List<Segment> segments = new ArrayList<>();
    private float currentTime = 0f;
    private final float frameDurationSec = 0.48f;  // mỗi frame YAMNet tương ứng 0.48s
    private String lastLabel = null;
    private float lastStartTime = 0f;
    private float  lastConfidence= 0f;
    private final List<Float> buffer = new ArrayList<>();
    private final Context context;
    private final Interpreter interpreter;
    private final String[] labels;
    Map<String, List<Segment>> allResults = new HashMap<>();




    //List<Segment> segments = new ArrayList<>();
    Segment currentSegment = null;
    Segment pendingShortSegment = null;

    public AudioClassifier(Context context) throws IOException {
        this.context = context;
        this.interpreter = loadModel();
        this.labels = loadLabels();
    }

    private Interpreter loadModel() throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd("yamnet.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        MappedByteBuffer modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        return new Interpreter(modelBuffer);
    }

    private String[] loadLabels() throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("yamnet_class_map.csv")));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(",")) {
                String[] parts = line.split(",");
                lines.add(parts[2]); // display_name
            }
        }
        return lines.toArray(new String[0]);
    }

    public Map<String, List<Segment>> getAllResults() {
        return allResults;
    }

    public void classifyFile(Uri uriPathPath) throws IOException {
        reset();
        MediaExtractor extractor = new MediaExtractor();
        ParcelFileDescriptor pfd_fg = null;
        try {
            pfd_fg = context.getContentResolver().openFileDescriptor(uriPathPath, "r");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        try {
            assert pfd_fg != null;
            extractor.setDataSource(pfd_fg.getFileDescriptor());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int trackIndex = selectAudioTrack(extractor);
        if (trackIndex < 0) throw new IllegalStateException("No audio track found.");

        extractor.selectTrack(trackIndex);
        MediaFormat format = extractor.getTrackFormat(trackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                int size = extractor.readSampleData(inputBuffer, 0);
                if (size > 0) {
                    codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                    extractor.advance();
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i("AudioClassifier", "End of stream reached");

                    // Xử lý phần cuối cùng nếu cần
                    if (lastLabel != null) {
                        segments.add(new Segment(lastLabel, lastStartTime, currentTime, lastConfidence));
                    }

                    // In toàn bộ kết quả
                    for (Segment s : segments) {
                        Log.i("FinalSegment", s.toString());
                    }

                    allResults.put(uriPathPath.toString(), segments);

                    codec.releaseOutputBuffer(index, false);
                    codec.stop();
                    codec.release();

                    Toast.makeText(context, "Classification has done", Toast.LENGTH_SHORT).show();
                    return;
                }

                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                byte[] pcmData = new byte[info.size];
                outputBuffer.get(pcmData);
                outputBuffer.clear();
                codec.releaseOutputBuffer(index, false);

                processPCMData(pcmData, format);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {}
            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e("AudioClassifier", "Codec error: " + e.getMessage());
            }
        });

        decoder.configure(format, null, null, 0);
        decoder.start();
    }

    private int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
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
            classifyInput(input);

            // Slide 0.48s
            buffer.subList(0, STEP_SIZE).clear();
        }
    }

    private void  classifyInput(float[] input) {
        float[][] outputScores = new float[1][labels.length];
        interpreter.run(input, outputScores);

        int topIdx = argMax(outputScores[0]);
        String rawLabel = labels[topIdx];
        String label = mapLabelToGroup(rawLabel);  // ← dùng label đã gom nhóm;
        lastConfidence = outputScores[0][topIdx];
        handleLabelChange(label);

        currentTime += frameDurationSec;
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

    private void reset(){
       lastLabel = null;
       segments = new ArrayList<>();
        lastStartTime = 0f;
        lastConfidence= 0f;
        currentTime = 0f;
        buffer.clear();
    }
}

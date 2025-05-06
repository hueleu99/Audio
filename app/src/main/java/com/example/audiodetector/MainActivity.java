package com.example.audiodetector;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.TextView;

import com.example.audiodetector.databinding.ActivityMainBinding;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'audiodetector' library on application startup.
    static {
        System.loadLibrary("audiodetector");
    }

    public native void nativeInit(int sampleRate, int channels);
    public native void nativePush(short[] pcm, int length, long ptsUs);
    public native void nativeFlush();

    public void onPcmDataFromDecoder(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        short[] pcm = new short[info.size / 2];
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        nativePush(pcm, pcm.length, info.presentationTimeUs);
    }

    public void init(int sampleRate, int channels) {
        nativeInit(sampleRate, channels);
    }

    public void flush(){
        nativeFlush();
    }

    private ActivityMainBinding binding;
    private static final int REQUEST_CODE_PICK_VIDEO = 1;
    private static final String TAG = "hue.leu";
    private Uri inputUri;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.button.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_PICK_VIDEO);
        });
        
        binding.decode.setOnClickListener(v -> {
            startDecode();
        });
    }

    private void startDecode() {
        try {
            MediaExtractor extractor = new MediaExtractor();
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContentResolver().openFileDescriptor(inputUri, "r");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            try {
                assert pfd != null;
                extractor.setDataSource(pfd.getFileDescriptor());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            int trackIndex = selectTrack(extractor); // Hàm phụ ở dưới
            if (trackIndex < 0) return;

            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            init(sampleRate, channelCount);

            assert mime != null;
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.setCallback(new MediaCodec.Callback() {
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
                        flush();
                        codec.stop();
                        codec.release();
                        extractor.release();
                        return;
                    }

                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if(buffer != null){

                        onPcmDataFromDecoder(buffer, info);
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
            });

            codec.configure(format, null, null, 0);
            codec.start();

        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    int selectTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            assert mime != null;
            if (mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_VIDEO && resultCode == RESULT_OK) {
            inputUri = data.getData();
            assert inputUri != null;
            Log.i(TAG, "onActivityResult: " + inputUri.getPath());
            binding.sampleText.setText(inputUri.getPath());
            //decodeMP4WithCallback(videoUri);
        }
    }

    /**
     * A native method that is implemented by the 'audiodetector' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
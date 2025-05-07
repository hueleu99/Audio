package com.example.audiodetector;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.example.audiodetector.databinding.ActivityMainBinding;

import java.io.FileNotFoundException;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private static final int REQUEST_CODE_PICK_VIDEO = 1;
    private static final int REQUEST_CODE_PICK_AUDIO = 2;
    private static final String TAG = "hue.leu";
    private Uri inputBGUri;
    private Uri inputFGUri;
    MediaFormat mediaFormatFG;
    MediaFormat mediaFormatBG;
    int trackIndex;
    AudioDetector audioDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        audioDetector = new AudioDetector();

        binding.button.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_PICK_VIDEO);
        });

        binding.bg.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("audio/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_PICK_AUDIO);
        });
        
        binding.decode.setOnClickListener(v -> {
            startDecode();
        });
    }

    private void startDecode() {
        try {
            MediaExtractor extractorFG = createMediaExtractor(inputFGUri);
            mediaFormatFG = extractorFG.getTrackFormat(trackIndex);
            String mime = mediaFormatFG.getString(MediaFormat.KEY_MIME);
            assert mime != null;
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.setCallback(new AudioDecoder(extractorFG,true, audioDetector));
            codec.configure(mediaFormatFG, null, null, 0);
            codec.start();

            int sampleRate = mediaFormatFG.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = mediaFormatFG.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            audioDetector.init(sampleRate, channelCount);

            MediaExtractor extractorBG = createMediaExtractor(inputBGUri);
            mediaFormatBG = extractorBG.getTrackFormat(trackIndex);
            String minebg = mediaFormatBG.getString(MediaFormat.KEY_MIME);
            assert minebg != null;
            MediaCodec codecBG = MediaCodec.createDecoderByType(minebg);
            codecBG.setCallback(new AudioDecoder(extractorBG,false, audioDetector));
            codecBG.configure(mediaFormatBG, null, null, 0);
            codecBG.start();
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

     MediaExtractor createMediaExtractor(Uri uri) {
         MediaExtractor extractor = new MediaExtractor();
         ParcelFileDescriptor pfd_fg = null;
         try {
             pfd_fg = getContentResolver().openFileDescriptor(uri, "r");
         } catch (FileNotFoundException e) {
             throw new RuntimeException(e);
         }
         try {
             assert pfd_fg != null;
             extractor.setDataSource(pfd_fg.getFileDescriptor());
         } catch (IOException e) {
             throw new RuntimeException(e);
         }

         trackIndex = selectTrack(extractor); // Hàm phụ ở dưới
         if (trackIndex < 0) return null;

         extractor.selectTrack(trackIndex);
         return extractor;
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
            inputFGUri = data.getData();
            assert inputFGUri != null;
            Log.i(TAG, "onActivityResult: " + inputFGUri.getPath());
            binding.sampleText.setText(inputFGUri.getPath());
            //decodeMP4WithCallback(videoUri);
        } else if(requestCode == REQUEST_CODE_PICK_AUDIO && resultCode == RESULT_OK) {
            inputBGUri = data.getData();
            assert inputBGUri != null;
            Log.i(TAG, "onActivityResult: " + inputBGUri.getPath());
            binding.sampleText.setText(inputBGUri.getPath());
        }
    }
}
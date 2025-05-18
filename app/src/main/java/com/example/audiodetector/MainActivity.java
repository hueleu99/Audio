package com.example.audiodetector;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.example.audiodetector.databinding.ActivityMainBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final Logger log = LogManager.getLogger(MainActivity.class);
    private ActivityMainBinding binding;
    private static final int REQUEST_CODE_PICK_VIDEO = 1;
    private static final int REQUEST_CODE_PICK_AUDIO = 2;
    private static final String TAG = "hue.leu";
    private Uri inputBGUri;
    private Uri inputFGUri;
    AudioClassifier classifier;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        try {
            classifier = new AudioClassifier(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        binding.button.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_CODE_PICK_VIDEO);
        });

        
        binding.decode.setOnClickListener(v -> {
            try {
                classifier.classifyFile(inputFGUri);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        binding.export.setOnClickListener(v -> {
            Map<String, List<Segment>> allResults = classifier.getAllResults();
            File outFile = new File(this.getExternalFilesDir(null), "yamnet_results.xlsx");
            try {
                ExcelExporter.exportAllSegmentsToExcel(this, outFile, allResults);
            } catch (IOException e) {
                Log.i(TAG, "onCreate: "+ e.getMessage());
                throw new RuntimeException(e);
            }
        });


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
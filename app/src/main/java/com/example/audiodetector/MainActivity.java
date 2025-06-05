package com.example.audiodetector;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.example.audiodetector.databinding.ActivityMainBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private SurfaceView surfaceView;
    private AudioVideoTranscoder transcoder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Khởi tạo SurfaceView
        surfaceView = findViewById(R.id.surface_view);

        // 2. Đảm bảo Surface đã sẵn sàng
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // Surface đã sẵn sàng, có thể bắt đầu transcode
                startTranscoding();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Xử lý thay đổi kích thước (nếu cần)
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // Dừng transcode khi Surface bị hủy
                if (transcoder != null) {
                    transcoder.stopTranscoding();
                }
            }
        });
    }

    private void startTranscoding() {
        transcoder = new AudioVideoTranscoder();
        try {
            transcoder.startTranscoding(
                    this,
                    getInputFilePath(),
                    getOutputFilePath(),
                    surfaceView // Truyền SurfaceView vào
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getInputFilePath() {
        // Lấy đường dẫn file input (ví dụ từ assets hoặc storage)
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString() + "/#000.mp4";
    }

    private String getOutputFilePath() {
        // Tạo file output
        File outputDir = getExternalFilesDir(null);
        return new File(outputDir, "output.mp4").getAbsolutePath();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (transcoder != null) {
            transcoder.stopTranscoding();
        }
    }
}
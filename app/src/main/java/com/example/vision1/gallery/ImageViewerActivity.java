package com.example.vision1.gallery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.vision1.R;

import java.io.File;
import java.io.IOException;

public class ImageViewerActivity extends AppCompatActivity {

    private MediaPlayer mediaPlayer;
    private String audioFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        ImageView fullImageViewer = findViewById(R.id.full_image_viewer);
        ImageButton btnPlay = findViewById(R.id.btn_play);
        ImageButton btnPause = findViewById(R.id.btn_pause);
        ImageButton btnExit = findViewById(R.id.btn_exit);

        String imagePath = getIntent().getStringExtra("IMAGE_PATH");
        audioFilePath = getIntent().getStringExtra("AUDIO_PATH");

        if (imagePath != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            Bitmap rotatedBitmap = rotateImageIfRequired(bitmap, imagePath);
            fullImageViewer.setImageBitmap(rotatedBitmap);
        }

        // FIX: Use synchronous prepare() to instantly lock the audio into memory
        if (audioFilePath != null && new File(audioFilePath).exists()) {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(audioFilePath);
                mediaPlayer.prepare(); // Changed from prepareAsync()
            } catch (IOException e) {
                Log.e("ImageViewer", "Error setting up audio", e);
                mediaPlayer = null;
            }
        }

        btnPlay.setOnClickListener(v -> {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                Toast.makeText(this, "Playing Audio", Toast.LENGTH_SHORT).show();
            } else if (mediaPlayer == null) {
                Toast.makeText(this, "No Audio Available", Toast.LENGTH_SHORT).show();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        });

        btnExit.setOnClickListener(v -> finish());
    }

    private Bitmap rotateImageIfRequired(Bitmap img, String selectedImage) {
        try {
            ExifInterface ei = new ExifInterface(selectedImage);
            int orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return rotateImage(img, 90);
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return rotateImage(img, 180);
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return rotateImage(img, 270);
                default:
                    return img;
            }
        } catch (IOException e) {
            return img;
        }
    }

    private Bitmap rotateImage(Bitmap img, int degree) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        Bitmap rotatedImg = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
        img.recycle();
        return rotatedImg;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
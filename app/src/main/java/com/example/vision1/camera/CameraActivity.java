package com.example.vision1.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.vision1.gallery.ImageGalleryActivity;
import com.example.vision1.R;
import com.example.vision1.utils.TextToSpeechHelper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Executor executor;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    private TextRecognizer textRecognizer;
    private TextToSpeechHelper ttsHelper;
    private String extractedText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        executor = ContextCompat.getMainExecutor(this);
        previewView = findViewById(R.id.previewView);
        ImageButton captureButton = findViewById(R.id.captureButton);
        ImageButton switchButton = findViewById(R.id.switchCameraButton);
        ImageView thumbnailPreview = findViewById(R.id.thumbnailPreview);
        ImageButton playPauseButton = findViewById(R.id.playPauseButton);

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        ttsHelper = new TextToSpeechHelper(this);

        checkPermissionsAndStartCamera();

        captureButton.setOnClickListener(v -> takePhoto());
        switchButton.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });

        thumbnailPreview.setOnClickListener(v -> startActivity(new Intent(CameraActivity.this, ImageGalleryActivity.class)));

        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> {
                if (ttsHelper.isSpeaking()) {
                    ttsHelper.stop();
                } else if (ttsHelper.isInitialized() && !extractedText.isEmpty()) {
                    ttsHelper.speak(extractedText);
                } else {
                    Toast.makeText(this, "No text to speak", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void checkPermissionsAndStartCamera() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasCamera && Environment.isExternalStorageManager()) {
                startCamera();
            } else if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
            }
        } else {
            boolean hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (hasCamera && hasStorage) {
                startCamera();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            checkPermissionsAndStartCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

                Preview preview = new Preview.Builder().build();

                // FIX: Force High Resolution 50MP UHD Capture
                ResolutionSelector captureResolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();

                imageCapture = new ImageCapture.Builder()
                        .setResolutionSelector(captureResolutionSelector)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // Maximizes sensor output
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera: " + e.getMessage());
            }
        }, executor);
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Images");
        if (!folder.exists()) folder.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis());
        String fileName = "IMG_" + timeStamp + ".jpg";
        File photoFile = new File(folder, fileName);

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        extractedText = "";

        imageCapture.takePicture(options, executor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Photo saved!", Toast.LENGTH_SHORT).show());
                processImageForText(photoFile);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Error saving photo", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void processImageForText(File imageFile) {
        try {
            InputImage image = InputImage.fromFilePath(this, Uri.fromFile(imageFile));
            textRecognizer.process(image)
                    .addOnSuccessListener(text -> {
                        extractedText = text.getText();
                        if (!extractedText.isEmpty()) {
                            runOnUiThread(() -> {
                                Toast.makeText(CameraActivity.this, "Text recognized!", Toast.LENGTH_SHORT).show();
                                ttsHelper.speak(extractedText);
                            });

                            // FIX: Save audio immediately in the background without waiting for back button
                            saveTextAsAudioSilently(extractedText, imageFile.getAbsolutePath());

                        } else {
                            runOnUiThread(() -> Toast.makeText(CameraActivity.this, "No text found.", Toast.LENGTH_SHORT).show());
                        }
                    })
                    .addOnFailureListener(e -> {
                        runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Text recognition failed.", Toast.LENGTH_SHORT).show());
                    });
        } catch (IOException e) {
            runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Error processing image.", Toast.LENGTH_SHORT).show());
        }
    }

    // New completely silent background saver
    private void saveTextAsAudioSilently(String textToSave, String imageFilePath) {
        File imageFile = new File(imageFilePath);
        File audioFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Audio");
        if (!audioFolder.exists()) audioFolder.mkdirs();

        String baseName = imageFile.getName().substring(0, imageFile.getName().lastIndexOf('.'));
        String audioFileName = baseName.replace("IMG_", "AUDIO_") + ".mp3";
        File audioFile = new File(audioFolder, audioFileName);

        ttsHelper.saveToFile(textToSave, audioFile);
    }

    @Override
    public void onBackPressed() {
        releaseResourcesAndFinish();
    }

    private void releaseResourcesAndFinish() {
        if (textRecognizer != null) textRecognizer.close();
        if (ttsHelper != null) ttsHelper.shutdown();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (textRecognizer != null) textRecognizer.close();
        if (ttsHelper != null) ttsHelper.shutdown();
    }
}
package com.example.vision1;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IdentifyActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextView identifiedTextView;
    private ObjectOverlayView objectOverlayView;

    private static final String TAG = "Vision1MainActivity";
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private ExecutorService cameraExecutor;
    private ImageAnalysis imageAnalysis;
    private ObjectDetector objectDetector;
    private TextToSpeechHelper textToSpeechHelper;
    private String lastSpokenLabel = null;
    private long lastSpokenTime = 0;
    private static final long SPEECH_THROTTLE_INTERVAL = 2000; // Minimum 2 seconds between speech

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identify);

        previewView = findViewById(R.id.identifyPreviewView);
        identifiedTextView = findViewById(R.id.identifiedTextView);
        objectOverlayView = findViewById(R.id.objectOverlayView);

        cameraExecutor = Executors.newSingleThreadExecutor();
        textToSpeechHelper = new TextToSpeechHelper(this);

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Initialize the ObjectDetector
        try {
            objectDetector = new ObjectDetector(this, "yolo11n_float32.tflite", "your_labels.txt"); // Replace with your actual file names
            Log.i(TAG, "ObjectDetector initialized successfully.");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing ObjectDetector: " + e.getMessage());
            Toast.makeText(this, "Error loading object detection model", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview
                Preview preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // ImageAnalysis
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();

                imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy imageProxy) {
                        Log.d(TAG, "Analyzing frame");
                        Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
                        if (bitmap != null) {
                            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
                            // Correct for rotation
                            Bitmap rotatedBitmap = ImageUtils.rotateBitmap(bitmap, rotationDegrees);

                            // Run object detection
                            List<ObjectDetector.Detection> results = objectDetector.detect(rotatedBitmap);
                            objectOverlayView.setResults(results);

                            if (results != null && !results.isEmpty()) {
                                // Find the detection with the highest confidence
                                ObjectDetector.Detection bestResult = Collections.max(results, (d1, d2) -> Float.compare(d1.confidence, d2.confidence));
                                String currentLabel = bestResult.label;
                                float confidence = bestResult.confidence;
                                long currentTime = System.currentTimeMillis();

                                if (!currentLabel.equals(lastSpokenLabel) || (currentTime - lastSpokenTime >= SPEECH_THROTTLE_INTERVAL)) {
                                    Log.d(TAG, "Detected object: " + currentLabel + " with confidence: " + confidence);
                                    textToSpeechHelper.speak(currentLabel);
                                    lastSpokenLabel = currentLabel;
                                    lastSpokenTime = currentTime;
                                } else {
                                    Log.d(TAG, "Suppressing speech for: " + currentLabel + " (too soon).");
                                }
                            } else {
                                Log.d(TAG, "No objects detected in this frame.");
                                lastSpokenLabel = null; // Reset last spoken label when no object is detected
                            }
                        }
                        imageProxy.close();
                    }
                });

                // Select back camera as a default
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                try {
                    // Unbind use cases before rebinding
                    cameraProvider.unbindAll();

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(
                            IdentifyActivity.this,
                            cameraSelector,
                            preview,
                            imageAnalysis);
                    Log.i(TAG, "Camera started successfully.");

                } catch (Exception e) {
                    Log.e(TAG, "Use case binding failed: " + e.getMessage());
                }

            } catch (Exception e) {
                Log.e(TAG, "Error starting camera provider: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (textToSpeechHelper != null) {
            textToSpeechHelper.shutdown();
        }
    }
}
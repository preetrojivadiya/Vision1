package com.example.vision1;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IdentifyActivity extends AppCompatActivity {

    private enum Mode { DETECTION, IDENTIFY, SCENE }
    private Mode currentMode = Mode.DETECTION;

    private PreviewView previewView;
    private TextView identifiedTextView;
    private ObjectOverlayView objectOverlayView;
    private Button btnDetect, btnIdentify, btnScene;

    private static final String TAG = "Vision1IdentifyActivity";
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    private ExecutorService cameraExecutor;
    private ObjectDetector objectDetector;
    private TextRecognizer textRecognizer;
    private ImageLabeler imageLabeler;
    private TextToSpeechHelper textToSpeechHelper;

    private String lastSpokenLabel = null;
    private long lastSpokenTime = 0;
    private static final long SPEECH_THROTTLE_INTERVAL = 3000; // 3 seconds between speech

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identify);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide(); // Remove Vision text at the top
        }

        previewView = findViewById(R.id.identifyPreviewView);
        identifiedTextView = findViewById(R.id.identifiedTextView);
        objectOverlayView = findViewById(R.id.objectOverlayView);
        btnDetect = findViewById(R.id.btn_mode_detect);
        btnIdentify = findViewById(R.id.btn_mode_identify);
        btnScene = findViewById(R.id.btn_mode_scene);

        cameraExecutor = Executors.newSingleThreadExecutor();
        textToSpeechHelper = new TextToSpeechHelper(this);

        // Initialize ML Kit Models
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);

        setupButtons();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        try {
            objectDetector = new ObjectDetector(this, "yolo11n_float32.tflite", "your_labels.txt");
        } catch (IOException e) {
            Toast.makeText(this, "Error loading YOLO model", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupButtons() {
        btnDetect.setOnClickListener(v -> setMode(Mode.DETECTION));
        btnIdentify.setOnClickListener(v -> setMode(Mode.IDENTIFY));
        btnScene.setOnClickListener(v -> setMode(Mode.SCENE));
    }

    private void setMode(Mode mode) {
        currentMode = mode;

        // Reset UI colors
        btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));
        btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));
        btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));

        // Highlight active mode and clear overlays
        objectOverlayView.setResults(null);
        identifiedTextView.setText("Scanning...");

        if (mode == Mode.DETECTION) btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3f8efc")));
        if (mode == Mode.IDENTIFY) btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3f8efc")));
        if (mode == Mode.SCENE) btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3f8efc")));
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Request Highest Possible Display Quality for the Preview
                ResolutionSelector previewResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewResSelector)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Keep Analysis low-res (640) for real-time model speed
                ResolutionSelector analysisResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
                    if (bitmap != null) {
                        Bitmap rotatedBitmap = ImageUtils.rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());

                        if (currentMode == Mode.DETECTION) {
                            runObjectDetection(rotatedBitmap);
                        } else if (currentMode == Mode.IDENTIFY) {
                            runBrandIdentification(rotatedBitmap);
                        } else if (currentMode == Mode.SCENE) {
                            runSceneDescription(rotatedBitmap);
                        }
                    }
                    imageProxy.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Error starting camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // MODE 1: Standard YOLO Box Detection
    private void runObjectDetection(Bitmap bitmap) {
        List<ObjectDetector.Detection> results = objectDetector.detect(bitmap);
        objectOverlayView.setResults(results);

        if (results != null && !results.isEmpty()) {
            ObjectDetector.Detection bestResult = Collections.max(results, (d1, d2) -> Float.compare(d1.confidence, d2.confidence));

            runOnUiThread(() -> identifiedTextView.setText("Detected: " + bestResult.label));
            speakText(bestResult.label);
        } else {
            runOnUiThread(() -> identifiedTextView.setText("Scanning for objects..."));
        }
    }

    // MODE 2: Combines YOLO physical shape with ML Kit Text Reading (e.g. Sprite Bottle)
    private void runBrandIdentification(Bitmap bitmap) {
        // Run YOLO to get physical item
        List<ObjectDetector.Detection> yoloResults = objectDetector.detect(bitmap);
        objectOverlayView.setResults(yoloResults);

        String physicalObject = (yoloResults != null && !yoloResults.isEmpty()) ? yoloResults.get(0).label : "Item";

        // Read Text to identify specific brand/type
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(image).addOnSuccessListener(visionText -> {
            String readText = visionText.getText().replaceAll("\n", " ").trim();

            if (!readText.isEmpty()) {
                String finalText = physicalObject + " identified as: " + readText;
                runOnUiThread(() -> identifiedTextView.setText(finalText));
                speakText(finalText);
            } else {
                runOnUiThread(() -> identifiedTextView.setText("Looking for labels on " + physicalObject + "..."));
            }
        });
    }

    // MODE 3: Describes scenes like "Room, Furniture, Laptop" in natural language
    private void runSceneDescription(Bitmap bitmap) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpokenTime < SPEECH_THROTTLE_INTERVAL) return; // Prevent spamming ML Kit

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        imageLabeler.process(image).addOnSuccessListener(labels -> {
            if (labels.isEmpty()) return;

            StringBuilder sceneDesc = new StringBuilder("The scene contains ");
            int count = 0;

            for (ImageLabel label : labels) {
                if (label.getConfidence() > 0.65f) { // Only high confidence elements
                    if (count > 0) sceneDesc.append(", ");
                    sceneDesc.append(label.getText().toLowerCase());
                    count++;
                }
                if (count >= 4) break; // Limit to 4 items for readability
            }

            if (count > 0) {
                String finalSpeech = sceneDesc.toString();
                runOnUiThread(() -> identifiedTextView.setText(finalSpeech));
                speakText(finalSpeech);
            }
        });
    }

    private void speakText(String text) {
        long currentTime = System.currentTimeMillis();
        if (!text.equals(lastSpokenLabel) || (currentTime - lastSpokenTime >= SPEECH_THROTTLE_INTERVAL)) {
            textToSpeechHelper.speak(text);
            lastSpokenLabel = text;
            lastSpokenTime = currentTime;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (textToSpeechHelper != null) textToSpeechHelper.shutdown();
    }
}
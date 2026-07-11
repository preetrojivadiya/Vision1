// IdentifyActivity.java
package com.example.vision1.detection;

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
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.vision1.R;
import com.example.vision1.utils.ImageUtils;
import com.example.vision1.utils.TextToSpeechHelper;
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
    private static final long SPEECH_THROTTLE_INTERVAL = 3000;

    private int frameSkip = 0;
    private static final int FRAME_SKIP_COUNT = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identify);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        previewView = findViewById(R.id.identifyPreviewView);
        identifiedTextView = findViewById(R.id.identifiedTextView);
        objectOverlayView = findViewById(R.id.objectOverlayView);
        btnDetect = findViewById(R.id.btn_mode_detect);
        btnIdentify = findViewById(R.id.btn_mode_identify);
        btnScene = findViewById(R.id.btn_mode_scene);

        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        cameraExecutor = Executors.newSingleThreadExecutor();
        textToSpeechHelper = new TextToSpeechHelper(this);

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);

        setupButtons();

        try {
            objectDetector = new ObjectDetector(this, "yolo26s_float32.tflite", "your_labels.txt");
        } catch (IOException e) {
            Toast.makeText(this, "Error loading YOLO model", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Model load failed", e);
        }

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void setupButtons() {
        btnDetect.setOnClickListener(v -> setMode(Mode.DETECTION));
        btnIdentify.setOnClickListener(v -> setMode(Mode.IDENTIFY));
        btnScene.setOnClickListener(v -> setMode(Mode.SCENE));
    }

    private void setMode(Mode mode) {
        currentMode = mode;

        btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));
        btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));
        btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));

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
        if (objectDetector == null) {
            identifiedTextView.setText("Model not loaded");
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ResolutionSelector previewResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewResSelector)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ResolutionSelector analysisResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new ResolutionStrategy(
                                        new Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                        )
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    try {
                        frameSkip++;
                        if (frameSkip % (FRAME_SKIP_COUNT + 1) != 0) {
                            return;
                        }

                        Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
                        if (bitmap == null) return;

                        Bitmap rotatedBitmap = ImageUtils.rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
                        if (rotatedBitmap == null) return;

                        if (currentMode == Mode.DETECTION) {
                            runObjectDetection(rotatedBitmap);
                        } else if (currentMode == Mode.IDENTIFY) {
                            runBrandIdentification(rotatedBitmap);
                        } else if (currentMode == Mode.SCENE) {
                            runSceneDescription(rotatedBitmap);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Analyzer crashed", e);
                    } finally {
                        imageProxy.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void runObjectDetection(Bitmap bitmap) {
        if (objectDetector == null) return;

        List<ObjectDetector.Detection> results = objectDetector.detect(bitmap);
        objectOverlayView.setResults(results, bitmap.getWidth(), bitmap.getHeight());

        if (results != null && !results.isEmpty()) {
            ObjectDetector.Detection bestResult = Collections.max(results, (d1, d2) -> Float.compare(d1.confidence, d2.confidence));
            runOnUiThread(() -> identifiedTextView.setText("Detected: " + bestResult.label));
            speakText(bestResult.label);
        } else {
            runOnUiThread(() -> identifiedTextView.setText("Scanning for objects..."));
        }
    }

    private void runBrandIdentification(Bitmap bitmap) {
        if (objectDetector == null) return;

        List<ObjectDetector.Detection> yoloResults = objectDetector.detect(bitmap);
        objectOverlayView.setResults(yoloResults, bitmap.getWidth(), bitmap.getHeight());

        String physicalObject = (yoloResults != null && !yoloResults.isEmpty()) ? yoloResults.get(0).label : "Item";

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

    private void runSceneDescription(Bitmap bitmap) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSpokenTime < SPEECH_THROTTLE_INTERVAL) return;

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        imageLabeler.process(image).addOnSuccessListener(labels -> {
            if (labels.isEmpty()) return;

            StringBuilder sceneDesc = new StringBuilder("The scene contains ");
            int count = 0;

            for (ImageLabel label : labels) {
                if (label.getConfidence() > 0.65f) {
                    if (count > 0) sceneDesc.append(", ");
                    sceneDesc.append(label.getText().toLowerCase());
                    count++;
                }
                if (count >= 4) break;
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
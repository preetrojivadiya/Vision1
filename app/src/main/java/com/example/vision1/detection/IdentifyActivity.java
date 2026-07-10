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

import com.example.vision1.detection.ObjectDetector;
import com.example.vision1.R;
import com.example.vision1.utils.ImageUtils;
import com.example.vision1.utils.TextToSpeechHelper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IdentifyActivity extends AppCompatActivity {

    private enum Mode { DETECTION, IDENTIFY, SCENE }
    private VisionMode currentModeHandler;

    // Mode Instances
    private DetectionMode detectionMode;
    private IdentifierMode identifierMode;
    private SceneMode sceneMode;

    private PreviewView previewView;
    private TextView identifiedTextView;
    private ObjectOverlayView objectOverlayView;
    private Button btnDetect, btnIdentify, btnScene;

    private ExecutorService cameraExecutor;
    private ObjectDetector objectDetector;
    private TextRecognizer textRecognizer;
    private TextToSpeechHelper textToSpeechHelper;

    private String lastSpokenLabel = null;
    private long lastSpokenTime = 0;
    private static final long SPEECH_THROTTLE = 3000;

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

        cameraExecutor = Executors.newSingleThreadExecutor();
        textToSpeechHelper = new TextToSpeechHelper(this);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        detectionMode = new DetectionMode();
        identifierMode = new IdentifierMode();
        sceneMode = new SceneMode();

        setupButtons();
        setMode(Mode.DETECTION);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        try {
            // Update the model file name here if your actual tflite file is named differently
            objectDetector = new ObjectDetector(this, "yolov26s_float32.tflite", "your_labels.txt");
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
        if (currentModeHandler != null) currentModeHandler.reset();

        btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));
        btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));
        btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")));

        objectOverlayView.setResults(null);
        identifiedTextView.setText("Scanning...");

        if (mode == Mode.DETECTION) {
            currentModeHandler = detectionMode;
            btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3f8efc")));
        } else if (mode == Mode.IDENTIFY) {
            currentModeHandler = identifierMode;
            btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3f8efc")));
        } else if (mode == Mode.SCENE) {
            currentModeHandler = sceneMode;
            btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3f8efc")));
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ResolutionSelector previewResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build();
                Preview preview = new Preview.Builder().setResolutionSelector(previewResSelector).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ResolutionSelector analysisResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
                    if (bitmap != null && objectDetector != null) {
                        Bitmap rotatedBitmap = ImageUtils.rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
                        // Pass execution to the active mode!
                        currentModeHandler.process(rotatedBitmap, objectDetector, this);
                    }
                    imageProxy.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e("IdentifyActivity", "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera();
    }

    // --- Helper Methods EXPOSED for the Modes to Use ---

    public void updateUI(String text, List<ObjectDetector.Detection> boxes) {
        runOnUiThread(() -> {
            identifiedTextView.setText(text);
            if (boxes != null) objectOverlayView.setResults(boxes);
        });
    }

    public void speakText(String text) {
        long currentTime = System.currentTimeMillis();
        if (!text.equals(lastSpokenLabel) || (currentTime - lastSpokenTime >= SPEECH_THROTTLE)) {
            textToSpeechHelper.speak(text);
            lastSpokenLabel = text;
            lastSpokenTime = currentTime;
        }
    }

    public TextRecognizer getTextRecognizer() {
        return textRecognizer;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (textToSpeechHelper != null) textToSpeechHelper.shutdown();
    }
}
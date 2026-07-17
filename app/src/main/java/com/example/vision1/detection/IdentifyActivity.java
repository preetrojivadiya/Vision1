package com.example.vision1.detection;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.vision1.R;
import com.example.vision1.utils.TextToSpeechHelper;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IdentifyActivity extends AppCompatActivity implements VisionUiController {

    private static final String TAG = "Vision1IdentifyActivity";
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    private PreviewView previewView;
    private TextView identifiedTextView;
    private VisionOverlayView visionOverlayView;

    private Button btnDetect, btnIdentify, btnScene;
    private Button btnStartIdentifier, btnContinueIdentifier;
    private LinearLayout identifierControlPanel;

    private ExecutorService cameraExecutor;
    private TextToSpeechHelper textToSpeechHelper;

    private TextRecognizer textRecognizer;
    private VisionDependencies visionDependencies;
    private ObjectDetector objectDetector;

    private FrameProcessor frameProcessor;
    private CameraController cameraController;

    private VisionModeType currentModeType = VisionModeType.DETECTION;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_identify);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        previewView = findViewById(R.id.identifyPreviewView);
        identifiedTextView = findViewById(R.id.identifiedTextView);
        visionOverlayView = findViewById(R.id.visionOverlayView);

        btnDetect = findViewById(R.id.btn_mode_detect);
        btnIdentify = findViewById(R.id.btn_mode_identify);
        btnScene = findViewById(R.id.btn_mode_scene);

        btnStartIdentifier = findViewById(R.id.btn_start_identifier);
        btnContinueIdentifier = findViewById(R.id.btn_continue_identifier);
        identifierControlPanel = findViewById(R.id.identifier_control_panel);

        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        cameraExecutor = Executors.newSingleThreadExecutor();
        textToSpeechHelper = new TextToSpeechHelper(this);

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        setupModels();
        setupButtons();
        setMode(VisionModeType.DETECTION);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void setupModels() {
        try {
            objectDetector = new ObjectDetector(this, "yolo26s_float32.tflite", "your_labels.txt");

            visionDependencies = new VisionDependencies(
                    objectDetector,
                    textRecognizer,
                    ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            );

            frameProcessor = new FrameProcessor(visionDependencies, this);

            cameraController = new CameraController(
                    this,
                    previewView,
                    cameraExecutor,
                    frameProcessor
            );

        } catch (IOException e) {
            Toast.makeText(this, "Error loading YOLO model", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Model load failed", e);
        }
    }

    private void setupButtons() {
        btnDetect.setOnClickListener(v -> setMode(VisionModeType.DETECTION));
        btnIdentify.setOnClickListener(v -> setMode(VisionModeType.IDENTIFY));
        btnScene.setOnClickListener(v -> setMode(VisionModeType.SCENE));

        btnStartIdentifier.setOnClickListener(v -> {
            if (frameProcessor != null) {
                frameProcessor.startIdentifierSession();
                identifiedTextView.setText("Identifier started. Hold the object steady...");
            }
        });

        btnContinueIdentifier.setOnClickListener(v -> {
            if (frameProcessor != null) {
                frameProcessor.continueIdentifierSession();
                identifiedTextView.setText("Continue scanning. Hold the object steady...");
            }
        });
    }

    private void setMode(VisionModeType modeType) {
        currentModeType = modeType;

        if (frameProcessor != null) {
            frameProcessor.setMode(modeType);
        }

        highlightSelectedButton(modeType);

        if (modeType == VisionModeType.IDENTIFY) {
            identifierControlPanel.setVisibility(View.VISIBLE);
            identifiedTextView.setText("Press Start to begin identifier scan");
        } else {
            identifierControlPanel.setVisibility(View.GONE);
            identifiedTextView.setText("Scanning...");
        }

        visionOverlayView.clear();
    }

    private void highlightSelectedButton(VisionModeType modeType) {
        int active = android.graphics.Color.parseColor("#3f8efc");
        int inactive = android.graphics.Color.parseColor("#333333");

        btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactive));
        btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactive));
        btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(inactive));

        if (modeType == VisionModeType.DETECTION) {
            btnDetect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active));
        } else if (modeType == VisionModeType.IDENTIFY) {
            btnIdentify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active));
        } else if (modeType == VisionModeType.SCENE) {
            btnScene.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active));
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        if (cameraController == null || visionDependencies == null || visionDependencies.getObjectDetector() == null) {
            identifiedTextView.setText("Model not loaded");
            return;
        }
        cameraController.startCamera();
    }

    @Override
    public void updateUI(
            String text,
            List<ObjectDetector.Detection> results,
            List<TextAnnotation> textAnnotations,
            int sourceWidth,
            int sourceHeight
    ) {
        runOnUiThread(() -> {
            identifiedTextView.setText(text);

            boolean hasObjects = results != null && !results.isEmpty();
            boolean hasText = textAnnotations != null && !textAnnotations.isEmpty();

            if (hasObjects) {
                visionOverlayView.setObjectResults(results, sourceWidth, sourceHeight);
            } else {
                visionOverlayView.setObjectResults(null, sourceWidth, sourceHeight);
            }

            if (hasText) {
                visionOverlayView.setTextAnnotations(textAnnotations, sourceWidth, sourceHeight);
            } else {
                visionOverlayView.setTextAnnotations(null, sourceWidth, sourceHeight);
            }

            if (!hasObjects && !hasText) {
                visionOverlayView.clear();
            }
        });
    }

    @Override
    public void speakText(String text) {
        if (textToSpeechHelper != null) {
            textToSpeechHelper.speak(text);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (cameraController != null) {
            cameraController.stopCamera();
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        if (textToSpeechHelper != null) {
            textToSpeechHelper.shutdown();
        }

        if (objectDetector != null) {
            objectDetector.close();
        }
    }
}
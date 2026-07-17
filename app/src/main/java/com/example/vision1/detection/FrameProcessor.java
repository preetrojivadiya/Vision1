package com.example.vision1.detection;

import android.graphics.Bitmap;

public class FrameProcessor {

    private final VisionDependencies dependencies;
    private final VisionUiController uiController;

    private final DetectionMode detectionMode = new DetectionMode();
    private final IdentifierMode identifierMode = new IdentifierMode();
    private final SceneMode sceneMode = new SceneMode();

    private VisionMode currentMode = detectionMode;

    public FrameProcessor(VisionDependencies dependencies, VisionUiController uiController) {
        this.dependencies = dependencies;
        this.uiController = uiController;
    }

    public void setMode(VisionModeType type) {
        if (currentMode != null) {
            currentMode.reset();
        }

        switch (type) {
            case IDENTIFY:
                currentMode = identifierMode;
                break;
            case SCENE:
                currentMode = sceneMode;
                break;
            case DETECTION:
            default:
                currentMode = detectionMode;
                break;
        }
    }

    public void startIdentifierSession() {
        identifierMode.startSession();
    }

    public void continueIdentifierSession() {
        identifierMode.startSession();
    }

    public void resetIdentifierSession() {
        identifierMode.reset();
    }

    public void process(Bitmap bitmap) {
        if (bitmap == null || currentMode == null) return;
        currentMode.process(bitmap, dependencies, uiController);
    }

    public void reset() {
        if (currentMode != null) {
            currentMode.reset();
        }
    }
}
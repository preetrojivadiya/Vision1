package com.example.vision1.detection;

import android.graphics.Bitmap;

public class FrameProcessor {

    private VisionMode currentMode;
    private final VisionDependencies dependencies;
    private final VisionUiController uiController;

    public FrameProcessor(VisionDependencies dependencies, VisionUiController uiController) {
        this.dependencies = dependencies;
        this.uiController = uiController;
        this.currentMode = VisionModeFactory.create(VisionModeType.DETECTION);
    }

    public void setMode(VisionModeType type) {
        if (currentMode != null) {
            currentMode.reset();
        }
        currentMode = VisionModeFactory.create(type);
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
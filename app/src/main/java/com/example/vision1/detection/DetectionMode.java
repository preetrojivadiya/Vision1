package com.example.vision1.detection;

import android.graphics.Bitmap;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DetectionMode implements VisionMode {

    private static final long SPEAK_THROTTLE_MS = 2000;
    private static final int REQUIRED_STABLE_FRAMES = 2;
    private static final float MIN_SPEAK_CONFIDENCE = 0.60f;

    private String lastLabel = null;
    private int stableLabelCount = 0;
    private long lastSpeakTime = 0;

    @Override
    public void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController) {
        if (bitmap == null || dependencies == null || dependencies.getObjectDetector() == null) {
            uiController.updateUI("Model not ready", null, null, 0, 0);
            return;
        }

        List<ObjectDetector.Detection> results = dependencies.getObjectDetector().detect(bitmap);

        if (results == null || results.isEmpty()) {
            lastLabel = null;
            stableLabelCount = 0;
            uiController.updateUI("Scanning for objects...", null, null, 0, 0);
            return;
        }

        ObjectDetector.Detection bestResult = Collections.max(
                results,
                (d1, d2) -> Float.compare(d1.confidence, d2.confidence)
        );

        uiController.updateUI(
                "Detected: " + bestResult.label,
                results,
                null,
                bitmap.getWidth(),
                bitmap.getHeight()
        );

        String label = bestResult.label == null ? "" : bestResult.label.trim().toLowerCase(Locale.ROOT);

        if (bestResult.confidence < MIN_SPEAK_CONFIDENCE || label.isEmpty()) {
            stableLabelCount = 0;
            lastLabel = label;
            return;
        }

        if (label.equals(lastLabel)) {
            stableLabelCount++;
        } else {
            lastLabel = label;
            stableLabelCount = 1;
        }

        long now = System.currentTimeMillis();

        if (stableLabelCount >= REQUIRED_STABLE_FRAMES && now - lastSpeakTime >= SPEAK_THROTTLE_MS) {
            uiController.speakText(bestResult.label);
            lastSpeakTime = now;
        }
    }

    @Override
    public void reset() {
        lastLabel = null;
        stableLabelCount = 0;
        lastSpeakTime = 0;
    }
}
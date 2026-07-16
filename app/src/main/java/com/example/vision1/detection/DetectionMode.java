package com.example.vision1.detection;

import android.graphics.Bitmap;

import java.util.Collections;
import java.util.List;

public class DetectionMode implements VisionMode {

    @Override
    public void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController) {
        if (bitmap == null || dependencies == null || dependencies.getObjectDetector() == null) {
            uiController.updateUI("Model not ready", null, 0, 0);
            return;
        }

        List<ObjectDetector.Detection> results = dependencies.getObjectDetector().detect(bitmap);

        if (results != null && !results.isEmpty()) {
            ObjectDetector.Detection bestResult = Collections.max(
                    results,
                    (d1, d2) -> Float.compare(d1.confidence, d2.confidence)
            );

            uiController.updateUI("Detected: " + bestResult.label, results, bitmap.getWidth(), bitmap.getHeight());
            uiController.speakText(bestResult.label);
        } else {
            uiController.updateUI("Scanning for objects...", null, 0, 0);
        }
    }

    @Override
    public void reset() {
    }
}
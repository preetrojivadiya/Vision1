package com.example.vision1.detection;

import android.graphics.Bitmap;
import java.util.Collections;
import java.util.List;

public class DetectionMode implements VisionMode {

    @Override
    public void process(Bitmap bitmap, ObjectDetector detector, IdentifyActivity activity) {
        List<ObjectDetector.Detection> results = detector.detect(bitmap);

        if (results != null && !results.isEmpty()) {
            // Get the highest confidence object
            ObjectDetector.Detection best = Collections.max(results, (d1, d2) -> Float.compare(d1.confidence, d2.confidence));

            activity.updateUI("Detected: " + best.label, results);
            activity.speakText(best.label);
        } else {
            activity.updateUI("Scanning for objects...", null);
        }
    }

    @Override
    public void reset() {
        // No persistent state needed to reset
    }
}
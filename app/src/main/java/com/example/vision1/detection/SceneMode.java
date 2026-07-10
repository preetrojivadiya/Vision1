package com.example.vision1.detection;

import android.graphics.Bitmap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SceneMode implements VisionMode {

    private long lastSpeakTime = 0;
    private static final long SCENE_THROTTLE = 4000;

    @Override
    public void process(Bitmap bitmap, ObjectDetector detector, IdentifyActivity activity) {
        List<ObjectDetector.Detection> results = detector.detect(bitmap);

        if (results == null || results.isEmpty()) {
            activity.updateUI("Scanning scene...", null);
            return;
        }

        // Count occurrences of each object
        Map<String, Integer> counts = new HashMap<>();
        for (ObjectDetector.Detection d : results) {
            counts.put(d.label, counts.getOrDefault(d.label, 0) + 1);
        }

        // Build a natural human sentence
        StringBuilder sb = new StringBuilder("The scene contains ");
        int i = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append(entry.getValue()).append(" ").append(entry.getKey());
            if (entry.getValue() > 1) sb.append("s"); // Make plural

            if (i == counts.size() - 2) sb.append(", and ");
            else if (i < counts.size() - 2) sb.append(", ");
            i++;
        }

        String description = sb.toString();
        activity.updateUI(description, results);

        if (System.currentTimeMillis() - lastSpeakTime > SCENE_THROTTLE) {
            activity.speakText(description);
            lastSpeakTime = System.currentTimeMillis();
        }
    }

    @Override
    public void reset() {}
}
package com.example.vision1.detection;

import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;

import java.util.ArrayList;
import java.util.List;

public class SceneMode implements VisionMode {

    private long lastSpeakTime = 0;
    private static final long SCENE_THROTTLE = 4000;

    @Override
    public void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController) {
        if (bitmap == null || dependencies == null || dependencies.getImageLabeler() == null) {
            uiController.updateUI("Model not ready", null, null, 0, 0);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSpeakTime < SCENE_THROTTLE) {
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        dependencies.getImageLabeler().process(image)
                .addOnSuccessListener(labels -> {
                    if (labels == null || labels.isEmpty()) {
                        uiController.updateUI("Scanning scene...", null, null, 0, 0);
                        return;
                    }

                    List<String> picked = new ArrayList<>();
                    for (ImageLabel label : labels) {
                        if (label.getConfidence() >= 0.65f) {
                            picked.add(label.getText().toLowerCase());
                        }
                        if (picked.size() >= 4) break;
                    }

                    if (picked.isEmpty()) {
                        uiController.updateUI("Scanning scene...", null, null, 0, 0);
                        return;
                    }

                    String description = buildDescription(picked);
                    uiController.updateUI(description, null, null, 0, 0);
                    uiController.speakText(description);
                    lastSpeakTime = System.currentTimeMillis();
                })
                .addOnFailureListener(e -> uiController.updateUI("Scene analysis failed", null, null, 0, 0));
    }

    private String buildDescription(List<String> labels) {
        StringBuilder sb = new StringBuilder("The scene contains ");
        for (int i = 0; i < labels.size(); i++) {
            sb.append(labels.get(i));
            if (i < labels.size() - 2) {
                sb.append(", ");
            } else if (i == labels.size() - 2) {
                sb.append(", and ");
            }
        }
        return sb.toString();
    }

    @Override
    public void reset() {
        lastSpeakTime = 0;
    }
}
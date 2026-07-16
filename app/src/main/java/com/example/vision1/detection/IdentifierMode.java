package com.example.vision1.detection;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.google.mlkit.vision.common.InputImage;

import java.util.Collections;
import java.util.List;

public class IdentifierMode implements VisionMode {

    private boolean isProcessingOcr = false;
    private long lastOcrTime = 0;
    private static final long OCR_THROTTLE = 1500;

    @Override
    public void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController) {
        if (bitmap == null || dependencies == null
                || dependencies.getObjectDetector() == null
                || dependencies.getTextRecognizer() == null) {
            uiController.updateUI("Model not ready", null, 0, 0);
            return;
        }

        List<ObjectDetector.Detection> results = dependencies.getObjectDetector().detect(bitmap);

        if (results == null || results.isEmpty()) {
            uiController.updateUI("Looking for objects to identify...", null, 0, 0);
            return;
        }

        ObjectDetector.Detection bestResult = Collections.max(
                results,
                (d1, d2) -> Float.compare(d1.confidence, d2.confidence)
        );

        uiController.updateUI("Found " + bestResult.label + ", reading label...", results, bitmap.getWidth(), bitmap.getHeight());

        long now = System.currentTimeMillis();
        if (isProcessingOcr || (now - lastOcrTime) < OCR_THROTTLE) {
            return;
        }

        isProcessingOcr = true;
        lastOcrTime = now;

        RectF box = bestResult.boundingBox;

        int left = Math.max(0, (int) (box.left * bitmap.getWidth()));
        int top = Math.max(0, (int) (box.top * bitmap.getHeight()));
        int right = Math.min(bitmap.getWidth(), (int) (box.right * bitmap.getWidth()));
        int bottom = Math.min(bitmap.getHeight(), (int) (box.bottom * bitmap.getHeight()));

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) {
            isProcessingOcr = false;
            return;
        }

        Bitmap croppedObject;
        try {
            croppedObject = Bitmap.createBitmap(bitmap, left, top, width, height);
        } catch (Exception e) {
            isProcessingOcr = false;
            return;
        }

        InputImage image = InputImage.fromBitmap(croppedObject, 0);

        dependencies.getTextRecognizer().process(image)
                .addOnSuccessListener(visionText -> {
                    String readText = visionText.getText().replace("\n", " ").trim();

                    if (!readText.isEmpty()) {
                        String announcement = bestResult.label + " identified as: " + readText;
                        uiController.updateUI(announcement, results, bitmap.getWidth(), bitmap.getHeight());
                        uiController.speakText(announcement);
                    } else {
                        uiController.updateUI("No readable text found on " + bestResult.label, results, bitmap.getWidth(), bitmap.getHeight());
                    }

                    isProcessingOcr = false;
                })
                .addOnFailureListener(e -> {
                    isProcessingOcr = false;
                    uiController.updateUI("Text reading failed", results, bitmap.getWidth(), bitmap.getHeight());
                });
    }

    @Override
    public void reset() {
        isProcessingOcr = false;
        lastOcrTime = 0;
    }
}
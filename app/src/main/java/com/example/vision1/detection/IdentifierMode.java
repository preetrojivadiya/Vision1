package com.example.vision1.detection;

import android.graphics.Bitmap;
import android.graphics.RectF;
import com.google.mlkit.vision.common.InputImage;
import java.util.Collections;
import java.util.List;

public class IdentifierMode implements VisionMode {

    private boolean isProcessingOcr = false;
    private long lastOcrTime = 0;
    private static final long OCR_THROTTLE = 1500; // Wait 1.5s between OCR scans

    @Override
    public void process(Bitmap bitmap, ObjectDetector detector, IdentifyActivity activity) {
        List<ObjectDetector.Detection> results = detector.detect(bitmap);

        if (results == null || results.isEmpty()) {
            activity.updateUI("Looking for objects to identify...", null);
            return;
        }

        ObjectDetector.Detection best = Collections.max(results, (d1, d2) -> Float.compare(d1.confidence, d2.confidence));
        activity.updateUI("Found " + best.label + ", reading label...", results);

        // Throttle OCR to prevent crashing ML Kit with too many requests
        if (isProcessingOcr || System.currentTimeMillis() - lastOcrTime < OCR_THROTTLE) return;

        isProcessingOcr = true;
        lastOcrTime = System.currentTimeMillis();

        // MASSIVE OPTIMIZATION: Crop the image to exactly where the object is
        RectF box = best.boundingBox;
        int left = Math.max(0, (int)(box.left * bitmap.getWidth()));
        int top = Math.max(0, (int)(box.top * bitmap.getHeight()));
        int width = Math.min(bitmap.getWidth() - left, (int)(box.width() * bitmap.getWidth()));
        int height = Math.min(bitmap.getHeight() - top, (int)(box.height() * bitmap.getHeight()));

        if (width <= 0 || height <= 0) {
            isProcessingOcr = false;
            return;
        }

        Bitmap croppedObject = Bitmap.createBitmap(bitmap, left, top, width, height);
        InputImage image = InputImage.fromBitmap(croppedObject, 0);

        activity.getTextRecognizer().process(image).addOnSuccessListener(visionText -> {
            String text = visionText.getText().replaceAll("\n", " ").trim();
            if (!text.isEmpty()) {
                String announcement = best.label + " identified as: " + text;
                activity.updateUI(announcement, results);
                activity.speakText(announcement);
            }
            isProcessingOcr = false;
        }).addOnFailureListener(e -> isProcessingOcr = false);
    }

    @Override
    public void reset() {
        isProcessingOcr = false;
    }
}
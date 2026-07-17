package com.example.vision1.detection;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IdentifierMode implements VisionMode {

    private final StableFrameGate stableFrameGate = new StableFrameGate();

    private boolean sessionActive = false;
    private boolean ocrInProgress = false;

    private static final long OCR_THROTTLE_MS = 1200;
    private long lastOcrTime = 0;

    public void startSession() {
        sessionActive = true;
        ocrInProgress = false;
        lastOcrTime = 0;
        stableFrameGate.reset();
    }

    @Override
    public void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController) {
        if (!sessionActive) {
            uiController.updateUI("Press Start to begin identifier scan", null, null, 0, 0);
            return;
        }

        if (bitmap == null || dependencies == null
                || dependencies.getObjectDetector() == null
                || dependencies.getTextRecognizer() == null) {
            uiController.updateUI("Model not ready", null, null, 0, 0);
            return;
        }

        if (ocrInProgress) return;

        List<ObjectDetector.Detection> results = dependencies.getObjectDetector().detect(bitmap);
        if (results == null || results.isEmpty()) {
            uiController.updateUI("Hold the object steady...", null, null, 0, 0);
            return;
        }

        ObjectDetector.Detection bestResult = Collections.max(
                results,
                (d1, d2) -> Float.compare(d1.confidence, d2.confidence)
        );

        if (!stableFrameGate.isStable(bestResult)) {
            uiController.updateUI(
                    "Hold steady for a clear label...",
                    results,
                    null,
                    bitmap.getWidth(),
                    bitmap.getHeight()
            );
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastOcrTime < OCR_THROTTLE_MS) return;

        lastOcrTime = now;
        ocrInProgress = true;

        List<RectF> cropCandidates = new ArrayList<>();
        cropCandidates.add(expandBox(bestResult.boundingBox, 0.14f, 0.30f, 0.14f, 0.16f));
        cropCandidates.add(expandBox(bestResult.boundingBox, 0.24f, 0.45f, 0.20f, 0.24f));
        cropCandidates.add(expandBox(bestResult.boundingBox, 0.36f, 0.58f, 0.28f, 0.30f));

        runOcrAttempt(
                bitmap,
                cropCandidates,
                0,
                bestResult,
                results,
                dependencies,
                uiController
        );
    }

    private void runOcrAttempt(
            Bitmap sourceBitmap,
            List<RectF> cropCandidates,
            int index,
            ObjectDetector.Detection bestResult,
            List<ObjectDetector.Detection> detections,
            VisionDependencies dependencies,
            VisionUiController uiController
    ) {
        if (index >= cropCandidates.size()) {
            ocrInProgress = false;
            sessionActive = false;
            uiController.updateUI(
                    "Could not read the label clearly. Press Continue to try again.",
                    detections,
                    null,
                    sourceBitmap.getWidth(),
                    sourceBitmap.getHeight()
            );
            return;
        }

        RectF cropBox = cropCandidates.get(index);
        Bitmap crop = cropBitmapFromNormalizedBox(sourceBitmap, cropBox);

        if (crop == null) {
            runOcrAttempt(sourceBitmap, cropCandidates, index + 1, bestResult, detections, dependencies, uiController);
            return;
        }

        InputImage image = InputImage.fromBitmap(crop, 0);

        dependencies.getTextRecognizer().process(image)
                .addOnSuccessListener(visionText -> {
                    OcrExtraction extraction = OcrTextUtils.extract(
                            visionText,
                            cropBox,
                            crop.getWidth(),
                            crop.getHeight()
                    );

                    String message = extraction.summary.isEmpty()
                            ? "Reading label..."
                            : bestResult.label + " text candidate found";

                    uiController.updateUI(
                            message,
                            detections,
                            extraction.annotations,
                            sourceBitmap.getWidth(),
                            sourceBitmap.getHeight()
                    );

                    if (OcrTextUtils.isStrongSummary(extraction.summary)) {
                        String finalText = bestResult.label + " identified as " + extraction.summary;
                        uiController.updateUI(
                                finalText,
                                detections,
                                extraction.annotations,
                                sourceBitmap.getWidth(),
                                sourceBitmap.getHeight()
                        );
                        uiController.speakText(finalText);
                        sessionActive = false;
                        ocrInProgress = false;
                    } else {
                        runOcrAttempt(
                                sourceBitmap,
                                cropCandidates,
                                index + 1,
                                bestResult,
                                detections,
                                dependencies,
                                uiController
                        );
                    }
                })
                .addOnFailureListener(e ->
                        runOcrAttempt(sourceBitmap, cropCandidates, index + 1, bestResult, detections, dependencies, uiController)
                );
    }

    private RectF expandBox(RectF box, float leftPad, float topPad, float rightPad, float bottomPad) {
        if (box == null) return new RectF(0f, 0f, 1f, 1f);

        float left = clampFloat(box.left - leftPad, 0f, 1f);
        float top = clampFloat(box.top - topPad, 0f, 1f);
        float right = clampFloat(box.right + rightPad, 0f, 1f);
        float bottom = clampFloat(box.bottom + bottomPad, 0f, 1f);

        return new RectF(left, top, right, bottom);
    }

    private Bitmap cropBitmapFromNormalizedBox(Bitmap bitmap, RectF box) {
        if (bitmap == null || box == null) return null;

        int left = clamp((int) (box.left * bitmap.getWidth()), 0, bitmap.getWidth() - 1);
        int top = clamp((int) (box.top * bitmap.getHeight()), 0, bitmap.getHeight() - 1);
        int right = clamp((int) (box.right * bitmap.getWidth()), left + 1, bitmap.getWidth());
        int bottom = clamp((int) (box.bottom * bitmap.getHeight()), top + 1, bitmap.getHeight());

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) return null;

        try {
            return Bitmap.createBitmap(bitmap, left, top, width, height);
        } catch (Exception e) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void reset() {
        sessionActive = false;
        ocrInProgress = false;
        lastOcrTime = 0;
        stableFrameGate.reset();
    }
}
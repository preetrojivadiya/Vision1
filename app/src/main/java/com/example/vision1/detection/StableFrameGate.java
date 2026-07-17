package com.example.vision1.detection;

import android.graphics.RectF;

public class StableFrameGate {

    private static final int REQUIRED_STABLE_FRAMES = 3;
    private static final float MIN_CONFIDENCE = 0.55f;
    private static final float MIN_BOX_AREA = 0.04f;
    private static final float IOU_THRESHOLD = 0.75f;

    private String lastLabel = null;
    private RectF lastBox = null;
    private int stableCount = 0;

    public boolean isStable(ObjectDetector.Detection detection) {
        if (detection == null || detection.boundingBox == null) {
            reset();
            return false;
        }

        if (detection.confidence < MIN_CONFIDENCE) {
            stableCount = 0;
            lastLabel = detection.label;
            lastBox = new RectF(detection.boundingBox);
            return false;
        }

        if (lastLabel != null
                && lastLabel.equalsIgnoreCase(detection.label)
                && lastBox != null) {

            float iou = calculateIoU(lastBox, detection.boundingBox);
            if (iou >= IOU_THRESHOLD) {
                stableCount++;
            } else {
                stableCount = 1;
            }
        } else {
            stableCount = 1;
        }

        lastLabel = detection.label;
        lastBox = new RectF(detection.boundingBox);

        return stableCount >= REQUIRED_STABLE_FRAMES && area(detection.boundingBox) >= MIN_BOX_AREA;
    }

    public void reset() {
        lastLabel = null;
        lastBox = null;
        stableCount = 0;
    }

    private float area(RectF box) {
        if (box == null) return 0f;
        return Math.max(0f, box.width()) * Math.max(0f, box.height());
    }

    private float calculateIoU(RectF rect1, RectF rect2) {
        RectF r1 = new RectF(
                Math.min(rect1.left, rect1.right),
                Math.min(rect1.top, rect1.bottom),
                Math.max(rect1.left, rect1.right),
                Math.max(rect1.top, rect1.bottom)
        );
        RectF r2 = new RectF(
                Math.min(rect2.left, rect2.right),
                Math.min(rect2.top, rect2.bottom),
                Math.max(rect2.left, rect2.right),
                Math.max(rect2.top, rect2.bottom)
        );

        float intersectionLeft = Math.max(r1.left, r2.left);
        float intersectionTop = Math.max(r1.top, r2.top);
        float intersectionRight = Math.min(r1.right, r2.right);
        float intersectionBottom = Math.min(r1.bottom, r2.bottom);

        float intersectionArea = Math.max(0, intersectionRight - intersectionLeft)
                * Math.max(0, intersectionBottom - intersectionTop);

        float area1 = (r1.right - r1.left) * (r1.bottom - r1.top);
        float area2 = (r2.right - r2.left) * (r2.bottom - r2.top);
        float unionArea = area1 + area2 - intersectionArea;

        if (unionArea <= 0) return 0f;
        return intersectionArea / unionArea;
    }
}
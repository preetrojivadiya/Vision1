package com.example.vision1.detection;

import android.graphics.Bitmap;

public interface VisionMode {
    void process(Bitmap bitmap, ObjectDetector detector, IdentifyActivity activity);
    void reset();
}
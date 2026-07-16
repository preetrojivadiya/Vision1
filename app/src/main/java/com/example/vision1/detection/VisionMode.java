package com.example.vision1.detection;

import android.graphics.Bitmap;

public interface VisionMode {
    void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController);
    void reset();
}
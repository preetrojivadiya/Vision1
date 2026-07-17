package com.example.vision1.detection;

import java.util.List;

public interface VisionUiController {
    void updateUI(
            String text,
            List<ObjectDetector.Detection> results,
            List<TextAnnotation> textAnnotations,
            int sourceWidth,
            int sourceHeight
    );

    void speakText(String text);
}
package com.example.vision1.detection;

import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.text.TextRecognizer;

public class VisionDependencies {

    private final ObjectDetector objectDetector;
    private final TextRecognizer textRecognizer;
    private final ImageLabeler imageLabeler;

    public VisionDependencies(ObjectDetector objectDetector,
                              TextRecognizer textRecognizer,
                              ImageLabeler imageLabeler) {
        this.objectDetector = objectDetector;
        this.textRecognizer = textRecognizer;
        this.imageLabeler = imageLabeler;
    }

    public ObjectDetector getObjectDetector() {
        return objectDetector;
    }

    public TextRecognizer getTextRecognizer() {
        return textRecognizer;
    }

    public ImageLabeler getImageLabeler() {
        return imageLabeler;
    }
}
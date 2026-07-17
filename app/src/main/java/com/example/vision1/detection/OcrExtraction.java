package com.example.vision1.detection;

import java.util.List;

public class OcrExtraction {
    public final String summary;
    public final List<TextAnnotation> annotations;

    public OcrExtraction(String summary, List<TextAnnotation> annotations) {
        this.summary = summary;
        this.annotations = annotations;
    }
}
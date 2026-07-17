package com.example.vision1.detection;

import android.graphics.RectF;

public class TextAnnotation {
    public final RectF boundingBox;
    public final String text;

    public TextAnnotation(RectF boundingBox, String text) {
        this.boundingBox = boundingBox;
        this.text = text;
    }
}
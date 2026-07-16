package com.example.vision1.detection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class ObjectOverlayView extends View {

    private List<ObjectDetector.Detection> results;
    private final Paint boxPaint;
    private final Paint textPaint;

    private int sourceWidth = 0;
    private int sourceHeight = 0;

    public ObjectOverlayView(Context context) {
        super(context);
        boxPaint = new Paint();
        textPaint = new Paint();
        init();
    }

    public ObjectOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        boxPaint = new Paint();
        textPaint = new Paint();
        init();
    }

    private void init() {
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4.0f);
        boxPaint.setAntiAlias(true);

        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(48.0f);
        textPaint.setAntiAlias(true);
    }

    public void setResults(List<ObjectDetector.Detection> results, int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;

        if (results == null) {
            this.results = null;
        } else {
            this.results = new ArrayList<>(results);
        }
        postInvalidateOnAnimation();
    }

    public void setResults(List<ObjectDetector.Detection> results) {
        setResults(results, 0, 0);
    }

    public void clear() {
        results = null;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (results == null || results.isEmpty()) return;

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            sourceWidth = getWidth();
            sourceHeight = getHeight();
        }

        float viewW = getWidth();
        float viewH = getHeight();

        RectF contentRect = getFitCenterContentRect(viewW, viewH, sourceWidth, sourceHeight);

        for (ObjectDetector.Detection result : results) {
            RectF normalizedBox = result.boundingBox;

            float left = contentRect.left + normalizedBox.left * contentRect.width();
            float top = contentRect.top + normalizedBox.top * contentRect.height();
            float right = contentRect.left + normalizedBox.right * contentRect.width();
            float bottom = contentRect.top + normalizedBox.bottom * contentRect.height();

            RectF scaledBox = new RectF(left, top, right, bottom);
            canvas.drawRect(scaledBox, boxPaint);

            String text = result.label + " (" + String.format("%.2f", result.confidence) + ")";
            float textY = Math.max(textPaint.getTextSize(), scaledBox.top - 10f);
            canvas.drawText(text, scaledBox.left, textY, textPaint);
        }
    }

    private RectF getFitCenterContentRect(float viewW, float viewH, int srcW, int srcH) {
        float viewAspect = viewW / viewH;
        float srcAspect = (float) srcW / (float) srcH;

        if (srcAspect > viewAspect) {
            float scaledH = viewW / srcAspect;
            float top = (viewH - scaledH) / 2f;
            return new RectF(0f, top, viewW, top + scaledH);
        } else {
            float scaledW = viewH * srcAspect;
            float left = (viewW - scaledW) / 2f;
            return new RectF(left, 0f, left + scaledW, viewH);
        }
    }
}
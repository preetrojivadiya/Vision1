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

public class VisionOverlayView extends View {

    private List<ObjectDetector.Detection> objectResults = new ArrayList<>();
    private List<TextAnnotation> textAnnotations = new ArrayList<>();

    private int sourceWidth = 0;
    private int sourceHeight = 0;

    private final Paint objectBoxPaint = new Paint();
    private final Paint objectTextPaint = new Paint();

    private final Paint textBoxPaint = new Paint();
    private final Paint textFillPaint = new Paint();
    private final Paint textLabelPaint = new Paint();

    public VisionOverlayView(Context context) {
        super(context);
        init();
    }

    public VisionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        objectBoxPaint.setColor(Color.GREEN);
        objectBoxPaint.setStyle(Paint.Style.STROKE);
        objectBoxPaint.setStrokeWidth(4f);
        objectBoxPaint.setAntiAlias(true);

        objectTextPaint.setColor(Color.WHITE);
        objectTextPaint.setStyle(Paint.Style.FILL);
        objectTextPaint.setTextSize(42f);
        objectTextPaint.setAntiAlias(true);

        textBoxPaint.setColor(Color.YELLOW);
        textBoxPaint.setStyle(Paint.Style.STROKE);
        textBoxPaint.setStrokeWidth(4f);
        textBoxPaint.setAntiAlias(true);

        textFillPaint.setColor(0x66FFFF00);
        textFillPaint.setStyle(Paint.Style.FILL);
        textFillPaint.setAntiAlias(true);

        textLabelPaint.setColor(Color.BLACK);
        textLabelPaint.setStyle(Paint.Style.FILL);
        textLabelPaint.setTextSize(34f);
        textLabelPaint.setAntiAlias(true);
    }

    public void setObjectResults(List<ObjectDetector.Detection> results, int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.objectResults = results == null ? new ArrayList<>() : new ArrayList<>(results);
        postInvalidateOnAnimation();
    }

    public void setTextAnnotations(List<TextAnnotation> annotations, int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.textAnnotations = annotations == null ? new ArrayList<>() : new ArrayList<>(annotations);
        postInvalidateOnAnimation();
    }

    public void clear() {
        objectResults.clear();
        textAnnotations.clear();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float viewW = getWidth();
        float viewH = getHeight();

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            sourceWidth = getWidth();
            sourceHeight = getHeight();
        }

        RectF contentRect = getFitCenterContentRect(viewW, viewH, sourceWidth, sourceHeight);

        drawObjects(canvas, contentRect);
        drawTextAnnotations(canvas, contentRect);
    }

    private void drawObjects(Canvas canvas, RectF contentRect) {
        if (objectResults == null || objectResults.isEmpty()) return;

        for (ObjectDetector.Detection result : objectResults) {
            RectF box = result.boundingBox;

            float left = contentRect.left + box.left * contentRect.width();
            float top = contentRect.top + box.top * contentRect.height();
            float right = contentRect.left + box.right * contentRect.width();
            float bottom = contentRect.top + box.bottom * contentRect.height();

            RectF scaled = new RectF(left, top, right, bottom);
            canvas.drawRect(scaled, objectBoxPaint);

            String label = result.label + " (" + String.format("%.2f", result.confidence) + ")";
            float textY = Math.max(objectTextPaint.getTextSize(), scaled.top - 10f);
            canvas.drawText(label, scaled.left, textY, objectTextPaint);
        }
    }

    private void drawTextAnnotations(Canvas canvas, RectF contentRect) {
        if (textAnnotations == null || textAnnotations.isEmpty()) return;

        for (TextAnnotation annotation : textAnnotations) {
            RectF box = annotation.boundingBox;

            float left = contentRect.left + box.left * contentRect.width();
            float top = contentRect.top + box.top * contentRect.height();
            float right = contentRect.left + box.right * contentRect.width();
            float bottom = contentRect.top + box.bottom * contentRect.height();

            RectF scaled = new RectF(left, top, right, bottom);

            canvas.drawRect(scaled, textFillPaint);
            canvas.drawRect(scaled, textBoxPaint);

            float labelX = scaled.left;
            float labelY = Math.max(textLabelPaint.getTextSize(), scaled.top - 8f);

            canvas.drawText(annotation.text, labelX, labelY, textLabelPaint);
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
package com.example.vision1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.List;

public class ObjectOverlayView extends View {

    private List<ObjectDetector.Detection> results;
    private Paint boxPaint;
    private Paint textPaint;

    public ObjectOverlayView(Context context) {
        super(context);
        init();
    }

    public ObjectOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4.0f);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(48.0f);
    }

    public void setResults(List<ObjectDetector.Detection> results) {
        this.results = results;
        invalidate(); // Trigger redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Log.d("OverlayView", "Canvas width" + canvas.getWidth() + "canvas height" + canvas.getHeight());
        if (results != null) {
            for (ObjectDetector.Detection result : results) {
                RectF normalizedBox = result.boundingBox;
                String label = result.label;
                float confidence = result.confidence;

                Log.d("OverlayView","Processing Detection"+label+",Normalized BBox["+normalizedBox.left+","+normalizedBox.top+","+normalizedBox.right+","+normalizedBox.bottom+"]");

                float left = normalizedBox.left * getWidth();
                float top = normalizedBox.top * getHeight();
                float right = normalizedBox.right * getWidth();
                float bottom = normalizedBox.bottom * getHeight();

                RectF scaledBox = new RectF(left, top, right, bottom);
                Log.d("OverlayView", "Scaled BBox: left=" + scaledBox.left + ", top=" + scaledBox.top + ", right=" + scaledBox.right + ", bottom=" + scaledBox.bottom);

                // Draw bounding box
                canvas.drawRect(scaledBox, boxPaint);

                // Draw label and confidence
                String text = label + " (" + String.format("%.2f", confidence) + ")";
                canvas.drawText(text, scaledBox.left, scaledBox.bottom - textPaint.getTextSize(), textPaint);
            }
        }
    }
}
package com.example.vision1.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjectDetector {

    private static final String TAG = "Vision1ObjectDetector";
    private Interpreter tfliteInterpreter;
    private List<String> labels;
    private int inputImageWidth = 640;
    private int inputImageHeight = 640;

    // Thresholds
    private static final float CONFIDENCE_THRESHOLD = 0.3f;
    private static final float NMS_THRESHOLD = 0.4f;
    private static final int NUM_DETECTIONS_TO_RETURN = 4;

    public static class Detection {
        public final RectF boundingBox;
        public final String label;
        public final float confidence;

        public Detection(RectF boundingBox, String label, float confidence) {
            this.boundingBox = boundingBox;
            this.label = label;
            this.confidence = confidence;
        }
    }

    public ObjectDetector(Context context, String modelFileName, String labelFileName) throws IOException {
        tfliteInterpreter = new Interpreter(FileUtil.loadMappedFile(context, modelFileName));
        labels = FileUtil.loadLabels(context, labelFileName);
        Log.i(TAG, "TFLite model loaded successfully. Output Shape: " + java.util.Arrays.toString(tfliteInterpreter.getOutputTensor(0).shape()));
    }

    private static float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    private float calculateIoU(RectF rect1, RectF rect2) {
        RectF r1 = new RectF(Math.min(rect1.left, rect1.right), Math.min(rect1.top, rect1.bottom), Math.max(rect1.left, rect1.right), Math.max(rect1.top, rect1.bottom));
        RectF r2 = new RectF(Math.min(rect2.left, rect2.right), Math.min(rect2.top, rect2.bottom), Math.max(rect2.left, rect2.right), Math.max(rect2.top, rect2.bottom));

        float intersectionLeft = Math.max(r1.left, r2.left);
        float intersectionTop = Math.max(r1.top, r2.top);
        float intersectionRight = Math.min(r1.right, r2.right);
        float intersectionBottom = Math.min(r1.bottom, r2.bottom);

        float intersectionArea = Math.max(0, intersectionRight - intersectionLeft) * Math.max(0, intersectionBottom - intersectionTop);
        float area1 = (r1.right - r1.left) * (r1.bottom - r1.top);
        float area2 = (r2.right - r2.left) * (r2.bottom - r2.top);
        float unionArea = area1 + area2 - intersectionArea;

        if (unionArea <= 0) return 0;
        return intersectionArea / unionArea;
    }

    public List<Detection> detect(Bitmap bitmap) {
        if (tfliteInterpreter == null) return null;

        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true);
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resizedBitmap);

        // Dynamically get the shape of the loaded model (e.g. [1, 300, 6] for YOLOv26s)
        int[] shape = tfliteInterpreter.getOutputTensor(0).shape();

        // Dynamically allocate the output buffer so it never crashes!
        float[][][] rawOutput = new float[shape[0]][shape[1]][shape[2]];
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, rawOutput);

        tfliteInterpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputMap);
        List<Detection> detections = new ArrayList<>();

        boolean isEndToEndYolo26 = (shape.length == 3 && shape[2] == 6);

        if (isEndToEndYolo26) {
            // FIX: Parsing logic specifically for YOLOv26s [1, 300, 6]
            int numDetections = shape[1];
            for (int i = 0; i < numDetections; i++) {
                float x1 = rawOutput[0][i][0];
                float y1 = rawOutput[0][i][1];
                float x2 = rawOutput[0][i][2];
                float y2 = rawOutput[0][i][3];
                float conf = rawOutput[0][i][4];
                int classId = (int) rawOutput[0][i][5];

                if (conf > CONFIDENCE_THRESHOLD && classId >= 0 && classId < labels.size()) {
                    // YOLOv26s usually outputs absolute pixels (0-640), not percentages.
                    float scaleX = (x2 > 2.0f) ? inputImageWidth : 1.0f;
                    float scaleY = (y2 > 2.0f) ? inputImageHeight : 1.0f;

                    float nx1 = Math.max(0, x1 / scaleX);
                    float ny1 = Math.max(0, y1 / scaleY);
                    float nx2 = Math.min(1, x2 / scaleX);
                    float ny2 = Math.min(1, y2 / scaleY);

                    detections.add(new Detection(new RectF(nx1, ny1, nx2, ny2), labels.get(classId), conf));
                }
            }
            // YOLOv26s doesn't need NMS, so we return immediately!
            return detections;

        } else {
            // LEGACY LOGIC for [1, 84, 8400] models
            int NUM_BOXES = shape[2];
            int NUM_CLASSES = shape[1] - 4;
            int CLASS_PROB_START_INDEX = 4;

            for (int i = 0; i < NUM_BOXES; ++i) {
                float rawCenterX = rawOutput[0][0][i];
                float rawCenterY = rawOutput[0][1][i];
                float rawWidth = rawOutput[0][2][i];
                float rawHeight = rawOutput[0][3][i];

                float maxClassProbability = 0.0f;
                int bestClassIndex = -1;
                for (int j = 0; j < NUM_CLASSES; ++j) {
                    float classProbability = sigmoid(rawOutput[0][CLASS_PROB_START_INDEX + j][i]);
                    if (classProbability > maxClassProbability) {
                        maxClassProbability = classProbability;
                        bestClassIndex = j;
                    }
                }

                if (maxClassProbability > CONFIDENCE_THRESHOLD) {
                    if (bestClassIndex != -1 && labels != null && bestClassIndex < labels.size()) {
                        String label = labels.get(bestClassIndex);

                        float pixelCenterX = rawCenterX * inputImageWidth;
                        float pixelCenterY = rawCenterY * inputImageHeight;
                        float pixelWidth = rawWidth * inputImageWidth;
                        float pixelHeight = rawHeight * inputImageHeight;

                        float pixelX1 = (pixelCenterX - pixelWidth / 2.0f);
                        float pixelY1 = (pixelCenterY - pixelHeight / 2.0f);
                        float pixelX2 = (pixelCenterX + pixelWidth / 2.0f);
                        float pixelY2 = (pixelCenterY + pixelHeight / 2.0f);

                        float normalizedX1 = Math.max(0f, pixelX1 / inputImageWidth);
                        float normalizedY1 = Math.max(0f, pixelY1 / inputImageHeight);
                        float normalizedX2 = Math.min(1f, pixelX2 / inputImageWidth);
                        float normalizedY2 = Math.min(1f, pixelY2 / inputImageHeight);

                        RectF boundingBox = new RectF(normalizedX1, normalizedY1, normalizedX2, normalizedY2);
                        detections.add(new Detection(boundingBox, label, maxClassProbability));
                    }
                }
            }

            Collections.sort(detections, (d1, d2) -> Float.compare(d2.confidence, d1.confidence));
            List<Detection> finalDetections = new ArrayList<>();
            boolean[] removed = new boolean[detections.size()];

            for (int i = 0; i < detections.size(); ++i) {
                if (removed[i]) continue;
                Detection currentDetection = detections.get(i);
                finalDetections.add(currentDetection);

                for (int j = i + 1; j < detections.size(); ++j) {
                    if (removed[j]) continue;
                    Detection otherDetection = detections.get(j);
                    float iou = calculateIoU(currentDetection.boundingBox, otherDetection.boundingBox);
                    if (iou > NMS_THRESHOLD) {
                        removed[j] = true;
                    }
                }
            }
            return finalDetections.subList(0, Math.min(finalDetections.size(), NUM_DETECTIONS_TO_RETURN));
        }
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * inputImageWidth * inputImageHeight * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[inputImageWidth * inputImageHeight];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < inputImageHeight; ++i) {
            for (int j = 0; j < inputImageWidth; ++j) {
                final int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val & 0xFF)) / 255.0f);
            }
        }
        byteBuffer.rewind();
        return byteBuffer;
    }
}
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

    private static final String TAG = "Vision1Yolo26";
    private Interpreter tfliteInterpreter;
    private List<String> labels;
    private final int inputImageWidth = 640;
    private final int inputImageHeight = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float NMS_THRESHOLD = 0.4f;

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
    }

    public List<Detection> detect(Bitmap bitmap) {
        if (tfliteInterpreter == null) return null;

        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true);
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resizedBitmap);

        // Advanced Architecture: Dynamically detect YOLOv26s End-To-End (NMS-Free) vs Legacy YOLO
        int[] shape = tfliteInterpreter.getOutputTensor(0).shape();
        boolean isEndToEndYolo26 = (shape.length == 3 && shape[2] == 6);

        // Allocate buffer dynamically based on model architecture
        float[][][] outputBuffer = new float[shape[0]][shape[1]][shape[2]];
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputBuffer);

        tfliteInterpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputMap);
        List<Detection> detections = new ArrayList<>();

        if (isEndToEndYolo26) {
            // YOLOv26s NMS-Free Parsing
            int numDetections = shape[1];
            for (int i = 0; i < numDetections; i++) {
                float x1 = outputBuffer[0][i][0];
                float y1 = outputBuffer[0][i][1];
                float x2 = outputBuffer[0][i][2];
                float y2 = outputBuffer[0][i][3];
                float conf = outputBuffer[0][i][4];
                int classId = (int) outputBuffer[0][i][5];

                if (conf > CONFIDENCE_THRESHOLD && classId >= 0 && classId < labels.size()) {
                    float nx1 = Math.max(0, x1 / inputImageWidth);
                    float ny1 = Math.max(0, y1 / inputImageHeight);
                    float nx2 = Math.min(1, x2 / inputImageWidth);
                    float ny2 = Math.min(1, y2 / inputImageHeight);
                    detections.add(new Detection(new RectF(nx1, ny1, nx2, ny2), labels.get(classId), conf));
                }
            }
            return detections; // NMS not needed for YOLOv26s

        } else {
            // Legacy YOLO parsing
            int numBoxes = shape[2];
            int numClasses = shape[1] - 4;

            for (int i = 0; i < numBoxes; ++i) {
                float rawCenterX = outputBuffer[0][0][i];
                float rawCenterY = outputBuffer[0][1][i];
                float rawWidth = outputBuffer[0][2][i];
                float rawHeight = outputBuffer[0][3][i];

                float maxClassProbability = 0.0f;
                int bestClassIndex = -1;
                for (int j = 0; j < numClasses; ++j) {
                    float classProbability = 1.0f / (1.0f + (float) Math.exp(-outputBuffer[0][4 + j][i])); // Sigmoid
                    if (classProbability > maxClassProbability) {
                        maxClassProbability = classProbability;
                        bestClassIndex = j;
                    }
                }

                if (maxClassProbability > CONFIDENCE_THRESHOLD && bestClassIndex != -1) {
                    float pixelX1 = (rawCenterX - rawWidth / 2.0f) * inputImageWidth;
                    float pixelY1 = (rawCenterY - rawHeight / 2.0f) * inputImageHeight;
                    float pixelX2 = (rawCenterX + rawWidth / 2.0f) * inputImageWidth;
                    float pixelY2 = (rawCenterY + rawHeight / 2.0f) * inputImageHeight;

                    RectF boundingBox = new RectF(
                            Math.max(0f, pixelX1 / inputImageWidth),
                            Math.max(0f, pixelY1 / inputImageHeight),
                            Math.min(1f, pixelX2 / inputImageWidth),
                            Math.min(1f, pixelY2 / inputImageHeight)
                    );
                    detections.add(new Detection(boundingBox, labels.get(bestClassIndex), maxClassProbability));
                }
            }
            return applyNMS(detections); // Run NMS for old models
        }
    }

    private List<Detection> applyNMS(List<Detection> detections) {
        Collections.sort(detections, (d1, d2) -> Float.compare(d2.confidence, d1.confidence));
        List<Detection> finalDetections = new ArrayList<>();
        boolean[] removed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); ++i) {
            if (removed[i]) continue;
            finalDetections.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); ++j) {
                if (removed[j]) continue;
                if (calculateIoU(detections.get(i).boundingBox, detections.get(j).boundingBox) > NMS_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }
        return finalDetections.subList(0, Math.min(finalDetections.size(), 4));
    }

    private float calculateIoU(RectF r1, RectF r2) {
        float intersectionArea = Math.max(0, Math.min(r1.right, r2.right) - Math.max(r1.left, r2.left)) *
                Math.max(0, Math.min(r1.bottom, r2.bottom) - Math.max(r1.top, r2.top));
        float unionArea = (r1.right - r1.left) * (r1.bottom - r1.top) + (r2.right - r2.left) * (r2.bottom - r2.top) - intersectionArea;
        return unionArea <= 0 ? 0 : intersectionArea / unionArea;
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
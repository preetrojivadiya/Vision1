// ObjectDetector.java
package com.example.vision1.detection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjectDetector {

    private static final String TAG = "Vision1ObjectDetector";

    private final Interpreter tfliteInterpreter;
    private final List<String> labels;

    private final int inputImageWidth;
    private final int inputImageHeight;
    private final boolean isNchw;

    private LetterboxInfo lastLetterboxInfo;

    private static final float CONFIDENCE_THRESHOLD = 0.30f;
    private static final float PERSON_CONFIDENCE_THRESHOLD = 0.65f;
    private static final float NMS_THRESHOLD = 0.45f;
    private static final int NUM_DETECTIONS_TO_RETURN = 5;

    private static class LetterboxInfo {
        final int sourceWidth;
        final int sourceHeight;
        final float scale;
        final int padX;
        final int padY;

        LetterboxInfo(int sourceWidth, int sourceHeight, float scale, int padX, int padY) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
        }
    }

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
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        tfliteInterpreter = new Interpreter(FileUtil.loadMappedFile(context, modelFileName), options);
        labels = FileUtil.loadLabels(context, labelFileName);

        int[] inputShape = tfliteInterpreter.getInputTensor(0).shape();
        Log.i(TAG, "Model input shape = " + Arrays.toString(inputShape));
        Log.i(TAG, "Model input type = " + tfliteInterpreter.getInputTensor(0).dataType());

        if (inputShape.length != 4) {
            throw new IllegalStateException("Unsupported input shape: " + Arrays.toString(inputShape));
        }

        if (inputShape[1] == 3) {
            isNchw = true;
            inputImageHeight = inputShape[2];
            inputImageWidth = inputShape[3];
        } else if (inputShape[3] == 3) {
            isNchw = false;
            inputImageHeight = inputShape[1];
            inputImageWidth = inputShape[2];
        } else {
            throw new IllegalStateException("Cannot infer layout from shape: " + Arrays.toString(inputShape));
        }

        Log.i(TAG, "Using layout = " + (isNchw ? "NCHW" : "NHWC"));
        Log.i(TAG, "Using width=" + inputImageWidth + " height=" + inputImageHeight);
    }

    public List<Detection> detect(Bitmap bitmap) {
        if (tfliteInterpreter == null || bitmap == null) return new ArrayList<>();

        try {
            Bitmap preprocessed = letterboxToSquare(bitmap);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(preprocessed);

            Log.i(TAG, "Input buffer capacity = " + inputBuffer.capacity());

            int[] outputShape = tfliteInterpreter.getOutputTensor(0).shape();
            float[][][] rawOutput = new float[outputShape[0]][outputShape[1]][outputShape[2]];

            Map<Integer, Object> outputMap = new HashMap<>();
            outputMap.put(0, rawOutput);

            tfliteInterpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputMap);

            List<Detection> detections = new ArrayList<>();
            int numDetections = outputShape[1];

            for (int i = 0; i < numDetections; i++) {
                float x1 = rawOutput[0][i][0];
                float y1 = rawOutput[0][i][1];
                float x2 = rawOutput[0][i][2];
                float y2 = rawOutput[0][i][3];
                float confidence = rawOutput[0][i][4];
                int classId = Math.round(rawOutput[0][i][5]);

                if (classId < 0 || classId >= labels.size()) continue;

                String label = labels.get(classId);
                float threshold = getThresholdForLabel(label);
                if (confidence < threshold) continue;

                RectF modelBox = normalizeCorners(x1, y1, x2, y2);
                RectF imageBox = unletterboxAndNormalize(modelBox, bitmap.getWidth(), bitmap.getHeight());

                if (isValidBox(imageBox)) {
                    detections.add(new Detection(imageBox, label, confidence));
                }
            }

            return suppressDuplicates(detections);

        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            return new ArrayList<>();
        }
    }

    private float getThresholdForLabel(String label) {
        if (label == null) return CONFIDENCE_THRESHOLD;
        if ("person".equalsIgnoreCase(label)) return PERSON_CONFIDENCE_THRESHOLD;
        return CONFIDENCE_THRESHOLD;
    }

    private Bitmap letterboxToSquare(Bitmap src) {
        Bitmap dst = Bitmap.createBitmap(inputImageWidth, inputImageHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dst);
        canvas.drawColor(Color.BLACK);

        int srcW = src.getWidth();
        int srcH = src.getHeight();

        float scale = Math.min(
                (float) inputImageWidth / (float) srcW,
                (float) inputImageHeight / (float) srcH
        );

        int scaledW = Math.round(srcW * scale);
        int scaledH = Math.round(srcH * scale);

        int padX = (inputImageWidth - scaledW) / 2;
        int padY = (inputImageHeight - scaledH) / 2;

        Rect srcRect = new Rect(0, 0, srcW, srcH);
        Rect dstRect = new Rect(padX, padY, padX + scaledW, padY + scaledH);

        canvas.drawBitmap(src, srcRect, dstRect, null);

        lastLetterboxInfo = new LetterboxInfo(srcW, srcH, scale, padX, padY);
        return dst;
    }

    private RectF unletterboxAndNormalize(RectF modelBox, int originalWidth, int originalHeight) {
        LetterboxInfo info = lastLetterboxInfo;
        if (info == null) return modelBox;

        float modelLeft = modelBox.left * inputImageWidth;
        float modelTop = modelBox.top * inputImageHeight;
        float modelRight = modelBox.right * inputImageWidth;
        float modelBottom = modelBox.bottom * inputImageHeight;

        float left = (modelLeft - info.padX) / info.scale;
        float top = (modelTop - info.padY) / info.scale;
        float right = (modelRight - info.padX) / info.scale;
        float bottom = (modelBottom - info.padY) / info.scale;

        left = clamp(left, 0f, originalWidth);
        top = clamp(top, 0f, originalHeight);
        right = clamp(right, 0f, originalWidth);
        bottom = clamp(bottom, 0f, originalHeight);

        return new RectF(
                clamp(left / originalWidth, 0f, 1f),
                clamp(top / originalHeight, 0f, 1f),
                clamp(right / originalWidth, 0f, 1f),
                clamp(bottom / originalHeight, 0f, 1f)
        );
    }

    private List<Detection> suppressDuplicates(List<Detection> detections) {
        if (detections.isEmpty()) return detections;

        Collections.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));

        List<Detection> finalDetections = new ArrayList<>();
        boolean[] removed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (removed[i]) continue;

            Detection current = detections.get(i);
            finalDetections.add(current);

            for (int j = i + 1; j < detections.size(); j++) {
                if (removed[j]) continue;

                Detection other = detections.get(j);
                if (!current.label.equals(other.label)) continue;

                float iou = calculateIoU(current.boundingBox, other.boundingBox);
                if (iou > NMS_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }

        if (finalDetections.size() > NUM_DETECTIONS_TO_RETURN) {
            return new ArrayList<>(finalDetections.subList(0, NUM_DETECTIONS_TO_RETURN));
        }
        return finalDetections;
    }

    private RectF normalizeCorners(float x1, float y1, float x2, float y2) {
        float maxCoord = Math.max(Math.max(Math.abs(x1), Math.abs(y1)), Math.max(Math.abs(x2), Math.abs(y2)));
        if (maxCoord > 2f) {
            x1 /= inputImageWidth;
            x2 /= inputImageWidth;
            y1 /= inputImageHeight;
            y2 /= inputImageHeight;
        }

        return new RectF(
                clamp(Math.min(x1, x2), 0f, 1f),
                clamp(Math.min(y1, y2), 0f, 1f),
                clamp(Math.max(x1, x2), 0f, 1f),
                clamp(Math.max(y1, y2), 0f, 1f)
        );
    }

    private boolean isValidBox(RectF box) {
        return box.right > box.left && box.bottom > box.top;
    }

    private float calculateIoU(RectF rect1, RectF rect2) {
        RectF r1 = new RectF(
                Math.min(rect1.left, rect1.right),
                Math.min(rect1.top, rect1.bottom),
                Math.max(rect1.left, rect1.right),
                Math.max(rect1.top, rect1.bottom)
        );
        RectF r2 = new RectF(
                Math.min(rect2.left, rect2.right),
                Math.min(rect2.top, rect2.bottom),
                Math.max(rect2.left, rect2.right),
                Math.max(rect2.top, rect2.bottom)
        );

        float intersectionLeft = Math.max(r1.left, r2.left);
        float intersectionTop = Math.max(r1.top, r2.top);
        float intersectionRight = Math.min(r1.right, r2.right);
        float intersectionBottom = Math.min(r1.bottom, r2.bottom);

        float intersectionArea = Math.max(0, intersectionRight - intersectionLeft) * Math.max(0, intersectionBottom - intersectionTop);
        float area1 = (r1.right - r1.left) * (r1.bottom - r1.top);
        float area2 = (r2.right - r2.left) * (r2.bottom - r2.top);
        float unionArea = area1 + area2 - intersectionArea;

        if (unionArea <= 0) return 0f;
        return intersectionArea / unionArea;
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        int pixelCount = inputImageWidth * inputImageHeight;
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[pixelCount];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        if (isNchw) {
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < pixelCount; i++) {
                    int val = intValues[i];
                    float v;
                    if (c == 0) v = ((val >> 16) & 0xFF) / 255.0f;
                    else if (c == 1) v = ((val >> 8) & 0xFF) / 255.0f;
                    else v = (val & 0xFF) / 255.0f;
                    byteBuffer.putFloat(v);
                }
            }
        } else {
            for (int i = 0; i < pixelCount; i++) {
                int val = intValues[i];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF) / 255.0f);
            }
        }

        byteBuffer.rewind();
        return byteBuffer;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
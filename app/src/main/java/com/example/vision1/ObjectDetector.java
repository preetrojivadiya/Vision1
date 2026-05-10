package com.example.vision1;

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
import java.util.List;
import java.util.Map;

public class ObjectDetector {

    private static final String TAG = "Vision1ObjectDetector";
    private Interpreter tfliteInterpreter;
    private List<String> labels;
    private int inputImageWidth = 640; // Model input width
    private int inputImageHeight = 640; // Model input height

    // Constants based on the reported output shape [1, 84, 8400]
    private static final int NUM_BOXES = 8400; // Total number of predicted boxes

    // !!! IMPORTANT: Assuming the 84 attributes are structured as [bbox (4), class_probs (80)] !!!
    private static final int BBOX_ELEMENTS = 4; // Number of elements for bounding box coordinates (cx, cy, w, h)
    // Assuming class probabilities start immediately after bbox elements
    private static final int CLASS_PROB_START_INDEX = 4; // Starting index of class probabilities (0-indexed within the 84 attributes)

    private static final int NUM_CLASSES = 80; // Number of object classes (Confirmed by user's labels file)

    private static final float CONFIDENCE_THRESHOLD = 0.5f; // Confidence threshold for initial filtering
    private static final float NMS_THRESHOLD = 0.4f; // NMS IoU threshold - Tune this value (e.g., 0.4, 0.5, 0.6)
    private static final int NUM_DETECTIONS_TO_RETURN = 4; // Max number of top detections to return after NMS


    public static class Detection {
        public final RectF boundingBox; // Normalized [0, 1]
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

        // >>>>>> START Logging for Step 2 (Already verified, but keep for reference) <<<<<<
        Log.i(TAG, "Number of input tensors: " + tfliteInterpreter.getInputTensorCount());
        Log.i(TAG, "Number of output tensors: " + tfliteInterpreter.getOutputTensorCount());

        if (tfliteInterpreter.getInputTensorCount() > 0) {
            org.tensorflow.lite.Tensor inputTensor = tfliteInterpreter.getInputTensor(0);
            Log.i(TAG, "Input Tensor 0 Name: " + inputTensor.name());
            Log.i(TAG, "Input Tensor 0 Shape: " + java.util.Arrays.toString(inputTensor.shape()));
            Log.i(TAG, "Input Tensor 0 Type: " + inputTensor.dataType());
        } else {
            Log.e(TAG, "No input tensors found!");
        }

        if (tfliteInterpreter.getOutputTensorCount() > 0) {
            org.tensorflow.lite.Tensor outputTensor = tfliteInterpreter.getOutputTensor(0);
            Log.i(TAG, "Output Tensor 0 Name: " + outputTensor.name());
            Log.i(TAG, "Output Tensor 0 Shape: " + java.util.Arrays.toString(outputTensor.shape()));
            Log.i(TAG, "Output Tensor 0 Type: " + outputTensor.dataType());
            Log.i(TAG, "Output Tensor 0 QuantizationParams: " + outputTensor.quantizationParams()); // Check if quantized
        } else {
            Log.e(TAG, "No output tensors found!");
        }
        // >>>>>> END Logging for Step 2 <<<<<<

        labels = FileUtil.loadLabels(context, labelFileName);
        Log.i(TAG, "Label list size: " + labels.size());
        if (labels.size() != NUM_CLASSES) {
            Log.e(TAG, "Error: Label count mismatch. Expected " + NUM_CLASSES + ", got " + labels.size());
            // Consider throwing an exception here if the label count MUST match NUM_CLASSES
        } else {
            Log.i(TAG, "Label count matches NUM_CLASSES.");
        }

        Log.i(TAG, "TFLite model and labels loaded.");
    }

    private static float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    // Helper method to calculate Intersection over Union (IoU)
    private float calculateIoU(RectF rect1, RectF rect2) {
        // Ensure bounding boxes are valid (min < max) - important for IoU
        RectF r1 = new RectF(Math.min(rect1.left, rect1.right), Math.min(rect1.top, rect1.bottom), Math.max(rect1.left, rect1.right), Math.max(rect1.top, rect1.bottom));
        RectF r2 = new RectF(Math.min(rect2.left, rect2.right), Math.min(rect2.top, rect2.bottom), Math.max(rect2.left, rect2.right), Math.max(rect2.top, rect2.bottom));

        // Calculate the intersection rectangle
        float intersectionLeft = Math.max(r1.left, r2.left);
        float intersectionTop = Math.max(r1.top, r2.top);
        float intersectionRight = Math.min(r1.right, r2.right);
        float intersectionBottom = Math.min(r1.bottom, r2.bottom);

        // Calculate the area of intersection
        float intersectionArea = Math.max(0, intersectionRight - intersectionLeft) * Math.max(0, intersectionBottom - intersectionTop);

        // Calculate the area of both bounding boxes
        float area1 = (r1.right - r1.left) * (r1.bottom - r1.top);
        float area2 = (r2.right - r2.left) * (r2.bottom - r2.top);

        // Calculate the union area
        float unionArea = area1 + area2 - intersectionArea;

        // Avoid division by zero
        if (unionArea <= 0) {
            return 0;
        }

        // Calculate IoU
        return intersectionArea / unionArea;
    }


    public List<Detection> detect(Bitmap bitmap) {
        if (tfliteInterpreter == null) {
            Log.e(TAG, "TFLite interpreter not initialized.");
            return null;
        }

        // Prepare Input - Resize and Convert Bitmap to ByteBuffer
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true);
        // Log.d(TAG, "Resized Bitmap Width: " + resizedBitmap.getWidth() + ", Height: " + resizedBitmap.getHeight()); // Keep or remove based on preference
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resizedBitmap);

        // Prepare output tensor buffer - Shape is [1, 84, 8400]
        // This matches the reported output shape [Batch Size, Attributes, Boxes]
        float[][][] rawOutput = new float[1][BBOX_ELEMENTS + NUM_CLASSES][NUM_BOXES]; // Shape [1, 4+80, 8400] = [1, 84, 8400]
        Map<Integer, Object> outputMap = new java.util.HashMap<>();
        outputMap.put(0, rawOutput); // Output tensor index 0

        // Run inference
        long startTime = System.nanoTime();
        tfliteInterpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputMap);
        long endTime = System.nanoTime();
        Log.d(TAG, "Inference Time: " + (endTime - startTime) / 1_000_000 + " ms");

        List<Detection> detections = new ArrayList<>();

        // >>>>>> START Initial Parsing and Confidence Filtering <<<<<<
        // Iterate through each predicted box (8400 boxes)
        for (int i = 0; i < NUM_BOXES; ++i) {
            // Access attributes for the i-th box
            // Attributes are accessed at rawOutput[0][attribute_index][i]

            // Access bounding box coordinates for box 'i' (cx, cy, w, h) - Indices 0-3
            // !!! ASSUMPTION: These raw values are normalized proportions [0, 1] of the 640x640 input !!!
            float rawCenterX = rawOutput[0][0][i];
            float rawCenterY = rawOutput[0][1][i];
            float rawWidth = rawOutput[0][2][i];
            float rawHeight = rawOutput[0][3][i];

            // Access class probabilities (80 classes starting from index 4) for box 'i'
            float maxClassProbability = 0.0f;
            int bestClassIndex = -1;
            for (int j = 0; j < NUM_CLASSES; ++j) {
                // Access class probability for box 'i' and class 'j'
                // Indices are CLASS_PROB_START_INDEX (4) + j (0 to 79) -> 4 to 83
                float classProbability = sigmoid(rawOutput[0][CLASS_PROB_START_INDEX + j][i]);
                if (classProbability > maxClassProbability) {
                    maxClassProbability = classProbability;
                    bestClassIndex = j;
                }
            }

            // Assuming max class probability is the confidence if no separate objectness score
            float finalConfidence = maxClassProbability;

            // Apply a confidence threshold for initial filtering
            if (finalConfidence > CONFIDENCE_THRESHOLD) {
                if (bestClassIndex != -1 && labels != null && bestClassIndex < labels.size()) {
                    String label = labels.get(bestClassIndex);

                    // Corrected Bounding Box Denormalization and Normalization
                    // Denormalize raw center/size to get pixel coordinates on the 640x640 input
                    float pixelCenterX = rawCenterX * inputImageWidth;
                    float pixelCenterY = rawCenterY * inputImageHeight;
                    float pixelWidth = rawWidth * inputImageWidth;
                    float pixelHeight = rawHeight * inputImageHeight;

                    // Convert pixel center/width/height to top-left/bottom-right pixel coordinates
                    float pixelX1 = (pixelCenterX - pixelWidth / 2.0f);
                    float pixelY1 = (pixelCenterY - pixelHeight / 2.0f);
                    float pixelX2 = (pixelCenterX + pixelWidth / 2.0f);
                    float pixelY2 = (pixelCenterY + pixelHeight / 2.0f);

                    // Normalize these pixel coordinates back to [0, 1] for the OverlayView
                    float normalizedX1 = pixelX1 / inputImageWidth;
                    float normalizedY1 = pixelY1 / inputImageHeight;
                    float normalizedX2 = pixelX2 / inputImageWidth;
                    float normalizedY2 = pixelY2 / inputImageHeight;

                    // Ensure normalized coordinates are within the [0, 1] range
                    normalizedX1 = Math.max(0f, normalizedX1);
                    normalizedY1 = Math.max(0f, normalizedY1);
                    normalizedX2 = Math.min(1f, normalizedX2);
                    normalizedY2 = Math.min(1f, normalizedY2);

                    RectF boundingBox = new RectF(normalizedX1, normalizedY1, normalizedX2, normalizedY2);

                    detections.add(new Detection(boundingBox, label, finalConfidence));
                    // Log.v(TAG, "Initial Detection (before NMS): " + label + ", Confidence=" + finalConfidence + ", Normalized BBox=" + boundingBox.toShortString() + " for box " + i + ", raw class index: " + bestClassIndex); // Uncomment for detailed initial detection logs
                } else {
                    // Log.w(TAG, "Best class index " + bestClassIndex + " out of labels bounds (" + labels.size() + ") for box " + i + " with final confidence " + finalConfidence + " (before NMS)"); // Uncomment for detailed logs
                }
            } else {
                // Log.v(TAG, "Filtered box " + i + " due to low initial confidence: " + finalConfidence); // Uncomment for detailed filtering logs
            }
        }
        // >>>>>> END Initial Parsing and Confidence Filtering <<<<<<

        Log.d(TAG, "Initial detections found (before NMS): " + detections.size());

        // >>>>>> START Non-Maximum Suppression (NMS) Implementation <<<<<<

        // Sort initial detections by confidence in descending order
        Collections.sort(detections, (d1, d2) -> Float.compare(d2.confidence, d1.confidence));

        List<Detection> finalDetections = new ArrayList<>();
        boolean[] removed = new boolean[detections.size()]; // To keep track of removed boxes

        for (int i = 0; i < detections.size(); ++i) {
            if (removed[i]) continue; // Skip if this box has already been marked for removal

            Detection currentDetection = detections.get(i);
            finalDetections.add(currentDetection); // Keep this box (it has the highest confidence among remaining)

            // Iterate through the rest of the boxes with lower confidence
            for (int j = i + 1; j < detections.size(); ++j) {
                if (removed[j]) continue; // Skip if this box has already been marked for removal

                Detection otherDetection = detections.get(j);

                // Calculate IoU between the current box and the other box
                float iou = calculateIoU(currentDetection.boundingBox, otherDetection.boundingBox);

                // If the IoU is above the threshold, remove the other box (it's likely detecting the same object)
                if (iou > NMS_THRESHOLD) {
                    removed[j] = true; // Mark the other box for removal
                }
            }
        }

        Log.d(TAG, "NMS applied. Original detections: " + detections.size() + ", Final detections (before top N): " + finalDetections.size());

        // >>>>>> END Non-Maximum Suppression (NMS) Implementation <<<<<<

        // Return the final detections after NMS, limited to the top N
        return finalDetections.subList(0, Math.min(finalDetections.size(), NUM_DETECTIONS_TO_RETURN));
    }

    // Helper method to convert Bitmap to ByteBuffer for model input
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        // Allocate ByteBuffer for float32 input (4 bytes per float) * width * height * 3 channels (RGB)
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * inputImageWidth * inputImageHeight * 3);
        byteBuffer.order(ByteOrder.nativeOrder()); // Use native byte order

        // Get pixel values from the bitmap
        int[] intValues = new int[inputImageWidth * inputImageHeight];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        // Put pixel values into the ByteBuffer, normalized to [0, 1]
        int pixel = 0;
        for (int i = 0; i < inputImageHeight; ++i) {
            for (int j = 0; j < inputImageWidth; ++j) {
                final int val = intValues[pixel++];
                // Extract R, G, B channels and normalize to [0, 1] by dividing by 255.0f
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f); // Red
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);  // Green
                byteBuffer.putFloat(((val & 0xFF)) / 255.0f);       // Blue - Corrected parenthesis
            }
        }

        byteBuffer.rewind(); // Reset the buffer's position to the beginning
        return byteBuffer;
    }
}
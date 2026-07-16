package com.example.vision1.detection;

import android.graphics.Bitmap;
import android.util.Log;
import android.util.Size;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.vision1.utils.ImageUtils;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;

public class CameraController {

    private static final String TAG = "Vision1CameraController";

    private final AppCompatActivity activity;
    private final PreviewView previewView;
    private final ExecutorService executor;
    private final FrameProcessor frameProcessor;
    private final FrameSkipper frameSkipper;

    private ProcessCameraProvider cameraProvider;

    public CameraController(AppCompatActivity activity,
                            PreviewView previewView,
                            ExecutorService executor,
                            FrameProcessor frameProcessor) {
        this.activity = activity;
        this.previewView = previewView;
        this.executor = executor;
        this.frameProcessor = frameProcessor;
        this.frameSkipper = new FrameSkipper(CameraConfig.FRAME_SKIP_COUNT);
    }

    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(activity);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                ResolutionSelector previewResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewResSelector)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ResolutionSelector analysisResSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new ResolutionStrategy(
                                        new Size(CameraConfig.ANALYSIS_WIDTH, CameraConfig.ANALYSIS_HEIGHT),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                        )
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(executor, imageProxy -> {
                    try {
                        if (!frameSkipper.shouldProcess()) {
                            return;
                        }

                        Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
                        if (bitmap == null) return;

                        Bitmap rotatedBitmap = ImageUtils.rotateBitmap(
                                bitmap,
                                imageProxy.getImageInfo().getRotationDegrees()
                        );

                        if (rotatedBitmap == null) return;

                        frameProcessor.process(rotatedBitmap);

                    } catch (Exception e) {
                        Log.e(TAG, "Analyzer crashed", e);
                    } finally {
                        imageProxy.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        activity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                );

            } catch (Exception e) {
                Log.e(TAG, "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    public void stopCamera() {
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping camera", e);
        }
    }
}
package com.example.vision1;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

public class ImageUtils {

    private static final String TAG = "Vision1ImageUtils";

    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        Image mediaImage = image.getImage();
        if (mediaImage == null) {
            return null;
        }

        if (mediaImage.getFormat() == ImageFormat.YUV_420_888) {
            ByteBuffer yBuffer = mediaImage.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = mediaImage.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = mediaImage.getPlanes()[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            try {
                android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(nv21, ImageFormat.NV21, mediaImage.getWidth(), mediaImage.getHeight(), null);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, mediaImage.getWidth(), mediaImage.getHeight()), 100, out); // Changed quality to 95
                byte[] imageBytes = out.toByteArray();
                return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            } catch (Exception e) {
                Log.e(TAG, "Error converting YUV to JPEG: " + e.getMessage());
                return null;
            }
        } else {
            // For other formats, try the previous simpler approach
            ByteBuffer buffer = mediaImage.getPlanes()[0].getBuffer();
            int width = mediaImage.getWidth();
            int height = mediaImage.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            try {
                bitmap.copyPixelsFromBuffer(buffer);
                return bitmap;
            } catch (Exception e) {
                Log.e(TAG, "Error converting ImageProxy to Bitmap (other format): " + e.getMessage());
                return null;
            }
        }
    }

    public static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0 || bitmap == null) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        try {
            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return rotatedBitmap;
        } catch (OutOfMemoryError ex) {
            ex.printStackTrace();
            return null;
        }
    }
}
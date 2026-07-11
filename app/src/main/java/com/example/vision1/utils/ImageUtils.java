// ImageUtils.java
package com.example.vision1.utils;

import android.graphics.Bitmap;
import android.graphics.Color;
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

        if (mediaImage.getFormat() != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: " + mediaImage.getFormat());
            return null;
        }

        int width = mediaImage.getWidth();
        int height = mediaImage.getHeight();

        Image.Plane[] planes = mediaImage.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        byte[] yBytes = new byte[yBuffer.remaining()];
        byte[] uBytes = new byte[uBuffer.remaining()];
        byte[] vBytes = new byte[vBuffer.remaining()];

        yBuffer.get(yBytes);
        uBuffer.get(uBytes);
        vBuffer.get(vBytes);

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int[] argb = new int[width * height];

        for (int y = 0; y < height; y++) {
            int yRowOffset = yRowStride * y;
            int uvRowOffset = uvRowStride * (y >> 1);

            for (int x = 0; x < width; x++) {
                int yVal = yBytes[yRowOffset + x] & 0xFF;
                int uvOffset = uvRowOffset + (x >> 1) * uvPixelStride;

                int uVal = uBytes[uvOffset] & 0xFF;
                int vVal = vBytes[uvOffset] & 0xFF;

                argb[y * width + x] = YuvToRgb(yVal, uVal, vVal);
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(argb, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static int YuvToRgb(int y, int u, int v) {
        int c = y - 16;
        int d = u - 128;
        int e = v - 128;

        if (c < 0) c = 0;

        int r = clamp((298 * c + 409 * e + 128) >> 8);
        int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
        int b = clamp((298 * c + 516 * d + 128) >> 8);

        return Color.argb(255, r, g, b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
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
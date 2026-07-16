package com.example.vision1.detection;

public class FrameSkipper {

    private final int skipCount;
    private int frameCounter = 0;

    public FrameSkipper(int skipCount) {
        this.skipCount = Math.max(0, skipCount);
    }

    public boolean shouldProcess() {
        frameCounter++;
        return frameCounter % (skipCount + 1) == 0;
    }

    public void reset() {
        frameCounter = 0;
    }
}
package com.example.vision1.detection;

public final class VisionModeFactory {
    private VisionModeFactory() {}

    public static VisionMode create(VisionModeType type) {
        switch (type) {
            case IDENTIFY:
                return new IdentifierMode();
            case SCENE:
                return new SceneMode();
            case DETECTION:
            default:
                return new DetectionMode();
        }
    }
}
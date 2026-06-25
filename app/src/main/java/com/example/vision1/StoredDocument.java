package com.example.vision1;

import java.io.File;
import java.util.Objects;

public class StoredDocument implements StorageItem {

    private String originalFilePath;
    private String audioFilePath;
    private boolean isProcessing;

    public StoredDocument(String originalFilePath) {
        if (originalFilePath == null || originalFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("originalFilePath cannot be null or empty");
        }
        this.originalFilePath = originalFilePath;
        this.audioFilePath = null;
        this.isProcessing = false;
    }

    public String getOriginalFilePath() { return originalFilePath; }
    public String getAudioFilePath() { return audioFilePath; }
    public boolean isProcessing() { return isProcessing; }

    public void setAudioFilePath(String audioFilePath) { this.audioFilePath = audioFilePath; }
    public void setProcessing(boolean processing) { isProcessing = processing; }

    // Implement StorageItem interface
    @Override
    public int getItemType() { return TYPE_DOCUMENT; }

    @Override
    public String getName() {
        return new File(originalFilePath).getName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredDocument that = (StoredDocument) o;
        return Objects.equals(originalFilePath, that.originalFilePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalFilePath);
    }
}
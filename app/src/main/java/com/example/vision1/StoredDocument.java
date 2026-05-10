package com.example.vision1;

import java.util.Objects;

public class StoredDocument {

    private String originalFilePath;
    private String audioFilePath; // Will be null initially, until generated
    private boolean isProcessing; // Optional: Flag to indicate if the document is currently being processed (extraction/TTS)

    /**
     * Constructor for a new StoredDocument.
     * The audioFilePath is initially null.
     *
     * @param originalFilePath The absolute path to the original file stored locally.
     */
    public StoredDocument(String originalFilePath) {
        // Basic validation (optional but recommended)
        if (originalFilePath == null || originalFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("originalFilePath cannot be null or empty");
        }
        this.originalFilePath = originalFilePath;
        this.audioFilePath = null; // Initially no audio file exists
        this.isProcessing = false; // Not processing by default
    }

    // --- Getters ---

    /**
     * Gets the absolute path to the original file.
     *
     * @return The original file path.
     */
    public String getOriginalFilePath() {
        return originalFilePath;
    }

    /**
     * Gets the absolute path to the generated audio file.
     *
     * @return The audio file path, or null if not yet generated or failed.
     */
    public String getAudioFilePath() {
        return audioFilePath;
    }

    /**
     * Checks if the document is currently being processed (text extraction, TTS).
     *
     * @return True if processing, false otherwise.
     */
    public boolean isProcessing() {
        return isProcessing;
    }

    // --- Setters ---

    /**
     * Sets the absolute path to the generated audio file.
     * Use null if audio generation failed or the audio file is removed.
     *
     * @param audioFilePath The audio file path, or null.
     */
    public void setAudioFilePath(String audioFilePath) {
        this.audioFilePath = audioFilePath;
    }

    /**
     * Sets the processing state of the document.
     *
     * @param processing True if processing, false otherwise.
     */
    public void setProcessing(boolean processing) {
        isProcessing = processing;
    }

    // --- Optional: equals() and hashCode() for better list management/comparison ---

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoredDocument that = (StoredDocument) o;
        // Primarily compare based on the unique original file path
        return Objects.equals(originalFilePath, that.originalFilePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originalFilePath);
    }

    // --- Optional: toString() for logging/debugging ---

    @Override
    public String toString() {
        return "StoredDocument{" +
                "originalFilePath='" + originalFilePath + '\'' +
                ", audioFilePath='" + audioFilePath + '\'' +
                ", isProcessing=" + isProcessing +
                '}';
    }
}
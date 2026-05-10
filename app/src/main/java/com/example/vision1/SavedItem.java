package com.example.vision1;

import android.net.Uri;
import java.io.File;
import java.io.Serializable; // Implementing Serializable might be useful if you pass this object via Intent

public class SavedItem implements Serializable { // Implementing Serializable
    private File imageFile;
    private File audioFile; // Can be null if no audio was saved

    public SavedItem(File imageFile, File audioFile) {
        this.imageFile = imageFile;
        this.audioFile = audioFile;
    }

    public File getImageFile() {
        return imageFile;
    }

    public File getAudioFile() {
        return audioFile;
    }

    public Uri getImageUri() {
        return Uri.fromFile(imageFile);
    }

    public Uri getAudioUri() {
        return (audioFile != null) ? Uri.fromFile(audioFile) : null;
    }

    // Optional: Add methods to get paths as Strings
    public String getImagePath() {
        return imageFile != null ? imageFile.getAbsolutePath() : null;
    }

    public String getAudioPath() {
        return audioFile != null ? audioFile.getAbsolutePath() : null;
    }
}
package com.example.vision1;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImageGalleryActivity extends AppCompatActivity implements ImageAdapter.OnItemClickListener { // Implement the interface

    private static final String TAG = "ImageGalleryActivity"; // For logging

    private RecyclerView recyclerView;
    private ImageAdapter imageAdapter;
    // Change the list type to SavedItem
    private List<SavedItem> savedItemList = new ArrayList<>();

    private MediaPlayer currentMediaPlayer = null; // To manage the currently playing audio

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.image_gallery); // Using the correct layout name

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3)); // Adjust span count as needed

        loadSavedItems(); // New method to load items

        // Pass the list of SavedItem and 'this' as the listener
        imageAdapter = new ImageAdapter(this, savedItemList, this);
        recyclerView.setAdapter(imageAdapter);
    }

    // New method to load saved images and audio files
    private void loadSavedItems() {
        savedItemList.clear(); // Clear existing list

        File imageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Images");
        File audioDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Audio"); // Assuming audio is saved here

        // Ensure audio directory exists, though we mainly iterate through image directory
        if (!audioDir.exists()) {
            audioDir.mkdirs(); // Create if it doesn't exist
        }


        if (imageDir.exists() && imageDir.isDirectory()) {
            File[] imageFiles = imageDir.listFiles();
            if (imageFiles != null) {
                // Optional: Sort files by name/date if needed
                // Arrays.sort(imageFiles); // Sorting might help pair if names are consistent

                for (File imageFile : imageFiles) {
                    // Only process image files
                    if (imageFile.isFile() && (imageFile.getName().endsWith(".jpg") || imageFile.getName().endsWith(".png"))) {

                        // Try to find the corresponding audio file
                        // This assumes a strict naming convention link (e.g., same base name, different extension)
                        String imageFileName = imageFile.getName();
                        String baseName = imageFileName.substring(0, imageFileName.lastIndexOf('.'));
                        // Ensure this naming convention matches how you save audio in CameraActivity
                        String audioFileName = baseName.replace("IMG_", "AUDIO_") + ".mp3";
                        File audioFile = new File(audioDir, audioFileName);

                        // Create a SavedItem
                        SavedItem item = new SavedItem(imageFile, audioFile.exists() ? audioFile : null);
                        savedItemList.add(item);
                    }
                }
                Log.d(TAG, "Loaded " + savedItemList.size() + " saved items.");
            } else {
                Log.d(TAG, "Image directory exists but is empty.");
            }
        } else {
            Log.d(TAG, "Image directory does not exist.");
            Toast.makeText(this, "No saved images found.", Toast.LENGTH_SHORT).show();
        }
        // The savedItemList is now populated with SavedItem objects
    }

    // Implementation of the OnItemClickListener interface methods

    @Override
    public void onImageClick(SavedItem item) {
        // Handle image click - open the image
        openImage(item.getImageFile());
    }

    @Override
    public void onAudioClick(SavedItem item) {
        // Handle audio click - play the audio
        playAudio(item.getAudioFile());
    }


    // Method to handle image click (open image)
    private void openImage(File imageFile) {
        if (imageFile != null && imageFile.exists()) {
            try {
                // Use FileProvider to get a content URI for the file
                Uri imageUri = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".provider", // Replace with your authority, defined in AndroidManifest.xml
                        imageFile
                );

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(imageUri, "image/*"); // Set MIME type for images
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // Grant read permission to the viewing app
                startActivity(intent);

            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error getting URI for file using FileProvider: " + e.getMessage());
                Toast.makeText(this, "Error opening image.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error opening image: " + e.getMessage());
                Toast.makeText(this, "Error opening image.", Toast.LENGTH_SHORT).show();
            }

        } else {
            Toast.makeText(this, "Image file not found.", Toast.LENGTH_SHORT).show();
        }
    }

    // Method to handle audio click (play audio)
    private void playAudio(File audioFile) {
        if (audioFile != null && audioFile.exists()) {
            // Stop any currently playing audio before starting a new one
            stopCurrentPlayback();

            try {
                currentMediaPlayer = new MediaPlayer();
                currentMediaPlayer.setDataSource(audioFile.getAbsolutePath());
                currentMediaPlayer.prepareAsync(); // Prepare asynchronously

                currentMediaPlayer.setOnPreparedListener(mp -> {
                    // Start playback when prepared
                    mp.start();
                    Log.d(TAG, "Playing audio: " + audioFile.getName());
                    Toast.makeText(this, "Playing audio...", Toast.LENGTH_SHORT).show();
                });

                currentMediaPlayer.setOnCompletionListener(mp -> {
                    // Release MediaPlayer when playback is complete
                    Log.d(TAG, "Audio playback complete.");
                    stopCurrentPlayback(); // Use the helper method
                });

                currentMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                    Toast.makeText(this, "Error during audio playback.", Toast.LENGTH_SHORT).show();
                    stopCurrentPlayback();
                    return true; // Indicate that the error was handled
                });


            } catch (IOException e) {
                Log.e(TAG, "IOException playing audio: " + e.getMessage());
                Toast.makeText(this, "Error setting up audio playback.", Toast.LENGTH_SHORT).show();
                stopCurrentPlayback(); // Clean up on error
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error playing audio: " + e.getMessage());
                Toast.makeText(this, "An error occurred during audio playback.", Toast.LENGTH_SHORT).show();
                stopCurrentPlayback(); // Clean up on error
            }
        } else {
            Toast.makeText(this, "Audio file not found for this item.", Toast.LENGTH_SHORT).show();
        }
    }

    // Helper method to stop and release the current MediaPlayer instance
    private void stopCurrentPlayback() {
        if (currentMediaPlayer != null) {
            if (currentMediaPlayer.isPlaying()) {
                currentMediaPlayer.stop();
            }
            currentMediaPlayer.release();
            currentMediaPlayer = null;
            Log.d(TAG, "MediaPlayer released.");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release MediaPlayer resources when activity is destroyed
        stopCurrentPlayback();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Consider stopping playback when the activity goes into the background
        stopCurrentPlayback();
    }
}
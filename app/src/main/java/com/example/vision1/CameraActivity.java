package com.example.vision1;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener; // Import UtteranceProgressListener
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout; // Optional
import android.widget.TextView; // Optional
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException; // Import IOException
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity"; // For logging

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Executor executor;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    // ML Kit and TTS variables
    private TextRecognizer textRecognizer;
    private TextToSpeech textToSpeech;
    private String extractedText = ""; // To store the text recognized
    private boolean isTtsInitialized = false;

    // Playback control UI (ensure these IDs exist in activity_camera.xml)
    private ImageButton playPauseButton;
    // private TextView recognizedTextView; // Optional: to display recognized text
    // private LinearLayout playbackControlsLayout; // Optional: layout to show/hide controls

    // To keep track of the saved image file path for linking audio
    private String lastCapturedImageFilePath = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = ContextCompat.getMainExecutor(this);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.previewView);
        ImageButton captureButton = findViewById(R.id.captureButton);
        ImageButton switchButton = findViewById(R.id.switchCameraButton);
        ImageView thumbnailPreview = findViewById(R.id.thumbnailPreview);

        // Find playback control views (you must add these IDs to activity_camera.xml)
        playPauseButton = findViewById(R.id.playPauseButton);
        // recognizedTextView = findViewById(R.id.recognizedTextView); // Uncomment if used
        // playbackControlsLayout = findViewById(R.id.playbackControlsLayout); // Uncomment if used

        // Initialize ML Kit Text Recognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); // Using default Latin script options

        // Initialize Text-to-Speech
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US); // Set your desired language, e.g., Locale.getDefault()

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language not supported");
                } else {
                    isTtsInitialized = true;
                    Log.d(TAG, "TTS Initialization successful");
                    // You might want to set the UtteranceProgressListener here after init
                    // textToSpeech.setOnUtteranceProgressListener(new TtsUtteranceListener()); // Example of setting it globally
                }
            } else {
                Log.e(TAG, "TTS Initialization failed, status: " + status);
                runOnUiThread(() -> Toast.makeText(this, "TTS Initialization failed.", Toast.LENGTH_SHORT).show());
            }
        });

        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
        }

        // Set up button listeners
        captureButton.setOnClickListener(v -> takePhoto());

        switchButton.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ?
                    CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            startCamera(); // Restart camera with new lens
        });

        thumbnailPreview.setOnClickListener(v -> {
            Intent intent = new Intent(CameraActivity.this, ImageGalleryActivity.class);
            startActivity(intent);
        });

        // Setup Play/Pause Button Listener (add this after finding the button)
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(v -> {
                if (textToSpeech != null && textToSpeech.isSpeaking()) { // Check if TTS is initialized and speaking
                    textToSpeech.stop(); // Stop speaking
                    // Optionally update button icon to indicate "Play"
                    // playPauseButton.setImageResource(android.R.drawable.ic_media_play);
                    Log.d(TAG, "TTS playback stopped by user.");
                } else {
                    if (isTtsInitialized && textToSpeech != null && !extractedText.isEmpty()) { // Check if TTS initialized, not null, and text exists
                        textToSpeech.speak(extractedText, TextToSpeech.QUEUE_FLUSH, null, "utteranceId");
                        // Optionally update button icon to indicate "Pause"
                        // playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
                        Log.d(TAG, "TTS playback started by user.");
                    } else {
                        Toast.makeText(this, "No text to speak or TTS not ready", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Cannot start TTS playback: isTtsInitialized=" + isTtsInitialized + ", extractedText empty=" + extractedText.isEmpty());
                    }
                }
            });
        }

        // Hide playback controls initially
        // if (playbackControlsLayout != null) playbackControlsLayout.setVisibility(View.GONE);
        // if (recognizedTextView != null) recognizedTextView.setText("");
        // if (playPauseButton != null) playPauseButton.setVisibility(View.GONE); // Maybe hide initially too
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder().build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                preview.setSurfaceProvider(previewView.getSurfaceProvider());

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera: " + e.getMessage());
            }
        }, executor);
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Images");
        if (!folder.exists()) folder.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis());
        String fileName = "IMG_" + timeStamp + ".jpg";
        File photoFile = new File(folder, fileName);

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // Reset previous text and image path before taking a new photo
        extractedText = "";
        lastCapturedImageFilePath = null;
        // Optionally hide playback controls
        // if (playbackControlsLayout != null) playbackControlsLayout.setVisibility(View.GONE);
        // if (recognizedTextView != null) recognizedTextView.setText("");
        // if (playPauseButton != null) playPauseButton.setVisibility(View.GONE);


        imageCapture.takePicture(options, executor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                // Photo saved successfully, store the path and process it for text recognition
                lastCapturedImageFilePath = photoFile.getAbsolutePath();
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Photo saved!", Toast.LENGTH_SHORT).show());
                processImageForText(photoFile);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Error saving photo: " + exception.getMessage(), Toast.LENGTH_SHORT).show());
                Log.e(TAG, "Image capture error: " + exception.getMessage());
            }
        });
    }

    // Method to process the saved image file for text recognition
    private void processImageForText(File imageFile) {
        Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
        if (bitmap == null) {
            runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Failed to load image bitmap", Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Failed to load image bitmap from path: " + imageFile.getAbsolutePath());
            return;
        }

        InputImage image = null;
        try {
            // Using fromFilePath or fromFile can be more memory efficient for large images
            image = InputImage.fromFilePath(this, Uri.fromFile(imageFile));
            // Alternatively, if using Bitmap:
            // image = InputImage.fromBitmap(bitmap, 0); // Orientation is 0 as Bitmap is usually already oriented
        } catch (IOException e) {
            Log.e(TAG, "Error creating InputImage from file: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Error processing image.", Toast.LENGTH_SHORT).show());
            return;
        }


        textRecognizer.process(image)
                .addOnSuccessListener(text -> {
                    extractedText = text.getText();
                    if (!extractedText.isEmpty()) {
                        Log.d(TAG, "Text recognized: " + extractedText);
                        runOnUiThread(() -> {
                            Toast.makeText(CameraActivity.this, "Text recognized!", Toast.LENGTH_SHORT).show();
                            // Optionally display the text
                            // if (recognizedTextView != null) recognizedTextView.setText(extractedText);
                            // if (playbackControlsLayout != null) playbackControlsLayout.setVisibility(View.VISIBLE);
                            // if (playPauseButton != null) playPauseButton.setVisibility(View.VISIBLE);

                            // Speak the text automatically
                            if (isTtsInitialized && textToSpeech != null) {
                                textToSpeech.speak(extractedText, TextToSpeech.QUEUE_FLUSH, null, "utteranceId");
                                // Optionally update playPauseButton icon to pause
                                // if (playPauseButton != null) playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
                                Log.d(TAG, "Speaking recognized text automatically.");
                            } else {
                                Toast.makeText(CameraActivity.this, "TTS not ready, cannot speak.", Toast.LENGTH_SHORT).show();
                                Log.w(TAG, "TTS not ready for auto-speak. isTtsInitialized=" + isTtsInitialized + ", textToSpeech null=" + (textToSpeech == null));
                            }
                        });
                    } else {
                        Log.d(TAG, "No text found in image.");
                        runOnUiThread(() -> {
                            Toast.makeText(CameraActivity.this, "No text found in image.", Toast.LENGTH_SHORT).show();
                            extractedText = ""; // Clear any previous text
                            // if (recognizedTextView != null) recognizedTextView.setText("");
                            // if (playbackControlsLayout != null) playbackControlsLayout.setVisibility(View.GONE);
                            // if (playPauseButton != null) playPauseButton.setVisibility(View.GONE);
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Text recognition failed: " + e.getMessage(), e); // Log exception for details
                    runOnUiThread(() -> {
                        Toast.makeText(CameraActivity.this, "Text recognition failed.", Toast.LENGTH_SHORT).show();
                        extractedText = ""; // Clear any previous text
                        // if (recognizedTextView != null) recognizedTextView.setText("");
                        // if (playbackControlsLayout != null) playbackControlsLayout.setVisibility(View.GONE);
                        // if (playPauseButton != null) playPauseButton.setVisibility(View.GONE);
                    });
                });
    }

    // Handle back button press - save audio if text exists
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Log.d(TAG, "Back button pressed. extractedText empty: " + extractedText.isEmpty() + ", lastCapturedImageFilePath: " + lastCapturedImageFilePath);

        // Only attempt to save audio if text was extracted and an image was captured
        if (!extractedText.isEmpty() && lastCapturedImageFilePath != null) {
            // Stop any ongoing TTS speaking immediately
            if (textToSpeech != null && textToSpeech.isSpeaking()) {
                textToSpeech.stop();
                Log.d(TAG, "Stopping ongoing TTS speaking before saving.");
            }
            // Synthesize and save the audio file, linking it to the image
            saveTextAsAudio(extractedText, lastCapturedImageFilePath);
            // releaseResourcesAndFinish() is now called in the UtteranceProgressListener's onDone/onError
        } else {
            // If no text or no image was captured/processed, just release and go back
            Log.d(TAG, "No text or image to save, releasing resources and finishing.");
            releaseResourcesAndFinish(); // Go back immediately
        }
    }

    // Method to synthesize text to an audio file and save it
    private void saveTextAsAudio(String textToSave, String imageFilePath) {
        // Double check conditions before starting
        if (!isTtsInitialized || textToSpeech == null || textToSave.isEmpty() || imageFilePath == null) {
            Toast.makeText(this, "Cannot save audio: preconditions not met.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "saveTextAsAudio failed preconditions: isTtsInitialized=" + isTtsInitialized + ", textToSpeech null=" + (textToSpeech == null) + ", textToSave empty=" + textToSave.isEmpty() + ", imageFilePath null=" + (imageFilePath == null));
            releaseResourcesAndFinish(); // Go back anyway
            return;
        }

        File imageFile = new File(imageFilePath);
        if (!imageFile.exists()) {
            Toast.makeText(this, "Cannot save audio: Linked image file not found.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "saveTextAsAudio failed: Linked image file does not exist at path: " + imageFilePath);
            releaseResourcesAndFinish(); // Go back anyway
            return;
        }

        File audioFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Audio");
        if (!audioFolder.exists()) {
            boolean created = audioFolder.mkdirs();
            if (created) {
                Log.d(TAG, "Created audio directory: " + audioFolder.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create audio directory: " + audioFolder.getAbsolutePath());
                runOnUiThread(() -> Toast.makeText(this, "Failed to create audio folder.", Toast.LENGTH_SHORT).show());
                releaseResourcesAndFinish(); // Go back anyway
                return;
            }
        }

        String imageFileName = imageFile.getName();
        String baseName = imageFileName.substring(0, imageFileName.lastIndexOf('.'));
        String audioFileName = baseName.replace("IMG_", "AUDIO_") + ".mp3"; // Ensure naming matches
        File audioFile = new File(audioFolder, audioFileName);

        // Log details before synthesis
        Log.d(TAG, "Attempting to save audio to: " + audioFile.getAbsolutePath());
        Log.d(TAG, "Text to synthesize (" + textToSave.length() + " chars): " + textToSave.substring(0, Math.min(textToSave.length(), 100)) + (textToSave.length() > 100 ? "..." : "")); // Log first 100 chars


        // Use UtteranceProgressListener to monitor synthesis progress
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "audioSaveUtteranceId"); // Use a unique utterance ID

        // Set the listener. This listener will be called for the synthesisToFile task.
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "TTS synthesis to file started: " + utteranceId);
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Saving audio...", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "TTS synthesis to file done: " + utteranceId);
                // Verify the file size after completion
                File savedFile = new File(audioFolder, audioFileName); // Recreate File object to get current state
                if (savedFile.exists() && savedFile.length() > 0) {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Audio saved successfully!", Toast.LENGTH_LONG).show());
                    Log.d(TAG, "Saved audio file size: " + savedFile.length() + " bytes");
                } else {
                    runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Audio file saved but is empty or missing.", Toast.LENGTH_LONG).show());
                    Log.e(TAG, "Saved audio file is empty or missing after synthesis. Path: " + savedFile.getAbsolutePath() + ", Exists: " + savedFile.exists() + ", Length: " + savedFile.length());
                }

                // Now release resources and finish the activity AFTER synthesis is done (success or empty file)
                releaseResourcesAndFinish();
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS synthesis to file error: " + utteranceId);
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Failed to save audio (synthesis error).", Toast.LENGTH_LONG).show());
                // Even on error during synthesis, release resources and finish
                releaseResourcesAndFinish();
            }

            // onStop is called when stop() is called, onError is for synthesis errors
            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                Log.d(TAG, "TTS synthesis to file stopped: " + utteranceId + ", interrupted: " + interrupted);
                // If stopped, it means synthesis didn't complete, so treat as failure to save fully
                runOnUiThread(() -> Toast.makeText(CameraActivity.this, "Audio saving stopped unexpectedly.", Toast.LENGTH_LONG).show());
                // Release resources and finish
                releaseResourcesAndFinish();
            }

            // Add other required override methods for UtteranceProgressListener for API compatibility
            @Override
            public void onAudioAvailable(String utteranceId, byte[] audio) {
                // Optional: Called as audio data becomes available
            }

            @Override
            public void onBeginSynthesis(String utteranceId, int sampleRateInHz, int audioFormat, int channelCount) {
                // Optional: Called when synthesis begins
                Log.d(TAG, "TTS synthesis began: " + utteranceId + ", sampleRate: " + sampleRateInHz + ", audioFormat: " + audioFormat + ", channels: " + channelCount);
            }

            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                // Optional: Called to indicate text range being spoken
            }

        });

        // Call synthesizeToFile with parameters and the listener is now set
        int result = textToSpeech.synthesizeToFile(textToSave, params, audioFile, "audioSaveUtteranceId");


        if (result == TextToSpeech.SUCCESS) {
            Log.d(TAG, "synthesizeToFile call successful. Waiting for UtteranceProgressListener callbacks.");
            // Do NOT release resources and finish here, it's done in onDone/onError/onStop callbacks
        } else {
            Log.e(TAG, "synthesizeToFile call failed immediately, result code: " + result);
            runOnUiThread(() -> Toast.makeText(this, "Failed to save audio (synthesizeToFile call failed).", Toast.LENGTH_SHORT).show());
            // Release resources and finish if the initial call fails
            releaseResourcesAndFinish();
        }
    }


    // Release ML Kit and TTS resources and finish the activity
    private void releaseResourcesAndFinish() {
        Log.d(TAG, "Releasing resources and finishing activity.");
        // Release ML Kit resources
        if (textRecognizer != null) {
            textRecognizer.close();
            Log.d(TAG, "TextRecognizer closed.");
        }

        // Release TTS resources
        if (textToSpeech != null) {
            // Stop speaking if it was speaking immediately
            if (textToSpeech.isSpeaking()) {
                textToSpeech.stop();
                Log.d(TAG, "Stopping TTS speaking.");
            }
            // IMPORTANT: Remove the UtteranceProgressListener to prevent leaks/unexpected behavior
            // when the activity is finishing but the listener might still receive callbacks.
            textToSpeech.setOnUtteranceProgressListener(null); // Remove listener
            textToSpeech.shutdown();
            isTtsInitialized = false;
            Log.d(TAG, "TextToSpeech shut down.");
        }

        // Reset state variables
        extractedText = ""; // Clear extracted text
        lastCapturedImageFilePath = null; // Reset the last captured image path

        // Call the super method to actually go back
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Activity onDestroy.");
        // Ensure resources are released if activity is destroyed for other reasons
        releaseResourcesAndFinish(); // Call the same cleanup method
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "Activity onStop.");
        // Consider stopping TTS playback if the activity goes into the background
        if (textToSpeech != null && textToSpeech.isSpeaking()) {
            textToSpeech.stop();
            Log.d(TAG, "Stopping TTS playback on onStop.");
            // Note: If you stop TTS here, and the user returns quickly, you might want to resume playback
            // or restart it. This depends on desired behavior.
        }
    }


    // Handle permission request results
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            boolean cameraGranted = false;
            boolean storageGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.CAMERA) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraGranted = true;
                }
                // Use WRITE_EXTERNAL_STORAGE for saving
                if (permissions[i].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    storageGranted = true;
                }
                // Also check for READ_EXTERNAL_STORAGE if you might read files directly later (though WRITE implies READ generally)
                // if (permissions[i].equals(Manifest.permission.READ_EXTERNAL_STORAGE) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                //    // readStorageGranted = true;
                // }
            }

            if (cameraGranted) { // Check if both necessary permissions are granted
                startCamera();
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Camera and Storage permissions are required to use this feature.", Toast.LENGTH_LONG).show();
                // Optionally disable capture button or finish activity if permissions are not granted
                // For accessibility, you might want to provide spoken feedback here too.
            }
        }
    }
}
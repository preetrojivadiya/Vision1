package com.example.vision1;


import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class StorageActivity extends AppCompatActivity implements FileAdapter.OnItemClickListener { // Implement the click listener

    private static final String TAG = "StorageActivity";
    private File visionDocumentsFolder; // The directory where files are stored

    // Changed from FileAdapter fileAdapter;
    private FileAdapter fileAdapter;

    // Changed from List<File> fileList = new ArrayList<>();
    // List to hold custom StoredDocument objects
    private List<StoredDocument> documentList = new ArrayList<>();

    private WorkManager workManager;

    // MediaPlayer for audio playback
    private MediaPlayer mediaPlayer;
    // Keep track of the document whose audio is currently playing
    private StoredDocument currentlyPlayingDocument = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);

        // Initialize WorkManager instance
        workManager = WorkManager.getInstance(getApplicationContext());

        // --- Setup the storage directory ---
        // Using getExternalFilesDir is recommended for app-specific files
        // as it doesn't require explicit WRITE_EXTERNAL_STORAGE permission on modern Android.
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) {
            Log.e(TAG, "External documents directory not available.");
            Toast.makeText(this, "Storage not available.", Toast.LENGTH_LONG).show();
            // Consider disabling file adding/loading features if storage is not available
            return; // Exit onCreate if storage is critical
        }

        File visionFolder = new File(documentsDir, "Vision");
        visionDocumentsFolder = new File(visionFolder, "Documents");

        if (!visionDocumentsFolder.exists()) {
            boolean created = visionDocumentsFolder.mkdirs();
            if (created) {
                Log.d(TAG, "Documents folder created successfully at: " + visionDocumentsFolder.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create Documents folder at: " + visionDocumentsFolder.getAbsolutePath());
                Toast.makeText(this, "Failed to create app storage folder.", Toast.LENGTH_LONG).show();
                // Handle error: maybe disable adding documents
            }
        }
        // --- End Setup storage directory ---


        // Setup RecyclerView
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Changed adapter initialization - pass the document list and 'this' as the listener
        fileAdapter = new FileAdapter(this, documentList, this);
        recyclerView.setAdapter(fileAdapter);

        // Load existing documents initially
        loadDocuments();

        // Set up the "Add Document" button click listener
        Button addDocumentButton = findViewById(R.id.btnAddDocument);
        addDocumentButton.setOnClickListener(view -> openFilePicker());

        // Observe WorkManager tasks related to document processing
        observeDocumentProcessingWork();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release MediaPlayer resources when the activity is destroyed
        releaseMediaPlayer();
        // Consider shutting down TTS if you initialized it directly in the Activity (though it's in Worker now)
    }


     //Releases the MediaPlayer resources.

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release(); // Release the MediaPlayer instance
            mediaPlayer = null;
            currentlyPlayingDocument = null; // Clear the currently playing document reference
        }
    }


    //Handles the result from the file picker.

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        // Instead of copying here, start the processing worker
                        startDocumentProcessing(selectedFileUri);
                    } else {
                        Toast.makeText(this, "No file selected.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.d(TAG, "File picking cancelled or failed.");
                    Toast.makeText(this, "File selection cancelled.", Toast.LENGTH_SHORT).show();
                }
            });


     //Opens the system file picker to select a document.

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Use specific MIME types if you only support certain document types
        intent.setType("*/*"); // Allows selection of any file type

        // Add extra for showing only local files if needed (behavior can vary)
        // intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

        try {
            filePickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching file picker: " + e.getMessage(), e);
            Toast.makeText(this, "Could not open file picker.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Starts the background WorkManager task to process the selected document.
     *
     * @param fileUri The URI of the selected file.
     */
    private void startDocumentProcessing(Uri fileUri) {
        // Create input data for the worker, including the file URI
        Data inputData = new Data.Builder()
                .putString(DocumentProcessingWorker.INPUT_URI, fileUri.toString())
                .build();

        // Create a unique tag for this type of work, or even a unique ID for each request
        String workTag = "document_processing";

        // Create a Work Request for the DocumentProcessingWorker
        OneTimeWorkRequest processRequest = new OneTimeWorkRequest.Builder(DocumentProcessingWorker.class)
                .setInputData(inputData)
                .addTag(workTag) // Add a tag to easily observe this type of work
                // You can add constraints here (e.g., requires network, battery)
                // .setConstraints(...)
                .build();

        // Enqueue the work request with WorkManager
        workManager.enqueue(processRequest);

        // --- Optional: Immediately add a processing entry to the list for UI feedback ---
        // This provides instant feedback to the user that processing has started.
        // We need a temporary StoredDocument object. The worker will later provide the final path.
        // Getting the file name from the URI immediately is helpful for this temporary entry.
        String fileName = getFileNameFromUri(fileUri);
        // Create a temporary path representation. The worker will create the actual file.
        // This temporary path might not exist on disk yet.
        File tempFileRepresentation = new File(visionDocumentsFolder, fileName);

        // Create a StoredDocument with the processing state true
        StoredDocument processingDoc = new StoredDocument(tempFileRepresentation.getAbsolutePath());
        processingDoc.setProcessing(true);

        // Add this temporary entry to the top of the list and notify the adapter
        documentList.add(0, processingDoc);
        fileAdapter.notifyItemInserted(0);
        // Scroll to the top to show the new item (optional)
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.scrollToPosition(0);

        Toast.makeText(this, "Processing document: " + fileName, Toast.LENGTH_SHORT).show();
        // --- End Optional UI Feedback ---
    }


    /**
     * Loads existing documents (and their associated audio files) from the storage folder.
     */
    private void loadDocuments() {
        documentList.clear(); // Clear the current list
        if (visionDocumentsFolder.exists() && visionDocumentsFolder.isDirectory()) {
            File[] files = visionDocumentsFolder.listFiles();
            if (files != null) {
                // Sort files if needed (e.g., by last modified date, newest first)
                Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());

                for (File file : files) {
                    // We only want to add the original document file to the list.
                    // The audio file will be linked to it.
                    // Check if the file name ends with "_audio.mp3" (our naming convention)
                    if (!file.getName().toLowerCase().endsWith("_audio.mp3")) {
                        String originalFilePath = file.getAbsolutePath();

                        // Construct the expected audio file path based on naming convention
                        String baseName = file.getName();
                        int dotIndex = baseName.lastIndexOf('.');
                        if (dotIndex > 0) {
                            baseName = baseName.substring(0, dotIndex);
                        }
                        String audioFileName = baseName + "_audio.mp3";
                        File audioFile = new File(visionDocumentsFolder, audioFileName);

                        // Create the StoredDocument object
                        StoredDocument doc = new StoredDocument(originalFilePath);

                        // Check if the corresponding audio file exists and set the audio path
                        if (audioFile.exists() && audioFile.length() > 0) {
                            doc.setAudioFilePath(audioFile.getAbsolutePath());
                        }

                        // Add the document to our list
                        documentList.add(doc);
                    }
                }
            } else {
                Log.d(TAG, "Documents folder is empty or listFiles returned null.");
            }
        } else {
            Log.d(TAG, "Documents folder does not exist or is not a directory.");
        }
        // Notify the adapter that the data set has changed
        fileAdapter.updateDocumentList(documentList);
    }


     //Observes the state of WorkManager tasks to update the UI.

    private void observeDocumentProcessingWork() {
        // Get LiveData for WorkInfos by the tag we used when enqueuing
        workManager.getWorkInfosByTagLiveData("document_processing").observe(this, workInfos -> {
            if (workInfos == null || workInfos.isEmpty()) {
                return; // No work info found
            }

            // Iterate through the list of WorkInfo objects
            for (WorkInfo workInfo : workInfos) {
                Data outputData = workInfo.getOutputData();
                // Get the original file path from the worker's output data
                String originalPath = outputData.getString(DocumentProcessingWorker.OUTPUT_ORIGINAL_PATH);

                // Find the index of the corresponding StoredDocument in our list
                // We need to match based on the original file path
                int index = -1;
                // Iterate backwards to safely remove/update if needed, or just update
                for (int i = documentList.size() - 1; i >= 0; i--) {
                    StoredDocument document = documentList.get(i);
                    // Match using the original file path
                    if (document.getOriginalFilePath().equals(originalPath)) {
                        index = i;
                        break; // Found the document
                    }
                    // Optional: If you used a unique ID in StoredDocument and passed it to the worker,
                    // you could match by ID instead for better reliability.
                }

                // If the document was found in our list
                if (index != -1) {
                    StoredDocument documentToUpdate = documentList.get(index);

                    // Update the StoredDocument based on the WorkInfo state
                    if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                        Log.d(TAG, "Worker SUCCEEDED for " + originalPath);
                        String audioPath = outputData.getString(DocumentProcessingWorker.OUTPUT_AUDIO_PATH);
                        documentToUpdate.setAudioFilePath(audioPath); // Set the generated audio path
                        documentToUpdate.setProcessing(false); // Processing is complete
                        fileAdapter.notifyItemChanged(index); // Notify adapter to update this item

                    } else if (workInfo.getState() == WorkInfo.State.FAILED) {
                        Log.e(TAG, "Worker FAILED for " + originalPath);
                        documentToUpdate.setAudioFilePath(null); // No audio on failure
                        documentToUpdate.setProcessing(false); // Processing is complete
                        fileAdapter.notifyItemChanged(index); // Notify adapter to update this item
                        Toast.makeText(this, "Failed to process document: " + new File(originalPath).getName(), Toast.LENGTH_LONG).show();

                    } else if (workInfo.getState() == WorkInfo.State.CANCELLED) {
                        Log.w(TAG, "Worker CANCELLED for " + originalPath);
                        documentToUpdate.setAudioFilePath(null); // No audio on cancellation
                        documentToUpdate.setProcessing(false); // Processing is complete
                        fileAdapter.notifyItemChanged(index); // Notify adapter to update this item
                        Toast.makeText(this, "Document processing cancelled: " + new File(originalPath).getName(), Toast.LENGTH_LONG).show();

                    } else if (workInfo.getState() == WorkInfo.State.RUNNING) {
                        // The worker is currently running
                        Log.d(TAG, "Worker RUNNING for " + originalPath);
                        documentToUpdate.setProcessing(true); // Ensure processing state is true
                        fileAdapter.notifyItemChanged(index); // Update UI to show progress

                    }
                    // Add other states like ENQUEUED if you want to show a pending state

                } else {
                    // This scenario might occur if the app was closed and reopened
                    // while a worker for a new file was running.
                    // The 'processing' item added in startDocumentProcessing might be gone.
                    // If a worker finishes for a document not currently in our list,
                    // it means a new document was successfully processed while the app was backgrounded/closed.
                    // In this case, the simplest approach is to reload the entire list to capture the new document.
                    if (workInfo.getState().isFinished()) { // Check if the worker is in a finished state (SUCCEEDED, FAILED, CANCELLED)
                        Log.d(TAG, "Worker finished for an item not found in the current list. Reloading list.");
                        loadDocuments(); // Reload the list to add the newly processed document
                    }
                }
            }
        });
    }


    /**
     * Helper method to get the display name from a file URI.
     * Used when a file is selected from the picker.
     *
     * @param uri The URI of the selected file.
     * @return The file name string.
     */
    private String getFileNameFromUri(Uri uri) {
        String fileName = "unknown_file";
        // Use OpenableColumns to get the display name from a content URI
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file name from URI", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return fileName;
    }


    // --- Implementation of FileAdapter.OnItemClickListener methods ---

    /**
     * Handles clicks on the original file information area in a list item.
     * Should open the original document.
     *
     * @param document The StoredDocument associated with the clicked item.
     */
    @Override
    public void onFileClick(StoredDocument document) {
        Log.d(TAG, "File clicked: " + document.getOriginalFilePath());
        // Open the original file
        openFile(new File(document.getOriginalFilePath()));
    }

    /**
     * Handles clicks on the audio play button in a list item.
     * Should play the associated audio file.
     *
     * @param document The StoredDocument associated with the clicked item.
     */
    @Override
    public void onAudioPlayClick(StoredDocument document) {
        Log.d(TAG, "Audio play clicked for: " + document.getOriginalFilePath());
        if (document.getAudioFilePath() != null) {
            playAudio(new File(document.getAudioFilePath()), document);
        } else {
            Log.w(TAG, "Audio file path is null for document: " + document.getOriginalFilePath());
            Toast.makeText(this, "Audio not available for this document.", Toast.LENGTH_SHORT).show();
        }
    }

    // --- End Implementation of FileAdapter.OnItemClickListener methods ---


    /**
     * Opens a file using an Intent.
     * Requires a FileProvider for sharing files from app-specific storage.
     *
     * @param file The File object to open.
     */
    private void openFile(File file) {
        if (!file.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "File not found at: " + file.getAbsolutePath());
            return;
        }
        // Use FileProvider to get a content URI for the file.
        // This is crucial for securely sharing files from your app's internal/external storage
        // with other apps, especially on modern Android (Nougat and above).
        Uri fileUri;
        try {
            // Authority must match the authority defined in your AndroidManifest.xml FileProvider
            fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", file);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "The selected file can't be shared via FileProvider: " + file.getAbsolutePath(), e);
            Toast.makeText(this, "Cannot open this file type or location.", Toast.LENGTH_SHORT).show();
            return;
        }


        Intent intent = new Intent(Intent.ACTION_VIEW);
        // Determine MIME type based on file extension to help the system find a suitable app
        String mimeType = getMimeType(file.getAbsolutePath());
        intent.setDataAndType(fileUri, mimeType);

        // Grant read permissions to the receiving app for this specific URI
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Add flag to open in a new task (optional, but common for ACTION_VIEW)
        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);


        try {
            startActivity(intent);
        } catch (Exception e) {
            // Catch ActivityNotFoundException or other issues if no app can handle the intent
            Log.e(TAG, "No application found to open this file type (" + mimeType + "): " + e.getMessage(), e);
            Toast.makeText(this, "No application found to open this file type.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Helper to get the MIME type of a file based on its extension.
     *
     * @param url The file path or URL string.
     * @return The MIME type string, or "* /*" if unknown.
     */
    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.US)); // Use Locale.US
        }
        return type != null ? type : "*/*"; // Default to generic if type is unknown
    }


    /**
     * Plays an audio file using MediaPlayer.
     * Manages stopping the currently playing audio if a new one is selected.
     *
     * @param audioFile The audio File object to play.
     * @param document  The StoredDocument object this audio belongs to.
     */
    private void playAudio(File audioFile, StoredDocument document) {
        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file not found.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Audio file not found at: " + audioFile.getAbsolutePath());
            return;
        }

        // Stop currently playing audio if any
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            // Check if the clicked document's audio is already playing
            if (currentlyPlayingDocument != null && currentlyPlayingDocument.equals(document)) {
                // Clicking the same audio file - maybe pause or stop? Let's stop and restart.
                Log.d(TAG, "Stopping currently playing audio for the same document.");
                releaseMediaPlayer(); // Release and prepare to play again from start
            } else {
                // Playing a different file - stop the current one and release
                Log.d(TAG, "Stopping currently playing audio for a different document.");
                releaseMediaPlayer();
            }
        }

        // Initialize and start new playback
        try {
            mediaPlayer = new MediaPlayer();
            // Set the data source using the file path
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());

            // Prepare the player asynchronously to avoid blocking the UI thread
            mediaPlayer.prepareAsync();

            // Listener for when the media player is prepared
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start(); // Start playback when prepared
                currentlyPlayingDocument = document; // Set the currently playing document
                Log.d(TAG, "Audio playback started for: " + audioFile.getName());
                Toast.makeText(this, "Playing audio.", Toast.LENGTH_SHORT).show();
            });

            // Listener for when playback completes
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "Audio playback completed.");
                releaseMediaPlayer(); // Release resources after playback
                Toast.makeText(this, "Audio playback finished.", Toast.LENGTH_SHORT).show();
            });

            // Listener for playback errors
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra + " for file: " + audioFile.getName());
                Toast.makeText(this, "Error playing audio.", Toast.LENGTH_SHORT).show();
                releaseMediaPlayer(); // Release resources on error
                return true; // Return true to indicate the error was handled
            });

        } catch (IOException e) {
            Log.e(TAG, "Error setting data source or preparing MediaPlayer for " + audioFile.getName() + ": " + e.getMessage(), e);
            Toast.makeText(this, "Error playing audio.", Toast.LENGTH_SHORT).show();
            releaseMediaPlayer(); // Release resources on error
        } catch (Exception e) {
            // Catch any other unexpected errors during setup/playback
            Log.e(TAG, "Unexpected error during MediaPlayer setup for " + audioFile.getName() + ": " + e.getMessage(), e);
            Toast.makeText(this, "An error occurred during audio playback.", Toast.LENGTH_SHORT).show();
            releaseMediaPlayer();
        }
    }

    // Re-use the getVisionDocumentsFolder method from the Worker if needed elsewhere,
    // but in this activity, we already set it up in onCreate.

}
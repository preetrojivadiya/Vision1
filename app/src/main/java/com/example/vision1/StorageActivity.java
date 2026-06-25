package com.example.vision1;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class StorageActivity extends AppCompatActivity implements FileAdapter.OnItemInteractionListener {

    private static final String TAG = "StorageActivity";
    private File visionDocumentsFolder;

    private FileAdapter fileAdapter;
    private List<StorageItem> mainStorageList = new ArrayList<>();
    private WorkManager workManager;
    private MediaPlayer mediaPlayer;
    private StoredDocument currentlyPlayingDocument = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);

        // Remove VISION top bar text
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        workManager = WorkManager.getInstance(getApplicationContext());

        // Setup File Directory
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) return;
        File visionFolder = new File(documentsDir, "Vision");
        visionDocumentsFolder = new File(visionFolder, "Documents");
        if (!visionDocumentsFolder.exists()) visionDocumentsFolder.mkdirs();

        // Setup Grid RecyclerView (Span Count 3 for uniform squares)
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));

        fileAdapter = new FileAdapter(this, mainStorageList, this);
        recyclerView.setAdapter(fileAdapter);

        loadFilesAndCollections();

        Button addDocumentButton = findViewById(R.id.btnAddDocument);
        addDocumentButton.setOnClickListener(view -> openFilePicker());

        observeDocumentProcessingWork();
    }

    // =============================
    // DRAG AND DROP & COLLECTIONS
    // =============================

    @Override
    public void onItemDropped(StorageItem draggedItem, StorageItem targetItem) {
        if (!(draggedItem instanceof StoredDocument)) return; // Prevents dragging folders

        StoredDocument draggedDoc = (StoredDocument) draggedItem;

        if (targetItem instanceof StoredDocument) {
            // MERGE: Dropped Document onto Document -> Create New Collection
            StoredDocument targetDoc = (StoredDocument) targetItem;

            DocumentCollection newCollection = new DocumentCollection("New Collection");
            newCollection.addDocument(draggedDoc);
            newCollection.addDocument(targetDoc);

            mainStorageList.remove(draggedDoc);
            mainStorageList.remove(targetDoc);
            mainStorageList.add(0, newCollection); // Add folder to beginning of grid

            saveCollectionsToPrefs();
            fileAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Collection Created", Toast.LENGTH_SHORT).show();

        } else if (targetItem instanceof DocumentCollection) {
            // MERGE: Dropped Document onto Collection -> Add to Collection
            DocumentCollection collection = (DocumentCollection) targetItem;
            collection.addDocument(draggedDoc);

            mainStorageList.remove(draggedDoc);

            saveCollectionsToPrefs();
            fileAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Added to " + collection.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCollectionClick(DocumentCollection collection) {
        // Open a dialog showing the documents inside the folder
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_collection);

        androidx.appcompat.widget.AppCompatTextView title = dialog.findViewById(R.id.dialog_title);
        title.setText(collection.getName());

        RecyclerView rv = dialog.findViewById(R.id.collection_recycler);
        rv.setLayoutManager(new GridLayoutManager(this, 3));

        // Pass only the documents inside this collection to a fresh adapter
        List<StorageItem> collectionItems = new ArrayList<>(collection.getDocuments());
        FileAdapter dialogAdapter = new FileAdapter(this, collectionItems, new FileAdapter.OnItemInteractionListener() {
            @Override public void onFileClick(StoredDocument document) { openFile(new File(document.getOriginalFilePath())); }
            @Override public void onAudioPlayClick(StoredDocument document) { playAudio(new File(document.getAudioFilePath()), document); }
            @Override public void onCollectionClick(DocumentCollection c) {} // Ignored inside dialog
            @Override public void onCollectionLongClick(DocumentCollection c, View v) {} // Ignored
            @Override public void onItemDropped(StorageItem dragged, StorageItem target) {} // Drag disabled inside folder view
        });
        rv.setAdapter(dialogAdapter);
        dialog.show();
    }

    @Override
    public void onCollectionLongClick(DocumentCollection collection, View anchorView) {
        PopupMenu popup = new PopupMenu(this, anchorView);
        popup.getMenu().add(Menu.NONE, 1, 1, "Edit Name");
        popup.getMenu().add(Menu.NONE, 2, 2, "Delete Collection");

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                // EDIT
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Edit Collection Name");
                final EditText input = new EditText(this);
                input.setText(collection.getName());
                builder.setView(input);
                builder.setPositiveButton("Save", (dialog, which) -> {
                    collection.setName(input.getText().toString());
                    saveCollectionsToPrefs();
                    fileAdapter.notifyDataSetChanged();
                });
                builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
                builder.show();
            } else if (item.getItemId() == 2) {
                // DELETE
                mainStorageList.remove(collection);
                // Extract all documents back to the main layout
                mainStorageList.addAll(collection.getDocuments());
                saveCollectionsToPrefs();
                fileAdapter.notifyDataSetChanged();
                Toast.makeText(this, "Collection Deleted", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        popup.show();
    }

    // =============================
    // SAVING AND LOADING (JSON)
    // =============================

    private void saveCollectionsToPrefs() {
        SharedPreferences prefs = getSharedPreferences("VisionStorage", MODE_PRIVATE);
        JSONArray jsonArray = new JSONArray();
        try {
            for (StorageItem item : mainStorageList) {
                if (item instanceof DocumentCollection) {
                    DocumentCollection col = (DocumentCollection) item;
                    JSONObject colObj = new JSONObject();
                    colObj.put("id", col.getId());
                    colObj.put("name", col.getName());
                    JSONArray docsArray = new JSONArray();
                    for (StoredDocument d : col.getDocuments()) {
                        docsArray.put(d.getOriginalFilePath());
                    }
                    colObj.put("documents", docsArray);
                    jsonArray.put(colObj);
                }
            }
            prefs.edit().putString("collections_data", jsonArray.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error saving collections", e);
        }
    }

    private void loadFilesAndCollections() {
        mainStorageList.clear();
        List<StoredDocument> physicalFiles = new ArrayList<>();

        // 1. Load physical files from disk
        if (visionDocumentsFolder.exists() && visionDocumentsFolder.isDirectory()) {
            File[] files = visionDocumentsFolder.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                for (File file : files) {
                    if (!file.getName().toLowerCase().endsWith("_audio.mp3")) {
                        StoredDocument doc = new StoredDocument(file.getAbsolutePath());

                        String baseName = file.getName();
                        int dotIndex = baseName.lastIndexOf('.');
                        if (dotIndex > 0) baseName = baseName.substring(0, dotIndex);
                        File audioFile = new File(visionDocumentsFolder, baseName + "_audio.mp3");

                        if (audioFile.exists() && audioFile.length() > 0) {
                            doc.setAudioFilePath(audioFile.getAbsolutePath());
                        }
                        physicalFiles.add(doc);
                    }
                }
            }
        }

        // 2. Load logic groupings (Collections) from JSON
        SharedPreferences prefs = getSharedPreferences("VisionStorage", MODE_PRIVATE);
        String collectionsData = prefs.getString("collections_data", "[]");

        try {
            JSONArray jsonArray = new JSONArray(collectionsData);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject colObj = jsonArray.getJSONObject(i);
                DocumentCollection collection = new DocumentCollection(colObj.getString("id"), colObj.getString("name"));

                JSONArray docsArray = colObj.getJSONArray("documents");
                for (int j = 0; j < docsArray.length(); j++) {
                    String path = docsArray.getString(j);
                    // Find document in physical files and move it to collection
                    for (int k = 0; k < physicalFiles.size(); k++) {
                        if (physicalFiles.get(k).getOriginalFilePath().equals(path)) {
                            collection.addDocument(physicalFiles.get(k));
                            physicalFiles.remove(k);
                            break;
                        }
                    }
                }
                // Only add collections that aren't empty
                if (!collection.getDocuments().isEmpty()) {
                    mainStorageList.add(collection);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing collections JSON", e);
        }

        // 3. Add any remaining individual physical files not assigned to a collection
        mainStorageList.addAll(physicalFiles);
        fileAdapter.updateList(mainStorageList);
    }

    // =============================
    // EXISTING FILE & WORKER LOGIC
    // =============================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
            currentlyPlayingDocument = null;
        }
    }

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        startDocumentProcessing(selectedFileUri);
                    }
                }
            });

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void startDocumentProcessing(Uri fileUri) {
        Data inputData = new Data.Builder()
                .putString(DocumentProcessingWorker.INPUT_URI, fileUri.toString())
                .build();

        OneTimeWorkRequest processRequest = new OneTimeWorkRequest.Builder(DocumentProcessingWorker.class)
                .setInputData(inputData)
                .addTag("document_processing")
                .build();

        workManager.enqueue(processRequest);

        String fileName = getFileNameFromUri(fileUri);
        File tempFileRepresentation = new File(visionDocumentsFolder, fileName);
        StoredDocument processingDoc = new StoredDocument(tempFileRepresentation.getAbsolutePath());
        processingDoc.setProcessing(true);

        mainStorageList.add(0, processingDoc);
        fileAdapter.notifyItemInserted(0);

        // ADD THIS: Auto-scroll to the top so the user instantly sees the new file
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.smoothScrollToPosition(0);

        Toast.makeText(this, "Processing: " + fileName, Toast.LENGTH_SHORT).show();
    }

    private void observeDocumentProcessingWork() {
        workManager.getWorkInfosByTagLiveData("document_processing").observe(this, workInfos -> {
            if (workInfos == null || workInfos.isEmpty()) return;

            for (WorkInfo workInfo : workInfos) {
                Data outputData = workInfo.getOutputData();
                String originalPath = outputData.getString(DocumentProcessingWorker.OUTPUT_ORIGINAL_PATH);

                int index = -1;
                for (int i = mainStorageList.size() - 1; i >= 0; i--) {
                    if (mainStorageList.get(i) instanceof StoredDocument) {
                        StoredDocument document = (StoredDocument) mainStorageList.get(i);
                        if (document.getOriginalFilePath().equals(originalPath)) {
                            index = i;
                            break;
                        }
                    }
                }

                if (index != -1) {
                    StoredDocument documentToUpdate = (StoredDocument) mainStorageList.get(index);

                    if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                        String audioPath = outputData.getString(DocumentProcessingWorker.OUTPUT_AUDIO_PATH);
                        documentToUpdate.setAudioFilePath(audioPath);
                        documentToUpdate.setProcessing(false);
                        fileAdapter.notifyItemChanged(index);
                    } else if (workInfo.getState() == WorkInfo.State.FAILED || workInfo.getState() == WorkInfo.State.CANCELLED) {
                        documentToUpdate.setAudioFilePath(null);
                        documentToUpdate.setProcessing(false);
                        fileAdapter.notifyItemChanged(index);
                    } else if (workInfo.getState() == WorkInfo.State.RUNNING) {
                        documentToUpdate.setProcessing(true);
                        fileAdapter.notifyItemChanged(index);
                    }
                } else if (workInfo.getState().isFinished()) {
                    loadFilesAndCollections();
                }
            }
        });
    }

    private String getFileNameFromUri(Uri uri) {
        String fileName = "unknown_file";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) fileName = cursor.getString(nameIndex);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file name", e);
        }
        return fileName;
    }

    @Override
    public void onFileClick(StoredDocument document) {
        openFile(new File(document.getOriginalFilePath()));
    }

    @Override
    public void onAudioPlayClick(StoredDocument document) {
        if (document.getAudioFilePath() != null) {
            playAudio(new File(document.getAudioFilePath()), document);
        }
    }

    private void openFile(File file) {
        if (!file.exists()) return;
        Uri fileUri;
        try {
            fileUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        } catch (IllegalArgumentException e) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        String mimeType = getMimeType(file.getAbsolutePath());
        intent.setDataAndType(fileUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No application found to open this file.", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.US));
        }
        return type != null ? type : "*/*";
    }

    private void playAudio(File audioFile, StoredDocument document) {
        if (!audioFile.exists()) return;
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            releaseMediaPlayer();
            if (currentlyPlayingDocument != null && currentlyPlayingDocument.equals(document)) return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                currentlyPlayingDocument = document;
            });
            mediaPlayer.setOnCompletionListener(mp -> releaseMediaPlayer());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                releaseMediaPlayer();
                return true;
            });
        } catch (Exception e) {
            releaseMediaPlayer();
        }
    }
}
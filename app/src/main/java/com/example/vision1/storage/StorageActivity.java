package com.example.vision1.storage;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Menu;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.vision1.pdf.PdfViewerActivity;
import com.example.vision1.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class StorageActivity extends AppCompatActivity implements FileAdapter.OnItemInteractionListener {

    private static final String TAG = "StorageActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private File visionDocumentsFolder;
    private FileAdapter fileAdapter;
    private List<StorageItem> mainStorageList = new ArrayList<>();
    private WorkManager workManager;

    // Restored MediaPlayer for non-PDFs
    private MediaPlayer mediaPlayer;
    private StoredDocument currentlyPlayingDocument = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        workManager = WorkManager.getInstance(getApplicationContext());

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));

        fileAdapter = new FileAdapter(this, mainStorageList, this);
        recyclerView.setAdapter(fileAdapter);

        Button addDocumentButton = findViewById(R.id.btnAddDocument);
        addDocumentButton.setOnClickListener(view -> openFilePicker());

        observeDocumentProcessingWork();
        checkStoragePermissions();
    }

    // =============================
    // AUDIO CONTROLS (NON-PDFs)
    // =============================

    @Override
    public void onAudioPlayClick(StoredDocument document) {
        if (document.getAudioFilePath() != null) {
            playAudio(new File(document.getAudioFilePath()), document);
        }
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
                Toast.makeText(this, "Playing Audio", Toast.LENGTH_SHORT).show();
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

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
            currentlyPlayingDocument = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
    }

    // =============================
    // PERMISSIONS
    // =============================

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                setupStorageAndLoadFiles();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            } else {
                setupStorageAndLoadFiles();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            setupStorageAndLoadFiles();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupStorageAndLoadFiles();
        }
    }

    private void setupStorageAndLoadFiles() {
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) return;
        File visionFolder = new File(documentsDir, "Vision");
        visionDocumentsFolder = new File(visionFolder, "Documents");
        if (!visionDocumentsFolder.exists()) visionDocumentsFolder.mkdirs();
        loadFilesAndCollections();
    }

    // =============================
    // DRAG AND DROP & COLLECTIONS
    // =============================

    @Override
    public void onItemDropped(StorageItem draggedItem, StorageItem targetItem) {
        if (!(draggedItem instanceof StoredDocument)) return;

        StoredDocument draggedDoc = (StoredDocument) draggedItem;

        if (targetItem instanceof StoredDocument) {
            StoredDocument targetDoc = (StoredDocument) targetItem;
            DocumentCollection newCollection = new DocumentCollection("New Collection");
            newCollection.addDocument(draggedDoc);
            newCollection.addDocument(targetDoc);

            mainStorageList.remove(draggedDoc);
            mainStorageList.remove(targetDoc);
            mainStorageList.add(0, newCollection);

            saveCollectionsToPrefs();
            fileAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Collection Created", Toast.LENGTH_SHORT).show();

        } else if (targetItem instanceof DocumentCollection) {
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
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_collection);

        // FIX: Force the dialog window to stretch to full screen width/height
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
            );
        }

        androidx.appcompat.widget.AppCompatTextView title = dialog.findViewById(R.id.dialog_title);
        title.setText(collection.getName());

        // Wire up the new close button
        android.widget.ImageButton btnClose = dialog.findViewById(R.id.btn_close_dialog);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        RecyclerView rv = dialog.findViewById(R.id.collection_recycler);
        rv.setLayoutManager(new GridLayoutManager(this, 3));

        List<StorageItem> collectionItems = new ArrayList<>(collection.getDocuments());

        FileAdapter dialogAdapter = new FileAdapter(this, collectionItems, new FileAdapter.OnItemInteractionListener() {
            @Override public void onFileClick(StoredDocument document) { openExternalOrPdf(document); }
            @Override public void onAudioPlayClick(StoredDocument document) { playAudio(new File(document.getAudioFilePath()), document); }
            @Override public void onCollectionClick(DocumentCollection c) {}
            @Override public void onCollectionLongClick(DocumentCollection c, View v) {}
            @Override public void onItemDropped(StorageItem dragged, StorageItem target) {}
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
                mainStorageList.remove(collection);
                mainStorageList.addAll(collection.getDocuments());
                saveCollectionsToPrefs();
                fileAdapter.notifyDataSetChanged();
            }
            return true;
        });
        popup.show();
    }

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
                    for (StoredDocument d : col.getDocuments()) docsArray.put(d.getOriginalFilePath());
                    colObj.put("documents", docsArray);
                    jsonArray.put(colObj);
                }
            }
            prefs.edit().putString("collections_data", jsonArray.toString()).apply();
        } catch (JSONException e) { }
    }

    private void loadFilesAndCollections() {
        if (visionDocumentsFolder == null) return;
        mainStorageList.clear();
        List<StoredDocument> physicalFiles = new ArrayList<>();

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
                        if (audioFile.exists() && audioFile.length() > 0) doc.setAudioFilePath(audioFile.getAbsolutePath());
                        physicalFiles.add(doc);
                    }
                }
            }
        }

        SharedPreferences prefs = getSharedPreferences("VisionStorage", MODE_PRIVATE);
        try {
            JSONArray jsonArray = new JSONArray(prefs.getString("collections_data", "[]"));
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject colObj = jsonArray.getJSONObject(i);
                DocumentCollection collection = new DocumentCollection(colObj.getString("id"), colObj.getString("name"));
                JSONArray docsArray = colObj.getJSONArray("documents");
                for (int j = 0; j < docsArray.length(); j++) {
                    String path = docsArray.getString(j);
                    for (int k = 0; k < physicalFiles.size(); k++) {
                        if (physicalFiles.get(k).getOriginalFilePath().equals(path)) {
                            collection.addDocument(physicalFiles.get(k));
                            physicalFiles.remove(k);
                            break;
                        }
                    }
                }
                if (!collection.getDocuments().isEmpty()) mainStorageList.add(collection);
            }
        } catch (JSONException e) { }

        mainStorageList.addAll(physicalFiles);
        fileAdapter.updateList(mainStorageList);
    }

    // =============================
    // WORKER LOGIC
    // =============================

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) startDocumentProcessing(selectedFileUri);
                }
            });

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void startDocumentProcessing(Uri fileUri) {
        Data inputData = new Data.Builder().putString(DocumentProcessingWorker.INPUT_URI, fileUri.toString()).build();
        OneTimeWorkRequest processRequest = new OneTimeWorkRequest.Builder(DocumentProcessingWorker.class).setInputData(inputData).addTag("document_processing").build();
        workManager.enqueue(processRequest);

        String fileName = getFileNameFromUri(fileUri);
        File tempFileRepresentation = new File(visionDocumentsFolder, fileName);
        StoredDocument processingDoc = new StoredDocument(tempFileRepresentation.getAbsolutePath());
        processingDoc.setProcessing(true);

        mainStorageList.add(0, processingDoc);
        fileAdapter.notifyItemInserted(0);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.smoothScrollToPosition(0);
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
                        if (document.getOriginalFilePath().equals(originalPath)) { index = i; break; }
                    }
                }

                if (index != -1) {
                    StoredDocument documentToUpdate = (StoredDocument) mainStorageList.get(index);
                    if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                        documentToUpdate.setAudioFilePath(outputData.getString(DocumentProcessingWorker.OUTPUT_AUDIO_PATH));
                        documentToUpdate.setProcessing(false);
                        fileAdapter.notifyItemChanged(index);
                    } else if (workInfo.getState() == WorkInfo.State.FAILED || workInfo.getState() == WorkInfo.State.CANCELLED) {
                        documentToUpdate.setAudioFilePath(null);
                        documentToUpdate.setProcessing(false);
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
        } catch (Exception e) { }
        return fileName;
    }

    @Override
    public void onFileClick(StoredDocument document) {
        openExternalOrPdf(document);
    }

    private void openExternalOrPdf(StoredDocument document) {
        File file = new File(document.getOriginalFilePath());

        if (file.getName().toLowerCase().endsWith(".pdf")) {
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.putExtra("PDF_PATH", file.getAbsolutePath());
            if (document.getAudioFilePath() != null) {
                intent.putExtra("AUDIO_PATH", document.getAudioFilePath());
            }
            startActivity(intent);
        } else {
            if (!file.exists()) return;
            Uri fileUri;
            try { fileUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file); }
            catch (IllegalArgumentException e) { return; }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
            String mimeType = extension != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.US)) : "*/*";

            intent.setDataAndType(fileUri, mimeType != null ? mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(intent); }
            catch (Exception e) { Toast.makeText(this, "No app found to open this.", Toast.LENGTH_SHORT).show(); }
        }
    }
}
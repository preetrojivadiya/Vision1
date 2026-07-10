package com.example.vision1.gallery;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vision1.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ImageGalleryActivity extends AppCompatActivity implements ImageAdapter.OnItemClickListener {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private RecyclerView recyclerView;
    private ImageAdapter imageAdapter;
    private List<SavedItem> savedItemList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.image_gallery);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        recyclerView = findViewById(R.id.recyclerView);

        // Changed to 2 columns as requested
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));

        imageAdapter = new ImageAdapter(this, savedItemList, this);
        recyclerView.setAdapter(imageAdapter);

        checkStoragePermissions();
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                loadSavedItems();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            } else {
                loadSavedItems();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            loadSavedItems();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSavedItems();
        }
    }

    private void loadSavedItems() {
        savedItemList.clear();
        File imageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Images");
        File audioDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vision/Audio");

        if (!audioDir.exists()) audioDir.mkdirs();

        if (imageDir.exists() && imageDir.isDirectory()) {
            File[] imageFiles = imageDir.listFiles();
            if (imageFiles != null) {
                for (File imageFile : imageFiles) {
                    if (imageFile.isFile() && (imageFile.getName().endsWith(".jpg") || imageFile.getName().endsWith(".png"))) {
                        String imageFileName = imageFile.getName();
                        String baseName = imageFileName.substring(0, imageFileName.lastIndexOf('.'));
                        String audioFileName = baseName.replace("IMG_", "AUDIO_") + ".mp3";
                        File audioFile = new File(audioDir, audioFileName);

                        savedItemList.add(new SavedItem(imageFile, audioFile.exists() ? audioFile : null));
                    }
                }
                imageAdapter.notifyDataSetChanged();
            }
        }
    }

    // Opens our new Custom Image Viewer
    @Override
    public void onImageClick(SavedItem item) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra("IMAGE_PATH", item.getImageFile().getAbsolutePath());
        if (item.getAudioFile() != null) {
            intent.putExtra("AUDIO_PATH", item.getAudioFile().getAbsolutePath());
        }
        startActivity(intent);
    }

    // Deletes the Image and the Audio file completely
    @Override
    public void onImageLongClick(SavedItem item, int position) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Image")
                .setMessage("Are you sure you want to delete this image and its audio?")
                .setPositiveButton("Delete", (dialog, which) -> {

                    // 1. Delete physical image file
                    if (item.getImageFile() != null && item.getImageFile().exists()) {
                        item.getImageFile().delete();
                    }

                    // 2. Delete physical audio file
                    if (item.getAudioFile() != null && item.getAudioFile().exists()) {
                        item.getAudioFile().delete();
                    }

                    // 3. Remove from UI
                    savedItemList.remove(position);
                    imageAdapter.notifyItemRemoved(position);
                    imageAdapter.notifyItemRangeChanged(position, savedItemList.size());

                    Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
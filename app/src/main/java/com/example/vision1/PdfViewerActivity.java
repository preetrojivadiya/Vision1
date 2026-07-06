package com.example.vision1;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;

public class PdfViewerActivity extends AppCompatActivity {

    private ImageView pdfPageViewer;
    private PdfRenderer pdfRenderer;
    private PdfRenderer.Page currentPage;
    private int pageIndex = 0;

    private MediaPlayer mediaPlayer;
    private String audioFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        pdfPageViewer = findViewById(R.id.pdf_page_viewer);
        Button btnNext = findViewById(R.id.btn_next_page);
        Button btnPrev = findViewById(R.id.btn_prev_page);
        ImageButton btnPlay = findViewById(R.id.btn_play);
        ImageButton btnPause = findViewById(R.id.btn_pause);
        ImageButton btnExit = findViewById(R.id.btn_exit);

        String pdfFilePath = getIntent().getStringExtra("PDF_PATH");
        audioFilePath = getIntent().getStringExtra("AUDIO_PATH");

        // 1. Initialize PDF Rendering
        try {
            if (pdfFilePath != null) {
                File pdfFile = new File(pdfFilePath);
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(pfd);
                showPage(0);
            }
        } catch (IOException e) {
            Toast.makeText(this, "Error opening PDF", Toast.LENGTH_SHORT).show();
            finish();
        }

        // 2. Setup PDF Controls
        btnNext.setOnClickListener(v -> showPage(pageIndex + 1));
        btnPrev.setOnClickListener(v -> showPage(pageIndex - 1));

        // 3. Setup Audio Controls
        setupAudioPlayer();

        btnPlay.setOnClickListener(v -> {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                Toast.makeText(this, "Playing Audio", Toast.LENGTH_SHORT).show();
            } else if (mediaPlayer == null) {
                Toast.makeText(this, "No Audio Available", Toast.LENGTH_SHORT).show();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        });

        btnExit.setOnClickListener(v -> finish());
    }

    private void showPage(int index) {
        if (pdfRenderer == null || index < 0 || index >= pdfRenderer.getPageCount()) return;

        if (currentPage != null) currentPage.close();

        pageIndex = index;
        currentPage = pdfRenderer.openPage(index);

        // Render bitmap with higher resolution for clarity
        Bitmap bitmap = Bitmap.createBitmap(
                getResources().getDisplayMetrics().densityDpi * currentPage.getWidth() / 72,
                getResources().getDisplayMetrics().densityDpi * currentPage.getHeight() / 72,
                Bitmap.Config.ARGB_8888);

        // Fill white background (PDFs are transparent by default)
        bitmap.eraseColor(android.graphics.Color.WHITE);

        currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        pdfPageViewer.setImageBitmap(bitmap);
    }

    private void setupAudioPlayer() {
        if (audioFilePath != null && new File(audioFilePath).exists()) {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(audioFilePath);
                mediaPlayer.prepareAsync();
            } catch (IOException e) {
                Log.e("PdfViewer", "Error setting up audio", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentPage != null) currentPage.close();
        if (pdfRenderer != null) pdfRenderer.close();
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
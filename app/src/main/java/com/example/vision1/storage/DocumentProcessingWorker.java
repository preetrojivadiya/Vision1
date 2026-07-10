package com.example.vision1.storage;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.Data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Import PdfBox-Android classes (com.tom_roush)
import com.example.vision1.pdf.PdfConverterUtil;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

// Import Apache POI classes (org.apache.poi)
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

public class DocumentProcessingWorker extends Worker {

    private static final String TAG = "DocProcessingWorker";
    public static final String INPUT_URI = "input_uri";
    public static final String OUTPUT_ORIGINAL_PATH = "output_original_path";
    public static final String OUTPUT_AUDIO_PATH = "output_audio_path";
    public static final String OUTPUT_ERROR_MESSAGE = "error_message";

    private Context context;
    private TextToSpeech tts;

    public DocumentProcessingWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "doWork started.");
        Data inputData = getInputData();
        String uriString = inputData.getString(INPUT_URI);

        if (uriString == null) {
            Log.e(TAG, "Input URI is null. Cannot process document.");
            Data errorOutput = new Data.Builder()
                    .putString(OUTPUT_ERROR_MESSAGE, "Input file URI is missing.")
                    .build();
            return Result.failure(errorOutput);
        }

        Uri fileUri = Uri.parse(uriString);
        Log.d(TAG, "Processing URI: " + fileUri.toString());

        File copiedOriginalFile = null;
        File audioFile = null;

        try {
            // --- Step 0: Initialize PdfBox-Android resources ---
            try {
                PDFBoxResourceLoader.init(context.getApplicationContext());
                Log.d(TAG, "PDFBoxResourceLoader initialized in doWork.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PDFBoxResourceLoader", e);
                Data errorOutput = new Data.Builder()
                        .putString(OUTPUT_ERROR_MESSAGE, "Failed to initialize PDF resources: " + e.getMessage())
                        .build();
                return Result.failure(errorOutput);
            }

            // --- Step 1: Copy & Convert File ---
            String originalFileName = getFileNameFromUri(context, fileUri);

            if (originalFileName == null || originalFileName.isEmpty()) {
                Log.e(TAG, "Could not determine file name from URI.");
                Data errorOutput = new Data.Builder().putString(OUTPUT_ERROR_MESSAGE, "Could not determine file name.").build();
                return Result.failure(errorOutput);
            }

            File visionDocumentsFolder = getVisionDocumentsFolder(context);
            if (visionDocumentsFolder == null) {
                Log.e(TAG, "Could not get vision documents folder.");
                Data errorOutput = new Data.Builder().putString(OUTPUT_ERROR_MESSAGE, "Could not access storage folder.").build();
                return Result.failure(errorOutput);
            }

            // Generate unique temp name to prevent overwrites
            int count = 0;
            String nameWithoutExtension = originalFileName;
            String extension = "";
            int dotIndex = originalFileName.lastIndexOf('.');
            if (dotIndex > 0) {
                nameWithoutExtension = originalFileName.substring(0, dotIndex);
                extension = originalFileName.substring(dotIndex);
            }

            File tempCopiedFile = new File(visionDocumentsFolder, originalFileName);
            while (tempCopiedFile.exists()) {
                count++;
                String base = nameWithoutExtension.length() > 50 ? nameWithoutExtension.substring(0, 50) : nameWithoutExtension;
                tempCopiedFile = new File(visionDocumentsFolder, base + "_" + count + extension);
                if (count > 1000) return Result.failure();
            }

            // 1A. Copy the raw file from the URI
            try (InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
                 FileOutputStream outputStream = new FileOutputStream(tempCopiedFile)) {

                if (inputStream == null) throw new IOException("Failed to open input stream");

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy original file: " + e.getMessage(), e);
                if (tempCopiedFile.exists()) tempCopiedFile.delete();
                Data errorOutput = new Data.Builder()
                        .putString(OUTPUT_ERROR_MESSAGE, "Failed to copy file: " + e.getMessage())
                        .build();
                return Result.failure(errorOutput);
            }

            // 1B. Convert to PDF automatically
            copiedOriginalFile = PdfConverterUtil.convertToPdfIfNeeded(tempCopiedFile, visionDocumentsFolder);
            Log.d(TAG, "Final Working File: " + copiedOriginalFile.getAbsolutePath());

            // --- Step 2: Extract Text from the copied file ---
            Log.d(TAG, "Starting text extraction for: " + copiedOriginalFile.getName());
            String extractedText = extractTextFromFile(copiedOriginalFile);

            if (extractedText == null || extractedText.trim().isEmpty()) {
                Log.w(TAG, "Text extraction failed or produced empty/whitespace text.");
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath())
                        .putString(OUTPUT_AUDIO_PATH, null)
                        .putString(OUTPUT_ERROR_MESSAGE, "Text extraction failed or returned empty.")
                        .build();
                return Result.success(outputData);
            }

            // --- Step 2.5: Clean Extracted Text for TTS ---
            Log.d(TAG, "Cleaning extracted text.");
            String cleanedText = cleanTextForTTS(extractedText);

            if (cleanedText == null || cleanedText.trim().isEmpty()) {
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath())
                        .putString(OUTPUT_AUDIO_PATH, null)
                        .putString(OUTPUT_ERROR_MESSAGE, "Cleaned text is empty.")
                        .build();
                return Result.success(outputData);
            }

            // --- Step 3: Convert Cleaned Text to Speech and Generate Audio File ---
            Log.d(TAG, "Starting audio generation for: " + copiedOriginalFile.getName());
            audioFile = generateAudioFile(cleanedText, copiedOriginalFile.getName());

            // --- Step 4: Build final output Data based on whether audioFile was generated ---
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                Log.d(TAG, "Audio file generated successfully at: " + audioFile.getAbsolutePath());
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath())
                        .putString(OUTPUT_AUDIO_PATH, audioFile.getAbsolutePath())
                        .build();
                return Result.success(outputData);

            } else {
                Log.e(TAG, "Audio file generation failed or resulted in an empty file.");
                if (audioFile != null && audioFile.exists()) {
                    audioFile.delete();
                }

                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath())
                        .putString(OUTPUT_AUDIO_PATH, null)
                        .putString(OUTPUT_ERROR_MESSAGE, "Audio generation failed or returned empty.")
                        .build();
                return Result.success(outputData);
            }

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during document processing: " + e.getMessage(), e);
            if (audioFile != null && audioFile.exists()) {
                audioFile.delete();
            }
            if (copiedOriginalFile != null && copiedOriginalFile.exists()) {
                copiedOriginalFile.delete();
            }

            Data errorOutput = new Data.Builder()
                    .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile != null ? copiedOriginalFile.getAbsolutePath() : "N/A")
                    .putString(OUTPUT_ERROR_MESSAGE, "Unexpected processing error: " + e.getMessage())
                    .build();
            return Result.failure(errorOutput);

        } finally {
            if (tts != null) {
                tts.shutdown();
                Log.d(TAG, "TTS engine shut down.");
            }
        }
    }

    // --- Helper methods ---

    private String getFileNameFromUri(Context context, Uri uri) {
        String fileName = null;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file name from URI", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return fileName;
    }

    private File getVisionDocumentsFolder(Context context) {
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) return null;

        File visionFolder = new File(documentsDir, "Vision");
        File visionDocumentsFolder = new File(visionFolder, "Documents");

        if (!visionDocumentsFolder.exists()) {
            if (!visionDocumentsFolder.mkdirs()) return null;
        } else if (!visionDocumentsFolder.isDirectory()) {
            return null;
        }
        return visionDocumentsFolder;
    }

    private String extractTextFromFile(File file) {
        if (file == null || !file.exists()) return null;

        String filePath = file.getAbsolutePath();
        String lowerCasePath = filePath.toLowerCase(Locale.US);
        StringBuilder text = new StringBuilder();

        try {
            if (lowerCasePath.endsWith(".txt")) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        text.append(line).append("\n");
                    }
                }
            } else if (lowerCasePath.endsWith(".pdf")) {
                PDDocument document = null;
                try {
                    FileInputStream fis = new FileInputStream(file);
                    document = PDDocument.load(fis);
                    if (document.isEncrypted()) return null;

                    PDFTextStripper pdfStripper = new PDFTextStripper();
                    String pdfContent = pdfStripper.getText(document);
                    if (pdfContent != null && !pdfContent.trim().isEmpty()) {
                        text.append(pdfContent);
                    }
                } finally {
                    if (document != null) document.close();
                }
            } else if (lowerCasePath.endsWith(".doc") || lowerCasePath.endsWith(".docx")) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    if (lowerCasePath.endsWith(".docx")) {
                        XWPFDocument document = new XWPFDocument(fis);
                        XWPFWordExtractor extractor = new XWPFWordExtractor(document);
                        text.append(extractor.getText());
                        extractor.close();
                    } else {
                        HWPFDocument document = new HWPFDocument(fis);
                        WordExtractor extractor = new WordExtractor(document);
                        String[] paragraphs = extractor.getParagraphText();
                        for (String para : paragraphs) {
                            if (para != null) text.append(para.trim()).append("\n");
                        }
                        extractor.close();
                    }
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during extraction for " + filePath + ": " + e.getMessage(), e);
            return null;
        }
        return text.toString();
    }

    private String cleanTextForTTS(String text) {
        if (text == null) return null;
        String cleaned = text.replaceAll("[^a-zA-Z0-9\\s.,!?;:]", "");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private File generateAudioFile(String text, String originalFileName) {
        if (text == null || text.trim().isEmpty()) return null;

        File audioDir = getVisionDocumentsFolder(context);
        if (audioDir == null) return null;

        String baseName = originalFileName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) baseName = baseName.substring(0, dotIndex);

        File potentialAudioFile = new File(audioDir, baseName + "_audio.mp3");

        int count = 0;
        while (potentialAudioFile.exists()) {
            count++;
            String base = baseName.length() > 50 ? baseName.substring(0, 50) : baseName;
            potentialAudioFile = new File(audioDir, base + "_" + count + "_audio.mp3");
            if (count > 1000) return null;
        }

        final File finalAudioFile = potentialAudioFile;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);

        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("en", "IN"));

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    latch.countDown();
                    return;
                }

                tts.setSpeechRate(0.9f);

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override
                    public void onDone(String utteranceId) {
                        success.set(true);
                        latch.countDown();
                    }
                    @Override
                    public void onError(String utteranceId) {
                        latch.countDown();
                    }
                });

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    Bundle params = new Bundle();
                    String utteranceId = "tts_" + finalAudioFile.getName() + "_" + System.currentTimeMillis();
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

                    int fileCreationResult = tts.synthesizeToFile(text, params, finalAudioFile, utteranceId);
                    if (fileCreationResult != TextToSpeech.SUCCESS) {
                        latch.countDown();
                    }
                } else {
                    latch.countDown();
                }
            } else {
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(300, TimeUnit.SECONDS);
            if (!completed && finalAudioFile.exists()) {
                finalAudioFile.delete();
            }
        } catch (InterruptedException e) {
            if (finalAudioFile.exists()) finalAudioFile.delete();
        }

        if (success.get() && finalAudioFile.exists() && finalAudioFile.length() > 0) {
            return finalAudioFile;
        } else {
            if (finalAudioFile.exists()) finalAudioFile.delete();
            return null;
        }
    }
}
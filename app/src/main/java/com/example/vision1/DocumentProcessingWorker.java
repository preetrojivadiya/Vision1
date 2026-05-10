package com.example.vision1;

import android.content.Context;
import android.content.Intent; // Needed for TTS language data install prompt (though not directly callable from Worker)
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
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Import PdfBox-Android classes (com.tom_roush)
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader; // Required for initialization

// Import Apache POI classes (org.apache.poi)
import org.apache.poi.hwpf.HWPFDocument; // For .doc files
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument; // For .docx files
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

public class DocumentProcessingWorker extends Worker {

    private static final String TAG = "DocProcessingWorker";
    // Input key for the file URI string
    public static final String INPUT_URI = "input_uri";
    // Output keys for the resulting file paths string
    public static final String OUTPUT_ORIGINAL_PATH = "output_original_path";
    public static final String OUTPUT_AUDIO_PATH = "output_audio_path";
    // Output key for error message (optional)
    public static final String OUTPUT_ERROR_MESSAGE = "error_message";


    private Context context;
    private TextToSpeech tts; // TextToSpeech engine instance

    public DocumentProcessingWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.context = context;
        // TTS engine will be initialized within doWork as needed
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "doWork started.");
        // Get the input file URI from the WorkManager Data
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


        // Declare variables to hold file references
        File copiedOriginalFile = null; // Will hold the path to the copied original file
        File audioFile = null; // Will hold the path to the generated audio file (if successful)

        try {
            // --- Step 0: Initialize PdfBox-Android resources (can be done once in Application class) ---
            // Doing it here ensures it's initialized when the worker runs, but can be less efficient.
            // If you have an Application class, initialize it there instead.
            try {
                // Implement a simple check if needed, or initialize every time (less efficient)
                // The isPdfBoxAndroidInitialized helper method is conceptual;
                // if initializing here, the check might not be needed or reliable.
                // If moving to Application, use a static flag.
                // For this doWork example, we'll call init directly.
                PDFBoxResourceLoader.init(context.getApplicationContext());
                Log.d(TAG, "PDFBoxResourceLoader initialized in doWork.");

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PDFBoxResourceLoader", e);
                Data errorOutput = new Data.Builder()
                        .putString(OUTPUT_ERROR_MESSAGE, "Failed to initialize PDF resources: " + e.getMessage())
                        .build();
                return Result.failure(errorOutput);
            }
            // --- End Initialization ---


            // --- Step 1: Copy the original file to the app's designated storage ---
            // This is crucial because the original URI might become invalid later
            String originalFileName = getFileNameFromUri(context, fileUri);
            if (originalFileName == null || originalFileName.isEmpty()) {
                Log.e(TAG, "Could not determine file name from URI.");
                Data errorOutput = new Data.Builder()
                        .putString(OUTPUT_ERROR_MESSAGE, "Could not determine file name.")
                        .build();
                return Result.failure(errorOutput);
            }
            File visionDocumentsFolder = getVisionDocumentsFolder(context);
            if (visionDocumentsFolder == null) {
                Log.e(TAG, "Could not get vision documents folder.");
                Data errorOutput = new Data.Builder()
                        .putString(OUTPUT_ERROR_MESSAGE, "Could not access storage folder.")
                        .build();
                return Result.failure(errorOutput);
            }

            // Handle potential duplicate names by adding a timestamp or counter
            copiedOriginalFile = new File(visionDocumentsFolder, originalFileName);
            int count = 0;
            String nameWithoutExtension = originalFileName;
            String extension = "";
            int dotIndex = originalFileName.lastIndexOf('.');
            if (dotIndex > 0) {
                nameWithoutExtension = originalFileName.substring(0, dotIndex);
                extension = originalFileName.substring(dotIndex);
            }

            // Loop to find a unique file name if the target file already exists
            while (copiedOriginalFile.exists()) {
                count++;
                // Prevent excessively long names
                String base = nameWithoutExtension.length() > 50 ? nameWithoutExtension.substring(0, 50) : nameWithoutExtension;
                String newFileName = base + "_" + count + extension;
                copiedOriginalFile = new File(visionDocumentsFolder, newFileName);
                // Add a safety break for infinite loops (e.g., if folder is read-only)
                if (count > 1000) {
                    Log.e(TAG, "Excessive attempts to find a unique file name.");
                    Data errorOutput = new Data.Builder()
                            .putString(OUTPUT_ERROR_MESSAGE, "Failed to find unique file name.")
                            .build();
                    return Result.failure(errorOutput);
                }
            }
            Log.d(TAG, "Attempting to copy file to: " + copiedOriginalFile.getAbsolutePath());


            try (InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
                 FileOutputStream outputStream = new FileOutputStream(copiedOriginalFile)) {

                if (inputStream == null) {
                    Log.e(TAG, "Failed to open input stream for URI: " + fileUri);
                    // Clean up the partially created file if exists
                    if (copiedOriginalFile != null && copiedOriginalFile.exists()) {
                        copiedOriginalFile.delete();
                    }
                    Data errorOutput = new Data.Builder()
                            .putString(OUTPUT_ERROR_MESSAGE, "Failed to open input file.")
                            .build();
                    return Result.failure(errorOutput);
                }

                byte[] buffer = new byte[4096];
                int bytesRead;
                // Read from input stream and write to output stream
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                Log.d(TAG, "Original file copied successfully to: " + copiedOriginalFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy original file: " + e.getMessage(), e);
                // Clean up the partially copied file if it exists
                if (copiedOriginalFile != null && copiedOriginalFile.exists()) {
                    copiedOriginalFile.delete();
                }
                Data errorOutput = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile != null ? copiedOriginalFile.getAbsolutePath() : "N/A") // Include path if available
                        .putString(OUTPUT_ERROR_MESSAGE, "Failed to copy file: " + e.getMessage())
                        .build();
                return Result.failure(errorOutput);
            }

            // --- Step 2: Extract Text from the copied file ---
            Log.d(TAG, "Starting text extraction for: " + copiedOriginalFile.getName());
            String extractedText = extractTextFromFile(copiedOriginalFile);
            if (extractedText == null || extractedText.trim().isEmpty()) {
                Log.w(TAG, "Text extraction failed or produced empty/whitespace text for: " + copiedOriginalFile.getName());
                // Even if text extraction fails, the original file was saved.
                // Return success with no audio path.
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath())
                        .putString(OUTPUT_AUDIO_PATH, null) // No audio generated
                        .putString(OUTPUT_ERROR_MESSAGE, "Text extraction failed or returned empty.") // Provide info about failure
                        .build();
                return Result.success(outputData);
            }
            Log.d(TAG, "Successfully extracted text (" + extractedText.length() + " chars) from: " + copiedOriginalFile.getName());
            // Log first few chars to verify extraction (be careful with large texts)
            Log.d(TAG, "Extracted snippet: " + extractedText.substring(0, Math.min(extractedText.length(), 500)) + "...");


            // --- Step 2.5: Clean Extracted Text for TTS ---
            Log.d(TAG, "Cleaning extracted text.");
            String cleanedText = cleanTextForTTS(extractedText);
            if (cleanedText == null || cleanedText.trim().isEmpty()) {
                Log.w(TAG, "Cleaned text is empty after cleaning.");
                // If cleaned text is empty, we cannot generate audio.
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath())
                        .putString(OUTPUT_AUDIO_PATH, null) // No audio generated
                        .putString(OUTPUT_ERROR_MESSAGE, "Cleaned text is empty.") // Provide info about failure
                        .build();
                return Result.success(outputData);
            }
            Log.d(TAG, "Cleaned text (" + cleanedText.length() + " chars) for TTS.");
            // Log cleaned snippet
            Log.d(TAG, "Cleaned snippet: " + cleanedText.substring(0, Math.min(cleanedText.length(), 500)) + "...");


            // --- Step 3: Convert Cleaned Text to Speech and Generate Audio File ---
            Log.d(TAG, "Starting audio generation for: " + copiedOriginalFile.getName());
            // Call generateAudioFile, it returns the audio File object or null
            audioFile = generateAudioFile(cleanedText, copiedOriginalFile.getName()); // Use cleanedText

            // --- Step 4: Build final output Data based on whether audioFile was generated ---
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                Log.d(TAG, "Audio file generated successfully at: " + audioFile.getAbsolutePath());
                // SUCCESS case: Original file copied, audio file generated
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath()) // Use copiedOriginalFile here (in scope)
                        .putString(OUTPUT_AUDIO_PATH, audioFile.getAbsolutePath()) // Use the successfully generated audioFile path
                        .build();
                Log.d(TAG, "doWork finished successfully with audio.");
                return Result.success(outputData);

            } else {
                // Audio file generation failed or resulted in an empty file
                Log.e(TAG, "Audio file generation failed or resulted in an empty file.");
                // Clean up the empty/failed audio file if it was partially created
                if (audioFile != null && audioFile.exists()) { // Check if audioFile object is not null AND the file exists
                    Log.w(TAG, "Deleting empty/failed audio file: " + audioFile.getAbsolutePath());
                    audioFile.delete();
                } else if (audioFile == null) {
                    Log.w(TAG, "generateAudioFile returned null.");
                }


                // Success case for WorkManager, but without audio (original file was saved)
                Data outputData = new Data.Builder()
                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile.getAbsolutePath()) // Use copiedOriginalFile here
                        .putString(OUTPUT_AUDIO_PATH, null) // Explicitly null audio path
                        .putString(OUTPUT_ERROR_MESSAGE, "Audio generation failed or returned empty.") // Indicate why audio is missing
                        .build();
                Log.d(TAG, "doWork finished successfully without audio (original file saved).");
                return Result.success(outputData); // Return success as the main task (saving original file) completed
            }


        } catch (Exception e) {
            // --- Handle any unexpected exceptions during the entire process (after copy) ---
            Log.e(TAG, "Unexpected error during document processing: " + e.getMessage(), e);
            // Clean up generated audio file if it exists on unexpected error
            if (audioFile != null && audioFile.exists()) {
                Log.w(TAG, "Deleting audio file due to unexpected error: " + audioFile.getAbsolutePath());
                audioFile.delete();
            }
            // Clean up the copied original file if it exists on unexpected error
            // Only delete the original if the error happened after copying but before success
            if (copiedOriginalFile != null && copiedOriginalFile.exists()) {
                Log.w(TAG, "Deleting copied original file due to unexpected error: " + copiedOriginalFile.getAbsolutePath());
                copiedOriginalFile.delete();
            }

            // Build failure Data
            Data errorOutput = new Data.Builder()
                    .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile != null ? copiedOriginalFile.getAbsolutePath() : "N/A") // Include path if available
                    .putString(OUTPUT_ERROR_MESSAGE, "Unexpected processing error: " + e.getMessage())
                    .build();
            Log.e(TAG, "doWork finished with unexpected failure.");
            return Result.failure(errorOutput); // Indicate overall failure
        } finally {
            // --- Ensure resources are cleaned up ---
            // Shutdown TTS engine if it was initialized
            if (tts != null) {
                tts.shutdown();
                Log.d(TAG, "TTS engine shut down.");
            }
            // Log the completion state (this will always run)
            Log.d(TAG, "Document processing task completion reached finally block.");
        }
    }

    // --- Helper methods ---

    /**
     * Simple check to see if PDFBoxResourceLoader has been initialized.
     * This is a heuristic and might not be foolproof depending on the library's internal state.
     * A more robust way is to initialize once in the Application class.
     */
    private static boolean isPdfBoxAndroidInitialized() {
        // PdfBox-Android doesn't expose a public isInitialized method.
        // Checking for a known resource or state might be possible but complex.
        // For simplicity, we rely on the init method not crashing if called multiple times
        // or move initialization to Application.
        // A simple flag could be used if initializing in Application.
        return false; // Always return false here to force init in worker (less efficient)
        // Or, if initializing in Application.onCreate():
        // public static volatile boolean isInitialized = false; // Declare in Application class
        // return YourApplicationClass.isInitialized; // Check the flag
    }


    /**
     * Retrieves the display name of a file from its URI.
     *
     * @param context The application context.
     * @param uri     The file's content or file URI.
     * @return The file name, or null if retrieval fails.
     */
    private String getFileNameFromUri(Context context, Uri uri) {
        String fileName = null; // Return null on failure
        // Use OpenableColumns to get the display name from a content URI
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
            if (cursor != null) {
                cursor.close();
            }
        }
        return fileName;
    }

    /**
     * Gets the File object representing the "Vision/Documents" folder within getExternalFilesDir.
     * Creates the folder if it doesn't exist.
     *
     * @param context The application context.
     * @return The File object for the documents folder, or null if creation fails.
     */
    private File getVisionDocumentsFolder(Context context) {
        // Using getExternalFilesDir is recommended for app-specific files
        // Change to use Environment.getExternalStoragePublicDirectory if you want files visible in file managers
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS); // Using app-specific storage

        if (documentsDir == null) {
            Log.e(TAG, "getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) returned null.");
            return null;
        }
        File visionFolder = new File(documentsDir, "Vision");
        File visionDocumentsFolder = new File(visionFolder, "Documents");


        if (!visionDocumentsFolder.exists()) {
            boolean created = visionDocumentsFolder.mkdirs();
            if (created) {
                Log.d(TAG, "Documents folder created successfully at: " + visionDocumentsFolder.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create Documents folder at: " + visionDocumentsFolder.getAbsolutePath());
                return null; // Indicate failure
            }
        } else if (!visionDocumentsFolder.isDirectory()) {
            Log.e(TAG, "Vision Documents path exists but is not a directory: " + visionDocumentsFolder.getAbsolutePath());
            return null; // Indicate failure
        }
        return visionDocumentsFolder;
    }


    /**
     * Extracts text content from various file types.
     * Supports .txt, .pdf (using PdfBox-Android), .doc, .docx (using Apache POI).
     *
     * @param file The file to extract text from.
     * @return The extracted text, or null if extraction fails or file type is unsupported.
     */
    private String extractTextFromFile(File file) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "File does not exist for extraction.");
            return null;
        }
        String filePath = file.getAbsolutePath();
        String lowerCasePath = filePath.toLowerCase(Locale.US); // Use Locale.US for case-insensitive comparison
        StringBuilder text = new StringBuilder(); // Use a single StringBuilder

        try {
            if (lowerCasePath.endsWith(".txt")) {
                Log.d(TAG, "Extracting text from TXT: " + filePath);
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        text.append(line).append("\n");
                    }
                }
            } else if (lowerCasePath.endsWith(".pdf")) {
                Log.d(TAG, "Extracting text from PDF using PdfBox-Android: " + filePath);

                PDDocument document = null; // Declare outside try for closing in finally

                try {
                    // Use PdfBox-Android's PDDocument.load() method
                    FileInputStream fis = new FileInputStream(file);
                    document = PDDocument.load(fis); // Load the PDF document from the stream

                    // Check if the document is encrypted and requires a password
                    if (document.isEncrypted()) {
                        Log.w(TAG, "PDF is encrypted and cannot be read: " + filePath);
                        return null; // Indicate encrypted
                    }
                    Log.d(TAG, "PDF is not encrypted.");

                    PDFTextStripper pdfStripper = new PDFTextStripper();
                    Log.d(TAG, "PDFTextStripper created. Getting text...");
                    String pdfContent = pdfStripper.getText(document); // Extract all text from the document

                    if (pdfContent != null && !pdfContent.trim().isEmpty()) {
                        text.append(pdfContent); // Append the extracted text
                    }
                    Log.d(TAG, "Text successfully extracted from PDF.");


                } catch (IOException e) {
                    Log.e(TAG, "IOException during PDF reading with PdfBox-Android: " + filePath, e);
                    return null; // Indicate failure
                } catch (Exception e) { // Catch any other exceptions from PdfBox-Android (e.g., invalid PDF format)
                    Log.e(TAG, "Unexpected Exception during PDF reading with PdfBox-Android: " + filePath + ": " + e.getMessage(), e);
                    return null;
                } finally {
                    // Ensure the document is closed
                    if (document != null) {
                        try {
                            document.close();
                            Log.d(TAG, "PdfBox document closed.");
                        } catch (IOException e) {
                            Log.e(TAG, "Error closing PdfBox document", e);
                        }
                    }
                }


            } else if (lowerCasePath.endsWith(".doc") || lowerCasePath.endsWith(".docx")) {
                // Use Apache POI library for Word files
                Log.d(TAG, "Extracting text from Word: " + filePath);
                try (FileInputStream fis = new FileInputStream(file)) {
                    if (lowerCasePath.endsWith(".docx")) {
                        XWPFDocument document = new XWPFDocument(fis);
                        XWPFWordExtractor extractor = new XWPFWordExtractor(document);
                        text.append(extractor.getText());
                        extractor.close();
                    } else if (lowerCasePath.endsWith(".doc")) {
                        HWPFDocument document = new HWPFDocument(fis);
                        WordExtractor extractor = new WordExtractor(document);
                        String[] paragraphs = extractor.getParagraphText();
                        for (String para : paragraphs) {
                            if (para != null) { // Check for null paragraphs
                                text.append(para.trim()).append("\n");
                            }
                        }
                        extractor.close();
                    } else {
                        Log.w(TAG, "Unsupported Word file extension (should not happen if logic is correct): " + filePath);
                        return null; // Should not reach here if logic is correct
                    }
                } // FileInputStream is closed automatically by try-with-resources
            } else {
                Log.w(TAG, "Unsupported file type for extraction: " + filePath);
                return null; // Indicate unsupported type
            }
        } catch (IOException e) {
            // Catch IO errors for TXT and Word files
            Log.e(TAG, "Error during file reading (TXT/Word): " + filePath, e);
            return null;
        } catch (Exception e) {
            // Catch any other unexpected exceptions during extraction process
            Log.e(TAG, "Unexpected error during extraction for " + filePath + ": " + e.getMessage(), e);
            return null;
        }

        return text.toString(); // Return the accumulated extracted text
    }


    /**
     * Performs basic cleaning on text extracted from documents for better TTS pronunciation.
     * Removes common punctuation and excessive whitespace.
     *
     * @param text The raw extracted text.
     * @return The cleaned text.
     */
    private String cleanTextForTTS(String text) {
        if (text == null) {
            return null;
        }

        // Remove common punctuation and special characters (you can expand this regex)
        // This regex keeps letters, numbers, basic whitespace, and some common sentence enders for flow (.)
        String cleaned = text.replaceAll("[^a-zA-Z0-9\\s.,!?;:]", "");

        // Normalize whitespace (replace multiple spaces, tabs, newlines with a single space)
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        // You can add more cleaning rules here as needed based on your documents
        // Example: Replace specific symbols
        // cleaned = cleaned.replace("$-", " dollar sign ");

        return cleaned;
    }


    /**
     * Converts text to speech and saves it as an MP3 file.
     * Uses TextToSpeech.synthesizeToFile().
     *
     * @param text             The text to synthesize.
     * @param originalFileName The name of the original file (used for naming the audio file).
     * @return The File object for the generated audio file, or null if generation fails.
     */
    private File generateAudioFile(String text, String originalFileName) {
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "No text provided for audio generation.");
            return null;
        }

        File audioDir = getVisionDocumentsFolder(context); // Save audio in the same folder
        if (audioDir == null) {
            Log.e(TAG, "Could not get vision documents folder for audio.");
            return null;
        }
        // Create a unique name for the audio file based on original file name
        String baseName = originalFileName;
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }

        File potentialAudioFile = new File(audioDir, baseName + "_audio.mp3");

        // Handle potential duplicate audio file names
        int count = 0;
        while (potentialAudioFile.exists()) {
            count++;
            // Prevent excessively long names
            String base = baseName.length() > 50 ? baseName.substring(0, 50) : baseName;
            String newAudioFileName = base + "_" + count + "_audio.mp3";
            potentialAudioFile = new File(audioDir, newAudioFileName);
            // Add a safety break
            if (count > 1000) {
                Log.e(TAG, "Excessive attempts to find a unique audio file name.");
                return null;
            }
        }

        // *** Capture the final File object in a final variable after the loop ***
        final File finalAudioFile = potentialAudioFile;
        Log.d(TAG, "Target audio file path: " + finalAudioFile.getAbsolutePath());


        final CountDownLatch latch = new CountDownLatch(1); // Latch to wait for TTS completion
        final AtomicBoolean success = new AtomicBoolean(false); // Flag to indicate synthesis success

        Log.d(TAG, "Initializing TextToSpeech engine...");
        // Initialize TextToSpeech engine
        tts = new TextToSpeech(context.getApplicationContext(), status -> { // Use application context
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "TTS initialized successfully.");
                // Set language - important for correct pronunciation
                // You might want to let the user choose language or detect it
                int result = tts.setLanguage(new Locale("en", "IN")); // Using English (India) as requested

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language (en-IN) not supported or missing data. Result: " + result);
                    // Note: Cannot directly prompt user to install data from Worker.
                    // A notification or UI element in the activity is needed for this.
                    latch.countDown(); // Release latch on language error
                    return; // Exit the listener
                } else {
                    Log.d(TAG, "TTS Language set to: " + tts.getLanguage().getDisplayName() + " (" + tts.getLanguage().toLanguageTag() + ")");
                }


                // *** Set the speech rate ***
                float speechRate = 0.9f; // Example: set speed to 90% of normal. Adjust as needed.
                tts.setSpeechRate(speechRate);
                Log.d(TAG, "TTS Speech rate set to: " + speechRate);
                // *** End of setting speech rate ***


                // Set a listener to track synthesis completion/errors
                // Use UtteranceProgressListener for API 15 and above
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.d(TAG, "TTS synthesis started: " + utteranceId);
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Log.d(TAG, "TTS synthesis done: " + utteranceId);
                        success.set(true); // Indicate success
                        latch.countDown(); // Release latch
                        Log.d(TAG, "Synthesis completed for utteranceId: " + utteranceId);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS synthesis error: " + utteranceId);
                        latch.countDown(); // Release latch on error
                    }

                    // Override the API 23+ onError with error code if needed
                    @Override
                    public void onError(String utteranceId, int errorCode) {
                        Log.e(TAG, "TTS synthesis error code: " + utteranceId + " code: " + errorCode);
                        latch.countDown(); // Release latch on error
                    }

                    @Override
                    public void onStop(String utteranceId, boolean interrupted) {
                        Log.w(TAG, "TTS synthesis stopped: " + utteranceId + ", interrupted: " + interrupted);
                        latch.countDown(); // Release latch on stop
                    }
                });


                // Use synthesizeToFile for API 21+ for saving to a file
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    Bundle params = new Bundle();
                    // Use a unique ID for the utterance to match in the listener
                    // Using the final file name and timestamp ensures a unique ID per synthesis request
                    String utteranceId = "tts_" + finalAudioFile.getName() + "_" + System.currentTimeMillis();
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

                    Log.d(TAG, "Calling synthesizeToFile for: " + finalAudioFile.getAbsolutePath() + " with utteranceId: " + utteranceId);
                    // Use the final File object here
                    int fileCreationResult = tts.synthesizeToFile(text, params, finalAudioFile, utteranceId);

                    if (fileCreationResult == TextToSpeech.SUCCESS) {
                        Log.d(TAG, "TTS synthesizeToFile request sent successfully.");
                        // The onDone/onError callback will signal completion asynchronously
                    } else {
                        Log.e(TAG, "TTS synthesizeToFile failed with code: " + fileCreationResult);
                        latch.countDown(); // Release latch on synthesis setup failure
                    }
                } else {
                    // synthesizeToFile is not available below API 21
                    Log.e(TAG, "synthesizeToFile is not supported on API < 21. Requires API 21+.");
                    latch.countDown(); // Release latch as we cannot save to file
                }

            } else {
                Log.e(TAG, "TTS initialization failed with status: " + status);
                // Log specific error details if possible
                // int errorCode = TextToSpeech.getErrorMessage(status); // No, status IS the error code sometimes
                // TextToSpeech.ERROR, TextToSpeech.ERROR_SYNTHESIS, etc.
                String errorDescription = "Unknown TTS init error status";
                if (status == TextToSpeech.ERROR) errorDescription = "Generic TTS init error";
                else if (status == TextToSpeech.ERROR_INVALID_REQUEST) errorDescription = "Invalid TTS init request";
                // Add checks for other TextToSpeech.ERROR_* constants

                Log.e(TAG, "TTS initialization failed: " + errorDescription + " (" + status + ")");
                latch.countDown(); // Release latch on TTS init error
            }
        }, TextToSpeech.Engine.ACTION_CHECK_TTS_DATA); // Optional: specify engine or check data


        try {
            // Wait for the latch to be counted down by the UtteranceProgressListener or init listener
            // Set a reasonable timeout for TTS initialization and synthesis
            boolean completed = latch.await(300, TimeUnit.SECONDS); // Increased timeout (e.g., 5 minutes)

            if (!completed) {
                Log.e(TAG, "TTS synthesis timed out.");
                // Clean up the potential partial audio file if it exists
                if (finalAudioFile.exists()) { // Use the final variable here
                    Log.w(TAG, "Deleting incomplete audio file due to timeout: " + finalAudioFile.getAbsolutePath());
                    finalAudioFile.delete();
                }
//                Data errorOutput = new Data.Builder()
//                        .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile != null ? copiedOriginalFile.getAbsolutePath() : "N/A")
//                        .putString(OUTPUT_ERROR_MESSAGE, "Audio generation timed out.")
//                        .build();
//                return Result.failure(errorOutput); // Indicate failure due to timeout
            }

        } catch (InterruptedException e) {
            Log.e(TAG, "TTS latch await interrupted", e);
            if (finalAudioFile.exists()) { // Use the final variable here
                Log.w(TAG, "Deleting incomplete audio file due to interruption: " + finalAudioFile.getAbsolutePath());
                finalAudioFile.delete();
            }
//            Data errorOutput = new Data.Builder()
//                    .putString(OUTPUT_ORIGINAL_PATH, copiedOriginalFile != null ? copiedOriginalFile.getAbsolutePath() : "N/A")
//                    .putString(OUTPUT_ERROR_MESSAGE, "Audio generation interrupted.")
//                    .build();
//            return Result.failure(errorOutput); // Indicate failure due to interruption
        }


        // Check if synthesis was successful AND the file was actually created and has content
        if (success.get() && finalAudioFile.exists() && finalAudioFile.length() > 0) { // Use the final variable here
            Log.d(TAG, "TTS synthesis reported success and audio file exists and is not empty.");
            return finalAudioFile; // Return the final File object
        } else {
            Log.e(TAG, "Audio file generation failed or resulted in an empty file.");
            // Clean up the empty/failed file
            if (finalAudioFile.exists()) { // Use the final variable here
                Log.w(TAG, "Deleting empty/failed audio file: " + finalAudioFile.getAbsolutePath());
                finalAudioFile.delete();
            }
            return null; // Indicate failure
        }
    }
    // --- End Helper methods ---
}
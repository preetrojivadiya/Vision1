package com.example.vision1;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;

public class PdfConverterUtil {

    private static final String TAG = "PdfConverterUtil";

    public static File convertToPdfIfNeeded(File inputFile, File outputDirectory) {
        String fileName = inputFile.getName().toLowerCase();

        // If it's already a PDF, just return the file
        if (fileName.endsWith(".pdf")) {
            return inputFile;
        }

        String baseName = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) baseName = fileName.substring(0, dotIndex);

        File outputPdfFile = new File(outputDirectory, baseName + ".pdf");

        if (fileName.endsWith(".txt")) {
            return convertTxtToPdf(inputFile, outputPdfFile) ? outputPdfFile : inputFile;
        }
        else if (fileName.endsWith(".doc") || fileName.endsWith(".docx") || fileName.endsWith(".ppt")) {
            // NOTE: Native Android cannot convert Word/PPT to PDF.
            // In a production app, you would upload the file to a Cloud API here,
            // wait for the PDF response, and save it to outputPdfFile.
            // For now, we return the original file to prevent the app from crashing.
            Log.w(TAG, "Cannot natively convert DOC/PPT to PDF. Returning original file.");
            return inputFile;
        }

        return inputFile; // Return original if unknown format
    }

    private static boolean convertTxtToPdf(File txtFile, File pdfFile) {
        try {
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(14f);

            BufferedReader reader = new BufferedReader(new FileReader(txtFile));
            String line;
            int yPosition = 50;

            while ((line = reader.readLine()) != null) {
                canvas.drawText(line, 50, yPosition, paint);
                yPosition += 20;

                // Extremely basic page break logic
                if (yPosition > 800) {
                    document.finishPage(page);
                    pageInfo = new PdfDocument.PageInfo.Builder(595, 842, document.getPages().size() + 1).create();
                    page = document.startPage(pageInfo);
                    canvas = page.getCanvas();
                    yPosition = 50;
                }
            }
            reader.close();
            document.finishPage(page);

            FileOutputStream fos = new FileOutputStream(pdfFile);
            document.writeTo(fos);
            document.close();
            fos.close();

            // Delete the old TXT file so only the PDF remains
            txtFile.delete();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "TXT to PDF conversion failed", e);
            return false;
        }
    }
}
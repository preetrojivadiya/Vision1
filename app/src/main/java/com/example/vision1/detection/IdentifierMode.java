package com.example.vision1.detection;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.Text.TextBlock;
import com.google.mlkit.vision.text.Text.Line;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class IdentifierMode implements VisionMode {

    private final StableFrameGate stableFrameGate = new StableFrameGate();

    private boolean sessionActive = false;
    private boolean ocrInProgress = false;

    private static final long OCR_THROTTLE_MS = 1500;
    private long lastOcrTime = 0;

    public void startSession() {
        sessionActive = true;
        ocrInProgress = false;
        lastOcrTime = 0;
        stableFrameGate.reset();
    }

    @Override
    public void process(Bitmap bitmap, VisionDependencies dependencies, VisionUiController uiController) {
        if (!sessionActive) {
            uiController.updateUI("Press Start to begin identifier scan", null, 0, 0);
            return;
        }

        if (bitmap == null || dependencies == null
                || dependencies.getObjectDetector() == null
                || dependencies.getTextRecognizer() == null) {
            uiController.updateUI("Model not ready", null, 0, 0);
            return;
        }

        if (ocrInProgress) {
            return;
        }

        List<ObjectDetector.Detection> results = dependencies.getObjectDetector().detect(bitmap);

        if (results == null || results.isEmpty()) {
            uiController.updateUI("Hold the object steady...", null, 0, 0);
            return;
        }

        ObjectDetector.Detection bestResult = Collections.max(
                results,
                (d1, d2) -> Float.compare(d1.confidence, d2.confidence)
        );

        if (!stableFrameGate.isStable(bestResult)) {
            uiController.updateUI(
                    "Hold steady for a clear scan...",
                    results,
                    bitmap.getWidth(),
                    bitmap.getHeight()
            );
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastOcrTime < OCR_THROTTLE_MS) {
            return;
        }

        lastOcrTime = now;
        ocrInProgress = true;

        RectF box = expandBox(bestResult.boundingBox, 0.12f);

        int left = clamp((int) (box.left * bitmap.getWidth()), 0, bitmap.getWidth() - 1);
        int top = clamp((int) (box.top * bitmap.getHeight()), 0, bitmap.getHeight() - 1);
        int right = clamp((int) (box.right * bitmap.getWidth()), left + 1, bitmap.getWidth());
        int bottom = clamp((int) (box.bottom * bitmap.getHeight()), top + 1, bitmap.getHeight());

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) {
            ocrInProgress = false;
            uiController.updateUI("Could not crop a valid object region", results, bitmap.getWidth(), bitmap.getHeight());
            return;
        }

        Bitmap croppedObject;
        try {
            croppedObject = Bitmap.createBitmap(bitmap, left, top, width, height);
        } catch (Exception e) {
            ocrInProgress = false;
            uiController.updateUI("Failed to crop object", results, bitmap.getWidth(), bitmap.getHeight());
            return;
        }

        InputImage image = InputImage.fromBitmap(croppedObject, 0);

        dependencies.getTextRecognizer().process(image)
                .addOnSuccessListener(visionText -> {
                    String rawText = visionText != null ? visionText.getText() : "";
                    String importantText = extractImportantText(visionText);

                    String finalText;
                    if (!importantText.isEmpty()) {
                        finalText = bestResult.label + " identified as " + importantText;
                    } else if (!rawText.trim().isEmpty()) {
                        finalText = bestResult.label + " identified as " + cleanText(rawText);
                    } else {
                        finalText = "No readable text found on " + bestResult.label;
                    }

                    uiController.updateUI(finalText, results, bitmap.getWidth(), bitmap.getHeight());
                    uiController.speakText(finalText);

                    sessionActive = false;
                    ocrInProgress = false;
                })
                .addOnFailureListener(e -> {
                    ocrInProgress = false;
                    sessionActive = false;
                    uiController.updateUI("Text reading failed. Press Continue to try again.", results, bitmap.getWidth(), bitmap.getHeight());
                });
    }

    private RectF expandBox(RectF box, float paddingRatio) {
        if (box == null) return new RectF(0f, 0f, 1f, 1f);

        float cx = (box.left + box.right) / 2f;
        float cy = (box.top + box.bottom) / 2f;
        float halfW = box.width() / 2f;
        float halfH = box.height() / 2f;

        float expandedHalfW = halfW * (1f + paddingRatio);
        float expandedHalfH = halfH * (1f + paddingRatio);

        float left = clampFloat(cx - expandedHalfW, 0f, 1f);
        float top = clampFloat(cy - expandedHalfH, 0f, 1f);
        float right = clampFloat(cx + expandedHalfW, 0f, 1f);
        float bottom = clampFloat(cy + expandedHalfH, 0f, 1f);

        return new RectF(left, top, right, bottom);
    }

    private String extractImportantText(Text visionText) {
        if (visionText == null) return "";

        List<ScoredLine> scoredLines = new ArrayList<>();

        for (TextBlock block : visionText.getTextBlocks()) {
            for (Line line : block.getLines()) {
                String cleaned = cleanText(line.getText());
                if (cleaned.isEmpty()) continue;

                String lower = cleaned.toLowerCase(Locale.ROOT);
                int score = scoreLine(lower, cleaned);

                if (score > 0) {
                    scoredLines.add(new ScoredLine(cleaned, score, scoredLines.size()));
                }
            }
        }

        scoredLines.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.score, a.score);
            if (scoreCompare != 0) return scoreCompare;
            return Integer.compare(a.index, b.index);
        });

        LinkedHashSet<String> selected = new LinkedHashSet<>();

        for (ScoredLine line : scoredLines) {
            if (isUsefulText(line.text)) {
                selected.add(line.text);
            }
            if (selected.size() >= 3) break;
        }

        if (selected.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String line : selected) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(line);
        }
        return sb.toString();
    }

    private int scoreLine(String lower, String original) {
        int score = 0;

        if (containsPricePattern(lower)) score += 5;
        if (containsQuantityPattern(lower)) score += 5;
        if (containsBrandLikeText(original)) score += 4;
        if (containsMixedAlphaNumeric(lower)) score += 2;
        if (containsCurrencyOrAmount(lower)) score += 3;
        if (containsAlphaWords(lower)) score += 3;
        if (containsDigits(lower)) score += 1;

        if (isNumericOnly(lower)) score -= 4;
        if (original.length() < 3) score -= 4;

        return score;
    }

    private boolean isUsefulText(String text) {
        if (text == null) return false;

        String lower = text.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty()) return false;
        if (isNumericOnly(lower)) return false;
        if (lower.length() < 3) return false;

        return containsAlphaWords(lower)
                || containsPricePattern(lower)
                || containsQuantityPattern(lower)
                || containsCurrencyOrAmount(lower)
                || containsMixedAlphaNumeric(lower);
    }

    private String cleanText(String input) {
        if (input == null) return "";
        return input
                .replaceAll("[^\\p{L}\\p{N}₹./%&()\\-\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsDigits(String text) {
        return text != null && Pattern.compile(".*\\d.*").matcher(text).matches();
    }

    private boolean containsAlphaWords(String text) {
        return text != null && Pattern.compile(".*[a-zA-Z]{2,}.*").matcher(text).matches();
    }

    private boolean isNumericOnly(String text) {
        return text != null && Pattern.compile("^\\d+(?:[.,]\\d+)?$").matcher(text.trim()).matches();
    }

    private boolean containsMixedAlphaNumeric(String text) {
        return text != null
                && Pattern.compile(".*[a-zA-Z].*\\d.*|.*\\d.*[a-zA-Z].*").matcher(text).matches();
    }

    private boolean containsCurrencyOrAmount(String text) {
        return text != null && Pattern.compile(".*(₹|rs\\.?|rupee|rupees|price|mrp|amt|amount).*").matcher(text).matches();
    }

    private boolean containsQuantityPattern(String text) {
        return text != null && Pattern.compile(
                ".*\\b\\d+(?:\\.\\d+)?\\s*(ml|l|liter|litre|g|kg|mg|pcs|piece|pieces|pack|packs|bottle|can|tablet|tablets)\\b.*"
        ).matcher(text).matches();
    }

    private boolean containsPricePattern(String text) {
        return text != null && Pattern.compile(
                ".*(₹|rs\\.?|rupee|rupees).*\\d+.*"
        ).matcher(text).matches();
    }

    private boolean containsBrandLikeText(String text) {
        if (text == null) return false;

        String cleaned = text.trim();
        if (cleaned.length() < 3) return false;

        int letters = 0;
        int total = 0;
        for (char c : cleaned.toCharArray()) {
            if (Character.isLetter(c)) letters++;
            if (!Character.isWhitespace(c)) total++;
        }

        if (total == 0) return false;

        float alphaRatio = (float) letters / (float) total;
        return alphaRatio >= 0.55f && cleaned.split("\\s+").length <= 4;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void reset() {
        sessionActive = false;
        ocrInProgress = false;
        lastOcrTime = 0;
        stableFrameGate.reset();
    }

    private static class ScoredLine {
        final String text;
        final int score;
        final int index;

        ScoredLine(String text, int score, int index) {
            this.text = text;
            this.score = score;
            this.index = index;
        }
    }
}
package com.example.vision1.detection;

import android.graphics.Rect;
import android.graphics.RectF;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.Text.Line;
import com.google.mlkit.vision.text.Text.TextBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class OcrTextUtils {

    private OcrTextUtils() {}

    public static OcrExtraction extract(
            Text visionText,
            RectF cropBoxNormalized,
            int cropWidth,
            int cropHeight
    ) {
        if (visionText == null) {
            return new OcrExtraction("", new ArrayList<>());
        }

        RectF cropBox = cropBoxNormalized != null
                ? new RectF(cropBoxNormalized)
                : new RectF(0f, 0f, 1f, 1f);

        List<ScoredLine> candidates = new ArrayList<>();
        List<TextAnnotation> annotations = new ArrayList<>();

        for (TextBlock block : visionText.getTextBlocks()) {
            for (Line line : block.getLines()) {
                String cleaned = cleanText(line.getText());
                if (cleaned.isEmpty()) continue;

                int score = scoreLine(cleaned);
                Rect box = line.getBoundingBox();

                if (box != null && score > 0) {
                    RectF sourceBox = mapCropBoxToSource(
                            box,
                            cropBox,
                            cropWidth,
                            cropHeight
                    );
                    annotations.add(new TextAnnotation(sourceBox, cleaned));
                }

                if (score > 0) {
                    candidates.add(new ScoredLine(cleaned, score, candidates.size()));
                }
            }
        }

        String summary = buildSummary(candidates);
        return new OcrExtraction(summary, annotations);
    }

    public static boolean isStrongSummary(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty()) return false;
        if (isNumericOnly(lower)) return false;
        return containsAlphaWords(lower)
                || containsPrice(lower)
                || containsQuantity(lower)
                || containsMixedAlphaNumeric(lower);
    }

    private static String buildSummary(List<ScoredLine> candidates) {
        if (candidates.isEmpty()) return "";

        candidates.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.score, a.score);
            if (scoreCompare != 0) return scoreCompare;
            return Integer.compare(a.index, b.index);
        });

        String primary = null;
        String price = null;
        String quantity = null;
        String otherUseful = null;

        for (ScoredLine candidate : candidates) {
            String text = candidate.text;
            String lower = text.toLowerCase(Locale.ROOT);

            if (primary == null && looksLikeTitleOrBrand(lower)) {
                primary = text;
                continue;
            }

            if (price == null && containsPrice(lower)) {
                price = text;
                continue;
            }

            if (quantity == null && containsQuantity(lower)) {
                quantity = text;
                continue;
            }

            if (otherUseful == null && isUseful(lower)) {
                otherUseful = text;
            }
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (primary != null) selected.add(primary);
        if (price != null) selected.add(price);
        if (quantity != null) selected.add(quantity);
        if (selected.isEmpty() && otherUseful != null) selected.add(otherUseful);

        StringBuilder sb = new StringBuilder();
        for (String item : selected) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.toString().trim();
    }

    private static boolean isUseful(String text) {
        return looksLikeTitleOrBrand(text)
                || containsPrice(text)
                || containsQuantity(text)
                || containsMixedAlphaNumeric(text);
    }

    private static String cleanText(String input) {
        if (input == null) return "";
        return input
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("[^\\p{L}\\p{N}₹./%&()\\-\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean looksLikeTitleOrBrand(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim();

        if (lower.length() < 3) return false;
        if (isNumericOnly(lower)) return false;

        String[] words = lower.split("\\s+");
        if (words.length > 5) return false;

        int letters = 0;
        int total = 0;
        for (char c : lower.toCharArray()) {
            if (!Character.isWhitespace(c)) total++;
            if (Character.isLetter(c)) letters++;
        }

        if (total == 0) return false;
        float alphaRatio = (float) letters / (float) total;

        return alphaRatio >= 0.60f;
    }

    private static int scoreLine(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;

        if (containsPrice(lower)) score += 5;
        if (containsQuantity(lower)) score += 5;
        if (looksLikeTitleOrBrand(lower)) score += 4;
        if (containsMixedAlphaNumeric(lower)) score += 3;
        if (containsAlphaWords(lower)) score += 2;
        if (containsDigits(lower)) score += 1;

        if (isNumericOnly(lower)) score -= 6;
        if (isNoise(lower)) score -= 4;

        return score;
    }

    private static boolean containsPrice(String text) {
        return text != null && Pattern.compile(
                ".*(₹|rs\\.?|rupee|rupees|mrp|price|amt|amount).*\\d+.*"
        ).matcher(text).matches();
    }

    private static boolean containsQuantity(String text) {
        return text != null && Pattern.compile(
                ".*\\b\\d+(?:\\.\\d+)?\\s*(ml|l|liter|litre|g|kg|mg|pcs|piece|pieces|pack|packs|bottle|can|tablet|tablets)\\b.*"
        ).matcher(text).matches();
    }

    private static boolean containsAlphaWords(String text) {
        return text != null && Pattern.compile(".*[a-zA-Z]{2,}.*").matcher(text).matches();
    }

    private static boolean containsDigits(String text) {
        return text != null && Pattern.compile(".*\\d.*").matcher(text).matches();
    }

    private static boolean containsMixedAlphaNumeric(String text) {
        return text != null && Pattern.compile(".*[a-zA-Z].*\\d.*|.*\\d.*[a-zA-Z].*").matcher(text).matches();
    }

    private static boolean isNumericOnly(String text) {
        return text != null && text.trim().matches("^\\d+(?:[.,]\\d+)?$");
    }

    private static boolean isNoise(String text) {
        if (text == null) return true;

        String lower = text.toLowerCase(Locale.ROOT).trim();
        if (lower.length() < 3) return true;

        int letters = 0;
        int digits = 0;

        for (char c : lower.toCharArray()) {
            if (Character.isLetter(c)) letters++;
            if (Character.isDigit(c)) digits++;
        }

        if (digits >= 3 && letters <= 1) return true;
        if (lower.matches("^[a-z]\\d{2,}$")) return true;
        if (lower.matches("^[a-z0-9]{1,4}$") && !containsAlphaWords(lower)) return true;

        return false;
    }

    private static RectF mapCropBoxToSource(
            Rect cropBoxInCropBitmap,
            RectF cropBoxNormalizedInSource,
            int cropWidth,
            int cropHeight
    ) {
        if (cropBoxInCropBitmap == null) {
            return new RectF(cropBoxNormalizedInSource);
        }

        float cropLeft = cropBoxNormalizedInSource.left;
        float cropTop = cropBoxNormalizedInSource.top;
        float cropWidthNorm = cropBoxNormalizedInSource.width();
        float cropHeightNorm = cropBoxNormalizedInSource.height();

        float left = cropLeft + (cropBoxInCropBitmap.left / (float) cropWidth) * cropWidthNorm;
        float top = cropTop + (cropBoxInCropBitmap.top / (float) cropHeight) * cropHeightNorm;
        float right = cropLeft + (cropBoxInCropBitmap.right / (float) cropWidth) * cropWidthNorm;
        float bottom = cropTop + (cropBoxInCropBitmap.bottom / (float) cropHeight) * cropHeightNorm;

        return new RectF(
                clamp(left, 0f, 1f),
                clamp(top, 0f, 1f),
                clamp(right, 0f, 1f),
                clamp(bottom, 0f, 1f)
        );
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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
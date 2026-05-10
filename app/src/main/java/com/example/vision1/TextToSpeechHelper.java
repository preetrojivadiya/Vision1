package com.example.vision1;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class TextToSpeechHelper implements TextToSpeech.OnInitListener {

    private static final String TAG = "Vision1TTSHelper";
    private TextToSpeech textToSpeech;
    private boolean isTtsInitialized = false;

    public TextToSpeechHelper(Context context) {
        textToSpeech = new TextToSpeech(context, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(Locale.getDefault());
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language is not available.");
            } else {
                isTtsInitialized = true;
                Log.i(TAG, "Text-to-speech engine initialized successfully.");
            }
        } else {
            Log.e(TAG, "Could not initialize text-to-speech engine (" + status + ")");
        }
    }

    public void speak(String text) {
        if (isTtsInitialized) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
            Log.d(TAG, "Speaking: " + text);
        } else {
            Log.w(TAG, "Text-to-speech engine not initialized yet.");
        }
    }

    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            Log.i(TAG, "Text-to-speech engine shut down.");
        }
    }
}
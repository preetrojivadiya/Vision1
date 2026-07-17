package com.example.vision1.utils;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
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
            int result = textToSpeech.setLanguage(new Locale("en", "IN"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language is not available.");
            } else {
                isTtsInitialized = true;
                textToSpeech.setSpeechRate(0.9f);
                Log.i(TAG, "Text-to-speech engine initialized successfully.");
            }
        } else {
            Log.e(TAG, "Could not initialize text-to-speech engine (" + status + ")");
        }
    }

    public void speak(String text) {
        if (isTtsInitialized && textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speakUtterance");
        }
    }

    public void stop() {
        if (isTtsInitialized && textToSpeech != null && textToSpeech.isSpeaking()) {
            textToSpeech.stop();
        }
    }

    public boolean isSpeaking() {
        return isTtsInitialized && textToSpeech != null && textToSpeech.isSpeaking();
    }

    public boolean isInitialized() {
        return isTtsInitialized;
    }

    public void saveToFile(String text, File audioFile) {
        if (!isTtsInitialized || textToSpeech == null) return;

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "saveAudioId");
        textToSpeech.synthesizeToFile(text, params, audioFile, "saveAudioId");
    }

    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            isTtsInitialized = false;
        }
    }
}
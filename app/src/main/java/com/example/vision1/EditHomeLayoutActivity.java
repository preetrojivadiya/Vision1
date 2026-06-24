package com.example.vision1;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;

public class EditHomeLayoutActivity extends Activity {

    // Add any new button containers to this array in the future
    int[] containerIds = {
            R.id.container_btn2,
            R.id.container_btn3,
            R.id.container_btn4,
            R.id.container_btn_profile
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_home);

        loadCurrentPositions();

        // Attach drag listeners to all buttons
        for (int id : containerIds) {
            View container = findViewById(id);
            container.setOnTouchListener(new DragListener());
        }

        Button btnSave = findViewById(R.id.btn_save_layout);
        ImageButton btnExit = findViewById(R.id.btn_exit_layout);

        btnSave.setOnClickListener(v -> {
            savePositions();
            finish(); // Close and go back to settings
        });

        btnExit.setOnClickListener(v -> finish()); // Close without saving
    }

    private void loadCurrentPositions() {
        SharedPreferences prefs = getSharedPreferences("HomeLayoutPrefs", MODE_PRIVATE);
        for (int id : containerIds) {
            View view = findViewById(id);
            view.post(() -> {
                if (prefs.contains("x_" + id)) {
                    view.setX(prefs.getFloat("x_" + id, 0));
                    view.setY(prefs.getFloat("y_" + id, 0));
                }
            });
        }
    }

    private void savePositions() {
        SharedPreferences prefs = getSharedPreferences("HomeLayoutPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        for (int id : containerIds) {
            View view = findViewById(id);
            editor.putFloat("x_" + id, view.getX());
            editor.putFloat("y_" + id, view.getY());
        }
        editor.apply();
    }

    // Custom Drag Logic
    private class DragListener implements View.OnTouchListener {
        float dX, dY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = view.getX() - event.getRawX();
                    dY = view.getY() - event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    view.animate()
                            .x(event.getRawX() + dX)
                            .y(event.getRawY() + dY)
                            .setDuration(0)
                            .start();
                    break;
            }
            return true;
        }
    }
}
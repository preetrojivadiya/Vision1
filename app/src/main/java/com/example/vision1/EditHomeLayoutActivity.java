package com.example.vision1;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

        // Attach drag listeners and prevent touch-stealing
        for (int id : containerIds) {
            ViewGroup container = findViewById(id);

            // FIX 1: Prevent the ImageButton inside from stealing the touch event
            // This forces the container to receive the drag gestures.
            for (int i = 0; i < container.getChildCount(); i++) {
                container.getChildAt(i).setClickable(false);
            }

            // Attach the smooth drag listener
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

    // FIX 2: Custom Drag Logic for smooth free-roam dragging
    private class DragListener implements View.OnTouchListener {
        float dX, dY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Calculate the offset between where you touched and the view's top-left corner
                    dX = view.getX() - event.getRawX();
                    dY = view.getY() - event.getRawY();

                    // FIX 3: Bring the dragged button to the top of the screen so it doesn't hide behind others
                    view.bringToFront();
                    break;

                case MotionEvent.ACTION_MOVE:
                    // Instantly apply the new coordinates for lag-free dragging
                    view.setX(event.getRawX() + dX);
                    view.setY(event.getRawY() + dY);
                    break;
            }
            // Return true so Android knows we are still handling the drag
            return true;
        }
    }
}
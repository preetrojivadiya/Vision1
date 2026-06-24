package com.example.vision1;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;

public class MainActivity extends Activity {

    int[] containerIds = {
            R.id.container_btn2,
            R.id.container_btn3,
            R.id.container_btn4,
            R.id.container_btn_profile
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load custom positions if the user set them
        loadCustomPositions();

        // Documents Button
        ImageButton btn4 = findViewById(R.id.mybtn4);
        btn4.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, StorageActivity.class);
            startActivity(intent);
        });

        // Scan Text Button
        ImageButton btn3 = findViewById(R.id.mybtn3);
        btn3.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CameraActivity.class);
            startActivity(intent);
        });

        // Object Detection Button
        ImageButton btn2 = findViewById(R.id.mybtn2);
        btn2.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, IdentifyActivity.class);
            intent.putExtra("runSingleImage", true);
            startActivity(intent);
        });

        // Profile Button
        ImageButton btnProfile = findViewById(R.id.mybtn_profile);
        btnProfile.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ProfileActivity.class);
            startActivity(intent);
        });
    }

    // Ensure positions update if user returns from Settings
    @Override
    protected void onResume() {
        super.onResume();
        loadCustomPositions();
    }

    private void loadCustomPositions() {
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
}
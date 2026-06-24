package com.example.vision1;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        LinearLayout btnEditLayout = findViewById(R.id.btn_edit_home_layout);
        btnEditLayout.setOnClickListener(v -> {
            startActivity(new Intent(SettingsActivity.this, EditHomeLayoutActivity.class));
        });
    }
}
package com.example.vision1;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

public class ProfileActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        LinearLayout btnPersonal = findViewById(R.id.btn_personal_details);
        LinearLayout btnSettings = findViewById(R.id.btn_settings);

        btnPersonal.setOnClickListener(v -> {
            startActivity(new Intent(ProfileActivity.this, PersonalDetailsActivity.class));
        });

        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(ProfileActivity.this, SettingsActivity.class));
        });
    }
}
package dev.nizav.documentscanner.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;

import dev.nizav.documentscanner.R;

public final class ProjectActivity extends MaterialMotionActivity {
    public static final String EXTRA_PROJECT_ID = "project_id";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_stub);
        findViewById(R.id.stubBack).setOnClickListener(v -> finish());
    }
}

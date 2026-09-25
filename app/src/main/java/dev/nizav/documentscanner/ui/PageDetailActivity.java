package dev.nizav.documentscanner.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;

import dev.nizav.documentscanner.R;

public final class PageDetailActivity extends MaterialMotionActivity {
    public static final String EXTRA_PAGE_ID = "page_id";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page_detail_stub);
        MaterialToolbar toolbar = findViewById(R.id.pageDetailToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }
}

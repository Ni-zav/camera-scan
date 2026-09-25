package dev.nizav.documentscanner.ui;

import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.transition.platform.MaterialSharedAxis;

public abstract class MaterialMotionActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setEnterTransition(
                    new MaterialSharedAxis(MaterialSharedAxis.Z, true)
            );
            getWindow().setReturnTransition(
                    new MaterialSharedAxis(MaterialSharedAxis.Z, false)
            );
            getWindow().setReenterTransition(
                    new MaterialSharedAxis(MaterialSharedAxis.Z, false)
            );
            getWindow().setExitTransition(
                    new MaterialSharedAxis(MaterialSharedAxis.Z, true)
            );
        }
        super.onCreate(savedInstanceState);
    }
}

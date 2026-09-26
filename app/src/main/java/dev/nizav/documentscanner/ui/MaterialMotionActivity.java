package dev.nizav.documentscanner.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.transition.platform.MaterialSharedAxis;

import dev.nizav.documentscanner.R;

public abstract class MaterialMotionActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());

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
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(layoutResID);
        applyCommonInsets();
    }

    private void applyCommonInsets() {
        applyTopMarginInset(findFirst(
                R.id.scanTopChrome,
                R.id.homeToolbar,
                R.id.projectToolbar,
                R.id.scanToolbar,
                R.id.cropToolbar,
                R.id.pageDetailToolbar
        ));

        applyBottomMarginInset(findFirst(
                R.id.newDocumentButton,
                R.id.scanButton,
                R.id.captureButton
        ));

        applyBottomPaddingInset(findFirst(
                R.id.scanBottomControls,
                R.id.editorBottomBar
        ));
    }

    @Nullable
    private View findFirst(int... ids) {
        for (int id : ids) {
            View view = findViewById(id);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private static void applyTopMarginInset(@Nullable View view) {
        if (view == null) {
            return;
        }

        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }

        int initialTop =
                ((ViewGroup.MarginLayoutParams) raw).topMargin;

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );

            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int target = initialTop + insets.top;
            if (params.topMargin != target) {
                params.topMargin = target;
                v.setLayoutParams(params);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private static void applyBottomMarginInset(@Nullable View view) {
        if (view == null) {
            return;
        }

        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }

        int initialBottom =
                ((ViewGroup.MarginLayoutParams) raw).bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()
                            | WindowInsetsCompat.Type.mandatorySystemGestures()
            );

            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int target = initialBottom + insets.bottom;
            if (params.bottomMargin != target) {
                params.bottomMargin = target;
                v.setLayoutParams(params);
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private static void applyBottomPaddingInset(@Nullable View view) {
        if (view == null) {
            return;
        }

        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()
                            | WindowInsetsCompat.Type.mandatorySystemGestures()
            );
            v.setPadding(
                    left,
                    top,
                    right,
                    bottom + insets.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}

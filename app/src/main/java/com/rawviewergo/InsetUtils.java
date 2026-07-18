package com.rawviewergo;

import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Keeps top button bars clear of the status bar under edge-to-edge display. */
final class InsetUtils {

    private static final int EXTRA_TOP_MARGIN_DP = 20;

    static void applyTopBarInset(View topBar) {
        int extraPx = Math.round(EXTRA_TOP_MARGIN_DP * topBar.getResources().getDisplayMetrics().density);
        int left = topBar.getPaddingLeft();
        int right = topBar.getPaddingRight();
        int bottom = topBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            int statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(left, statusBarTop + extraPx, right, bottom);
            return insets;
        });
    }

    private InsetUtils() {
    }
}

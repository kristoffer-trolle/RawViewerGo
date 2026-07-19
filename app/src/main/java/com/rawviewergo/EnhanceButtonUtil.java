package com.rawviewergo;

import android.content.res.ColorStateList;
import android.widget.Button;

import androidx.core.content.ContextCompat;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the Auto Enhance toggle button's text short enough to always fit on one line - its
 * on/off state is shown via background color instead of a longer "Disable Auto Enhance" label.
 */
final class EnhanceButtonUtil {

    // AppCompat auto-tints Button backgrounds from the theme at inflate time; passing null to
    // setBackgroundTintList later doesn't restore that, it just exposes the untinted drawable
    // (black). Capturing each button's real original tint once (before we ever touch it) lets
    // the "off" state genuinely restore it instead of guessing/hardcoding a color. WeakHashMap
    // so entries don't outlive the button/activity they belong to.
    private static final Map<Button, ColorStateList> DEFAULT_TINTS = new WeakHashMap<>();

    static void update(Button button, boolean enabled) {
        ColorStateList defaultTint = DEFAULT_TINTS.computeIfAbsent(button, Button::getBackgroundTintList);
        button.setText(R.string.enhance);
        button.setBackgroundTintList(enabled
                ? ColorStateList.valueOf(ContextCompat.getColor(button.getContext(), R.color.enhance_on))
                : defaultTint);
    }

    private EnhanceButtonUtil() {
    }
}

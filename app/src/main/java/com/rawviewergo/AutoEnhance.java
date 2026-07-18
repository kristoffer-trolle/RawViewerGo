package com.rawviewergo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/**
 * Post-processes an already-decoded raw preview/image: these medium-format backs' raw
 * previews tend to come out dark and flat with no in-camera tone curve applied, so this
 * gives an opt-in "make it look nicer" pass rather than changing the base decode itself
 * (which would mean re-running the expensive native demosaic on every toggle).
 *
 * Pipeline: auto-levels brightness/contrast stretch (from the image's own histogram) ->
 * +20% saturation -> unsharp-mask-style sharpen. Order matters: tone and color first, then
 * sharpen last so we're not amplifying artifacts from the earlier steps.
 */
final class AutoEnhance {

    private static final String PREFS = "rawviewergo";
    private static final String KEY_ENABLED = "auto_enhance_enabled";

    private static final float SATURATION_BOOST = 1.2f; // +20%
    private static final float SHARPEN_AMOUNT = 0.5f;
    private static final float LEVELS_LOW_PERCENTILE = 0.01f;
    private static final float LEVELS_HIGH_PERCENTILE = 0.99f;
    private static final float MAX_LEVELS_SCALE = 3.0f;

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static Bitmap apply(Bitmap source) {
        Bitmap toned = applyLevelsAndSaturation(source);
        return sharpen(toned, SHARPEN_AMOUNT);
    }

    private static Bitmap applyLevelsAndSaturation(Bitmap source) {
        float[] blackWhite = computeLevels(source);
        float low = blackWhite[0];
        float high = blackWhite[1];
        float scale = 255f / Math.max(1f, high - low);
        scale = Math.min(scale, MAX_LEVELS_SCALE);
        float offset = -low * scale;

        ColorMatrix levels = new ColorMatrix(new float[]{
                scale, 0, 0, 0, offset,
                0, scale, 0, 0, offset,
                0, 0, scale, 0, offset,
                0, 0, 0, 1, 0,
        });
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(SATURATION_BOOST);

        ColorMatrix combined = new ColorMatrix();
        combined.postConcat(levels);
        combined.postConcat(saturation);

        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(combined));
        canvas.drawBitmap(source, 0, 0, paint);
        return output;
    }

    /**
     * Finds a robust black/white point from a sampled luminance histogram (1st/99th
     * percentile) so the levels stretch is driven by this image's actual tonal range
     * instead of a fixed brightness multiplier.
     */
    private static float[] computeLevels(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] histogram = new int[256];

        int stepX = Math.max(1, width / 400);
        int stepY = Math.max(1, height / 400);
        int sampleCount = 0;
        int[] row = new int[width];
        for (int y = 0; y < height; y += stepY) {
            source.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x += stepX) {
                int p = row[x];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int luminance = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                histogram[luminance]++;
                sampleCount++;
            }
        }

        if (sampleCount == 0) {
            return new float[]{0f, 255f};
        }

        int lowCount = (int) (sampleCount * LEVELS_LOW_PERCENTILE);
        int highCount = (int) (sampleCount * LEVELS_HIGH_PERCENTILE);

        int cumulative = 0;
        int low = 0;
        for (int i = 0; i < 256; i++) {
            cumulative += histogram[i];
            if (cumulative >= lowCount) {
                low = i;
                break;
            }
        }
        cumulative = 0;
        int high = 255;
        for (int i = 255; i >= 0; i--) {
            cumulative += histogram[i];
            if (cumulative >= (sampleCount - highCount)) {
                high = i;
                break;
            }
        }
        if (high <= low) {
            return new float[]{0f, 255f};
        }
        return new float[]{low, high};
    }

    /** Simple 3x3 unsharp-mask-style convolution: center pixel boosted, neighbors subtracted. */
    private static Bitmap sharpen(Bitmap source, float amount) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] out = new int[pixels.length];

        float center = 1 + 4 * amount;
        float side = -amount;

        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            boolean edgeRow = (y == 0 || y == height - 1);
            for (int x = 0; x < width; x++) {
                int idx = rowStart + x;
                if (edgeRow || x == 0 || x == width - 1) {
                    out[idx] = pixels[idx];
                    continue;
                }
                int c = pixels[idx];
                int up = pixels[idx - width];
                int down = pixels[idx + width];
                int left = pixels[idx - 1];
                int right = pixels[idx + 1];

                int r = sharpenChannel(c, up, down, left, right, 16, center, side);
                int g = sharpenChannel(c, up, down, left, right, 8, center, side);
                int b = sharpenChannel(c, up, down, left, right, 0, center, side);
                out[idx] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int sharpenChannel(int c, int up, int down, int left, int right, int shift,
                                       float center, float side) {
        int cv = (c >> shift) & 0xFF;
        int uv = (up >> shift) & 0xFF;
        int dv = (down >> shift) & 0xFF;
        int lv = (left >> shift) & 0xFF;
        int rv = (right >> shift) & 0xFF;
        int value = Math.round(center * cv + side * (uv + dv + lv + rv));
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private AutoEnhance() {
    }
}

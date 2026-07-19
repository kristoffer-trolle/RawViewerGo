package com.rawviewergo;

import android.content.Context;
import android.graphics.Bitmap;

import com.rawviewergo.RawFileUtils.RawFormat;

/**
 * Post-processes an already-decoded raw preview/image: these medium-format backs' raw
 * previews tend to come out dark and flat with no in-camera tone curve applied, so this
 * gives an opt-in "make it look nicer" pass rather than changing the base decode itself
 * (which would mean re-running the expensive native demosaic on every toggle).
 *
 * Pipeline: format-specific color balance -> auto-levels brightness/contrast stretch (from the
 * image's own histogram, plus an extra flat brightness boost) -> saturation boost -> contrast
 * reduction (pivoted around mid-gray) -> unsharp-mask-style sharpen. Mamiya ZD (.mef) files
 * get a bigger brightness/saturation boost than Kodak files (they come out noticeably darker
 * otherwise) plus a contrast pull-back and extra sharpening. Kodak Pro Back (.dcr) files get
 * their own, smaller brightness boost and contrast pull-back, a color balance shift toward
 * red/yellow (they render slightly cool/green otherwise), and slightly less saturation than
 * the general amount. Phase One (.iiq) files get a bigger contrast pull-back than either, a
 * small brightness boost, extra sharpening, and a color balance shift away from red/yellow
 * (opposite direction from DCR's shift). Any other/unknown format gets only the general
 * boost, no extras.
 *
 * The core math (computeLevels/applyToPixels/sharpen) works on plain int[] ARGB pixel
 * arrays with no android.graphics dependency, so it's directly unit-testable on the JVM
 * without Robolectric or an emulator - see app/src/test/java/.../AutoEnhanceTest.java.
 */
final class AutoEnhance {

    private static final String PREFS = "rawviewergo";
    private static final String KEY_ENABLED = "auto_enhance_enabled";

    static final float BRIGHTNESS_BOOST = 1.2f; // +20% general
    static final float BRIGHTNESS_BOOST_MEF_EXTRA = 1.2f; // additional +20% for MEF, stacked
    static final float BRIGHTNESS_BOOST_DCR_EXTRA = 1.05f * 1.05f; // additional +5% for DCR, stacked twice
    static final float SATURATION_BOOST = 1.35f; // +35% general
    static final float SATURATION_BOOST_MEF_EXTRA = 1.2f; // additional +20% for MEF, stacked
    static final float DCR_SATURATION_DELTA = -0.05f; // -5 points off the general saturation boost, DCR only
    static final float SHARPEN_AMOUNT = 0.9f * 1.1f * 1.1f; // +10% general, twice now
    static final float SHARPEN_AMOUNT_MEF_EXTRA = 1.10f; // additional +10% for MEF, stacked
    static final float SHARPEN_AMOUNT_IIQ_EXTRA = 1.20f * 1.10f; // additional +20%, then +10% more, for IIQ, stacked
    static final float LEVELS_LOW_PERCENTILE = 0.01f;
    static final float LEVELS_HIGH_PERCENTILE = 0.99f;
    static final float MAX_LEVELS_SCALE = 3.0f;
    static final float DCR_RED_SHIFT = 0.20f; // toward red on the green<->red axis
    static final float DCR_YELLOW_SHIFT = 0.25f - 0.05f; // toward yellow on the blue<->yellow axis, -5% more
    static final float IIQ_RED_SHIFT = -0.05f; // away from red (toward green) on the green<->red axis
    static final float IIQ_YELLOW_SHIFT = -0.10f; // away from yellow (toward blue) on the blue<->yellow axis
    static final float IIQ_BRIGHTNESS_EXTRA = 1.05f * 1.10f * 1.10f; // +5%, then +10%, then +10% more, IIQ only, stacked
    static final float DCR_CONTRAST_FACTOR = 1f - 0.10f; // -10% contrast, pivoted around mid-gray
    static final float MEF_CONTRAST_FACTOR = 1f - 0.10f; // -10% contrast, pivoted around mid-gray
    static final float IIQ_CONTRAST_FACTOR = 1f - 0.40f; // -40% contrast total (was -20%, -10%, -10% more), pivoted around mid-gray
    static final float NEUTRAL_CONTRAST_FACTOR = 1f; // no change, for formats with no specific tuning

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static Bitmap apply(Bitmap source, RawFormat format) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] result = applyToPixels(pixels, width, height, format);
        return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888);
    }

    /** Pure pixel-array pipeline: color balance -> levels/saturation -> contrast -> sharpen. */
    static int[] applyToPixels(int[] pixels, int width, int height, RawFormat format) {
        int[] toned = applyLevelsSaturationAndColorBalance(pixels, width, height, format);
        return sharpen(toned, width, height, sharpenAmountFor(format));
    }

    static float sharpenAmountFor(RawFormat format) {
        switch (format) {
            case MEF:
                return SHARPEN_AMOUNT * SHARPEN_AMOUNT_MEF_EXTRA;
            case IIQ:
                return SHARPEN_AMOUNT * SHARPEN_AMOUNT_IIQ_EXTRA;
            default:
                return SHARPEN_AMOUNT;
        }
    }

    private static int[] applyLevelsSaturationAndColorBalance(int[] pixels, int width, int height, RawFormat format) {
        float brightnessBoost;
        float saturationBoost;
        float contrastFactor;
        float redGain = 1f;
        float greenGain = 1f;
        float blueGain = 1f;

        switch (format) {
            case MEF:
                brightnessBoost = BRIGHTNESS_BOOST * BRIGHTNESS_BOOST_MEF_EXTRA;
                saturationBoost = SATURATION_BOOST * SATURATION_BOOST_MEF_EXTRA;
                contrastFactor = MEF_CONTRAST_FACTOR;
                break;
            case DCR:
                brightnessBoost = BRIGHTNESS_BOOST * BRIGHTNESS_BOOST_DCR_EXTRA;
                saturationBoost = SATURATION_BOOST + DCR_SATURATION_DELTA;
                contrastFactor = DCR_CONTRAST_FACTOR;
                redGain = 1f + DCR_RED_SHIFT;
                greenGain = 1f - DCR_RED_SHIFT;
                blueGain = 1f - DCR_YELLOW_SHIFT;
                break;
            case IIQ:
                brightnessBoost = BRIGHTNESS_BOOST * IIQ_BRIGHTNESS_EXTRA;
                saturationBoost = SATURATION_BOOST;
                contrastFactor = IIQ_CONTRAST_FACTOR;
                redGain = 1f + IIQ_RED_SHIFT;
                greenGain = 1f - IIQ_RED_SHIFT;
                blueGain = 1f - IIQ_YELLOW_SHIFT;
                break;
            default:
                brightnessBoost = BRIGHTNESS_BOOST;
                saturationBoost = SATURATION_BOOST;
                contrastFactor = NEUTRAL_CONTRAST_FACTOR;
                break;
        }

        float[] blackWhite = computeLevels(pixels, width, height);
        float low = blackWhite[0];
        float high = blackWhite[1];
        float scale = 255f / Math.max(1f, high - low);
        scale = Math.min(scale, MAX_LEVELS_SCALE);
        scale *= brightnessBoost;
        float offset = -low * scale;

        float invSat = 1f - saturationBoost;
        float cr = 0.213f * invSat;
        float cg = 0.715f * invSat;
        float cb = 0.072f * invSat;

        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) * redGain;
            float g = ((p >> 8) & 0xFF) * greenGain;
            float b = (p & 0xFF) * blueGain;

            float outR = (cr + saturationBoost) * r + cg * g + cb * b;
            float outG = cr * r + (cg + saturationBoost) * g + cb * b;
            float outB = cr * r + cg * g + (cb + saturationBoost) * b;

            outR = outR * scale + offset;
            outG = outG * scale + offset;
            outB = outB * scale + offset;

            outR = applyContrast(outR, contrastFactor);
            outG = applyContrast(outG, contrastFactor);
            outB = applyContrast(outB, contrastFactor);

            out[i] = (0xFF << 24) | (clamp255(outR) << 16) | (clamp255(outG) << 8) | clamp255(outB);
        }
        return out;
    }

    /**
     * Finds a robust black/white point from a sampled luminance histogram (1st/99th
     * percentile) so the levels stretch is driven by this image's actual tonal range
     * instead of a fixed brightness multiplier.
     */
    static float[] computeLevels(int[] pixels, int width, int height) {
        int[] histogram = new int[256];

        int stepX = Math.max(1, width / 400);
        int stepY = Math.max(1, height / 400);
        int sampleCount = 0;
        for (int y = 0; y < height; y += stepY) {
            int rowStart = y * width;
            for (int x = 0; x < width; x += stepX) {
                int p = pixels[rowStart + x];
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
    static int[] sharpen(int[] pixels, int width, int height, float amount) {
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
        return out;
    }

    private static int sharpenChannel(int c, int up, int down, int left, int right, int shift,
                                       float center, float side) {
        int cv = (c >> shift) & 0xFF;
        int uv = (up >> shift) & 0xFF;
        int dv = (down >> shift) & 0xFF;
        int lv = (left >> shift) & 0xFF;
        int rv = (right >> shift) & 0xFF;
        return clamp255(center * cv + side * (uv + dv + lv + rv));
    }

    /** Linear contrast scale pivoted around mid-gray: factor &lt; 1 reduces contrast. */
    static float applyContrast(float value, float factor) {
        return (value - 128f) * factor + 128f;
    }

    private static int clamp255(float value) {
        int rounded = Math.round(value);
        if (rounded < 0) return 0;
        if (rounded > 255) return 255;
        return rounded;
    }

    private AutoEnhance() {
    }
}

package com.rawviewergo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.rawviewergo.RawFileUtils.RawFormat;

import org.junit.Test;

/**
 * Tests the pure pixel-array pipeline directly (no Bitmap/Canvas involved), so these run as
 * plain JVM unit tests - fast, no emulator or Robolectric needed. Run with:
 *   ./gradlew.bat test
 */
public class AutoEnhanceTest {

    private static int gray(int value) {
        return (0xFF << 24) | (value << 16) | (value << 8) | value;
    }

    private static int rgb(int r, int g, int b) {
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int red(int pixel) {
        return (pixel >> 16) & 0xFF;
    }

    private static int green(int pixel) {
        return (pixel >> 8) & 0xFF;
    }

    private static int blue(int pixel) {
        return pixel & 0xFF;
    }

    // --- computeLevels ---

    @Test
    public void computeLevels_rampImage_excludesExtremeTails() {
        // A 0..255 ramp gives each luminance bucket roughly one sample (float rounding in the
        // luminance formula can nudge a value into a neighboring bucket, so this allows a
        // small margin rather than asserting an exact bucket index), so the 1%/99% percentile
        // cut should land just inside the extremes, not at 0/255.
        int width = 256;
        int[] pixels = new int[width];
        for (int x = 0; x < width; x++) {
            pixels[x] = gray(x);
        }

        float[] result = AutoEnhance.computeLevels(pixels, width, 1);

        assertTrue("low should exclude the darkest tail, was " + result[0], result[0] >= 0f && result[0] <= 3f);
        assertTrue("high should exclude the brightest tail, was " + result[1], result[1] >= 251f && result[1] <= 255f);
    }

    @Test
    public void computeLevels_uniformImage_fallsBackToFullRange() {
        int width = 10;
        int height = 10;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, gray(128));

        float[] result = AutoEnhance.computeLevels(pixels, width, height);

        assertEquals(0f, result[0], 0f);
        assertEquals(255f, result[1], 0f);
    }

    // --- applyToPixels: color balance branching ---

    // Large enough that the 1%/99% percentile counts aren't degenerate (a 3x3 image rounds
    // those counts to 0, which trivially picks low=0 and produces an unrealistically extreme
    // stretch/clip that isn't representative of any real photo) - this size also triggers the
    // same "uniform image" full-range fallback as computeLevels_uniformImage_fallsBackToFullRange.
    private static final int UNIFORM_TEST_SIZE = 50;

    @Test
    public void applyToPixels_mef_keepsNeutralGrayNeutral() {
        // Saturation/levels are luminance-preserving for an exactly neutral input, and MEF
        // skips the DCR-only color balance shift, so R/G/B should stay exactly equal.
        int[] pixels = new int[UNIFORM_TEST_SIZE * UNIFORM_TEST_SIZE];
        java.util.Arrays.fill(pixels, gray(128));

        int[] result = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.MEF);

        int pixel = result[0];
        assertEquals(red(pixel), green(pixel));
        assertEquals(green(pixel), blue(pixel));
    }

    @Test
    public void applyToPixels_other_keepsNeutralGrayNeutral() {
        // Any unrecognized format should get only the general boost - no color balance shift -
        // so a neutral gray input stays neutral.
        int[] pixels = new int[UNIFORM_TEST_SIZE * UNIFORM_TEST_SIZE];
        java.util.Arrays.fill(pixels, gray(128));

        int[] result = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.OTHER);

        int pixel = result[0];
        assertEquals(red(pixel), green(pixel));
        assertEquals(green(pixel), blue(pixel));
    }

    @Test
    public void applyToPixels_dcr_shiftsNeutralGrayTowardRedAndYellow() {
        int[] pixels = new int[UNIFORM_TEST_SIZE * UNIFORM_TEST_SIZE];
        java.util.Arrays.fill(pixels, gray(128));

        int[] result = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.DCR);

        int pixel = result[0];
        assertTrue("expected red > green (shift toward red)", red(pixel) > green(pixel));
        // >= rather than > : if DCR_RED_SHIFT and DCR_YELLOW_SHIFT ever end up numerically
        // equal, green and blue gains coincide too, and a perfectly neutral gray input (like
        // this one) collapses green == blue exactly - that's correct behavior, not a bug.
        assertTrue("expected green >= blue (shift toward yellow)", green(pixel) >= blue(pixel));
    }

    @Test
    public void applyToPixels_iiq_appliesFlatBrightnessAndRedGreenShiftAsideFromSharpening() {
        // IIQ skips levels/saturation/contrast entirely, but does get a flat, direct brightness
        // multiplier plus a small red/green shift - a uniform image is a fixed point of the
        // sharpen kernel too, so the full pipeline should be exactly these per-channel gains.
        int[] pixels = new int[UNIFORM_TEST_SIZE * UNIFORM_TEST_SIZE];
        java.util.Arrays.fill(pixels, rgb(100, 150, 200));

        int[] result = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.IIQ);

        float redGain = AutoEnhance.IIQ_BRIGHTNESS_MULTIPLIER * (1f + AutoEnhance.IIQ_RED_SHIFT);
        float greenGain = AutoEnhance.IIQ_BRIGHTNESS_MULTIPLIER * (1f - AutoEnhance.IIQ_RED_SHIFT);
        float blueGain = AutoEnhance.IIQ_BRIGHTNESS_MULTIPLIER;
        int[] expected = new int[pixels.length];
        int expectedPixel = rgb(Math.round(100 * redGain), Math.round(150 * greenGain), Math.round(200 * blueGain));
        java.util.Arrays.fill(expected, expectedPixel);
        assertArrayEquals(expected, result);
    }

    @Test
    public void applyToPixels_dcrVsMefVsIiqVsOther_dcrAndIiqShiftInOppositeDirections() {
        // Same input, only the format differs - DCR and IIQ shift color balance in opposite
        // directions, while MEF and OTHER both leave a neutral input neutral.
        int[] pixels = new int[UNIFORM_TEST_SIZE * UNIFORM_TEST_SIZE];
        java.util.Arrays.fill(pixels, gray(100));

        int[] mefResult = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.MEF);
        int[] otherResult = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.OTHER);
        int[] dcrResult = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.DCR);
        int[] iiqResult = AutoEnhance.applyToPixels(pixels, UNIFORM_TEST_SIZE, UNIFORM_TEST_SIZE, RawFormat.IIQ);

        int mefPixel = mefResult[0];
        int otherPixel = otherResult[0];
        int dcrPixel = dcrResult[0];
        int iiqPixel = iiqResult[0];
        assertEquals(red(mefPixel), green(mefPixel)); // MEF stays neutral
        assertEquals(red(otherPixel), green(otherPixel)); // OTHER stays neutral
        assertTrue(red(dcrPixel) > green(dcrPixel)); // DCR shifts toward red
        assertTrue(green(iiqPixel) > red(iiqPixel)); // IIQ shifts away from red
    }

    // --- contrast ---

    @Test
    public void applyContrast_pullsValuesTowardMidGray() {
        float factor = AutoEnhance.MEF_CONTRAST_FACTOR; // 0.85

        float above = AutoEnhance.applyContrast(200f, factor);
        float below = AutoEnhance.applyContrast(50f, factor);

        assertEquals(128f + (200f - 128f) * factor, above, 0.001f);
        assertEquals(128f - (128f - 50f) * factor, below, 0.001f);
        assertTrue("distance above mid-gray should shrink", (above - 128f) < (200f - 128f));
        assertTrue("distance below mid-gray should shrink", (128f - below) < (128f - 50f));
    }

    @Test
    public void applyContrast_leavesMidGrayUnchanged() {
        assertEquals(128f, AutoEnhance.applyContrast(128f, AutoEnhance.MEF_CONTRAST_FACTOR), 0.001f);
    }

    @Test
    public void iiqContrast_hasNoAdjustment() {
        // Every brightness/contrast/color-balance combination tried for IIQ still looked
        // overcontrasty, so IIQ skips the whole color/tone step (including contrast) entirely -
        // same as OTHER - rather than using any factor.
        assertTrue("IIQ should have no contrast adjustment", !AutoEnhance.hasContrastAdjustment(RawFormat.IIQ));
        assertEquals(200f, AutoEnhance.applyContrastIfEnabled(200f, RawFormat.IIQ), 0.001f);
    }

    @Test
    public void sharpenAmountFor_mefExceedsGeneral_iiqIsReducedToAvoidAmplifyingNoise() {
        float general = AutoEnhance.sharpenAmountFor(RawFormat.OTHER);
        float dcr = AutoEnhance.sharpenAmountFor(RawFormat.DCR);
        float mef = AutoEnhance.sharpenAmountFor(RawFormat.MEF);
        float iiq = AutoEnhance.sharpenAmountFor(RawFormat.IIQ);

        assertEquals("DCR has no sharpen-specific tuning yet", general, dcr, 0.0001f);
        assertTrue("MEF should sharpen more than general", mef > general);
        // IIQ's smooth-sky sample photo showed amplified sensor/demosaic noise as a visible
        // speckled/grid texture under the general sharpen amount, so IIQ is dialed well below
        // general rather than above it.
        assertTrue("IIQ should sharpen less than general to avoid amplifying noise", iiq < general);
    }

    @Test
    public void dcrSaturation_isLessThanGeneralAndLessThanMef() {
        // "Subtract 5% for DCR" means DCR's effective saturation boost should end up below
        // both the general amount and MEF's (which instead stacks an extra multiplier on top).
        float dcrSaturation = AutoEnhance.SATURATION_BOOST + AutoEnhance.DCR_SATURATION_DELTA;
        float mefSaturation = AutoEnhance.SATURATION_BOOST * AutoEnhance.SATURATION_BOOST_MEF_EXTRA;

        assertTrue("DCR saturation should be below the general boost", dcrSaturation < AutoEnhance.SATURATION_BOOST);
        assertTrue("DCR saturation should be below MEF's", dcrSaturation < mefSaturation);
    }

    // --- sharpen ---

    @Test
    public void sharpen_zeroAmount_isIdentity() {
        int width = 4;
        int height = 4;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = rgb(i * 5 % 256, i * 7 % 256, i * 11 % 256);
        }

        int[] result = AutoEnhance.sharpen(pixels, width, height, 0f);

        assertArrayEquals(pixels, result);
    }

    @Test
    public void sharpen_boostsBrightCenterAndLeavesBorderUnchanged() {
        int width = 3;
        int height = 3;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, gray(50));
        pixels[4] = gray(200); // (1,1) - the only non-border pixel

        int[] result = AutoEnhance.sharpen(pixels, width, height, AutoEnhance.SHARPEN_AMOUNT);

        // center is boosted well past 255 regardless of the exact tuning of SHARPEN_AMOUNT,
        // so this clamps to white either way.
        assertEquals(255, red(result[4]));
        // every border pixel (everything except index 4) passes through unchanged
        for (int i = 0; i < result.length; i++) {
            if (i == 4) continue;
            assertEquals("border pixel " + i + " should be unchanged", pixels[i], result[i]);
        }
    }

    @Test
    public void sharpen_uniformImage_isUnchanged() {
        // center + 4*side == 1 always, so a flat image is a fixed point of the kernel.
        int width = 3;
        int height = 3;
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, gray(150));

        int[] result = AutoEnhance.sharpen(pixels, width, height, AutoEnhance.SHARPEN_AMOUNT);

        assertArrayEquals(pixels, result);
    }
}

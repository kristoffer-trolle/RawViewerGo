package com.rawviewergo;

import com.rawviewergo.RawFileUtils.RawFormat;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Standalone runner (NOT part of the Gradle build) that calls the app's real, current
 * AutoEnhance.applyToPixels against full-resolution sample photos and writes the result back
 * to enhance_tuning/, so tuning changes can be checked without an emulator and without a
 * separate reimplementation to keep in sync.
 *
 * This lives outside app/src because Android Gradle Plugin compiles app/src/test against a
 * restricted JDK module set (core-for-system-modules.jar) that excludes java.desktop/
 * javax.imageio entirely, even though unit tests execute on the full host JVM. Compiling and
 * running this file directly with the JDK's own javac/java (not through Gradle) sidesteps
 * that restriction while still exercising the exact same AutoEnhance class the app ships.
 *
 * Run via enhance_tuning/render.sh (after `gradlew test` or `gradlew bundleDebugClassesToCompileJar`
 * so the compiled classes are current), or manually:
 *   javac -cp "<app classes jar>;<android.jar>" -d out enhance_tuning/EnhanceRunner.java
 *   java -cp "out;<app classes jar>;<android.jar>" com.rawviewergo.EnhanceRunner
 */
public class EnhanceRunner {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "enhance_tuning");
        render(dir, "dcr", RawFormat.DCR);
        render(dir, "mef", RawFormat.MEF);
        render(dir, "iiq", RawFormat.IIQ);
    }

    private static void render(File dir, String name, RawFormat format) throws Exception {
        File baseFile = new File(dir, "base_" + name + ".png");
        if (!baseFile.isFile()) {
            System.out.println("skipping " + name + " - " + baseFile.getPath() + " not present");
            return;
        }

        BufferedImage base = ImageIO.read(baseFile);
        int width = base.getWidth();
        int height = base.getHeight();
        int[] pixels = base.getRGB(0, 0, width, height, null, 0, width);

        printDiagnostics(name, format, pixels, width, height);

        int[] enhanced = AutoEnhance.applyToPixels(pixels, width, height, format);

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, width, height, enhanced, 0, width);

        File outFile = new File(dir, "enhanced_" + name + ".png");
        ImageIO.write(out, "png", outFile);

        File previewFile = new File(dir, "enhanced_" + name + "_preview.jpg");
        ImageIO.write(scaleDown(out, 1400), "jpg", previewFile);

        System.out.println(name + ": " + width + "x" + height
                + " -> wrote " + outFile.getPath() + " and " + previewFile.getPath());
    }

    private static void printDiagnostics(String name, RawFormat format, int[] pixels, int width, int height) {
        if (format == RawFormat.IIQ) {
            float redGain = AutoEnhance.IIQ_BRIGHTNESS_MULTIPLIER * (1f + AutoEnhance.IIQ_RED_SHIFT);
            float greenGain = AutoEnhance.IIQ_BRIGHTNESS_MULTIPLIER * (1f - AutoEnhance.IIQ_RED_SHIFT);
            System.out.println(name + " diagnostics: no auto-levels/saturation/contrast at all - just flat"
                    + " per-channel gains (red " + redGain + "x, green " + greenGain + "x, blue "
                    + AutoEnhance.IIQ_BRIGHTNESS_MULTIPLIER + "x; no percentile stretch, so no clipping beyond"
                    + " what was already in the base decode) plus sharpening.");
            return;
        }

        float[] blackWhite = AutoEnhance.computeLevels(pixels, width, height);
        float low = blackWhite[0];
        float high = blackWhite[1];

        float brightnessBoost = AutoEnhance.brightnessBoostFor(format);
        float preBoostScale = Math.min(255f / Math.max(1f, high - low), AutoEnhance.MAX_LEVELS_SCALE);
        float scale = preBoostScale * brightnessBoost;
        float offset = -low * scale;
        float highPointBeforeClamp = high * scale + offset; // where the 99th-percentile tone actually lands

        boolean hasContrast = AutoEnhance.hasContrastAdjustment(format);
        float afterContrast = AutoEnhance.applyContrastIfEnabled(highPointBeforeClamp, format);

        System.out.println(name + " diagnostics:");
        System.out.println("  black/white point (1st/99th percentile): " + low + " / " + high);
        System.out.println("  brightness multiplier: " + brightnessBoost
                + " (levels-stretch scale " + preBoostScale + " -> boosted scale " + scale + ", offset " + offset + ")");
        System.out.println("  99th-percentile tone after levels+brightness (pre-clamp): " + highPointBeforeClamp
                + (highPointBeforeClamp > 255 ? "  <-- clips to white, overshoot by " + (highPointBeforeClamp - 255) : ""));
        System.out.println("  contrast adjustment: " + (hasContrast ? ("factor " + AutoEnhance.contrastFactorFor(format)) : "none (skipped)")
                + " -> same 99th-percentile tone after contrast: " + afterContrast
                + (afterContrast > 255 ? "  <-- still clips to white" : ""));
    }

    private static BufferedImage scaleDown(BufferedImage src, int maxDim) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, maxDim / (double) Math.max(w, h));
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }
}

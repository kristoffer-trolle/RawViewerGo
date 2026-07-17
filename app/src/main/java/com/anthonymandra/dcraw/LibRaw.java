package com.anthonymandra.dcraw;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;

/**
 * JNI bridge to the prebuilt libraw_r.so / libraw.so binaries (LibRaw compiled for Android).
 * The package/class name and native method signature below must match exactly what is
 * statically bound inside libraw.so (Java_com_anthonymandra_dcraw_LibRaw_getThumbFile) -
 * this is the one entry point we've verified byte-for-byte against a decompile, everything
 * else libraw.so exports (getImageFile, getHalfImageFile, etc.) is left unused until we
 * build our own JNI layer against LibRaw source (see project notes).
 */
public class LibRaw {

    private static final String TAG = "LibRaw";
    public static volatile boolean loaded = false;

    static {
        try {
            System.loadLibrary("raw_r");
            System.loadLibrary("raw");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Could not load libraw native libraries", e);
        }
    }

    private static native byte[] getThumbFile(String path, String[] args, int quality,
                                               Bitmap.Config config, Bitmap.CompressFormat format);

    /**
     * Decodes the embedded preview/thumbnail image from a raw file and returns it as a Bitmap.
     * Returns null if the native libs failed to load or the file could not be decoded.
     */
    public static Bitmap decodePreview(File file) {
        if (!loaded) {
            return null;
        }
        try {
            byte[] jpeg = getThumbFile(file.getAbsolutePath(), new String[0], 90,
                    Bitmap.Config.ARGB_8888, Bitmap.CompressFormat.JPEG);
            if (jpeg == null || jpeg.length == 0) {
                return null;
            }
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Throwable t) {
            Log.e(TAG, "decodePreview failed for " + file, t);
            return null;
        }
    }
}

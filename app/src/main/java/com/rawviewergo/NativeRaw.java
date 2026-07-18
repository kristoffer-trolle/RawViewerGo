package com.rawviewergo;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * JNI bridge to our own from-source native build (app/src/main/cpp, vendoring LibRaw 0.22.2),
 * distinct from the reused prebuilt com.anthonymandra.dcraw.LibRaw used for fast browse-grid
 * thumbnails. This is the full-resolution demosaic path used by the viewer screen.
 */
final class NativeRaw {

    private static final String TAG = "NativeRaw";
    static volatile boolean loaded = false;

    static {
        try {
            System.loadLibrary("rawviewer");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Could not load native rawviewer library", e);
        }
    }

    private static native Bitmap decodeFull(String path);

    /**
     * Runs a full-resolution LibRaw demosaic of the raw file and returns it as a Bitmap.
     * Returns null if the native lib failed to load or the file could not be decoded.
     */
    static Bitmap decode(java.io.File file) {
        if (!loaded) {
            return null;
        }
        try {
            return decodeFull(file.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "decode failed for " + file, t);
            return null;
        }
    }

    private NativeRaw() {
    }
}

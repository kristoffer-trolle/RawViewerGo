package com.rawviewergo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

/**
 * JNI bridge to our own from-source native build (app/src/main/cpp, vendoring LibRaw 0.22.2).
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

    private static native Object decodeThumbFd(int fd);

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

    /**
     * Extracts the embedded thumbnail straight from the SAF content:// Uri via a seekable
     * file descriptor - no local copy of the (often tens-of-MB) raw file is made, since
     * LibRaw only reads the specific byte ranges it needs to locate and decode the thumb.
     * Returns null if the native lib failed to load or no thumbnail could be extracted.
     */
    static Bitmap decodeThumbnail(Context context, Uri uri) {
        if (!loaded) {
            return null;
        }
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                return null;
            }
            Object result = decodeThumbFd(pfd.getFd());
            if (result instanceof Bitmap) {
                return (Bitmap) result;
            } else if (result instanceof byte[]) {
                byte[] jpeg = (byte[]) result;
                return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            }
            return null;
        } catch (IOException e) {
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "decodeThumbnail failed for " + uri, t);
            return null;
        }
    }

    private NativeRaw() {
    }
}

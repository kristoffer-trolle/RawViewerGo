package com.rawviewergo;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Helpers for finding a sensible default folder (USB card reader root, falling back to
 * Downloads), listing raw files in a picked folder, and staging a picked raw file into local
 * cache storage so the LibRaw JNI layer (which needs a real filesystem path) can read it.
 */
final class RawFileUtils {

    private static final String TAG = "RawFileUtils";
    private static final String[] RAW_EXTENSIONS = {".dcr", ".mef"};

    private RawFileUtils() {
    }

    static boolean isRawFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : RAW_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    static List<DocumentFile> listRawFiles(DocumentFile folder) {
        List<DocumentFile> result = new ArrayList<>();
        if (folder == null || !folder.isDirectory()) {
            return result;
        }
        for (DocumentFile child : folder.listFiles()) {
            if (child.isFile() && isRawFile(child.getName())) {
                result.add(child);
            }
        }
        result.sort((a, b) -> {
            String an = a.getName() == null ? "" : a.getName();
            String bn = b.getName() == null ? "" : b.getName();
            return an.compareToIgnoreCase(bn);
        });
        return result;
    }

    /**
     * Builds an ACTION_OPEN_DOCUMENT_TREE intent seeded at the best-guess default location:
     * the first removable storage volume (USB card reader) if one is mounted, else the
     * Downloads folder on primary storage. This still requires one user tap to confirm access
     * (Android does not allow silently granting folder access) but avoids manual navigation.
     */
    static Intent createDefaultFolderIntent(Context context) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        StorageManager storageManager = context.getSystemService(StorageManager.class);
        if (storageManager != null) {
            for (StorageVolume volume : storageManager.getStorageVolumes()) {
                if (volume.isRemovable() && !volume.isPrimary()) {
                    Intent scoped = volume.createOpenDocumentTreeIntent();
                    scoped.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    Log.i(TAG, "Seeding folder picker at removable volume: " + volume.getDescription(context));
                    return scoped;
                }
            }
        }

        // No USB volume mounted - fall back to the Downloads folder on primary storage.
        Uri downloadsUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOWNLOADS);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri);
        Log.i(TAG, "No removable volume found, seeding folder picker at Downloads");
        return intent;
    }

    static boolean hasPersistedPermission(Context context, Uri treeUri) {
        for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies a SAF document into this app's cache directory so native code can open it by
     * path. Skips the copy if a same-named file is already cached (raw files on a card reader
     * are not expected to change during a browsing session).
     */
    static File stageForDecode(Context context, DocumentFile document) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "raw_stage");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Could not create cache dir " + cacheDir);
        }
        String name = document.getName() != null ? document.getName() : ("raw_" + document.getUri().hashCode());
        File dest = new File(cacheDir, safeFileName(document.getUri()) + "_" + name);

        if (dest.exists() && dest.length() == document.length() && document.length() > 0) {
            return dest;
        }

        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = resolver.openInputStream(document.getUri())) {
            if (in == null) {
                throw new IOException("Could not open " + document.getUri());
            }
            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return dest;
    }

    private static String safeFileName(Uri uri) {
        return Integer.toHexString(uri.toString().hashCode());
    }
}

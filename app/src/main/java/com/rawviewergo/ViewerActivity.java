package com.rawviewergo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import com.anthonymandra.dcraw.LibRaw;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends AppCompatActivity {

    static final String EXTRA_URI = "extra_uri";
    static final String EXTRA_NAME = "extra_name";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageView imageView;
    private ProgressBar progressBar;
    private TextView errorText;
    private Button buttonAutoEnhance;

    private Bitmap baseBitmap;
    private Bitmap enhancedBitmap;
    private boolean enhanceComputing;
    private RawFileUtils.RawFormat rawFormat;
    private String documentName;
    // Background decode/enhance work can still be in flight (native JNI calls don't respond to
    // Thread.interrupt(), so executor.shutdownNow() in onDestroy doesn't stop them) when the
    // user backs out mid-load. Both background tasks post their result back via mainHandler,
    // which runs on the main thread alongside onDestroy, so checking this flag first (set
    // before shutting down the executor) reliably skips touching the executor/UI afterward
    // instead of crashing with RejectedExecutionException.
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        imageView = findViewById(R.id.imageViewer);
        progressBar = findViewById(R.id.progressViewer);
        errorText = findViewById(R.id.textViewerError);
        buttonAutoEnhance = findViewById(R.id.buttonAutoEnhance);

        InsetUtils.applyTopBarInset(findViewById(R.id.topBar));

        findViewById(R.id.buttonBack).setOnClickListener(v -> finish());
        buttonAutoEnhance.setOnClickListener(v -> toggleAutoEnhance());
        findViewById(R.id.buttonShare).setOnClickListener(v -> shareCurrentImage());
        updateAutoEnhanceButtonText();

        Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
        if (uri == null) {
            finish();
            return;
        }
        documentName = getIntent().getStringExtra(EXTRA_NAME);
        setTitle(documentName);
        rawFormat = RawFileUtils.classify(documentName);
        loadImage(uri);
    }

    private void loadImage(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);

        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                DocumentFile document = DocumentFile.fromSingleUri(this, uri);
                File staged = RawFileUtils.stageForDecode(this, document);
                bitmap = NativeRaw.decode(staged);
                if (bitmap == null) {
                    bitmap = LibRaw.decodePreview(staged);
                }
            } catch (Exception ignored) {
                // handled below via null bitmap
            }
            Bitmap finalBitmap = bitmap;
            mainHandler.post(() -> {
                if (destroyed) {
                    return;
                }
                if (finalBitmap != null) {
                    baseBitmap = finalBitmap;
                    onBaseImageReady();
                } else {
                    progressBar.setVisibility(View.GONE);
                    errorText.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void onBaseImageReady() {
        if (!AutoEnhance.isEnabled(this)) {
            progressBar.setVisibility(View.GONE);
            imageView.setImageBitmap(baseBitmap);
        }
        // else leave the spinner up - startEnhanceComputation's callback shows the result.

        // Precompute the enhanced version in the background regardless of whether Auto Enhance
        // is on right now, so toggling it on later doesn't need its own multi-second wait on
        // top of the decode - by the time the button is tapped this is usually already done.
        startEnhanceComputation();
    }

    private void toggleAutoEnhance() {
        boolean enabled = !AutoEnhance.isEnabled(this);
        AutoEnhance.setEnabled(this, enabled);
        updateAutoEnhanceButtonText();
        if (!enabled) {
            imageView.setImageBitmap(baseBitmap);
            return;
        }
        if (enhancedBitmap != null) {
            imageView.setImageBitmap(enhancedBitmap);
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        startEnhanceComputation();
    }

    private void updateAutoEnhanceButtonText() {
        buttonAutoEnhance.setText(AutoEnhance.isEnabled(this)
                ? R.string.disable_auto_enhance
                : R.string.auto_enhance);
    }

    /** No-op if already computed/computing - safe to call any time the base image is ready. */
    private void startEnhanceComputation() {
        if (baseBitmap == null || enhancedBitmap != null || enhanceComputing) {
            return;
        }
        enhanceComputing = true;
        Bitmap sourceForThisRequest = baseBitmap;
        executor.execute(() -> {
            Bitmap result = AutoEnhance.apply(sourceForThisRequest, rawFormat);
            mainHandler.post(() -> {
                enhanceComputing = false;
                if (destroyed || sourceForThisRequest != baseBitmap) {
                    return; // activity gone, or a different image loaded while this was computing
                }
                enhancedBitmap = result;
                if (AutoEnhance.isEnabled(this)) {
                    progressBar.setVisibility(View.GONE);
                    imageView.setImageBitmap(result);
                }
            });
        });
    }

    /** Shares whatever is currently on screen - the enhanced version if that's what's showing. */
    private void shareCurrentImage() {
        if (baseBitmap == null) {
            return; // still loading - nothing to share yet
        }
        Bitmap toShare = (AutoEnhance.isEnabled(this) && enhancedBitmap != null) ? enhancedBitmap : baseBitmap;

        executor.execute(() -> {
            Uri uri = null;
            try {
                File dir = new File(getCacheDir(), "shared_images");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Could not create " + dir);
                }
                String baseName = documentName != null ? stripExtension(documentName) : "image";
                File file = new File(dir, baseName + ".jpg");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    toShare.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            } catch (IOException ignored) {
                // handled below via null uri
            }
            Uri finalUri = uri;
            mainHandler.post(() -> {
                if (destroyed) {
                    return;
                }
                if (finalUri == null) {
                    Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("image/jpeg");
                shareIntent.putExtra(Intent.EXTRA_STREAM, finalUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
            });
        });
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        executor.shutdownNow();
    }
}

package com.rawviewergo;

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

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.anthonymandra.dcraw.LibRaw;

import java.io.File;
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
    private boolean isMef;

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
        updateAutoEnhanceButtonText();

        Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
        if (uri == null) {
            finish();
            return;
        }
        String name = getIntent().getStringExtra(EXTRA_NAME);
        setTitle(name);
        isMef = RawFileUtils.isMef(name);
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
                progressBar.setVisibility(View.GONE);
                if (finalBitmap != null) {
                    baseBitmap = finalBitmap;
                    applyCurrentEnhanceState();
                } else {
                    errorText.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void toggleAutoEnhance() {
        boolean enabled = !AutoEnhance.isEnabled(this);
        AutoEnhance.setEnabled(this, enabled);
        updateAutoEnhanceButtonText();
        applyCurrentEnhanceState();
    }

    private void updateAutoEnhanceButtonText() {
        buttonAutoEnhance.setText(AutoEnhance.isEnabled(this)
                ? R.string.disable_auto_enhance
                : R.string.auto_enhance);
    }

    private void applyCurrentEnhanceState() {
        if (baseBitmap == null) {
            return;
        }
        if (!AutoEnhance.isEnabled(this)) {
            imageView.setImageBitmap(baseBitmap);
            return;
        }
        if (enhancedBitmap != null) {
            imageView.setImageBitmap(enhancedBitmap);
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        Bitmap sourceForThisRequest = baseBitmap;
        executor.execute(() -> {
            Bitmap result = AutoEnhance.apply(sourceForThisRequest, isMef);
            enhancedBitmap = result;
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                if (AutoEnhance.isEnabled(this) && sourceForThisRequest == baseBitmap) {
                    imageView.setImageBitmap(result);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}

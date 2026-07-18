package com.rawviewergo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class BrowseActivity extends AppCompatActivity {

    private static final String PREFS = "rawviewergo";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final int GRID_SPAN_COUNT = 3;

    private RecyclerView recyclerView;
    private TextView emptyState;
    private Button buttonAutoEnhance;
    private ThumbnailAdapter adapter;
    private Uri currentTreeUri;

    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Uri treeUri = result.getData().getData();
                if (treeUri == null) {
                    return;
                }
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_TREE_URI, treeUri.toString()).apply();
                openFolder(treeUri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);

        recyclerView = findViewById(R.id.recyclerThumbnails);
        emptyState = findViewById(R.id.textEmptyState);
        buttonAutoEnhance = findViewById(R.id.buttonAutoEnhance);
        recyclerView.setLayoutManager(new GridLayoutManager(this, GRID_SPAN_COUNT));

        InsetUtils.applyTopBarInset(findViewById(R.id.topBar));
        findViewById(R.id.buttonSelectFolder).setOnClickListener(v -> launchFolderPicker());
        buttonAutoEnhance.setOnClickListener(v -> toggleAutoEnhance());
        updateAutoEnhanceButtonText();

        restorePersistedFolderOrShowEmptyState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = AutoEnhance.isEnabled(this);
        updateAutoEnhanceButtonText();
        if (adapter != null) {
            adapter.setEnhanceEnabled(enabled);
        }
    }

    private void toggleAutoEnhance() {
        boolean enabled = !AutoEnhance.isEnabled(this);
        AutoEnhance.setEnabled(this, enabled);
        updateAutoEnhanceButtonText();
        if (adapter != null) {
            adapter.setEnhanceEnabled(enabled);
        }
    }

    private void updateAutoEnhanceButtonText() {
        buttonAutoEnhance.setText(AutoEnhance.isEnabled(this)
                ? R.string.disable_auto_enhance
                : R.string.auto_enhance);
    }

    private void launchFolderPicker() {
        Intent intent = currentTreeUri != null
                ? seededIntent(currentTreeUri)
                : RawFileUtils.createDefaultFolderIntent(this);
        folderPickerLauncher.launch(intent);
    }

    private Intent seededIntent(Uri initialUri) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        return intent;
    }

    private void restorePersistedFolderOrShowEmptyState() {
        String saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null);
        if (saved != null) {
            Uri treeUri = Uri.parse(saved);
            if (RawFileUtils.hasPersistedPermission(this, treeUri)) {
                openFolder(treeUri);
                return;
            }
        }
        emptyState.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    private void openFolder(Uri treeUri) {
        currentTreeUri = treeUri;
        DocumentFile folder = DocumentFile.fromTreeUri(this, treeUri);
        List<DocumentFile> rawFiles = RawFileUtils.listRawFiles(folder);

        if (adapter != null) {
            adapter.shutdown();
        }
        adapter = new ThumbnailAdapter(this, rawFiles, this::openViewer);
        recyclerView.setAdapter(adapter);

        boolean hasFiles = !rawFiles.isEmpty();
        recyclerView.setVisibility(hasFiles ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(hasFiles ? View.GONE : View.VISIBLE);
        emptyState.setText(hasFiles ? "" : getString(R.string.no_raw_files));
    }

    private void openViewer(DocumentFile document) {
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.putExtra(ViewerActivity.EXTRA_URI, document.getUri());
        intent.putExtra(ViewerActivity.EXTRA_NAME, document.getName());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) {
            adapter.shutdown();
        }
    }
}

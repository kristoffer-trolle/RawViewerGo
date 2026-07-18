package com.rawviewergo;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import com.anthonymandra.dcraw.LibRaw;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class ThumbnailAdapter extends RecyclerView.Adapter<ThumbnailAdapter.ViewHolder> {

    private static final String TAG = "ThumbnailAdapter";

    interface OnItemClickListener {
        void onItemClick(DocumentFile document);
    }

    private final Context context;
    private final List<DocumentFile> items;
    private final OnItemClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, CachedThumb> memoryCache;
    private boolean enhanceEnabled;

    ThumbnailAdapter(Context context, List<DocumentFile> items, OnItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
        this.enhanceEnabled = AutoEnhance.isEnabled(context);

        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.memoryCache = new LruCache<String, CachedThumb>(maxMemoryKb / 8) {
            @Override
            protected int sizeOf(String key, CachedThumb value) {
                int bytes = value.base.getByteCount();
                if (value.enhanced != null) {
                    bytes += value.enhanced.getByteCount();
                }
                return bytes / 1024;
            }
        };
    }

    void setEnhanceEnabled(boolean enabled) {
        this.enhanceEnabled = enabled;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_thumbnail, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DocumentFile document = items.get(position);
        String uriString = document.getUri().toString();

        holder.itemView.setTag(uriString);
        holder.name.setText(document.getName());
        holder.image.setImageBitmap(null);

        CachedThumb cached = memoryCache.get(uriString);
        if (cached != null) {
            displayCached(holder, uriString, cached);
            holder.itemView.setOnClickListener(v -> listener.onItemClick(document));
            return;
        }

        holder.progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            Bitmap bitmap = decode(document);
            CachedThumb entry = bitmap != null ? new CachedThumb(bitmap) : null;
            if (entry != null) {
                memoryCache.put(uriString, entry);
                if (enhanceEnabled) {
                    entry.enhanced = AutoEnhance.apply(entry.base);
                }
            }
            mainHandler.post(() -> {
                if (uriString.equals(holder.itemView.getTag())) {
                    holder.progress.setVisibility(View.GONE);
                    if (entry != null) {
                        holder.image.setImageBitmap(
                                enhanceEnabled && entry.enhanced != null ? entry.enhanced : entry.base);
                    }
                }
            });
        });

        holder.itemView.setOnClickListener(v -> listener.onItemClick(document));
    }

    private void displayCached(ViewHolder holder, String uriString, CachedThumb cached) {
        holder.progress.setVisibility(View.GONE);
        if (!enhanceEnabled) {
            holder.image.setImageBitmap(cached.base);
            return;
        }
        if (cached.enhanced != null) {
            holder.image.setImageBitmap(cached.enhanced);
            return;
        }
        holder.image.setImageBitmap(cached.base);
        executor.execute(() -> {
            Bitmap enhanced = AutoEnhance.apply(cached.base);
            cached.enhanced = enhanced;
            mainHandler.post(() -> {
                if (uriString.equals(holder.itemView.getTag())) {
                    holder.image.setImageBitmap(enhanced);
                }
            });
        });
    }

    private Bitmap decode(DocumentFile document) {
        // Reads directly off the SAF Uri via a seekable file descriptor - no local copy of
        // the raw file needed, since LibRaw only pulls the byte ranges it actually needs.
        Bitmap bitmap = NativeRaw.decodeThumbnail(context, document.getUri());
        if (bitmap != null) {
            return bitmap;
        }

        // Fallback: the reused prebuilt binary needs a real file path, so stage a local copy.
        try {
            File staged = RawFileUtils.stageForDecode(context, document);
            bitmap = LibRaw.decodePreview(staged);
            if (bitmap == null) {
                Log.w(TAG, "decodePreview returned null for " + document.getName());
            } else {
                Log.i(TAG, "decodePreview " + document.getName()
                        + " -> " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "decode failed for " + document.getName(), e);
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private static class CachedThumb {
        final Bitmap base;
        volatile Bitmap enhanced;

        CachedThumb(Bitmap base) {
            this.base = base;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ProgressBar progress;
        final TextView name;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imageThumb);
            progress = itemView.findViewById(R.id.progressThumb);
            name = itemView.findViewById(R.id.textThumbName);
        }
    }
}

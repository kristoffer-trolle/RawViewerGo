package com.rawviewergo;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
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

    interface OnItemClickListener {
        void onItemClick(DocumentFile document);
    }

    private final Context context;
    private final List<DocumentFile> items;
    private final OnItemClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memoryCache;

    ThumbnailAdapter(Context context, List<DocumentFile> items, OnItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;

        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.memoryCache = new LruCache<String, Bitmap>(maxMemoryKb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
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

        Bitmap cached = memoryCache.get(uriString);
        if (cached != null) {
            holder.image.setImageBitmap(cached);
            holder.progress.setVisibility(View.GONE);
            return;
        }

        holder.progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            Bitmap bitmap = decode(document);
            if (bitmap != null) {
                memoryCache.put(uriString, bitmap);
            }
            mainHandler.post(() -> {
                if (uriString.equals(holder.itemView.getTag())) {
                    holder.progress.setVisibility(View.GONE);
                    if (bitmap != null) {
                        holder.image.setImageBitmap(bitmap);
                    }
                }
            });
        });

        holder.itemView.setOnClickListener(v -> listener.onItemClick(document));
    }

    private Bitmap decode(DocumentFile document) {
        try {
            File staged = RawFileUtils.stageForDecode(context, document);
            Bitmap bmp = LibRaw.decodePreview(staged);
            if (bmp == null) {
                android.util.Log.w("ThumbnailAdapter", "decodePreview returned null for " + document.getName());
            }
            return bmp;
        } catch (Exception e) {
            android.util.Log.e("ThumbnailAdapter", "decode failed for " + document.getName(), e);
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

package com.example.vision1;

import android.content.ClipData;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<StorageItem> itemList;
    private Context context;
    private OnItemInteractionListener listener;

    public interface OnItemInteractionListener {
        void onFileClick(StoredDocument document);
        void onAudioPlayClick(StoredDocument document);
        void onCollectionClick(DocumentCollection collection);
        void onCollectionLongClick(DocumentCollection collection, View anchorView);
        void onItemDropped(StorageItem draggedItem, StorageItem targetItem);
    }

    public FileAdapter(Context context, List<StorageItem> itemList, OnItemInteractionListener listener) {
        this.context = context;
        this.itemList = itemList;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return itemList.get(position).getItemType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == StorageItem.TYPE_COLLECTION) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_storage_collection, parent, false);
            return new CollectionViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_storage_document, parent, false);
            return new DocumentViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        StorageItem item = itemList.get(position);

        // --- Setup Drag and Drop Mechanics ---
        // Allow ONLY documents to be dragged (you cannot drag a collection inside another collection)
        if (item.getItemType() == StorageItem.TYPE_DOCUMENT) {
            holder.itemView.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("", "");
                View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(v);
                // We pass the actual item as the "Local State" so we know EXACTLY what was dragged
                v.startDragAndDrop(data, shadowBuilder, item, 0);
                return true;
            });
        } else {
            // Collections listen for Long Clicks to trigger Edit/Delete menu
            holder.itemView.setOnLongClickListener(v -> {
                listener.onCollectionLongClick((DocumentCollection) item, v);
                return true;
            });
        }

        // Allow both Documents and Collections to act as DROP TARGETS
        holder.itemView.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setAlpha(0.6f); // Visual feedback when hovering over an item
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1.0f); // Restore transparency
                    break;
                case DragEvent.ACTION_DROP:
                    v.setAlpha(1.0f);
                    StorageItem draggedItem = (StorageItem) event.getLocalState();
                    if (draggedItem != item) { // Make sure they didn't drop it on itself
                        listener.onItemDropped(draggedItem, item);
                    }
                    break;
            }
            return true;
        });

        // --- Render UI ---
        if (holder instanceof DocumentViewHolder) {
            StoredDocument doc = (StoredDocument) item;
            DocumentViewHolder docHolder = (DocumentViewHolder) holder;
            File originalFile = new File(doc.getOriginalFilePath());

            docHolder.fileName.setText(originalFile.getName());

            if (isImage(originalFile)) {
                docHolder.filePreview.setImageBitmap(BitmapFactory.decodeFile(originalFile.getAbsolutePath()));
            } else if (isVideo(originalFile)) {
                docHolder.filePreview.setImageBitmap(ThumbnailUtils.createVideoThumbnail(originalFile.getAbsolutePath(), MediaStore.Images.Thumbnails.MINI_KIND));
            } else {
                docHolder.filePreview.setImageResource(android.R.drawable.ic_menu_agenda); // fallback generic icon
            }

            if (doc.isProcessing()) {
                docHolder.audioPlayButton.setVisibility(View.GONE);
                docHolder.processingProgress.setVisibility(View.VISIBLE);
            } else {
                docHolder.processingProgress.setVisibility(View.GONE);
                if (doc.getAudioFilePath() != null && new File(doc.getAudioFilePath()).exists()) {
                    docHolder.audioPlayButton.setVisibility(View.VISIBLE);
                } else {
                    docHolder.audioPlayButton.setVisibility(View.GONE);
                }
            }

            docHolder.itemView.setOnClickListener(v -> listener.onFileClick(doc));
            docHolder.audioPlayButton.setOnClickListener(v -> listener.onAudioPlayClick(doc));

        } else if (holder instanceof CollectionViewHolder) {
            DocumentCollection collection = (DocumentCollection) item;
            CollectionViewHolder colHolder = (CollectionViewHolder) holder;

            colHolder.collectionName.setText(collection.getName());

            // Render up to 4 mini previews
            ImageView[] previews = {colHolder.prev1, colHolder.prev2, colHolder.prev3, colHolder.prev4};
            List<StoredDocument> docs = collection.getDocuments();

            for (int i = 0; i < 4; i++) {
                if (i < docs.size()) {
                    previews[i].setVisibility(View.VISIBLE);
                    File f = new File(docs.get(i).getOriginalFilePath());
                    if (isImage(f)) { previews[i].setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath())); }
                    else { previews[i].setImageResource(android.R.drawable.ic_menu_agenda); }
                } else {
                    previews[i].setVisibility(View.INVISIBLE);
                }
            }

            colHolder.itemView.setOnClickListener(v -> listener.onCollectionClick(collection));
        }
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    public void updateList(List<StorageItem> newList) {
        itemList = newList;
        notifyDataSetChanged();
    }

    static class DocumentViewHolder extends RecyclerView.ViewHolder {
        TextView fileName;
        ImageView filePreview;
        ImageView audioPlayButton;
        ProgressBar processingProgress;

        public DocumentViewHolder(View itemView) {
            super(itemView);
            fileName = itemView.findViewById(R.id.file_name);
            filePreview = itemView.findViewById(R.id.file_preview);
            audioPlayButton = itemView.findViewById(R.id.audio_play_button);
            processingProgress = itemView.findViewById(R.id.processing_progress);
        }
    }

    static class CollectionViewHolder extends RecyclerView.ViewHolder {
        TextView collectionName;
        ImageView prev1, prev2, prev3, prev4;

        public CollectionViewHolder(View itemView) {
            super(itemView);
            collectionName = itemView.findViewById(R.id.collection_name);
            prev1 = itemView.findViewById(R.id.prev1);
            prev2 = itemView.findViewById(R.id.prev2);
            prev3 = itemView.findViewById(R.id.prev3);
            prev4 = itemView.findViewById(R.id.prev4);
        }
    }

    private boolean isImage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif");
    }

    private boolean isVideo(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov");
    }
}
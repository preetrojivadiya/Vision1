package com.example.vision1;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private List<StoredDocument> documentList; // Changed from List<File>
    private Context context;
    private OnItemClickListener listener; // Interface for click events

    // Interface to handle clicks back to the Activity/Fragment
    public interface OnItemClickListener {
        void onFileClick(StoredDocument document);
        void onAudioPlayClick(StoredDocument document);
    }

    /**
     * Constructor for the FileAdapter.
     *
     * @param context      The context.
     * @param documentList The list of StoredDocument objects to display.
     * @param listener     The click listener for handling item clicks.
     */
    public FileAdapter(Context context, List<StoredDocument> documentList, OnItemClickListener listener) {
        this.context = context;
        this.documentList = documentList;
        this.listener = listener;
    }

    @Override
    public FileViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        // Inflate the item layout (using the potentially modified item_file.xml)
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(FileViewHolder holder, int position) {
        StoredDocument document = documentList.get(position);
        File originalFile = new File(document.getOriginalFilePath()); // Get the original file details

        // Set the file name
        holder.textView.setText(originalFile.getName());

        // Set the file preview icon based on the original file's type
        if (isImage(originalFile)) {
            holder.imageView.setImageBitmap(BitmapFactory.decodeFile(originalFile.getAbsolutePath()));
        } else if (isVideo(originalFile)) {
            holder.imageView.setImageBitmap(ThumbnailUtils.createVideoThumbnail(originalFile.getAbsolutePath(), MediaStore.Images.Thumbnails.MINI_KIND));
        } else if (isPdf(originalFile)) {
            // Assuming you have ic_pdf in your drawable resources
            holder.imageView.setImageResource(R.drawable.ic_pdf);
        } else {
            // Assuming you have ic_file as a default icon
            holder.imageView.setImageResource(R.drawable.ic_file);
        }

        // --- Handle Visibility of Audio Button and Progress ---
        if (document.isProcessing()) {
            // If processing, show progress, hide play button
            holder.audioPlayButton.setVisibility(View.GONE);
            holder.processingProgress.setVisibility(View.VISIBLE);
        } else {
            // If not processing, hide progress
            holder.processingProgress.setVisibility(View.GONE);

            // Check if an audio file path exists and the file actually exists
            if (document.getAudioFilePath() != null && new File(document.getAudioFilePath()).exists()) {
                // If audio exists, show the play button
                holder.audioPlayButton.setVisibility(View.VISIBLE);
            } else {
                // If no audio file or file doesn't exist, hide the play button
                holder.audioPlayButton.setVisibility(View.GONE);
            }
        }
        // --- End Handle Visibility ---


        // --- Set Click Listeners ---
        // Click listener for the original file info area (icon + name container)
        // This should trigger opening the document
        holder.fileInfoContainer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFileClick(document); // Call the interface method
            }
        });

        // Click listener for the audio play button
        // This should trigger playing the audio file
        holder.audioPlayButton.setOnClickListener(v -> {
            if (listener != null && document.getAudioFilePath() != null) {
                listener.onAudioPlayClick(document); // Call the interface method
            }
        });
        // --- End Set Click Listeners ---
    }

    @Override
    public int getItemCount() {
        return documentList.size();
    }

    /**
     * ViewHolder class to hold references to the views in the item layout.
     */
    public static class FileViewHolder extends RecyclerView.ViewHolder {
        TextView textView; // file_name
        ImageView imageView; // file_preview
        View fileInfoContainer; // The container view for icon and name (e.g., LinearLayout in item_file.xml)
        ImageView audioPlayButton; // The play icon/button for audio
        ProgressBar processingProgress; // Optional progress indicator

        public FileViewHolder(View itemView) {
            super(itemView);
            // Initialize views by finding them by their IDs from the item_file.xml layout
            fileInfoContainer = itemView.findViewById(R.id.file_info_container); // Get the container view
            textView = itemView.findViewById(R.id.file_name);
            imageView = itemView.findViewById(R.id.file_preview);
            audioPlayButton = itemView.findViewById(R.id.audio_play_button);
            processingProgress = itemView.findViewById(R.id.processing_progress);
        }
    }

    /**
     * Updates the list of documents displayed by the adapter.
     *
     * @param newList The new list of StoredDocument objects.
     */
    public void updateDocumentList(List<StoredDocument> newList) { // Changed method name and parameter type
        documentList = newList;
        notifyDataSetChanged(); // Notify RecyclerView to refresh
    }

    // --- Helper methods to determine file type (can remain similar) ---
    private boolean isImage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif");
    }

    private boolean isVideo(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov");
    }

    private boolean isPdf(File file) {
        return file.getName().toLowerCase().endsWith(".pdf");
    }
    // --- End Helper methods ---
}
package com.example.vision1;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {

    // Change the list type to SavedItem
    private final List<SavedItem> savedItems;
    private final Context context;

    // Define a click listener interface
    public interface OnItemClickListener {
        void onImageClick(SavedItem item);
        void onAudioClick(SavedItem item);
    }

    private OnItemClickListener listener; // Listener instance

    // Constructor updated to accept List<SavedItem> and the listener
    public ImageAdapter(Context context, List<SavedItem> savedItems, OnItemClickListener listener) {
        this.context = context;
        this.savedItems = savedItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Inflate the item layout (should be item_image.xml now)
        View view = LayoutInflater.from(context).inflate(R.layout.item_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        SavedItem item = savedItems.get(position);

        // Bind Image
        holder.imageView.setImageURI(item.getImageUri());
        holder.imageView.setContentDescription("Image " + (position + 1)); // Add content description for accessibility

        // Bind Audio Button
        if (item.getAudioFile() != null && item.getAudioFile().exists()) {
            holder.playAudioButton.setVisibility(View.VISIBLE);
            holder.playAudioButton.setEnabled(true); // Ensure button is clickable
            holder.playAudioButton.setContentDescription("Play audio for image " + (position + 1)); // Add content description
        } else {
            holder.playAudioButton.setVisibility(View.GONE);
            holder.playAudioButton.setEnabled(false); // Make it non-clickable
        }

        // Set Click Listeners
        // Click listener for the whole item (can be used for image click)
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onImageClick(item);
            }
        });

        // Click listener specifically for the audio play button
        holder.playAudioButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAudioClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return savedItems.size();
    }

    // Update ViewHolder to include the ImageButton
    public static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ImageButton playAudioButton; // Declare the ImageButton

        public ImageViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageView);
            playAudioButton = itemView.findViewById(R.id.playAudioButton); // Find the button
        }
    }

    // Note: MediaPlayer management is better handled in the Activity
    // Remove the releaseMediaPlayer method if you added it here previously
    // MediaPlayer currentMediaPlayer = null; // This instance should be in the Activity

    // If you had a MediaPlayer instance here, remove it.
    // Add a method to stop playback if needed from the activity
    public void stopPlayback() {
        // The logic to stop playback should be in the Activity that manages MediaPlayer
    }
}
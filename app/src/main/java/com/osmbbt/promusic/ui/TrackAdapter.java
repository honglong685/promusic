package com.osmbbt.promusic.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.osmbbt.promusic.R;
import com.osmbbt.promusic.model.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter rendering the track list. Highlights the currently-playing track and
 * reports taps via {@link OnTrackClickListener}.
 */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.TrackVH> {

    public interface OnTrackClickListener {
        void onTrackClick(int position, Track track);
    }

    public interface OnTrackDeleteListener {
        void onTrackDelete(int position, Track track);
    }

    private final List<Track> tracks = new ArrayList<>();
    private final Context context;
    private final OnTrackClickListener clickListener;
    private final OnTrackDeleteListener deleteListener;
    private int activePosition = -1;
    private boolean playing = false;

    public TrackAdapter(@NonNull Context context, @NonNull OnTrackClickListener listener) {
        this(context, listener, null);
    }

    public TrackAdapter(@NonNull Context context, @NonNull OnTrackClickListener listener,
                        @Nullable OnTrackDeleteListener deleteListener) {
        this.context = context;
        this.clickListener = listener;
        this.deleteListener = deleteListener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setTracks(@NonNull List<Track> newTracks) {
        this.tracks.clear();
        this.tracks.addAll(newTracks);
        notifyDataSetChanged();
    }

    public void setActive(int position, boolean isPlaying) {
        int old = this.activePosition;
        this.activePosition = position;
        this.playing = isPlaying;
        if (old >= 0) notifyItemChanged(old);
        if (position >= 0) notifyItemChanged(position);
    }

    public void clearActive() {
        int old = this.activePosition;
        this.activePosition = -1;
        this.playing = false;
        if (old >= 0) notifyItemChanged(old);
    }

    @NonNull
    @Override
    public TrackVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_track, parent, false);
        return new TrackVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull TrackVH h, int position) {
        Track t = tracks.get(position);
        h.title.setText(t.getTitle());
        h.subtitle.setText(t.displaySubtitle());

        long dur = t.getDuration();
        if (dur > 0) {
            h.duration.setText(formatDuration(dur));
            h.duration.setVisibility(View.VISIBLE);
        } else {
            h.duration.setVisibility(View.GONE);
        }

        boolean isActive = position == activePosition;
        int bgRes = isActive
                ? R.color.item_active
                : android.R.color.transparent;
        h.itemView.setBackgroundColor(ContextCompat.getColor(context, bgRes));

        if (isActive) {
            h.title.setTextColor(ContextCompat.getColor(context, R.color.accent));
            if (playing) {
                h.title.setText("▶ " + t.getTitle());
            }
        } else {
            h.title.setTextColor(ContextCompat.getColor(context, R.color.on_surface));
        }
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    private String formatDuration(long ms) {
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", m, s);
    }

    class TrackVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView duration;
        final ImageButton btnRemove;

        TrackVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.trackTitle);
            subtitle = itemView.findViewById(R.id.trackSubtitle);
            duration = itemView.findViewById(R.id.trackDuration);
            btnRemove = itemView.findViewById(R.id.btnRemove);
            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    clickListener.onTrackClick(pos, tracks.get(pos));
                }
            });
            if (btnRemove != null) {
                btnRemove.setOnClickListener(v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && deleteListener != null) {
                        deleteListener.onTrackDelete(pos, tracks.get(pos));
                    }
                });
            }
        }
    }
}

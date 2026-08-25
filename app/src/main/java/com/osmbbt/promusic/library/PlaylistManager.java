package com.osmbbt.promusic.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.osmbbt.promusic.model.Playlist;
import com.osmbbt.promusic.model.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the currently active playlist and playback position for the app.
 * Acts as the single source of truth shared between the UI and the service.
 */
public final class PlaylistManager {

    /** Repeat / loop behaviour for auto-advance on track completion. */
    public enum RepeatMode { OFF, ALL, ONE }

    public interface Listener {
        void onPlaylistChanged(Playlist playlist);
        void onCurrentChanged(int index, Track track);
        /** Fired when shuffle or repeat mode changes so the UI can refresh icons. */
        void onPlaybackModeChanged(boolean shuffle, RepeatMode repeatMode);
    }

    private static volatile PlaylistManager INSTANCE;

    @NonNull
    public static PlaylistManager get() {
        PlaylistManager local = INSTANCE;
        if (local == null) {
            synchronized (PlaylistManager.class) {
                local = INSTANCE;
                if (local == null) {
                    INSTANCE = local = new PlaylistManager();
                }
            }
        }
        return local;
    }

    private PlaylistManager() { }

    private Playlist playlist;
    private int currentIndex = -1;
    private final List<Listener> listeners = new ArrayList<>();

    private boolean shuffle = false;
    private RepeatMode repeatMode = RepeatMode.ALL;

    public void setPlaylist(@Nullable Playlist playlist) {
        this.playlist = playlist;
        this.currentIndex = playlist != null && !playlist.isEmpty() ? 0 : -1;
        notifyPlaylistChanged();
        notifyCurrentChanged();
    }

    @Nullable
    public Playlist getPlaylist() { return playlist; }

    public int getCurrentIndex() { return currentIndex; }

    public void setCurrentIndex(int index) {
        if (index == currentIndex) return;
        if (playlist == null || index < 0 || index >= playlist.size()) return;
        currentIndex = index;
        notifyCurrentChanged();
    }

    @Nullable
    public Track getCurrentTrack() {
        if (playlist == null || currentIndex < 0 || currentIndex >= playlist.size()) return null;
        return playlist.get(currentIndex);
    }

    @Nullable
    public Track getTrackAt(int index) {
        if (playlist == null || index < 0 || index >= playlist.size()) return null;
        return playlist.get(index);
    }

    public int size() { return playlist == null ? 0 : playlist.size(); }

    public boolean isShuffle() { return shuffle; }
    public void setShuffle(boolean shuffle) {
        if (this.shuffle != shuffle) {
            this.shuffle = shuffle;
            notifyPlaybackModeChanged();
        }
    }

    public RepeatMode getRepeatMode() { return repeatMode; }
    public void setRepeatMode(@NonNull RepeatMode mode) {
        if (this.repeatMode != mode) {
            this.repeatMode = mode;
            notifyPlaybackModeChanged();
        }
    }

    /** Cycle: OFF → ALL → ONE → OFF. */
    public void cycleRepeatMode() {
        RepeatMode[] vals = RepeatMode.values();
        repeatMode = vals[(repeatMode.ordinal() + 1) % vals.length];
        notifyPlaybackModeChanged();
    }

    /**
     * Auto-advance on track completion. Respects repeat mode:
     * <ul>
     *   <li>ONE — returns the same track (replay)</li>
     *   <li>ALL — wraps around to the next track</li>
     *   <li>OFF — returns null when the last track finishes</li>
     * </ul>
     * Shuffle overrides to a random index.
     */
    @Nullable
    public Track next() {
        if (playlist == null || playlist.isEmpty()) return null;
        if (shuffle) {
            currentIndex = (int) (Math.random() * playlist.size());
        } else if (repeatMode == RepeatMode.ONE) {
            return getCurrentTrack(); // replay same
        } else if (repeatMode == RepeatMode.OFF) {
            if (currentIndex >= playlist.size() - 1) return null; // stop at end
            currentIndex++;
        } else { // ALL
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        notifyCurrentChanged();
        return getCurrentTrack();
    }

    /**
     * User-initiated next — always advances and wraps, regardless of repeat mode.
     * Shuffle picks a random track.
     */
    @Nullable
    public Track forceNext() {
        if (playlist == null || playlist.isEmpty()) return null;
        if (shuffle) {
            currentIndex = (int) (Math.random() * playlist.size());
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        notifyCurrentChanged();
        return getCurrentTrack();
    }

    /** Go to previous track (wrapping). Returns the new track, or null if empty. */
    @Nullable
    public Track previous() {
        if (playlist == null || playlist.isEmpty()) return null;
        if (shuffle) {
            currentIndex = (int) (Math.random() * playlist.size());
        } else {
            currentIndex = currentIndex <= 0 ? playlist.size() - 1 : currentIndex - 1;
        }
        notifyCurrentChanged();
        return getCurrentTrack();
    }

    /**
     * Remove a track from the current playlist by position (list-only; the file
     * on disk is NOT deleted). Adjusts the current index and fires listeners.
     *
     * @return true if the removal also removed the currently-active track.
     */
    public boolean removeTrack(int position) {
        if (playlist == null || position < 0 || position >= playlist.size()) return false;
        boolean wasCurrent = (position == currentIndex);
        playlist.getTracks().remove(position);

        if (playlist.isEmpty()) {
            currentIndex = -1;
        } else if (wasCurrent) {
            if (currentIndex >= playlist.size()) currentIndex = playlist.size() - 1;
        } else if (position < currentIndex) {
            currentIndex--;
        }

        notifyPlaylistChanged();
        notifyCurrentChanged();
        return wasCurrent;
    }

    /**
     * Append a single track to the current playlist. Creates a playlist if none
     * exists yet. Does not change the current index unless the list was empty.
     */
    public void addTrack(@NonNull Track track) {
        if (playlist == null) {
            playlist = new Playlist(Playlist.SOURCE_LOCAL, "当前列表");
        }
        playlist.add(track);
        if (currentIndex < 0) currentIndex = 0;
        notifyPlaylistChanged();
        notifyCurrentChanged();
    }

    /** Push the current state (playlist + current + mode) to a single listener. */
    public void notifyState(@NonNull Listener l) {
        l.onPlaylistChanged(playlist);
        l.onCurrentChanged(currentIndex, getCurrentTrack());
        l.onPlaybackModeChanged(shuffle, repeatMode);
    }

    public void addListener(@NonNull Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(@NonNull Listener l) {
        listeners.remove(l);
    }

    private void notifyPlaylistChanged() {
        for (Listener l : new ArrayList<>(listeners)) l.onPlaylistChanged(playlist);
    }

    private void notifyCurrentChanged() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onCurrentChanged(currentIndex, getCurrentTrack());
        }
    }

    private void notifyPlaybackModeChanged() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onPlaybackModeChanged(shuffle, repeatMode);
        }
    }
}

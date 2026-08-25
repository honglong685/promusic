package com.osmbbt.promusic.playback;

import com.osmbbt.promusic.model.Track;

/**
 * Callbacks delivered by {@link MusicService} to its bound UI counterpart.
 * All methods are invoked on the main thread.
 */
public interface PlaybackListener {
    /** Playback state changed (playing / paused / stopped). */
    void onStateChanged(boolean isPlaying, Track track);

    /** Duration became known / changed (milliseconds). -1 if unknown. */
    void onDurationChanged(int durationMs);

    /** Periodic progress tick while playing. */
    void onProgressChanged(int positionMs, int durationMs);

    /** Current track switched (by user or auto-advance). */
    void onTrackChanged(Track track);

    /** Playback failed for the current track. */
    void onPlaybackError(String message);
}

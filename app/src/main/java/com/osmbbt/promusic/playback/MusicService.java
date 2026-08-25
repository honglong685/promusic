package com.osmbbt.promusic.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.osmbbt.promusic.R;
import com.osmbbt.promusic.library.PlaylistManager;
import com.osmbbt.promusic.model.Track;
import com.osmbbt.promusic.ui.MainActivity;

import java.util.Locale;

/**
 * Foreground, bound service that owns the single {@link MediaPlayer} instance and
 * exposes playback control to the UI. Plays both local (content:// or file) and
 * remote (http/https) sources.
 *
 * Lifecycle:
 *  - UI calls {@link #playTrack(Track)} which starts playback; the service promotes
 *    itself to the foreground with a media notification so it survives backgrounding.
 *  - UI binds via {@link #onBind(Intent)} to receive state callbacks.
 *  - When paused and idle for a while (or on destroy) the service stops.
 */
public class MusicService extends Service {

    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "promusic_playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final int PROGRESS_INTERVAL_MS = 500;

    public static final String ACTION_TOGGLE = "com.osmbbt.promusic.action.TOGGLE";

    private final IBinder binder = new MusicBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private boolean prepared = false;
    private boolean playing = false;     // user intent: should be playing
    private Track currentTrack;

    private PlaybackListener listener;

    private final Runnable progressTask = new Runnable() {
        @Override public void run() {
            if (player != null && playing && prepared) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    if (listener != null) listener.onProgressChanged(pos, dur);
                } catch (IllegalStateException ignored) { }
            }
            main.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    // ---- binding ---------------------------------------------------------------

    public class MusicBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensurePlayer();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            if (playing) pause(); else resumePlayback();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(progressTask);
        releasePlayer();
        super.onDestroy();
    }

    // ---- public API (called by bound UI) --------------------------------------

    public void setListener(@Nullable PlaybackListener l) { this.listener = l; }

    public boolean isPlaying() { return playing; }

    @Nullable
    public Track getCurrentTrack() { return currentTrack; }

    public int getCurrentPosition() {
        if (player == null || !prepared) return 0;
        try { return player.getCurrentPosition(); } catch (IllegalStateException e) { return 0; }
    }

    public int getDuration() {
        if (player == null || !prepared) return -1;
        try { return player.getDuration(); } catch (IllegalStateException e) { return -1; }
    }

    /** Begin playing a specific track. Resets the player and prepares the new source. */
    public void playTrack(@Nullable Track track) {
        if (track == null) return;
        currentTrack = track;
        startForegroundPlayback(track);
        playInternal(track);
    }

    /** Toggle play/pause. */
    public void togglePlay() {
        if (playing) pause(); else resumePlayback();
    }

    public void pause() {
        if (player == null || !prepared) return;
        try { player.pause(); } catch (IllegalStateException ignored) { }
        playing = false;
        updateNotification();
        notifyState();
    }

    public void resumePlayback() {
        if (player == null || !prepared || currentTrack == null) return;
        try {
            player.start();
            playing = true;
            updateNotification();
            notifyState();
        } catch (IllegalStateException ignored) { }
    }

    public void seekTo(int msec) {
        if (player == null || !prepared) return;
        try { player.seekTo(msec); } catch (IllegalStateException ignored) { }
    }

    /**
     * Stop and reset playback, clearing the current track. Used when the active
     * track is removed from the playlist (the file itself is not deleted).
     */
    public void stopPlayback() {
        if (player != null) {
            try {
                if (playing) player.stop();
                player.reset();
            } catch (IllegalStateException ignored) { }
        }
        prepared = false;
        playing = false;
        currentTrack = null;
        updateNotification();
        notifyState();
        notifyTrackChanged();
    }

    // ---- internals ------------------------------------------------------------

    private void ensurePlayer() {
        if (player != null) return;
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnPreparedListener(mp -> {
            prepared = true;
            mp.start();
            playing = true;
            int dur = mp.getDuration();
            if (listener != null) listener.onDurationChanged(dur);
            notifyState();
            notifyTrackChanged();
        });
        player.setOnCompletionListener(mp -> {
            // Auto-advance to the next track in the active playlist (loops around).
            main.post(() -> {
                Track next = PlaylistManager.get().next();
                if (next != null) {
                    playTrack(next);
                } else {
                    playing = false;
                    notifyState();
                }
            });
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
            String msg = "无法播放此音频";
            if (listener != null) listener.onPlaybackError(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            playing = false;
            prepared = false;
            notifyState();
            return true; // error consumed
        });
    }

    private int getDurationSafe() {
        if (player == null || !prepared) return 0;
        try { return player.getDuration(); } catch (IllegalStateException e) { return 0; }
    }

    private void playInternal(@NonNull Track track) {
        ensurePlayer();
        try {
            player.reset();
            prepared = false;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, track.getUri());
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "setDataSource failed for " + track.getSource(), e);
            if (listener != null) listener.onPlaybackError("无法加载：" + e.getMessage());
            playing = false;
            notifyState();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                if (playing) player.stop();
                player.reset();
                player.release();
            } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
        playing = false;
    }

    private void notifyState() {
        if (listener != null) listener.onStateChanged(playing, currentTrack);
    }

    private void notifyTrackChanged() {
        if (listener != null) listener.onTrackChanged(currentTrack);
    }

    // ---- foreground + notification -------------------------------------------

    private void startForegroundPlayback(@NonNull Track track) {
        Notification n = buildNotification(track, playing);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (!main.hasCallbacks(progressTask)) main.post(progressTask);
    }

    private void updateNotification() {
        try {
            Notification n = buildNotification(currentTrack, playing);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        } catch (Exception ignored) { }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("正在播放的音乐控制");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(@Nullable Track track, boolean isPlaying) {
        String title = track != null && !TextUtils.isEmpty(track.getTitle())
                ? track.getTitle() : "ProMusic";
        String text = track != null ? track.displaySubtitle() : "";

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        PendingIntent togglePi = PendingIntent.getService(this, 1,
                new Intent(this, MusicService.class).setAction(ACTION_TOGGLE), piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(isPlaying)
                .setContentIntent(contentPi)
                .setShowWhen(false)
                .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play,
                        isPlaying ? "暂停" : "播放", togglePi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ---- util -----------------------------------------------------------------

    public static String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        int m = total / 60;
        int s = total % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }
}

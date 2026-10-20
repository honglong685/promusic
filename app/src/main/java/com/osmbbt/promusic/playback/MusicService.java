package com.osmbbt.promusic.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
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

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

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
    public static final String ACTION_STOP = "com.osmbbt.promusic.action.STOP";
    public static final String ACTION_NEXT = "com.osmbbt.promusic.action.NEXT";

    private final IBinder binder = new MusicBinder();
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Pauses playback when the active audio output device is lost — e.g. the
     * connected Bluetooth headset/speaker disconnects, BT is switched off, or the
     * system reroutes audio back to the speaker ("becoming noisy"). Avoids the
     * music suddenly blasting out of the phone speaker. Only acts while playing.
     */
    private final BroadcastReceiver audioDeviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (action == null) return;

            boolean lost = false;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                lost = (state == BluetoothAdapter.STATE_OFF);
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                lost = (state == BluetoothProfile.STATE_DISCONNECTED);
            } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
                lost = true;
            }

            if (lost) interruptForAudioDeviceLoss();
        }
    };

    private void interruptForAudioDeviceLoss() {
        if (playing) {
            pause();
            Toast.makeText(this, R.string.audio_device_disconnected_paused, Toast.LENGTH_SHORT).show();
        }
    }

    private MediaPlayer player;
    private boolean prepared = false;
    private boolean playing = false;     // user intent: should be playing
    private Track currentTrack;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private AudioManager.OnAudioFocusChangeListener focusChangeListener;

    @SuppressWarnings("deprecation")
    private MediaSession mediaSession;

    /** Routes system media controls (status-bar / lock-screen / headset buttons) to playback. */
    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override
        public void onPlay() { resumePlayback(); }

        @Override
        public void onPause() { pause(); }

        @Override
        public void onStop() { stopPlayback(); }

        @Override
        public void onSkipToNext() { skipToNext(); }
    };

    private PlaybackListener listener;

    private final Runnable progressTask = new Runnable() {
        @Override public void run() {
            // Self-terminate when not actively playing so we don't keep waking the
            // CPU / draining the battery once paused or stopped. resumePlayback()
            // and the prepared-callback re-arm the loop.
            if (player == null || !playing || !prepared) return;
            try {
                int pos = player.getCurrentPosition();
                int dur = player.getDuration();
                if (listener != null) listener.onProgressChanged(pos, dur);
                // Keep the system media card (status bar / lock screen) showing progress.
                updatePlaybackState();
            } catch (IllegalStateException ignored) { }
            main.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    /** Ensure the periodic progress loop is running (no-op if already scheduled). */
    private void ensureProgressLoop() {
        if (!main.hasCallbacks(progressTask)) main.post(progressTask);
    }

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
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        initMediaSession();
        registerAudioDeviceReceiver();
    }

    /** Create the media session that powers the system status-bar / lock-screen controls. */
    @SuppressWarnings("deprecation")
    private void initMediaSession() {
        mediaSession = new MediaSession(this, "ProMusic");
        mediaSession.setCallback(sessionCallback);
        updatePlaybackState();
    }

    /** Watch for Bluetooth / audio-output loss so we can pause instead of blasting the speaker. */
    private void registerAudioDeviceReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(audioDeviceReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_TOGGLE.equals(a)) {
                if (playing) pause(); else resumePlayback();
            } else if (ACTION_STOP.equals(a)) {
                stopPlayback();
            } else if (ACTION_NEXT.equals(a)) {
                skipToNext();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(progressTask);
        try { unregisterReceiver(audioDeviceReceiver); } catch (IllegalArgumentException ignored) { }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        releasePlayer();
        // Guarantee the media notification is removed on every teardown path
        // (including when the host process is killed), so it can't linger in
        // the status bar after the app is closed.
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    /**
     * Called when the user swipes the app out of the recent-tasks list. If we are
     * not actively playing, tear the service down so the media notification does
     * not linger in the status bar. While playing we keep the foreground service
     * alive for background playback (as intended).
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!playing) stopSelf();
        super.onTaskRemoved(rootIntent);
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

    /** Skip to the next track (wraps around), advancing the playlist index. */
    public void skipToNext() {
        Track next = PlaylistManager.get().forceNext();
        if (next != null) playTrack(next);
    }

    public void pause() {
        if (player == null || !prepared) return;
        try { player.pause(); } catch (IllegalStateException ignored) { }
        playing = false;
        abandonAudioFocus();
        updatePlaybackState();
        updateNotification();
        notifyState();
    }

    public void resumePlayback() {
        if (player == null || !prepared || currentTrack == null) return;
        try {
            requestAudioFocus();
            player.start();
            playing = true;
            ensureProgressLoop();
            updatePlaybackState();
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
        abandonAudioFocus();
        if (mediaSession != null) {
            updatePlaybackState();
            mediaSession.setActive(false);
        }
        updateNotification();
        notifyState();
        notifyTrackChanged();
        // A stopped player should no longer pin the foreground notification.
        // Demote from foreground (removes the notification) and let the service
        // exit once nothing else is bound to it.
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
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
            ensureProgressLoop();
            int dur = mp.getDuration();
            if (listener != null) listener.onDurationChanged(dur);
            updatePlaybackState();
            updateMetadata();
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
                    // End of playlist (repeat OFF at the last track): stop cleanly so the
                    // notification and status-bar controls reflect the idle state.
                    stopPlayback();
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
            abandonAudioFocus();
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
            requestAudioFocus();
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "setDataSource failed for " + track.getSource(), e);
            if (listener != null) listener.onPlaybackError("无法加载：" + e.getMessage());
            playing = false;
            notifyState();
        }
    }

    private void releasePlayer() {
        abandonAudioFocus();
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

    /**
     * Ask the system for exclusive media audio focus. While we hold it, the
     * {@link #focusChangeListener} is notified if another app (another music or
     * video player, a call, etc.) takes the focus away — in which case we pause.
     */
    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (audioFocusRequest == null) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusChangeListener = focusChange -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                        || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    pauseForFocusLoss();
                }
            };
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
        }
        audioManager.requestAudioFocus(audioFocusRequest);
    }

    private void pauseForFocusLoss() {
        if (playing) {
            pause();
            Toast.makeText(this, R.string.audio_focus_lost_paused, Toast.LENGTH_SHORT).show();
        } else {
            abandonAudioFocus();
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null || audioFocusRequest == null) return;
        try {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } catch (Exception ignored) { }
    }

    private void notifyState() {
        if (listener != null) listener.onStateChanged(playing, currentTrack);
    }

    private void notifyTrackChanged() {
        updateMetadata();
        if (listener != null) listener.onTrackChanged(currentTrack);
    }

    // ---- media session (status-bar / lock-screen controls) ---------------------

    /** Mirror the current playback state into the media session so the system UI can show transport controls. */
    private void updatePlaybackState() {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT;
        int state;
        if (currentTrack == null) state = PlaybackState.STATE_STOPPED;
        else if (playing) state = PlaybackState.STATE_PLAYING;
        else state = PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, getCurrentPosition(), 1f)
                .build());
    }

    /** Push the current track's title/artist into the media session metadata (shown on the status-bar card). */
    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        if (currentTrack != null) {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, currentTrack.getTitle());
            b.putString(MediaMetadata.METADATA_KEY_ARTIST, currentTrack.getArtist());
            int dur = getDurationSafe();
            if (dur > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        } else {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, "ProMusic");
        }
        mediaSession.setMetadata(b.build());
    }

    // ---- foreground + notification -------------------------------------------

    private void startForegroundPlayback(@NonNull Track track) {
        if (mediaSession != null) mediaSession.setActive(true);
        Notification n = buildNotification(track, playing);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
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

    @SuppressWarnings("deprecation")
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
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, MusicService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent nextPi = PendingIntent.getService(this, 3,
                new Intent(this, MusicService.class).setAction(ACTION_NEXT), piFlags);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(isPlaying)
                .setContentIntent(contentPi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                // Transport actions shown in the notification shade:
                // [0]=play/pause  [1]=stop  [2]=next
                .addAction(new Notification.Action(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play,
                        isPlaying ? "暂停" : "播放", togglePi))
                .addAction(new Notification.Action(R.drawable.ic_stop, "停止", stopPi))
                .addAction(new Notification.Action(R.drawable.ic_next, "下一首", nextPi));

        // MediaStyle wires the session into the system status-bar / lock-screen controls.
        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2);
        builder.setStyle(style);

        return builder.build();
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

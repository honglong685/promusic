package com.osmbbt.promusic.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.osmbbt.promusic.R;
import com.osmbbt.promusic.library.FolderScanner;
import com.osmbbt.promusic.library.LocalMediaScanner;
import com.osmbbt.promusic.library.NetworkPlaylistLoader;
import com.osmbbt.promusic.library.PlaylistManager;
import com.osmbbt.promusic.library.PlaylistStorage;
import com.osmbbt.promusic.model.Playlist;
import com.osmbbt.promusic.model.Track;
import com.osmbbt.promusic.playback.MusicService;
import com.osmbbt.promusic.playback.PlaybackListener;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main screen: a track list (local or network) plus a bottom playback bar bound to
 * {@link MusicService}. Pull-to-rescan local music and a toolbar action to load a
 * network playlist by URL.
 */
public class MainActivity extends AppCompatActivity
        implements TrackAdapter.OnTrackClickListener, TrackAdapter.OnTrackDeleteListener,
        PlaylistManager.Listener {

    private MaterialToolbar toolbar;
    private TextView listTitle;
    private RecyclerView recyclerView;
    private View emptyState;
    private TextView nowTitle;
    private TextView nowTime;
    private SeekBar seekBar;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private ImageButton btnRepeat;
    private ImageButton btnShuffle;
    private FloatingActionButton btnPlay;

    private TrackAdapter adapter;
    private final PlaylistManager pm = PlaylistManager.get();

    private MusicService musicService;
    private boolean bound = false;
    private boolean userSeeking = false;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PlaylistStorage storage;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder b = (MusicService.MusicBinder) service;
            musicService = b.getService();
            bound = true;
            musicService.setListener(playbackListener);
            syncPlaybackUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            musicService = null;
        }
    };

    private final PlaybackListener playbackListener = new PlaybackListener() {
        @Override public void onStateChanged(boolean isPlaying, Track track) {
            runOnUiThread(() -> {
                btnPlay.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                adapter.setActive(pm.getCurrentIndex(), isPlaying);
            });
        }

        @Override public void onDurationChanged(int durationMs) {
            runOnUiThread(() -> seekBar.setMax(durationMs > 0 ? durationMs : 0));
        }

        @Override public void onProgressChanged(int positionMs, int durationMs) {
            runOnUiThread(() -> {
                if (!userSeeking) seekBar.setProgress(positionMs);
                nowTime.setText(MusicService.formatTime(positionMs)
                        + " / " + MusicService.formatTime(durationMs));
            });
        }

        @Override public void onTrackChanged(Track track) {
            runOnUiThread(() -> {
                if (track != null) {
                    nowTitle.setText(track.getTitle());
                } else {
                    nowTitle.setText("未在播放");
                }
                adapter.setActive(pm.getCurrentIndex(), musicService != null && musicService.isPlaying());
            });
        }

        @Override public void onPlaybackError(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    };

    // permission launcher
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) scanLocal();
                else Toast.makeText(this, R.string.perm_audio_denied, Toast.LENGTH_LONG).show();
            });

    // system folder picker (SAF) launcher
    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return; // user cancelled
                onFolderPicked(uri);
            });

    // SAF single/multi-file picker — lets the user add individual audio files
    // to the current list without scanning a whole folder.
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris == null || uris.isEmpty()) return;
                        onFilesPicked(uris);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new PlaylistStorage(this);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        listTitle = findViewById(R.id.listTitle);
        recyclerView = findViewById(R.id.recyclerView);
        emptyState = findViewById(R.id.emptyState);
        nowTitle = findViewById(R.id.nowTitle);
        nowTime = findViewById(R.id.nowTime);
        seekBar = findViewById(R.id.progressBar);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnPlay = findViewById(R.id.btnPlay);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnShuffle = findViewById(R.id.btnShuffle);

        adapter = new TrackAdapter(this, this, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnPlay.setImageResource(R.drawable.ic_play);
        btnPlay.setOnClickListener(v -> {
            if (bound && musicService != null) musicService.togglePlay();
            else maybePlayCurrent();
        });
        btnPrev.setOnClickListener(v -> skip(false));
        btnNext.setOnClickListener(v -> skip(true));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && bound && musicService != null) {
                    nowTime.setText(MusicService.formatTime(p)
                            + " / " + MusicService.formatTime(musicService.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (bound && musicService != null) musicService.seekTo(sb.getProgress());
            }
        });

        btnRepeat.setOnClickListener(v -> {
            pm.cycleRepeatMode();
            int msgRes;
            switch (pm.getRepeatMode()) {
                case ONE:  msgRes = R.string.repeat_one;  break;
                case ALL:  msgRes = R.string.repeat_all;  break;
                default:   msgRes = R.string.repeat_off;  break;
            }
            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
        });
        btnShuffle.setOnClickListener(v -> {
            pm.setShuffle(!pm.isShuffle());
            Toast.makeText(this,
                    pm.isShuffle() ? R.string.shuffle_on : R.string.shuffle_off,
                    Toast.LENGTH_SHORT).show();
        });

        pm.addListener(this);

        // Restore the last source: a previously picked folder (if the grant
        // survived) takes priority; otherwise fall back to the full local scan.
        if (pm.getPlaylist() == null) {
            Uri saved = getSavedFolderUri();
            if (saved != null && hasFolderPermission(saved)) {
                scanFolder(saved, getSavedFolderRecursive());
            } else {
                if (saved != null) clearSavedFolder(); // grant revoked
                requestAudioPermissionAndScan();
            }
        } else {
            // Playlist already loaded (singleton survived activity recreation):
            // push the current state to refresh the UI.
            pm.notifyState(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent i = new Intent(this, MusicService.class);
        bindService(i, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            if (musicService != null) musicService.setListener(null);
            unbindService(connection);
            bound = false;
        }
    }

    @Override
    protected void onDestroy() {
        pm.removeListener(this);
        super.onDestroy();
    }

    // ---- menu ----------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_pick_folder) {
            folderPicker.launch(null);
            return true;
        } else if (id == R.id.action_add_file) {
            filePicker.launch(new String[]{"audio/*"});
            return true;
        } else if (id == R.id.action_save_list) {
            showSaveListDialog();
            return true;
        } else if (id == R.id.action_open_list) {
            showOpenListDialog();
            return true;
        } else if (id == R.id.action_network) {
            showNetworkDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- list click ----------------------------------------------------------

    @Override
    public void onTrackClick(int position, Track track) {
        pm.setCurrentIndex(position);
        playCurrent();
    }

    private void playCurrent() {
        Track t = pm.getCurrentTrack();
        if (t == null) {
            Toast.makeText(this, "请先选择曲目", Toast.LENGTH_SHORT).show();
            return;
        }
        // Start as a *started* service (not just bound) so the foreground
        // service survives unbinding when the UI goes to the background.
        startService(new Intent(this, MusicService.class));
        if (bound && musicService != null) {
            musicService.playTrack(t);
        } else {
            // The binding from onStart will connect and syncPlaybackUi() will
            // call maybePlayCurrent() which plays the queued track.
            pendingPlay = t;
        }
    }

    private Track pendingPlay = null;

    private void maybePlayCurrent() {
        if (pendingPlay != null && bound && musicService != null) {
            Track t = pendingPlay;
            pendingPlay = null;
            musicService.playTrack(t);
        }
    }

    private void syncPlaybackUi() {
        if (musicService == null) return;
        Track t = musicService.getCurrentTrack();
        if (t != null) nowTitle.setText(t.getTitle());
        int dur = musicService.getDuration();
        seekBar.setMax(dur > 0 ? dur : 0);
        if (!userSeeking) seekBar.setProgress(musicService.getCurrentPosition());
        btnPlay.setImageResource(musicService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        adapter.setActive(pm.getCurrentIndex(), musicService.isPlaying());

        // Also refresh the list + mode icons in case the activity was recreated.
        Playlist pl = pm.getPlaylist();
        if (pl != null && !pl.isEmpty()) {
            adapter.setTracks(pl.getTracks());
        }
        updateModeIcons(pm.isShuffle(), pm.getRepeatMode());

        maybePlayCurrent();
    }

    private void skip(boolean forward) {
        Track t = forward ? pm.forceNext() : pm.previous();
        if (t == null) {
            Toast.makeText(this, "歌单为空", Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, MusicService.class));
        if (bound && musicService != null) musicService.playTrack(t);
    }

    // ---- PlaylistManager.Listener -------------------------------------------

    @Override
    public void onPlaylistChanged(Playlist playlist) {
        runOnUiThread(() -> {
            listTitle.setText(playlist != null ? playlist.getName() : getString(R.string.list_title_default));
            if (playlist == null || playlist.isEmpty()) {
                adapter.setTracks(java.util.Collections.emptyList());
                emptyState.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                emptyState.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                adapter.setTracks(playlist.getTracks());
            }
        });
    }

    @Override
    public void onCurrentChanged(int index, Track track) {
        runOnUiThread(() -> {
            if (track != null) nowTitle.setText(track.getTitle());
            adapter.setActive(index, musicService != null && musicService.isPlaying());
        });
    }

    @Override
    public void onPlaybackModeChanged(boolean shuffle, PlaylistManager.RepeatMode repeatMode) {
        runOnUiThread(() -> updateModeIcons(shuffle, repeatMode));
    }

    /** Refresh the repeat / shuffle button icons and tints to reflect state. */
    private void updateModeIcons(boolean shuffle, PlaylistManager.RepeatMode repeatMode) {
        // Shuffle
        btnShuffle.setImageState(
                shuffle ? new int[]{android.R.attr.state_activated}
                        : new int[]{-android.R.attr.state_activated},
                true);
        btnShuffle.setColorFilter(ContextCompat.getColor(this,
                shuffle ? R.color.accent : R.color.on_surface_variant));

        // Repeat
        if (repeatMode == PlaylistManager.RepeatMode.ONE) {
            btnRepeat.setImageResource(R.drawable.ic_repeat_one);
            btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        } else if (repeatMode == PlaylistManager.RepeatMode.ALL) {
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.accent));
        } else { // OFF
            btnRepeat.setImageResource(R.drawable.ic_repeat);
            btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.on_surface_variant));
        }
    }

    // ---- delete from list (file is NOT deleted) -----------------------------

    @Override
    public void onTrackDelete(int position, Track track) {
        boolean wasCurrent = pm.removeTrack(position);
        Toast.makeText(this,
                getString(R.string.removed_from_list, track.getTitle()),
                Toast.LENGTH_SHORT).show();
        if (wasCurrent && bound && musicService != null) {
            musicService.stopPlayback();
        }
    }

    // ---- add individual files to the current list ---------------------------

    private void onFilesPicked(@NonNull java.util.List<Uri> uris) {
        io.execute(() -> {
            java.util.List<Track> newTracks = new java.util.ArrayList<>();
            for (Uri uri : uris) {
                Track t = trackFromUri(uri);
                if (t != null) newTracks.add(t);
            }
            runOnUiThread(() -> {
                if (newTracks.isEmpty()) {
                    Toast.makeText(this, R.string.add_file_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                for (Track t : newTracks) pm.addTrack(t);
                Toast.makeText(this,
                        getString(R.string.added_files, newTracks.size()),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Build a {@link Track} from a SAF document URI returned by the file picker.
     * Derives title/artist from the display name ("Artist - Title.ext" split).
     */
    @Nullable
    private Track trackFromUri(@NonNull Uri uri) {
        String name = queryDisplayName(uri);
        if (name == null) name = "未知曲目";
        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        String title = base;
        String artist = null;
        int sep = base.indexOf(" - ");
        if (sep > 0 && sep < base.length() - 3) {
            artist = base.substring(0, sep).trim();
            title = base.substring(sep + 3).trim();
        }
        return Track.local(uri.toString(), title, artist, 0L, uri);
    }

    @Nullable
    private String queryDisplayName(@NonNull Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0) return c.getString(col);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ---- local scan ----------------------------------------------------------

    private void requestAudioPermissionAndScan() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            scanLocal();
        } else {
            permissionLauncher.launch(perm);
        }
    }

    private void scanLocal() {
        listTitle.setText(R.string.loading);
        io.execute(() -> {
            Playlist playlist = LocalMediaScanner.scan(getContentResolver());
            runOnUiThread(() -> {
                // Always replace the list with the fresh scan result.
                pm.setPlaylist(playlist);
                if (playlist.isEmpty()) {
                    Toast.makeText(this, R.string.scan_empty, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            getString(R.string.scan_done, playlist.size()),
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ---- folder scanning (user-selected directory) ---------------------------

    private static final String PREFS = "promusic_prefs";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_FOLDER_RECURSIVE = "folder_recursive";

    /** Called when a folder was picked in the system dialog. */
    private void onFolderPicked(@NonNull Uri treeUri) {
        // Persist the grant so the folder (and its files) stay readable across restarts.
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Some pickers do not offer a persistable grant; scanning still works for now.
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply();

        // Ask the user whether to include sub-folders before scanning.
        String[] items = {
                getString(R.string.folder_depth_sub),
                getString(R.string.folder_depth_top)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.folder_depth_title)
                .setItems(items, (d, which) -> {
                    boolean recursive = (which == 0);
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putBoolean(KEY_FOLDER_RECURSIVE, recursive).apply();
                    scanFolder(treeUri, recursive);
                })
                .setCancelable(false)
                .show();
    }

    private void scanFolder(@NonNull Uri treeUri, boolean recursive) {
        listTitle.setText(R.string.loading);
        io.execute(() -> {
            try {
                Playlist playlist = FolderScanner.scanTree(
                        getContentResolver(), treeUri, recursive);
                runOnUiThread(() -> {
                    // Always replace the list — even if empty, so the old
                    // list is cleared and only the folder's content shows.
                    pm.setPlaylist(playlist);
                    if (playlist.isEmpty()) {
                        Toast.makeText(this, R.string.scan_folder_empty, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this,
                                getString(R.string.scan_done, playlist.size()),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            getString(R.string.scan_folder_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    listTitle.setText(pm.getPlaylist() != null
                            ? pm.getPlaylist().getName() : getString(R.string.list_title_default));
                });
            }
        });
    }

    @Nullable
    private Uri getSavedFolderUri() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FOLDER_URI, null);
        if (s == null) return null;
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasFolderPermission(@NonNull Uri treeUri) {
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (treeUri.equals(p.getUri()) && p.isReadPermission()) return true;
        }
        return false;
    }

    private void clearSavedFolder() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().remove(KEY_FOLDER_URI).remove(KEY_FOLDER_RECURSIVE).apply();
    }

    /** Returns the saved sub-folder scan preference (default: true = recursive). */
    private boolean getSavedFolderRecursive() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_FOLDER_RECURSIVE, true);
    }

    // ---- save / open local playlist ------------------------------------------

    private void showSaveListDialog() {
        Playlist pl = pm.getPlaylist();
        if (pl == null || pl.isEmpty()) {
            Toast.makeText(this, R.string.save_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // Pre-fill a suggested name from the current playlist name.
        String suggested = pl.getName() != null ? pl.getName() : "";
        TextInputEditText input = new TextInputEditText(this);
        input.setHint(R.string.save_hint);
        input.setText(suggested);
        input.setSelection(suggested.length());
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(pad, pad, pad, pad);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.save_dialog_title)
                .setView(input)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.save_dialog_title, (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(name)) name = "列表 " + System.currentTimeMillis();
                    savePlaylist(pl, name);
                })
                .show();
    }

    private void savePlaylist(@NonNull Playlist playlist, @NonNull String name) {
        io.execute(() -> {
            File saved = storage.save(playlist, name);
            runOnUiThread(() -> {
                if (saved != null) {
                    Toast.makeText(this,
                            getString(R.string.save_success, name),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showOpenListDialog() {
        List<PlaylistStorage.Meta> saved = storage.list();
        if (saved.isEmpty()) {
            Toast.makeText(this, R.string.open_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[saved.size()];
        for (int i = 0; i < saved.size(); i++) {
            PlaylistStorage.Meta m = saved.get(i);
            labels[i] = m.name + "  (" + m.trackCount + " 首)";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.open_dialog_title)
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < saved.size()) {
                        loadSavedPlaylist(saved.get(which).name);
                    }
                })
                .setPositiveButton(android.R.string.cancel, null)
                .show();
    }

    private void loadSavedPlaylist(@NonNull String name) {
        io.execute(() -> {
            Playlist loaded = storage.load(name);
            runOnUiThread(() -> {
                if (loaded != null) {
                    pm.setPlaylist(loaded);
                    Toast.makeText(this,
                            getString(R.string.open_success, loaded.getName(), loaded.size()),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ---- network dialog ------------------------------------------------------

    private void showNetworkDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_network_playlist, null);
        TextInputEditText input = view.findViewById(R.id.urlInput);

        new MaterialAlertDialogBuilder(this)
                .setView(view)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_load, (d, w) -> {
                    String url = input.getText() != null ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(url)) return;
                    loadNetworkPlaylist(url);
                })
                .show();
    }

    private void loadNetworkPlaylist(String url) {
        listTitle.setText(R.string.loading);
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        NetworkPlaylistLoader.load(url, new NetworkPlaylistLoader.Callback() {
            @Override public void onSuccess(Playlist playlist) {
                pm.setPlaylist(playlist);
                Toast.makeText(MainActivity.this,
                        "已加载：" + playlist.getName() + " (" + playlist.size() + " 首)",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onFailure(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    listTitle.setText(pm.getPlaylist() != null
                            ? pm.getPlaylist().getName() : getString(R.string.list_title_default));
                });
            }
        });
    }
}

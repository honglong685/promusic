package com.osmbbt.promusic.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.osmbbt.promusic.model.Playlist;
import com.osmbbt.promusic.model.Track;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Persists and restores {@link Playlist}s as JSON files in the app's private
 * storage ({@code filesDir/playlists/}).  Each file is a self-contained JSON
 * blob that can be loaded back into a {@link Playlist} with all track URIs
 * intact (SAF document URIs rely on previously taken persistable permissions).
 */
public final class PlaylistStorage {

    private static final String DIR = "playlists";
    private static final String EXT = ".json";

    private final File rootDir;

    public PlaylistStorage(@NonNull Context context) {
        this.rootDir = new File(context.getFilesDir(), DIR);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    // ---- save ---------------------------------------------------------------

    /**
     * Persist the given playlist under {@code name}.json. Overwrites if the
     * file already exists.
     *
     * @return the saved file, or null on failure.
     */
    @Nullable
    public File save(@NonNull Playlist playlist, @NonNull String name) {
        String safeName = sanitize(name);
        JSONObject json = toJson(playlist, name);
        File file = new File(rootDir, safeName + EXT);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    // ---- load ---------------------------------------------------------------

    /**
     * Load a playlist from the file named {@code name}.json. Returns null if
     * the file doesn't exist or is corrupted.
     */
    @Nullable
    public Playlist load(@NonNull String name) {
        File file = new File(rootDir, sanitize(name) + EXT);
        if (!file.exists()) return null;
        try {
            String text = readFile(file);
            if (text == null) return null;
            return fromJson(new JSONObject(text));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    // ---- list ---------------------------------------------------------------

    /** Metadata for a saved playlist, used to populate the "open list" dialog. */
    public static class Meta {
        public final String name;
        public final int trackCount;
        public final long lastModified;

        public Meta(String name, int trackCount, long lastModified) {
            this.name = name;
            this.trackCount = trackCount;
            this.lastModified = lastModified;
        }
    }

    /**
     * Return metadata for all saved playlists, sorted by last-modified
     * (newest first).
     */
    @NonNull
    public List<Meta> list() {
        File[] files = rootDir.listFiles((dir, n) -> n.endsWith(EXT));
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<Meta> result = new ArrayList<>(files.length);
        for (File f : files) {
            String name = f.getName();
            name = name.substring(0, name.length() - EXT.length()); // strip .json
            int count = countTracks(f);
            result.add(new Meta(name, count, f.lastModified()));
        }
        return result;
    }

    // ---- delete -------------------------------------------------------------

    public boolean delete(@NonNull String name) {
        File file = new File(rootDir, sanitize(name) + EXT);
        return file.exists() && file.delete();
    }

    // ---- JSON helpers -------------------------------------------------------

    private static JSONObject toJson(@NonNull Playlist playlist, @NonNull String saveName) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("name", saveName);
            obj.put("source", playlist.getSource());
            JSONArray arr = new JSONArray();
            for (Track t : playlist.getTracks()) {
                JSONObject j = new JSONObject();
                j.put("type", t.getType());
                j.put("id", t.getId());
                j.put("title", t.getTitle());
                j.put("artist", t.getArtist() != null ? t.getArtist() : "");
                j.put("duration", t.getDuration());
                j.put("source", t.getSource() != null ? t.getSource() : "");
                if (t.getCoverUrl() != null) j.put("cover", t.getCoverUrl());
                // The resolved URI — this is what MediaPlayer will use.
                if (t.getUri() != null) j.put("uri", t.getUri().toString());
                arr.put(j);
            }
            obj.put("tracks", arr);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    @Nullable
    private static Playlist fromJson(@NonNull JSONObject obj) {
        try {
            String name = obj.optString("name", "已保存列表");
            int source = obj.optInt("source", Playlist.SOURCE_LOCAL);
            Playlist playlist = new Playlist(source, name);
            JSONArray arr = obj.getJSONArray("tracks");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                int type = j.optInt("type", Track.TYPE_LOCAL);
                String title = j.optString("title", "未知曲目");
                String artist = j.optString("artist", "");
                if (artist.isEmpty()) artist = null;
                long duration = j.optLong("duration", 0);
                String sourceStr = j.optString("source", "");
                String cover = j.optString("cover", null);
                String uriStr = j.optString("uri", sourceStr);
                Uri uri = uriStr.isEmpty() ? null : Uri.parse(uriStr);
                String id = j.optString("id", uriStr);
                Track t = new Track(type, id, title, artist, duration, sourceStr, cover, uri);
                playlist.add(t);
            }
            return playlist;
        } catch (JSONException e) {
            return null;
        }
    }

    private int countTracks(@NonNull File file) {
        try {
            String text = readFile(file);
            if (text == null) return 0;
            JSONObject obj = new JSONObject(text);
            JSONArray arr = obj.optJSONArray("tracks");
            return arr != null ? arr.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Nullable
    private static String readFile(@NonNull File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            if (read <= 0) return null;
            return new String(data, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static String sanitize(@NonNull String name) {
        // Strip characters that are illegal in file names.
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}

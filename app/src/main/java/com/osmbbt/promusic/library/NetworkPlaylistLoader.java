package com.osmbbt.promusic.library;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.osmbbt.promusic.model.Playlist;
import com.osmbbt.promusic.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches a JSON playlist from a network URL and parses it into a {@link Playlist}.
 *
 * Expected JSON shape (both fields optional but tracks recommended):
 * <pre>
 * {
 *   "name": "我的歌单",
 *   "tracks": [
 *     { "title": "Song", "artist": "Singer", "url": "https://.../song.mp3", "cover": "https://.../cover.jpg" }
 *   ]
 * }
 * </pre>
 *
 * Also tolerates a bare array of tracks at the top level:
 * <pre>[ { "title": ... }, ... ]</pre>
 */
public final class NetworkPlaylistLoader {

    private static final String TAG = "NetworkPlaylistLoader";
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 20_000;

    public interface Callback {
        void onSuccess(Playlist playlist);
        void onFailure(String message);
    }

    private NetworkPlaylistLoader() { }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Fetch + parse off the main thread. Always reports back on the main thread. */
    public static void load(@NonNull final String rawUrl, @NonNull final Callback callback) {
        IO.execute(() -> {
            try {
                Playlist playlist = fetch(rawUrl);
                if (playlist == null || playlist.isEmpty()) {
                    postFailure(callback, "歌单为空或解析失败");
                } else {
                    playlist.setSourceUrl(rawUrl);
                    postSuccess(callback, playlist);
                }
            } catch (Exception e) {
                Log.e(TAG, "load failed", e);
                postFailure(callback, "加载失败：" + e.getMessage());
            }
        });
    }

    @Nullable
    private static Playlist fetch(@NonNull String rawUrl) throws Exception {
        URL url = new URL(rawUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");

        int code = conn.getResponseCode();
        InputStream is = null;
        try {
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code);
            }
            is = conn.getInputStream();
            String body = readFully(is);
            return parse(body, rawUrl);
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) { }
            conn.disconnect();
        }
    }

    private static String readFully(@NonNull InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    @NonNull
    private static Playlist parse(@NonNull String body, @NonNull String sourceUrl) throws Exception {
        String trimmed = body.trim();
        if (TextUtils.isEmpty(trimmed)) {
            return new Playlist(Playlist.SOURCE_NETWORK, "网络歌单");
        }

        String name = "网络歌单";
        JSONArray trackArr;

        char first = trimmed.charAt(0);
        if (first == '[') {
            trackArr = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            if (root.has("name")) name = root.getString("name");
            trackArr = root.has("tracks") ? root.getJSONArray("tracks") : new JSONArray();
        }

        Playlist playlist = new Playlist(Playlist.SOURCE_NETWORK, name);
        playlist.setSourceUrl(sourceUrl);

        for (int i = 0; i < trackArr.length(); i++) {
            JSONObject t = trackArr.getJSONObject(i);
            String title = t.optString("title", "未知曲目");
            String artist = t.optString("artist", t.optString("singer", "未知艺人"));
            String trackUrl = t.optString("url", t.optString("src", ""));
            String cover = t.optString("cover", t.optString("coverUrl", t.optString("pic", null)));
            if (trackUrl.isEmpty()) continue;
            playlist.add(Track.network(title, artist, trackUrl, cover));
        }
        return playlist;
    }

    // ---- main-thread delivery -------------------------------------------------

    private static void postSuccess(@NonNull Callback cb, @NonNull Playlist p) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onSuccess(p));
    }

    private static void postFailure(@NonNull Callback cb, @NonNull String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onFailure(msg));
    }
}

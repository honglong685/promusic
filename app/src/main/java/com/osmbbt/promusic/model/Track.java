package com.osmbbt.promusic.model;

import android.net.Uri;

/**
 * Represents a single playable track, either local (on-device file) or remote (network URL).
 */
public class Track {

    public static final int TYPE_LOCAL = 0;
    public static final int TYPE_NETWORK = 1;

    private final int type;
    private final String id;          // stable id (MediaStore _id for local, url for network)
    private final String title;
    private final String artist;
    private final long duration;      // milliseconds; 0 if unknown until playback
    private final String source;      // file path / uri / url string
    private final String coverUrl;    // optional album-art url (network) or null
    private final Uri uri;            // resolved playable uri

    public Track(int type, String id, String title, String artist, long duration,
                 String source, String coverUrl, Uri uri) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.duration = duration;
        this.source = source;
        this.coverUrl = coverUrl;
        this.uri = uri;
    }

    public static Track local(String id, String title, String artist, long duration, Uri uri) {
        return new Track(TYPE_LOCAL, id, title, artist, duration,
                uri != null ? uri.toString() : null, null, uri);
    }

    public static Track network(String title, String artist, String url, String coverUrl) {
        return new Track(TYPE_NETWORK, url, title, artist, 0L, url, coverUrl,
                url != null ? Uri.parse(url) : null);
    }

    public int getType() { return type; }
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public long getDuration() { return duration; }
    public String getSource() { return source; }
    public String getCoverUrl() { return coverUrl; }
    public Uri getUri() { return uri; }

    public boolean isNetwork() { return type == TYPE_NETWORK; }

    public String displaySubtitle() {
        if (artist != null && !artist.isEmpty()) return artist;
        return isNetwork() ? "网络" : "本地";
    }
}

package com.osmbbt.promusic.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named collection of {@link Track}s. May originate from the local device scan
 * ("本地音乐") or a fetched network list (the JSON playlist name).
 */
public class Playlist {

    public static final int SOURCE_LOCAL = 0;
    public static final int SOURCE_NETWORK = 1;

    private final int source;
    private String name;
    private String sourceUrl; // for network playlists
    private final List<Track> tracks = new ArrayList<>();

    public Playlist(int source, String name) {
        this.source = source;
        this.name = name;
    }

    public int getSource() { return source; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public boolean isNetwork() { return source == SOURCE_NETWORK; }

    public List<Track> getTracks() { return tracks; }

    public void add(Track track) { tracks.add(track); }

    public int size() { return tracks.size(); }

    public boolean isEmpty() { return tracks.isEmpty(); }

    public Track get(int index) {
        if (index < 0 || index >= tracks.size()) return null;
        return tracks.get(index);
    }

    public List<Track> unmodifiable() { return Collections.unmodifiableList(tracks); }
}

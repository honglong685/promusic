package com.osmbbt.promusic.library;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.osmbbt.promusic.model.Playlist;
import com.osmbbt.promusic.model.Track;

/**
 * Scans the device's {@link MediaStore} for music files and builds a local {@link Playlist}.
 *
 * On Android 13+ (API 33) reading audio media requires the runtime permission
 * READ_MEDIA_AUDIO; the caller is expected to have it granted before invoking
 * {@link #scan(ContentResolver)}.
 */
public final class LocalMediaScanner {

    private LocalMediaScanner() { }

    /**
     * Query all audio entries the system has indexed. Returns a playlist named
     * "本地音乐" (Local Music) whose tracks point to content:// uris.
     */
    @NonNull
    public static Playlist scan(@NonNull ContentResolver resolver) {
        Playlist playlist = new Playlist(Playlist.SOURCE_LOCAL, "本地音乐");

        Uri collection;
        // API 29+ exposes audio via the "audio" collection directly.
        collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);

        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        Cursor cursor = null;
        try {
            cursor = resolver.query(collection, projection, selection, null, sortOrder);
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String title = cursor.getString(titleCol);
                    String artist = cursor.getString(artistCol);
                    long duration = cursor.getLong(durCol);
                    Uri contentUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

                    String fallbackPath = dataCol >= 0 ? cursor.getString(dataCol) : null;
                    // Prefer the content uri for playback; keep raw path only as a hint.
                    Track track = Track.local(String.valueOf(id),
                            title != null ? title : "未知曲目",
                            artist != null ? artist : "未知艺人",
                            duration,
                            contentUri);
                    playlist.add(track);
                }
            }
        } catch (SecurityException e) {
            // Permission not granted — return whatever (empty) we have.
        } finally {
            if (cursor != null) cursor.close();
        }

        return playlist;
    }
}

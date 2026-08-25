package com.osmbbt.promusic.library;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;

import com.osmbbt.promusic.model.Playlist;
import com.osmbbt.promusic.model.Track;

import java.util.Comparator;
import java.util.Locale;

/**
 * Scans a user-selected folder (obtained via the system folder picker,
 * ACTION_OPEN_DOCUMENT_TREE) for audio files and builds a local {@link Playlist}.
 *
 * The caller must hold a persisted read grant on the tree URI for the resulting
 * document URIs to remain playable (see
 * {@link android.content.ContentResolver#takePersistableUriPermission}).
 */
public final class FolderScanner {

    private static final int MAX_FILES = 2000;
    private static final int MAX_DEPTH = 12;

    /** Common audio file extensions used when the provider mime type is generic. */
    private static final String[] AUDIO_EXTS = {
            ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".oga",
            ".opus", ".wma", ".amr", ".mid", ".midi", ".ape", ".mka"
    };

    private FolderScanner() { }

    /**
     * Collect playable audio files under the picked tree.
     *
     * @param recursive {@code true} to walk into sub-folders, {@code false} to
     *                  scan only the top-level directory.
     */
    @NonNull
    public static Playlist scanTree(@NonNull ContentResolver resolver,
                                    @NonNull Uri treeUri, boolean recursive)
            throws Exception {
        String folderName = folderDisplayName(treeUri);
        Playlist playlist = new Playlist(Playlist.SOURCE_LOCAL, folderName);
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        walk(resolver, treeUri, rootId, playlist, 0, recursive);
        playlist.getTracks().sort(Comparator.comparing(Track::getTitle,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return playlist;
    }

    /** Recursively collect playable audio files under the picked tree (kept for backward compat). */
    @NonNull
    public static Playlist scanTree(@NonNull ContentResolver resolver, @NonNull Uri treeUri)
            throws Exception {
        return scanTree(resolver, treeUri, true);
    }

    private static void walk(@NonNull ContentResolver resolver, @NonNull Uri treeUri,
                             @NonNull String docId, @NonNull Playlist playlist,
                             int depth, boolean recursive)
            throws Exception {
        if (depth > MAX_DEPTH || playlist.size() >= MAX_FILES) return;

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Cursor c = null;
        try {
            c = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c == null) return;

            int idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (c.moveToNext() && playlist.size() < MAX_FILES) {
                String id = c.getString(idCol);
                String name = c.getString(nameCol);
                String mime = c.getString(mimeCol);
                if (id == null || name == null) continue;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    // Only recurse into sub-folders when the user opted in.
                    if (recursive) {
                        walk(resolver, treeUri, id, playlist, depth + 1, recursive);
                    }
                } else if (isAudio(mime, name)) {
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    playlist.add(buildTrack(id, name, fileUri));
                }
            }
        } finally {
            if (c != null) c.close();
        }
    }

    /** Build a Track from a document file name: "Artist - Title.ext" is split when possible. */
    @NonNull
    private static Track buildTrack(@NonNull String docId, @NonNull String fileName, @NonNull Uri fileUri) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        String title = base;
        String artist = null;
        int sep = base.indexOf(" - ");
        if (sep > 0 && sep < base.length() - 3) {
            artist = base.substring(0, sep).trim();
            title = base.substring(sep + 3).trim();
        }
        return Track.local(docId, title, artist, 0L, fileUri);
    }

    private static boolean isAudio(String mime, String name) {
        if (mime != null && mime.startsWith("audio/")) return true;
        // Generic/unknown mime: fall back to extension matching.
        if (mime != null && !mime.isEmpty()
                && !"application/octet-stream".equals(mime)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** Derive a human-readable folder name from the tree URI's document id. */
    @NonNull
    public static String folderDisplayName(@NonNull Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            String name = docId.substring(docId.indexOf(':') + 1);
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            if (!name.isEmpty()) return name;
        } catch (Exception ignored) { }
        return "文件夹音乐";
    }
}

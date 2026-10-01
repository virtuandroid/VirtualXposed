package com.lody.virtual.server.mediastore;

import android.database.Cursor;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import java.util.Collections;
import java.util.Vector;

/**
 * Contains methods that parse a cursor object to return useful objects.
 *
 * @author Luca Boscolo Meneguolo @calugj
 */
public class MediaStoreCursorParser {

    /**
     * Truly sort the cursor entries. Fixes the issue when two inserts are done
     * within one second, and are not correctly sorted by the query.
     * @param cursor the cursor to truly sort the entries from
     * @return a truly sorted array of MediaStoreEntry (latest first)
     */
    public static MediaStoreEntry[] parseCursorAndSort(Cursor cursor) {
        Vector<MediaStoreEntry> vector = new Vector<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int id;
                try {
                    id = cursor.getInt(cursor.getColumnIndexOrThrow(BaseColumns._ID));
                } catch(IllegalArgumentException e) {
                    throw new RuntimeException("Column " + BaseColumns._ID +
                            " not available on this cursor!"
                    );
                }

                String date = "";
                try {
                    date = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED));
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("Column " + MediaStore.MediaColumns.DATE_ADDED +
                            " not available on this cursor!"
                    );
                }

                MediaStoreEntry entry = new MediaStoreEntry(id);
                entry.addColumn(MediaStore.MediaColumns.DATE_ADDED, date);
                vector.add(entry);
            }
            cursor.close();
        }
        Collections.sort(vector, Collections.reverseOrder());
        return vector.toArray(new MediaStoreEntry[0]);
    }
}




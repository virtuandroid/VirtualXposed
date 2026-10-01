package com.lody.virtual.server.mediastore;

import android.provider.BaseColumns;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import java.util.HashMap;

/**
 * This class holds a representation of the columns that are required by the
 * "parseCursorAndSort" method in class MediaStoreCursorParser. It can be useful to compare
 * efficiently two entries that have the same date in seconds (for successive inserts in
 * the database).
 *
 * @author Luca Boscolo Meneguolo @calugj
 */
class MediaStoreEntry implements Comparable<MediaStoreEntry> {
    HashMap<String, String> columns;

    public MediaStoreEntry(final int id) {
        columns = new HashMap<>();
        columns.put(BaseColumns._ID, String.valueOf(id));   // Mandatory column
    }
    public void addColumn(final String name, final String value) {
        columns.put(name, value);
    }

    public String get(final String name) {
        return columns.get(name);
    }

    @NonNull
    public String toString() {
        return columns.toString();
    }

    @Override
    public int compareTo(MediaStoreEntry o) {
        int thisId =  Integer.parseInt(get(BaseColumns._ID));
        int thatId =  Integer.parseInt(o.get(BaseColumns._ID));

        String thisDateStr = get(MediaStore.MediaColumns.DATE_ADDED);
        String thatDateStr = o.get(MediaStore.MediaColumns.DATE_ADDED);
        if(thisDateStr != null && thatDateStr != null) {
            int thisDate = Integer.parseInt(thisDateStr);
            int thatDate = Integer.parseInt(thatDateStr);

            if(thisDate == thatDate) return Integer.compare(thisId, thatId); // If dates are equal, compare by ID (IDs are generated sequentially)
            return Integer.compare(thisDate, thatDate); // Prefer comparison by date, if date column exists
        }
        return Integer.compare(thisId, thatId); // As a last resort, compare by ID
    }
}
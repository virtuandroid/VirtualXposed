package com.lody.virtual.server.mediastore;

import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import com.lody.virtual.client.core.VirtualCore;
import java.util.Vector;

/**
 * Allows to perform VirtualMediaProvider-related operations, on a service where proxies are
 * not accessible.
 * @author Luca Boscolo Meneguolo @calugj
 */
public class MediaProviderService extends Service {

    public static final String MODE = "mode";
    public static final String URI = "uri";

    public static final int MODE_DETECT = 1;

    public MediaProviderService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int mode = intent.getIntExtra(MODE, -1);
        switch(mode) {
            case MODE_DETECT-> detectChanges(intent);
            default -> throw new RuntimeException("Invalid MODE: " + mode + " for MediaProviderService");
        }

        stopSelf();
        return super.onStartCommand(intent, flags, startId);
    }


    /**
     * Invoked to detect changes to the MediaStore from outside the framework, like
     * deleting a file from the gallery app. Such changes are reflected to the cache.
     */
    private void detectChanges(final Intent intent) {
        Uri uri = intent.getParcelableExtra(URI);
        VMediaProvider provider = VMediaProvider.get();
        // get all ids (owned by all virtual apps with -1)
        Integer[] ids = provider.getIds(-1, uri);
        if(ids == null) return;

        String[] projection = new String[]{BaseColumns._ID};
        String selection = MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " == ?";
        String[] selectionArgs = new String[]{VirtualCore.get().getHostPkg()};
        Cursor cursor = VirtualCore.get().getContext().getContentResolver().query(
                uri,
                projection, // Columns to retrieve
                selection,
                selectionArgs,
                MediaStore.Audio.Media.DATE_ADDED + " DESC" // Sort order (latest first)
        );
        Vector<Integer> vector = new Vector<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int id = cursor.getInt(cursor.getColumnIndexOrThrow(BaseColumns._ID));
                vector.add(id);
            }
            cursor.close();

            for(int id : ids)
                if(!vector.contains(id)) provider.remove(id, uri);
        }
    }
}
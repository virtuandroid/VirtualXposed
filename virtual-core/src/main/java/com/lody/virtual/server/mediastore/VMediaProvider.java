package com.lody.virtual.server.mediastore;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.providers.ProviderHook;
import com.lody.virtual.os.VEnvironment;
import java.util.HashSet;
import java.util.Objects;
import java.util.Vector;
import java.util.function.Function;

import timber.log.Timber;

/**
 * Virtual implementation of MediaStore ownership enforcing for VirtualApp. This
 * class manages and links virtual owners with files on the MediaStore. Allows for owner-safe
 * operations among all virtual apps.
 *
 * @author Luca Boscolo Meneguolo @calugj
 */
public final class VMediaProvider {
    private static final VMediaProvider instance = new VMediaProvider();
    public static VMediaProvider get() { return instance; }


    private static MediaStoreCache audioCache;
    private static MediaStoreCache videoCache;
    private static MediaStoreCache imageCache;
    private static final int HOST_OWNED = -1;

    private VMediaProvider() {
        MediaStoreFileParser parser = new MediaStoreFileParser();

        audioCache = new MediaStoreCache(VEnvironment.getMediaAudioFile(), parser);
        audioCache.init();

        videoCache = new MediaStoreCache(VEnvironment.getMediaVideoFile(), parser);
        videoCache.init();

        imageCache = new MediaStoreCache(VEnvironment.getMediaImageFile(), parser);
        imageCache.init();

        detectChanges();    // when on virtual environment startup, detect changes to the files and update
    }


    /**
     * Add an entry to the cache
     * @param id the true unique _ID of the file on the MediaStore
     * @param owner the virtual owner of the file
     * @param uri the uri of the content provider
     */
    public void add(final int id, final int owner, final Uri uri) {
        if(uri.toString().contains("audio")) add(id, owner, audioCache);
        else if(uri.toString().contains("video")) add(id, owner, videoCache);
        else if(uri.toString().contains("image")) add(id, owner, imageCache);
    }

    /**
     * Get all true _IDs for files for a virtual owner
     * @param owner the virtual owner of the files
     * @param uri the uri of the content provider
     * @return Integer array containing all ids
     */
    public Integer[] getIds(final int owner, final Uri uri) {
        if(uri.toString().contains("audio")) return getIds(owner, audioCache);
        else if(uri.toString().contains("video")) return getIds(owner, videoCache);
        else if(uri.toString().contains("image")) return getIds(owner, imageCache);
        return null;
    }

    /**
     * Get the virtual owner for an _ID
     * @param id the true unique _ID of the file on the MediaStore
     * @param uri the uri of the content provider
     * @return the virtual owner of the file
     */
    public int getOwner(final int id, final Uri uri) {
        if(uri.toString().contains("audio")) return getOwner(id, audioCache);
        else if(uri.toString().contains("video")) return getOwner(id, videoCache);
        else if(uri.toString().contains("image")) return getOwner(id, imageCache);
        return -1;
    }

    /**
     * Remove an entry on the cache
     * @param id the true unique _ID of the file on the MediaStore
     * @param uri the uri of the content provider
     */
    public void remove(final int id, final Uri uri) {
        if(uri.toString().contains("audio")) remove(id, audioCache);
        else if(uri.toString().contains("video")) remove(id, videoCache);
        else if(uri.toString().contains("image")) remove(id, imageCache);
    }


    /**
     * Remove all entries that belong to the virtual owner
     * @param owner the virtual owner of the files
     * @param uri the uri of the content provider
     */
    public void removeOwner(final int owner, final Uri uri) {
        if(uri.toString().contains("audio")) removeOwner(owner, audioCache);
        else if(uri.toString().contains("video")) removeOwner(owner, videoCache);
        else if(uri.toString().contains("image")) removeOwner(owner, imageCache);
    }


    /**
     * Set to owner -1 all entries that match
     * @param owner the virtual owner of the files
     */
    public void unown(final int owner) {
        unown(owner, audioCache);
        unown(owner, videoCache);
        unown(owner, imageCache);
    }


    /**
     * Retrieve the ID of the last inserted file and then add it to the VirtualMediaProvider cache
     * @param owner the virtual owner of the file
     * @param uri the uri of the content provider
     */
    public synchronized static void finalizeInsert(final int owner, final Uri uri) {
        String[] projection = new String[]{BaseColumns._ID, MediaStore.MediaColumns.DATE_ADDED};

        Bundle queryArgs = new Bundle();
        queryArgs.putString("android:query-arg-sql-selection", MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " == ?");
        queryArgs.putStringArray("android:query-arg-sql-selection-args", new String[] {VirtualCore.get().getHostPkg()});

        ProviderHook.deactivate();
        Cursor cursor = VirtualCore.get().getContext().getContentResolver().query(
                uri,
                projection, // Columns to retrieve
                queryArgs,
                null  // Selection args
        );
        ProviderHook.activate();

        MediaStoreEntry[] entries = MediaStoreCursorParser.parseCursorAndSort(cursor);
        if(entries.length > 0) {
            int id = Integer.parseInt(entries[0].get(BaseColumns._ID));
            VMediaProvider.get().add(id, owner, uri);
        }
    }


    /**
     * Eliminates from the cache all references that are linked to deleted files
     * @param owner the virtual owner of the files
     * @param uri the uri of the content provider
     */
    public static void finalizeDelete(final int owner, final Uri uri) {
        Integer[] ids = VMediaProvider.get().getIds(owner, uri);
        if(ids == null) return;

        String[] projection = new String[]{BaseColumns._ID, MediaStore.MediaColumns.DATE_ADDED};
        String selection = MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "== ?";
        String[] selectionArgs = new String[] {VirtualCore.get().getHostPkg()};

        ProviderHook.deactivate();
        Cursor cursor = VirtualCore.get().getContext().getContentResolver().query(
                uri,
                projection, // Columns to retrieve
                selection,
                selectionArgs,
                null
        );
        ProviderHook.activate();

        Vector<Integer> vector = new Vector<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int id = cursor.getInt(cursor.getColumnIndexOrThrow(BaseColumns._ID));
                vector.add(id);
            }
            cursor.close();

            for(int id : ids)
                if(!vector.contains(id)) VMediaProvider.get().remove(id, uri);
        }
    }


    @NonNull
    public String toString() {
        return "VMediaProvider audio: " + audioCache + ", image: " + imageCache + ", video: " + videoCache;
    }




    // ----- private methods ----- //


    /**
     * Add an entry to the cache
     */
    private void add(final int id, final int owner, MediaStoreCache localCache) {
        localCache.update(cache -> cache.put(id, owner));
    }

    /**
     * remove an entry to the cache
     */
    private void remove(final int id, MediaStoreCache localCache) {
        localCache.update(cache -> cache.remove(id));
    }

    /**
     * Remove all entries that match the virtual owner (uninstall an app?)
     */
    private void removeOwner(final int owner, MediaStoreCache localCache) {
        localCache.update(cache -> {
            HashSet<Integer> backup = new HashSet<>(cache.keySet());
            for(int key : backup) {
                if(Objects.equals(cache.get(key), owner)) {
                    cache.remove(key);
                }
            }
        });
    }

    /**
     * Get virtual owner
     */
    private int getOwner(final int id, MediaStoreCache localCache) {
        return localCache.read(id, Function.identity());
    }

    /*
     * Get all ID for virtual owner (get all files owned by virtual app)
     */
    private Integer[] getIds(final int owner, MediaStoreCache localCache) {

        return localCache.read(cache -> {
            Vector<Integer> vector = new Vector<>();
            for(int key : cache.keySet()) {
                if(cache.get(key) == owner || owner == -1) vector.add(key);
            }
            if(vector.isEmpty()) return null;
            return vector.toArray(new Integer[0]);
        });
    }


    /**
     * Remove owner info and set to -1 (-1 is interpreted as owned by the whole framework)
     */
    private void unown(final int owner, MediaStoreCache localCache) {
        localCache.update(cache -> {
            HashSet<Integer> backup = new HashSet<>(cache.keySet());
            for(int key : backup) {
                if(Objects.equals(cache.get(key), owner)) {
                    cache.remove(key);
                    cache.put(key, HOST_OWNED);
                }
            }
        });
    }


    /**
     * Eliminates from the cache all references that are linked to deleted files (done at startup)
     */
    private static void detectChanges() {
        detectChanges(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
        detectChanges(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        detectChanges(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    }
    private static void detectChanges(final Uri uri) {
        Context context = VirtualCore.get().getContext();
        Intent serviceIntent = new Intent(context, MediaProviderService.class);
        serviceIntent.putExtra(MediaProviderService.MODE, MediaProviderService.MODE_DETECT);
        serviceIntent.putExtra(MediaProviderService.URI, uri);
        try {
            context.startService(serviceIntent);
        } catch (Exception exception) {
            Timber.e(exception);
        }
    }
}
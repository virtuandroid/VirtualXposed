package com.lody.virtual.client.hook.providers;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.hook.base.MethodBox;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.server.mediastore.VMediaProvider;
import com.lody.virtual.server.permission.VPermissionManager;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/**
 * @author weishu
 * @date 2018/6/28.
 */
class MediaProviderHook extends ProviderHook {
    private static final String COLUMN_NAME = "_data";

    MediaProviderHook(Object base) {
        super(base);
    }

    @Override
    public synchronized Uri insert(MethodBox methodBox, Uri url, ContentValues initialValues) throws InvocationTargetException {
        boolean condition = (
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI.equals(url) ||
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.equals(url) ||
                MediaStore.Video.Media.INTERNAL_CONTENT_URI.equals(url) ||
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.equals(url) ||
                MediaStore.Images.Media.INTERNAL_CONTENT_URI.equals(url) ||
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI.equals(url) ||
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).equals(url) ||
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).equals(url) ||
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).equals(url)
        );

        if (!condition) return super.insert(methodBox, url, initialValues);

        Object v2 = initialValues.get(COLUMN_NAME);
        if (v2 instanceof String) {
            String path = NativeEngine.getEscapePath((String) v2);
            initialValues.put(COLUMN_NAME, path);
        }
        Uri retVal = super.insert(methodBox, url, initialValues);

        // If the file is not set to pending (pending = 1 hides the file until it's set to 0)
        Object isPending = initialValues.get(MediaStore.MediaColumns.IS_PENDING);
        if(isPending == null || isPending.equals(0))
            VMediaProvider.finalizeInsert(VBinder.getCallingUid(), url);


        return retVal;
    }

    @Override
    public synchronized Cursor query(MethodBox methodBox, Uri url, String[] projection, String selection, String[] selectionArgs, String sortOrder, Bundle originQueryArgs) throws InvocationTargetException {
        Object[] updated = enforceQuery(url, selection, selectionArgs, originQueryArgs);
        selection = (String) updated[0];
        selectionArgs = (String[]) updated[1];

        Cursor cursor = super.query(methodBox, url, projection, selection, selectionArgs, sortOrder, originQueryArgs);
        return new QueryRedirectCursor(cursor, COLUMN_NAME);
    }


    @Override
    public synchronized int delete(MethodBox methodBox, Uri url, String selection, String[] selectionArgs) throws InvocationTargetException {
        if(methodBox.args[2] instanceof Bundle) {
            Object[] updated = enforceDelete(url, selection, selectionArgs, (Bundle) methodBox.args[2]);
            selection = (String) updated[0];
            selectionArgs = (String[]) updated[1];
        }

        int retVal =  super.delete(methodBox, url, selection, selectionArgs);

        if(methodBox.args[2] instanceof Bundle)
            VMediaProvider.finalizeDelete(VBinder.getCallingUid(), url);
        else if(methodBox.args[2] instanceof ContentValues) {
            ContentValues initialValues = (ContentValues) methodBox.args[2];
            Object isPending = initialValues.get(MediaStore.MediaColumns.IS_PENDING);

            // Finalize the insert when the file is set to pending = 0
            if(isPending != null && isPending.equals(0))
                VMediaProvider.finalizeInsert(VBinder.getCallingUid(), url);
        }

        return retVal;
    }




    /**
     * Injects to a query statement the control logic that enables ownership enforcing.
     */
    private Object[] enforceQuery(Uri uri, String selection, String[] selectionArgs, Bundle queryArgs) throws SecurityException {
        String permission = null;

        if(uri.toString().contains(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) ||
                uri.toString().contains(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString())
        ) { permission = Manifest.permission.READ_MEDIA_VIDEO; }

        else if(uri.toString().contains(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()) ||
                uri.toString().contains(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString())
        ) { permission = Manifest.permission.READ_MEDIA_IMAGES; }

        else if(uri.toString().contains(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString()) ||
                uri.toString().contains(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString())
        ) { permission = Manifest.permission.READ_MEDIA_AUDIO; }

        final int uid = VBinder.getCallingUid();

        // if permission is null, uri is for internal storage: i need to enforce as well
        int permissionState;
        try {
            permissionState = VPermissionManager.get().checkPermission(permission, uid);
        } catch (Exception e) {
            permissionState = PackageManager.PERMISSION_DENIED;
        }
        if (permission == null || permissionState == PackageManager.PERMISSION_DENIED) {
            // Permission is not granted! Need to only query for owned files!

            Integer[] ids = VMediaProvider.get().getIds(uid, uri);  // ID for the owned files

            if(ids == null) {   // I don't own any files: Select nothing
                selection = "1=0";
                selectionArgs = null;
            }
            else {   // Select only the owned files
                if(selection == null || selection.isEmpty()) selection = "1=1";    // Dummy select statement
                selection += " AND " + BaseColumns._ID + " IN (";
                for(int id : ids) {
                    selection += " ? ,";
                }
                selection = selection.substring(0, selection.length() - 1);
                selection += " )";

                if (selectionArgs == null) selectionArgs = new String[0];
                String[] temp = Arrays.copyOf(selectionArgs, selectionArgs.length + ids.length);
                for (int i = 0; i < ids.length; i++)
                    temp[selectionArgs.length + i] = String.valueOf(ids[i]);
                selectionArgs = temp;
            }

            queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
            queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        }
        return new Object[]{selection, selectionArgs};
    }


    /**
     * Injects to a delete statement the control logic that enables ownership enforcing.
     */
    private Object[] enforceDelete(Uri uri, String selection, String[] selectionArgs, Bundle queryArgs) {
        final int uid = VBinder.getCallingUid();
        Integer[] ids = VMediaProvider.get().getIds(uid, uri);  // ID for the owned files

        if(ids == null) {   // I don't own any files: Select nothing
            selection = "1=0";
            selectionArgs = null;
        }
        else {   // Select only the owned files
            if(selection == null || selection.isEmpty()) selection = "1=1";    // Dummy select statement
            selection += " AND " + BaseColumns._ID + " IN (";
            for(int id : ids) {
                selection += " ? ,";
            }
            selection = selection.substring(0, selection.length() - 1);
            selection += " )";

            if (selectionArgs == null) selectionArgs = new String[0];
            String[] temp = Arrays.copyOf(selectionArgs, selectionArgs.length + ids.length);
            for (int i = 0; i < ids.length; i++)
                temp[selectionArgs.length + i] = String.valueOf(ids[i]);
            selectionArgs = temp;
        }

        queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
        queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);

        return new Object[]{selection, selectionArgs};
    }
}

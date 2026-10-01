package com.lody.virtual.client.hook.providers;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.fixer.ContextFixer;
import com.lody.virtual.client.hook.base.MethodBox;
import com.lody.virtual.client.stub.StorageRedirect;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.server.permission.VPermissionManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import mirror.android.content.AttributionSource;
import mirror.android.content.AttributionSourceState;
import mirror.android.content.IContentProvider;

/**
 * @author Lody
 */

public class ProviderHook implements InvocationHandler {

    private static boolean STATUS = true;   // Set ProviderHook to active

    public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";

    public static final String QUERY_ARG_SQL_SELECTION_ARGS =
            "android:query-arg-sql-selection-args";
    public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";


    private static final Map<String, HookFetcher> PROVIDER_MAP = new HashMap<>();

    static {
        PROVIDER_MAP.put("settings", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new SettingsProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("downloads", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new DownloadProviderHook(provider);
            }
        });
        PROVIDER_MAP.put("media", new HookFetcher() {
            @Override
            public ProviderHook fetch(boolean external, IInterface provider) {
                return new MediaProviderHook(provider);
            }
        });
    }

    protected final Object mBase;

    public ProviderHook(Object base) {
        this.mBase = base;
    }

    private static HookFetcher fetchHook(String authority) {
        HookFetcher fetcher = PROVIDER_MAP.get(authority);
        if (fetcher == null) {
            fetcher = new HookFetcher() {
                @Override
                public ProviderHook fetch(boolean external, IInterface provider) {
                    if (external) {
                        return new ExternalProviderHook(provider);
                    }
                    return new InternalProviderHook(provider);
                }
            };
        }
        return fetcher;
    }

    private static IInterface createProxy(IInterface provider, ProviderHook hook) {
        if (provider == null || hook == null) {
            return null;
        }
        return (IInterface) Proxy.newProxyInstance(provider.getClass().getClassLoader(), new Class[]{
                IContentProvider.TYPE,
        }, hook);
    }

    public static IInterface createProxy(boolean external, String authority, IInterface provider) {
        if (provider instanceof Proxy && Proxy.getInvocationHandler(provider) instanceof ProviderHook) {
            return provider;
        }
        ProviderHook.HookFetcher fetcher = ProviderHook.fetchHook(authority);
        if (fetcher != null) {
            ProviderHook hook = fetcher.fetch(external, provider);
            IInterface proxyProvider = ProviderHook.createProxy(provider, hook);
            if (proxyProvider != null) {
                provider = proxyProvider;
            }
        }
        return provider;
    }

    public Bundle call(MethodBox methodBox, String method, String arg, Bundle extras) throws InvocationTargetException {
        return methodBox.call();
    }

    public Cursor query(MethodBox methodBox, Uri url, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder, Bundle originQueryArgs) throws InvocationTargetException {
        return (Cursor) methodBox.call();
    }

    public Uri insert(MethodBox methodBox, Uri url, ContentValues initialValues) throws InvocationTargetException {
        return (Uri) methodBox.call();
    }

    public int bulkInsert(MethodBox methodBox, Uri url, ContentValues[] initialValues) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public ContentProviderResult[] applyBatch(MethodBox methodBox, ArrayList<ContentProviderOperation> operations) throws InvocationTargetException {
        return (ContentProviderResult[]) methodBox.call();
    }

    public int delete(MethodBox methodBox, Uri url, String selection, String[] selectionArgs) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public int update(MethodBox methodBox, Uri url, ContentValues values, String selection,
                      String[] selectionArgs) throws InvocationTargetException {
        return (int) methodBox.call();
    }

    public String getType(MethodBox methodBox, Uri url) throws InvocationTargetException {
        return (String) methodBox.call();
    }

    public ParcelFileDescriptor openFile(MethodBox methodBox, Uri url, String mode) throws InvocationTargetException {
        return (ParcelFileDescriptor) methodBox.call();
    }

    public AssetFileDescriptor openAssetFile(MethodBox methodBox, Uri url, String mode) throws InvocationTargetException {
        return (AssetFileDescriptor) methodBox.call();
    }

    public void fixAttributionSource(Object attributionSource) {
        AttributionSourceState.packageName.set(AttributionSource.mAttributionSourceState.get(attributionSource), VirtualCore.get().getContext().getPackageName());
        AttributionSourceState.uid.set(AttributionSource.mAttributionSourceState.get(attributionSource), VirtualCore.get().myUid());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object... args) throws Throwable {
        try {
            processArgs(method, args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        MethodBox methodBox = new MethodBox(method, mBase, args);

        if(!STATUS) return methodBox.call();    // If ProviderHook is not active, return base call

        int start = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ? 1 : 0;
        if (BuildCompat.isS()) {
            // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r16:frameworks/base/core/java/android/content/IContentProvider.java
            start = 1;
        } else if (BuildCompat.isR()) {
            // Android 11: https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/IContentProvider.java?q=IContentProvider&ss=android%2Fplatform%2Fsuperproject
            start = 2;
        }
        String name = method.getName();
        if (BuildCompat.isS() ) {
            tryFixAttributionSource(name, args);
        }
        enforcePermissions(name, args, start);
        try {
            if ("call".equals(name)) {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    start = 2;
                } else if (BuildCompat.isR()) {
                    start = 3;
                } else if (BuildCompat.isQ()) {
                    start = 2;
                }
                String methodName = (String) args[start];
                String arg = (String) args[start + 1];
                Bundle extras = (Bundle) args[start + 2];
                return call(methodBox, methodName, arg, extras);
            } else if ("insert".equals(name)) {
                final var url = (Uri) args[start];
                final var values = (ContentValues) args[start + 1];
                // TODO: fixit
                for (String key : values.keySet()) {
                    String file = values.get(key).toString();
                    if (file.contains("file:///")) {
                        values.remove(key);
                        values.put(key, StorageRedirect.redirect(file));
                        args[start + 1] = values;
                    }
                }

                return insert(methodBox, url, values);
            } else if ("getType".equals(name)) {
                return getType(methodBox, (Uri) args[0]);
            } else if ("delete".equals(name)) {
                final Uri url = (Uri) args[start];
                final String selection;
                final String[] selectionArgs;
                final Object selectionParam = args[start + 1];
                if (selectionParam == null) {
                    return delete(methodBox, url, null, null);
                }
                if (selectionParam instanceof Bundle extras) {
                    selection = extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
                    selectionArgs = extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
                } else {
                    selection = (String) args[start + 1];
                    selectionArgs = (String[]) args[start + 2];
                }
                return delete(methodBox, url, selection, selectionArgs);
            } else if ("bulkInsert".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues[] initialValues = (ContentValues[]) args[start + 1];
                return bulkInsert(methodBox, url, initialValues);
            } else if ("update".equals(name)) {
                Uri url = (Uri) args[start];
                ContentValues values = (ContentValues) args[start + 1];
                final String selection;
                final String[] selectionArgs;
                final Object selectionParam = args[start + 2];
                if (selectionParam == null) {
                    return delete(methodBox, url, null, null);
                }
                if (selectionParam instanceof Bundle extras) {
                    selection = extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
                    selectionArgs = extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
                } else {
                    selection = (String) args[start + 2];
                    selectionArgs = (String[]) args[start + 3];
                }
                return update(methodBox, url, values, selection, selectionArgs);
            } else if ("openFile".equals(name)) {
                Uri url = (Uri) args[start];
                String mode = (String) args[start + 1];
                return openFile(methodBox, url, mode);
            } else if ("openAssetFile".equals(name)) {
                Uri url = (Uri) args[start];
                String mode = (String) args[start + 1];
                return openAssetFile(methodBox, url, mode);
            } else if ("query".equals(name)) {
                Uri url = (Uri) args[start];
                String[] projection = (String[]) args[start + 1];
                String selection = null;
                String[] selectionArgs = null;
                String sortOrder = null;
                Bundle queryArgs = null;

                if (Build.VERSION.SDK_INT >= 26) {
                    queryArgs = (Bundle) args[start + 2];
                    if (queryArgs != null) {
                        selection = queryArgs.getString(QUERY_ARG_SQL_SELECTION);
                        selectionArgs = queryArgs.getStringArray(QUERY_ARG_SQL_SELECTION_ARGS);
                        sortOrder = queryArgs.getString(QUERY_ARG_SQL_SORT_ORDER);
                    }
                } else {
                    selection = (String) args[start + 2];
                    selectionArgs = (String[]) args[start + 3];
                    sortOrder = (String) args[start + 4];
                }

                return query(methodBox, url, projection, selection, selectionArgs, sortOrder, queryArgs);
            } else if ("applyBatch".equals(name)) {
                final var operations = (ArrayList<ContentProviderOperation>) args[start + 1];
                return applyBatch(methodBox, operations);
            }
            return methodBox.call();
        } catch (Throwable e) {
            VLog.d("ProviderHook", "call: %s (%s) with error", name, Arrays.toString(args));
            if (e instanceof InvocationTargetException) {
                throw e.getCause();
            }
            throw e;
        }
    }


    private int enforcePermissions(final String method, final Object[] args, final int start)
    throws OperationApplicationException {
        if (args != null && args.length > start && args[0] != null && args[start] != null
                && args[0] instanceof android.content.AttributionSource accessAttributionSource
                && args[start] instanceof Uri uri) {
            return switch (method) {
                case "query", "canonicalize", "uncanonicalize", "refresh" ->
                    enforceReadPermission(accessAttributionSource, uri);
                case "insert", "bulkInsert", "delete", "update" ->
                    enforceWritePermission(accessAttributionSource, uri);
                case "applyBatch" -> {
                    final var operations = (ArrayList<ContentProviderOperation>) args[start + 1];
                    for (final var operation : operations) {
                        if (operation.isReadOperation()) {
                            if (enforceReadPermission(accessAttributionSource, uri)
                                    != PackageManager.PERMISSION_GRANTED) {
                                throw new OperationApplicationException("App op not allowed", 0);
                            }
                        }
                        if (operation.isWriteOperation()) {
                            if (enforceWritePermission(accessAttributionSource, uri)
                                    != PackageManager.PERMISSION_GRANTED) {
                                throw new OperationApplicationException("App op not allowed", 0);
                            }
                        }
                    }
                    yield PackageManager.PERMISSION_GRANTED;
                }
                default -> PackageManager.PERMISSION_GRANTED;
            };
        }
        return PackageManager.PERMISSION_GRANTED;
    }

    // Simplified version of:
    // https://cs.android.com/android/platform/superproject/+/android-15.0.0_r3:frameworks/base/core/java/android/content/ContentProvider.java;l=816
    protected int enforceReadPermission(
            final android.content.AttributionSource accessAttributionSource,
            final Uri uri) throws SecurityException {
        final var permission = VirtualCore.getPM()
            .resolveContentProvider(uri.getAuthority(), PackageManager.GET_META_DATA)
            .readPermission;
        final int pid = VBinder.getCallingPid();
        final int uid = VBinder.getCallingUid();
        // if providerInfo.readPermission is null, no permission is required -> GRANT
        if (permission == null || VPermissionManager.get().checkPermission(permission, uid)
                == PackageManager.PERMISSION_GRANTED) {
            return PackageManager.PERMISSION_GRANTED;
        }
        final String suffix;
        if (android.Manifest.permission.MANAGE_DOCUMENTS.equals(permission)) {
            suffix = " requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs";
        } else {
            suffix = " requires " + permission + ", or grantUriPermission()";
        // Assume the provider is exported
        // } else {
        //     suffix = " requires the provider be exported, or grantUriPermission()";
        }
        throw new SecurityException("Permission Denial: reading "
                + ProviderHook.class.getSimpleName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid + suffix);
    }

    // Simplified version of:
    // https://cs.android.com/android/platform/superproject/+/android-15.0.0_r3:frameworks/base/core/java/android/content/ContentProvider.java;l=834
    protected int enforceWritePermission(
            final android.content.AttributionSource accessAttributionSource,
            final Uri uri) throws SecurityException {
        final var permission = VirtualCore.getPM()
            .resolveContentProvider(uri.getAuthority(), PackageManager.GET_META_DATA)
            .writePermission;
        final int pid = VBinder.getCallingPid();
        final int uid = VBinder.getCallingUid();
        // if providerInfo.writePermission is null, no permission is required -> GRANT
        if (permission == null || VPermissionManager.get().checkPermission(permission, uid)
                == PackageManager.PERMISSION_GRANTED) {
            return PackageManager.PERMISSION_GRANTED;
        }
        final String failReason =
                // Assume the provider is exported
                // mExported ?
                " requires " + permission + ", or grantUriPermission()"
                // : " requires the provider be exported, or grantUriPermission()"
                ;
        throw new SecurityException("Permission Denial: writing "
                + ProviderHook.class.getSimpleName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid + failReason);
    }

    protected void processArgs(Method method, Object... args) {
    }

    public interface HookFetcher {
        ProviderHook fetch(boolean external, IInterface provider);
    }

    private void tryFixAttributionSource(String method, Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        Object attribution = args[0];
        if (attribution == null) {
            return;
        }
        if (!attribution.getClass().getName().equals("android.content.AttributionSource")) {
            return;
        }

        ContextFixer.fixAttributionSource(attribution, VirtualCore.get().getHostPkg(), VirtualCore.get().myUid());

        final var methodsToFix = Set.of("call", "query", "insert", "bulkInsert", "delete",
                "update", "applyBatch", "openFile", "openAssetFile");
        if (methodsToFix.contains(method)) {
            fixAttributionSource(attribution);
        }
    }


    public static void activate() {
        STATUS = true;
    }

    public static void deactivate() {
        STATUS = false;
    }
}

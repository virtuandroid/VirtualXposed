package com.lody.virtual.server.permission;

import androidx.annotation.Nullable;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.LockedOperation;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;

import java.io.File;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import timber.log.Timber;

/**
 * @author Alberto Lazari
 */
class PermissionCache extends LockedOperation {

    private static final String TAG = PermissionCache.class.getSimpleName();
    private static final VirtualCore CORE = VirtualCore.get();

    private final PermissionFileParser parser;
    private Map<Integer, AppPermissions> cache;

    public PermissionCache(final File file, final PermissionFileParser parser) {
        super(file);
        this.parser = parser;
    }

    @Override
    protected void load(final FileChannel channel) {
        try {
            Timber.d("Loading permission cache from file");
            cache = parser.read(Channels.newInputStream(channel));
        } catch (Exception e) {
            Timber.e(e,"Could not read permissions file");
        }
    }

    @Override
    protected void save(final FileChannel channel) {
        try {
            VLog.i(TAG, "Writing permission cache to file");
            parser.write(cache, Channels.newOutputStream(channel));
        } catch (Exception e) {
            VLog.e(TAG, "Could not write permissions file");
            e.printStackTrace();
        }
    }

    /**
     * Initialize cache by reading/creating the file
     */
    public final synchronized void init() {
        // Gets called when the permission file is missing and has to be created
        final Runnable onFileCreate = () -> {
            // Create the file with empty permissions
            cache = new HashMap<>();
        };
        init(onFileCreate);
    }

    /**
     * Perform a read operation on @uid permissions
     */
    public final <T> T read(final int uid, final Function<AppPermissions, T> operation) {
        requirePermissionToManage(uid);
        return read(() -> {
            final var permissions = cache.get(uid);
            return operation.apply(permissions != null
                    ? permissions : new AppPermissions());
        });
    }

    /**
     * Perform a read operation on entire permission cache
     */
    public final <T> T read(final Function<Map<Integer, AppPermissions>, T> operation) {
        requirePermissionToManageAll();
        return read(() -> {
            return operation.apply(cache);
        });
    }

    /**
     * Perform an update operation on @uid permissions
     */
    public final synchronized void update(final int uid,
            final Consumer<AppPermissions> operation) {

        requirePermissionToManage(uid);
        update(() -> {
            final var permissions = cache.get(uid);
            if (permissions == null) {
                throw new RuntimeException(String.format(
                            "Permissions for user %d not found", uid));
            }
            operation.accept(permissions);
            return null;
        });
    }

    /**
     * Perform an update operation on entire permission cache
     */
    public final synchronized void update(final Consumer<Map<Integer, AppPermissions>> operation) {
        requirePermissionToManageAll();
        update(() -> {
            operation.accept(cache);
            return null;
        });
    }

    /**
     * @throws SecurityException if a VUID is trying to access permissions for a different @uid
     */
    private void requirePermissionToManage(final int uid) {
        final int callingUid = VBinder.getCallingUid();
        if (CORE.isVAppProcess() && callingUid != uid) {
            throw new SecurityException(String.format(
                        "User %d is trying to access user %d permissions", callingUid, uid));
        }
    }

    /**
     * @throws SecurityException if a VUID is trying to access permissions for all apps
     */
    private void requirePermissionToManageAll() {
        // Trigger only if current process is from a virtual app and it has been started
        if (CORE.isVAppProcess() && CORE.isStartup()) {
            throw new SecurityException(String.format(
                        "User %d is trying to access all users permissions",
                        VBinder.getCallingUid()));
        }
    }
}

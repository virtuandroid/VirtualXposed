package com.lody.virtual.helper.utils;

import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.hook.proxies.libcore.LibCoreStub;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.function.Supplier;

/**
 * @author Alberto Lazari
 *
 * Perform reading/writing operations on a file, acquiring a shared/exclusive
 * lock to it for the entire duration of the operation
 */
public abstract class LockedOperation {

    private static final String TAG = LockedOperation.class.getSimpleName();

    private final File file;
    private final FileLocker locker = new FileLocker();
    private long fileLastRead = 0;


    public LockedOperation(final File file) {
        this.file = file;
    }

    /**
     * Ensure file data is loaded and up to date for potential changes
     */
    protected abstract void load(FileChannel channel);

    /**
     * Save changes made by the `operation` on the file
     */
    protected abstract void save(FileChannel channel);


    /**
     * Load the file for the first time, creating it with initial values
     * (set by calling `onFileCreate`) if it does not exist
     */
    public final synchronized void init(final Runnable onFileCreate) {
        try {
            prepare();

            if (file.exists() && file.isFile()) {
                // Load the file contents for the first time
                locker.performWithSharedLockOn(file, channel -> {
                    ensureLoaded(channel);
                    return null;
                });
            } else {
                // Open the file for writing to create it
                locker.performWithExclusiveLockOn(file, channel -> {
                    // Initialize data
                    onFileCreate.run();
                    // Persist data initialized in `onFileCreate`
                    persist(channel);
                    return null;
                });
            }
        } finally {
             restore();
        }

    }

    /**
     * Perform the operation, reading the file while holding a shared lock
     */
    public final <T> T read(final Supplier<T> operation) {
        try {
            prepare();

            return locker.performWithSharedLockOn(file, channel -> {
                ensureLoaded(channel);
                return operation.get();
            });
        } finally {
            restore();
        }
    }

    /**
     * Perform the operation, ensuring that the file is up to date before writing the changes.
     * Everything is run while holding an exclusive lock to the file
     */
    public final synchronized <T> T update(final Supplier<T> operation) {
        try {
            prepare();

            return locker.performWithExclusiveLockOn(file, channel -> {
                ensureLoaded(channel);
                final T result = operation.get();
                try {
                    // Overwrite file instead of appending changes
                    channel.truncate(0);
                } catch (IOException ignored) {
                }
                persist(channel);
                return result;
            });
        } finally {
            restore();
        }
    }


    /**
     * Load file contents if necessary, keeping trace of the last read timestamp
     */
    private synchronized void ensureLoaded(final FileChannel channel) {
        if (file.lastModified() > fileLastRead) {
            fileLastRead = System.currentTimeMillis();
            load(channel);
        }
    }

    /**
     * Persist changes to file, keeping trace of the last read timestamp
     */
    private synchronized void persist(final FileChannel channel) {
        save(channel);
        fileLastRead = System.currentTimeMillis();
    }

    /**
     * Prepare a I/O operation
     */
    private synchronized void prepare() {
        InvocationStubManager.removeMethodProxy(LibCoreStub.class, "open");
    }

    /**
     * Restore every preparation previously executed
     */
    private synchronized void restore() {
        InvocationStubManager.insertMethodProxy(LibCoreStub.class, new LibCoreStub.Open());
    }
}

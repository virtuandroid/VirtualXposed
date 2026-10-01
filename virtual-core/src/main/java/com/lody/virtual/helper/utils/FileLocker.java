package com.lody.virtual.helper.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Manage shared/exclusive locks to a file in a thread-safe way
 */
public class FileLocker {

    private static final String TAG = FileLocker.class.getSimpleName();

    private FileLock fileLock;
    private final Object sharedMutex = new Object();
    private final ReentrantReadWriteLock accessLock = new ReentrantReadWriteLock();

    public <T> T performWithSharedLockOn(final File file,
            final Function<FileChannel, T> operation) {

        FileChannel channel = null;
        try {
            channel = new FileInputStream(file).getChannel();
            acquireSharedLockFor(channel);
            return operation.apply(channel);
        } catch (FileNotFoundException e) {
            VLog.e(TAG, "Cannot open file %s: file not found", file.toString());
            e.printStackTrace();
        } catch (IOException | OverlappingFileLockException e) {
            VLog.e(TAG, "Cannot acquire shared lock on file %s", file.toString());
            e.printStackTrace();
        } finally {
            releaseSharedLock();
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    public <T> T performWithExclusiveLockOn(final File file,
            final Function<FileChannel, T> operation) {

        FileChannel channel = null;
        try {
            channel = new RandomAccessFile(file, "rw").getChannel();
            acquireExclusiveLockFor(channel);
            return operation.apply(channel);
        } catch (FileNotFoundException e) {
            VLog.e(TAG, "Cannot open file %s: file not found", file.toString());
            e.printStackTrace();
        } catch (IOException | OverlappingFileLockException e) {
            VLog.e(TAG, "Cannot acquire exclusive lock on file %s", file.toString());
            e.printStackTrace();
        } finally {
            releaseExclusiveLock();
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }


    private void releaseSharedLock() {
        synchronized(sharedMutex) {
            accessLock.readLock().unlock();
            if (accessLock.getReadLockCount() == 0) {
                releaseFileLock();
            }
        }
    }

    private void releaseExclusiveLock() {
        accessLock.writeLock().unlock();
        releaseFileLock();
    }

    private void releaseFileLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
            }
        } catch (IOException e) {
            VLog.e(TAG, "Could not release the file lock");
            e.printStackTrace();
        }
    }

    private void acquireSharedLockFor(final FileChannel channel) throws IOException {
        synchronized(sharedMutex) {
            accessLock.readLock().lock();
            if (accessLock.getReadLockCount() == 1) {
                fileLock = channel.lock(0, Long.MAX_VALUE, true);
            }
        }
    }

    private void acquireExclusiveLockFor(final FileChannel channel) throws IOException {
        accessLock.writeLock().lock();
        fileLock = channel.lock();
    }
}

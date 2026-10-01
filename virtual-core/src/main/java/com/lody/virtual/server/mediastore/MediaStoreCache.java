package com.lody.virtual.server.mediastore;

import com.lody.virtual.helper.utils.LockedOperation;
import com.lody.virtual.helper.utils.VLog;
import java.io.File;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import timber.log.Timber;

/**
 * @author Luca Boscolo Meneguolo @calugj
 */
class MediaStoreCache extends LockedOperation {

    private static final String TAG = MediaStoreCache.class.getSimpleName();

    private final MediaStoreFileParser parser;
    private Map<Integer, Integer> cache;

    public MediaStoreCache(final File file, final MediaStoreFileParser parser) {
        super(file);
        this.parser = parser;
    }

    @Override
    protected void load(final FileChannel channel) {
        try {
            Timber.d("Loading MediaStore cache from file");
            cache = parser.read(Channels.newInputStream(channel));
        } catch (Exception e) {
            Timber.d("Could not read MediaStore file");
            Timber.d(e);
        }
    }

    @Override
    protected void save(final FileChannel channel) {
        try {
            VLog.i(TAG, "Writing MediaStore cache to file");
            parser.write(cache, Channels.newOutputStream(channel));
        } catch (Exception e) {
            VLog.e(TAG, "Could not write MediaStore cache to file");
            e.printStackTrace();
        }
    }

    /**
     * Initialize cache by reading/creating the file
     */
    public final synchronized void init() {
        // Gets called when the file is missing and has to be created
        final Runnable onFileCreate = () -> {
            // Create the file
            cache = new HashMap<>();
        };
        init(onFileCreate);
    }

    /**
     * Perform a read operation
     */
    public final <T> T read(final Integer id, final Function<Integer, T> operation) {
        return read(() -> {
            final var owner = cache.get(id);
            return operation.apply(owner);
        });
    }

    /**
     * Perform a read operation on entire cache
     */
    public final <T> T read(final Function<Map<Integer, Integer>, T> operation) {
        return read(() -> {
            return operation.apply(cache);
        });
    }

    /**
     * Perform an update operation on entire cache
     */
    public final synchronized void update(final Consumer<Map<Integer, Integer>> operation) {
        update(() -> {
            operation.accept(cache);
            return null;
        });
    }


    public String toString() {
        return cache.toString();
    }
}

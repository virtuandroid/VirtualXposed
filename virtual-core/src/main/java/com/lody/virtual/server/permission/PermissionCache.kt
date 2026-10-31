package com.lody.virtual.server.permission

import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.utils.LockedOperation
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VBinder
import timber.log.Timber
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel

/**
 * @author Alberto Lazari
 */
internal class PermissionCache(file: File?, private val parser: PermissionFileParser) :
    LockedOperation(file) {
    private var cache: MutableMap<Int, AppPermissions>? = null

    override fun load(channel: FileChannel?) {
        try {
            Timber.d("Loading permission cache from file")
            cache = parser.read(Channels.newInputStream(channel))
        } catch (e: Exception) {
            Timber.e(e, "Could not read permissions file")
        }
    }

    override fun save(channel: FileChannel?) {
        try {
            VLog.i(TAG, "Writing permission cache to file")
            parser.write(cache, Channels.newOutputStream(channel))
        } catch (e: Exception) {
            VLog.e(TAG, "Could not write permissions file")
            e.printStackTrace()
        }
    }

    /**
     * Initialize cache by reading/creating the file
     */
    @Synchronized
    fun init() {
        // Gets called when the permission file is missing and has to be created
        val onFileCreate = Runnable {
            // Create the file with empty permissions
            cache = HashMap()
        }
        init(onFileCreate)
    }

    /**
     * Perform a read operation on @uid permissions
     */
    fun <T> read(uid: Int, operation: (AppPermissions?) -> T?): T? {
        requirePermissionToManage(uid)
        return read {
            val permissions = cache!![uid]
            operation.invoke(permissions)
        }
    }

    /**
     * Perform an update operation on @uid permissions
     */
    @Synchronized
    fun update(
        uid: Int,
        operation: (AppPermissions) -> Unit
    ) {
        requirePermissionToManage(uid)
        update<Any?> {
            val permissions = cache!![uid] ?: throw RuntimeException(
                String.format(
                    "Permissions for user %d not found", uid
                )
            )
            operation.invoke(permissions)
            null
        }
    }

    fun readAll(): Map<Int, AppPermissions>? {
        // TODO check lock
        return cache
    }

    /**
     * Perform an update operation on entire permission cache
     */
    @Synchronized
    fun update(operation: (MutableMap<Int, AppPermissions>?) -> Unit) {
        requirePermissionToManageAll()

        update<Any?> {
            operation.invoke(cache)
            null
        }
    }

    /**
     * @throws SecurityException if a VUID is trying to access permissions for a different @uid
     */
    private fun requirePermissionToManage(uid: Int) {
        val callingUid = VBinder.getCallingUid()
        if (CORE.isVAppProcess && callingUid != uid) {
            throw SecurityException(
                String.format(
                    "User %d is trying to access user %d permissions", callingUid, uid
                )
            )
        }
    }

    /**
     * @throws SecurityException if a VUID is trying to access permissions for all apps
     */
    private fun requirePermissionToManageAll() {
        // Trigger only if current process is from a virtual app and it has been started
        if (CORE.isVAppProcess && CORE.isStartup) {
            throw SecurityException(
                String.format(
                    "User %d is trying to access all users permissions",
                    VBinder.getCallingUid()
                )
            )
        }
    }

    companion object {
        private val TAG: String = PermissionCache::class.java.getSimpleName()
        private val CORE: VirtualCore by lazy { VirtualCore.get() }
    }
}

package com.lody.virtual.server.vs

import android.os.RemoteException
import android.util.SparseArray
import com.lody.virtual.server.IVirtualStorageService
import com.lody.virtual.server.pm.VUserManagerService

/**
 * @author Lody
 */
class VirtualStorageService private constructor() : IVirtualStorageService.Stub() {
    private val mLayer = VSPersistenceLayer(this)
    val configs: SparseArray<HashMap<String?, VSConfig?>?> =
        SparseArray()

    init {
        mLayer.read()
    }

    @Throws(RemoteException::class)
    override fun setVirtualStorage(packageName: String?, userId: Int, vsPath: String?) {
        checkUserId(userId)
        synchronized(this.configs) {
            val config = getOrCreateVSConfigLocked(packageName, userId)
            config.vsPath = vsPath
            mLayer.save()
        }
    }

    private fun getOrCreateVSConfigLocked(packageName: String?, userId: Int): VSConfig {
        var userMap = configs.get(userId)
        if (userMap == null) {
            userMap = HashMap()
            configs.put(userId, userMap)
        }
        var config = userMap[packageName]
        if (config == null) {
            config = VSConfig()
            config.enable = false
            userMap[packageName] = config
        }
        return config
    }


    @Throws(RemoteException::class)
    override fun getVirtualStorage(packageName: String?, userId: Int): String? {
        checkUserId(userId)
        synchronized(this.configs) {
            val config = getOrCreateVSConfigLocked(packageName, userId)
            return config.vsPath
        }
    }

    @Throws(RemoteException::class)
    override fun setVirtualStorageState(packageName: String?, userId: Int, enable: Boolean) {
        checkUserId(userId)
        synchronized(this.configs) {
            val config = getOrCreateVSConfigLocked(packageName, userId)
            config.enable = enable
            mLayer.save()
        }
    }

    @Throws(RemoteException::class)
    override fun isVirtualStorageEnable(packageName: String?, userId: Int): Boolean {
        checkUserId(userId)
        synchronized(this.configs) {
            val config = getOrCreateVSConfigLocked(packageName, userId)
            return config.enable
        }
    }

    private fun checkUserId(userId: Int) {
        check(VUserManagerService.get().exists(userId)) { "Invalid userId $userId" }
    }

    companion object {
        private val sService = VirtualStorageService()
        @JvmStatic
        fun get(): VirtualStorageService {
            return sService
        }
    }
}

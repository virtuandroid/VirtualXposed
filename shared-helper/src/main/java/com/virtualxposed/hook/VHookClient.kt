package com.virtualxposed.hook

import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.RemoteException
import com.virtualxposed.hook.server.IVHookService
import com.virtualxposed.log.client.LogMessage
import timber.log.Timber
import java.lang.reflect.Constructor
import java.lang.reflect.Member

object VHookClient {
    private val loadedModules = mutableListOf<PackageInfo>()

    fun registerLoadedModule(packageInfo: PackageInfo) {
        loadedModules.add(packageInfo)
    }
    fun getLoadedModules(): List<PackageInfo> {
        return loadedModules
    }

    private var attacher: VHookAttacher? = null

    fun attach(attacher: VHookAttacher) {
        this.attacher = attacher
    }

    fun getVectorManager(): IBinder? {
        val attacher = this.attacher

        if (attacher == null) {
            Timber.e("VHookAttacher is not yet initialized.")
            return null
        }

        try {
            return attacher.getInterface()?.vectorManager
        } catch (e: RemoteException) {
            Timber.e(e)
        }

        return null
    }

    @Synchronized
    fun isHookAllowed(target: Member, modulePackage: String): Boolean {
        val attacher = this.attacher

        if (attacher == null) {
            Timber.e("VHookAttacher is not yet initialized. Hook denied!")
            return false
        }

        val fullMethod = when (target.javaClass.simpleName) {
            Constructor::class.simpleName -> "${target.declaringClass.name}.<init>"
            else -> "${target.declaringClass.name}.${target.name}"
        }

        try {
            return attacher.getInterface()?.isHookAllowed(fullMethod, modulePackage) == IVHookService.ALLOWED
        } catch (e: RemoteException) {
            Timber.e(e)
        }

        return false
    }

    @JvmStatic
    fun get(): VHookClient {
        return this
    }
}

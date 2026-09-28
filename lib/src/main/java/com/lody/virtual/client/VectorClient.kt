package com.lody.virtual.client

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.server.vector.VectorManagerService
import timber.log.Timber

object VectorClient {
    const val VECTOR_PACKAGE = "org.matrix.vector.manager"
    private fun isVector(applicationInfo: ApplicationInfo) =
        applicationInfo.packageName == VECTOR_PACKAGE

    @JvmStatic
    fun initVectorManager(appContext: Context, applicationInfo: ApplicationInfo, appClassLoader: ClassLoader) {
        if (!isVector(applicationInfo)) {
            return
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?
            ) {
                if (service != null) {
                    Timber.i("Vector manager service connected")
                    sendBinderToManager(appClassLoader, service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
            }
        }

        VectorManagerService.start(VirtualCore.get().context, connection)
    }

    fun sendBinderToManager(classLoader: ClassLoader, binder: IBinder): Boolean {
        try {
            val constantsClass = classLoader.loadClass("org.matrix.vector.manager.Constants")
            val iBinderClass = classLoader.loadClass("android.os.IBinder")
            val setBinderMethod = constantsClass.getMethod("setBinder", iBinderClass)

            setBinderMethod.isAccessible = true
            val result = setBinderMethod.invoke(null, binder)

            return result is Boolean && result
        } catch (e: Exception) {
            Timber.e(e)
            return false
        }
    }
}
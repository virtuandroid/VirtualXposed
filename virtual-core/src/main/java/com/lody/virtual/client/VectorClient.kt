package com.lody.virtual.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.IBinder
import com.virtualxposed.hook.VHookClient
import org.matrix.vector.service.VectorManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

object VectorClient {
    const val VECTOR_PACKAGE = "org.matrix.vector.manager"
    private fun isVector(applicationInfo: ApplicationInfo) = applicationInfo.packageName == VECTOR_PACKAGE

    @JvmStatic
    fun initVectorManager(appContext: Context, applicationInfo: ApplicationInfo, appClassLoader: ClassLoader) {
        if (!isVector(applicationInfo)) return

        val binder = VHookClient.getVectorManager()
        if (binder == null) {
            Timber.e("No vector manager received from VHookClient!")
            return
        }

        sendBinderToManager(appClassLoader, binder)
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
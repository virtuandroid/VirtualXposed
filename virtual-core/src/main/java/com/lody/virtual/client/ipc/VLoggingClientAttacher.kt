package com.lody.virtual.client.ipc

import com.lody.virtual.client.core.VirtualCore
import com.virtualxposed.log.client.VLoggingAttacher
import com.virtualxposed.log.server.IVLoggingService

class VLoggingClientAttacher : VLoggingAttacher {
    private var mRemote: IVLoggingService? = null
    private fun getRemoteInterface(): IVLoggingService? {
        val logBinder = ServiceManagerNative.getService(ServiceManagerNative.VIRTUAL_LOG)
        return IVLoggingService.Stub.asInterface(logBinder)
    }

    override fun getInterface(): IVLoggingService? {
        if (mRemote == null || (!mRemote!!.asBinder()
                .pingBinder() && !VirtualCore.get().isVAppProcess)
        ) {
            synchronized(VPackageManager::class.java) {
                val remote = this.getRemoteInterface()
                mRemote = LocalProxyUtils.genProxy(
                    IVLoggingService::class.java,
                    remote
                )
            }
        }
        return mRemote
    }
}

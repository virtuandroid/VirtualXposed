package com.lody.virtual.client.ipc

import com.lody.virtual.client.core.VirtualCore
import com.virtualxposed.hook.VHookAttacher
import com.virtualxposed.hook.server.IVHookService

class VHookClientAttacher : VHookAttacher {
    private var mRemote: IVHookService? = null
    private fun getRemoteInterface(): IVHookService? {
        val hookBinder = ServiceManagerNative.getService(ServiceManagerNative.VIRTUAL_HOOK)
        return IVHookService.Stub.asInterface(hookBinder)
    }

    override fun getInterface(): IVHookService? {
        if (mRemote == null || (!mRemote!!.asBinder()
                .pingBinder() && !VirtualCore.get().isVAppProcess)
        ) {
            synchronized(VPackageManager::class.java) {
                val remote = this.getRemoteInterface()
                mRemote = LocalProxyUtils.genProxy(
                    IVHookService::class.java,
                    remote
                )
            }
        }
        return mRemote
    }
}

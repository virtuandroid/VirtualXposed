package org.matrix.vector.service

import com.lody.virtual.client.core.VirtualCore
import org.matrix.vector.ipc.IManagerService
import java.io.File

class VectorManagerService {
    @Synchronized
    fun start() {
        if (_binder == null) {
            _binder = VectorManagerServiceImpl(getBackingFile()).also {
                it.start()
            }
        }
    }

    companion object {
        private var _binder: VectorManagerServiceImpl? = null

        /** Shortcut for calls in the main process. Does not bind the service if not called before! */
        fun getManagerServer(): IVectorManagerService? {
            return if (VirtualCore.get().isServerProcess) {
                _binder
            } else {
                null
            }
        }

        private fun getBackingFile(): File {
            return File(VirtualCore.get().context.applicationContext.filesDir, "modules.json")
        }

        @JvmStatic
        @Synchronized
        fun getCurrentStore(): ModuleStore? {
            return PersistentModuleStore.getStore(getBackingFile())
        }
    }
}
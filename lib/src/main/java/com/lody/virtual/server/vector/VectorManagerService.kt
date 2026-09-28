package com.lody.virtual.server.vector

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VBinder
import timber.log.Timber
import java.io.File

class VectorManagerService : Service() {
    companion object {
        fun start(context: Context, connection: ServiceConnection) {
            val intent = Intent(context, VectorManagerService::class.java)
            context.bindService(intent, connection, BIND_AUTO_CREATE)
        }

        private fun getBackingFile(): File {
            return File(VirtualCore.get().context.applicationContext.filesDir, "modules.json")
        }

        @JvmStatic
        @Synchronized
        fun getOrCreateBinder(): VectorManagerServiceImpl {
            val binder = _binder ?: VectorManagerServiceImpl(getBackingFile()).also {
                it.start()
                _binder = it
            }
            return binder
        }

        private var _binder: VectorManagerServiceImpl? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isPrivileged()) {
            Timber.w("Tried to start the Vector Manager Service from unknown process!")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Timber.i("Started Vector Manager Service")
        return START_STICKY
    }

    private fun isPrivileged(): Boolean {
        // This service is privileged, therefore to prevent abuse we only allow
        // our own process (engine process) to bind to it.
        val callingPid = VBinder.getCallingPid()
        val myPid = android.os.Process.myPid()
        return callingPid == myPid
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (isPrivileged()) {
            val binder = getOrCreateBinder()
            Timber.i("Bound Vector Manager Service")
            binder
        } else {
            Timber.w("Tried to bind to Vector Manager Service from unknown process!")
            null
        }
    }
}
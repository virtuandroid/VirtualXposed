package com.lody.virtual.server.vector

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import org.matrix.vector.ipc.IManagerService
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume

class VectorManagerService : Service() {
    companion object {
        fun start(context: Context, connection: ServiceConnection) {
            val intent = Intent(context, VectorManagerService::class.java)
            context.bindService(intent, connection, BIND_AUTO_CREATE)
        }

        private fun getBackingFile(): File {
            return File(VirtualCore.get().context.applicationContext.filesDir, "modules.json")
        }

        suspend fun getService(): IManagerService {
            val context = VirtualCore.get().context
            val intent = Intent(context, VectorManagerService::class.java)
            val binder = getServiceBinder(context, intent)
            return IManagerService.Stub.asInterface(binder)
        }

        @JvmStatic
        @Synchronized
        fun getCurrentStore(): ModuleStore? {
            return PersistentModuleStore.getStore(getBackingFile())
        }

        private suspend fun getServiceBinder(
            context: Context,
            intent: Intent,
            flags: Int = BIND_AUTO_CREATE
        ): IBinder =
            suspendCancellableCoroutine { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                        if (continuation.isActive) {
                            continuation.resume(service)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(IllegalStateException("Binding died for $name")))
                        }
                    }
                }

                val bound = context.bindService(intent, connection, flags)

                if (!bound) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IllegalArgumentException("Failed to bind service for $intent")))
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        context.unbindService(connection)
                    }
                }
            }
    }

    private var _binder: VectorManagerServiceImpl? = null

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
            val binder = _binder ?: VectorManagerServiceImpl(getBackingFile()).also {
                it.start()
                _binder = it
            }
            Timber.i("Bound Vector Manager Service")
            binder
        } else {
            Timber.w("Tried to bind to Vector Manager Service from unknown process!")
            null
        }
    }
}
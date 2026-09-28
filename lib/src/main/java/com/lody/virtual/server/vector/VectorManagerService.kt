package com.lody.virtual.server.vector

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lody.virtual.os.VBinder
import timber.log.Timber

class VectorManagerService : Service() {
    companion object {
        fun start(context: Context, connection: ServiceConnection) {
            val intent = Intent(context, VectorManagerService::class.java)
            context.bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    private val binder = VectorManagerServiceImpl()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        val callingPid = VBinder.getCallingPid()
        val myPid = android.os.Process.myPid()

        // This service is privileged, therefore to prevent abuse we only allow
        // our own process (engine process) to bind to it.
        return if (callingPid == myPid) {
            binder
        } else {
            Timber.w("Tried to bind to Vector Manager Service ($myPid) from unknown process: $callingPid")
            null
        }
    }
}
package com.virtualxposed.log.client

import android.os.RemoteException
import timber.log.Timber

object VLoggingClient {
    private var attacher: VLoggingAttacher? = null

    fun attach(attacher: VLoggingAttacher) {
        this.attacher = attacher
    }

    fun log(message: LogMessage) {
        val attacher = this.attacher

        if (attacher == null) {
            Timber.e("VLoggingClient is not yet initialized. Log message dropped!")
            throw RuntimeException()
        }

        try {
            attacher.getInterface()?.log(message)
        } catch (e: RemoteException) {
            Timber.e(e)
        }
    }

    @JvmStatic
    fun get(): VLoggingClient {
        return this
    }
}

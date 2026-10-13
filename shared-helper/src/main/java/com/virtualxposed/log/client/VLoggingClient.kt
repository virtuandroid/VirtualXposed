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

    fun getFingerprintAction(): Int? {
        val attacher = this.attacher

        if (attacher == null) {
            Timber.e("VLoggingClient is not yet initialized. Action dropped!")
            throw RuntimeException()
        }

        return try {
            attacher.getInterface()?.fingerprintLogAction
        } catch (e: RemoteException) {
            Timber.e(e)
            null
        }
    }

    fun setFingerprintAction(action: Int): Boolean {
        val attacher = this.attacher

        if (attacher == null) {
            Timber.e("VLoggingClient is not yet initialized. Action dropped!")
            throw RuntimeException()
        }

        return try {
            attacher.getInterface()?.setFingerprintLogAction(action) ?: false
        } catch (e: RemoteException) {
            Timber.e(e)
            false
        }
    }

    @JvmStatic
    fun get(): VLoggingClient {
        return this
    }
}

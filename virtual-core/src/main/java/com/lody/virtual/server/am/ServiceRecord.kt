package com.lody.virtual.server.am

import android.app.IServiceConnection
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import java.util.Collections

class ServiceRecord : Binder() {
    val bindings: MutableList<IntentBindRecord> = ArrayList()
    var activeSince: Long = 0
    var lastActivityTime: Long = 0
    var serviceInfo: ServiceInfo? = null
    @Volatile
    var startId: Int = 0
    var process: ProcessRecord? = null
    var foregroundId: Int = 0
    var foregroundNoti: Notification? = null

    fun containConnection(connection: IServiceConnection?): Boolean {
        if (connection == null) {
            return false
        }

        for (record in bindings) {
            if (record.containConnection(connection)) {
                return true
            }
        }
        return false
    }

    val clientCount: Int
        get() = bindings.size


    val connectionCount: Int
        get() {
            var count = 0
            synchronized(bindings) {
                for (record in bindings) {
                    count += record.connections.size
                }
            }
            return count
        }


    fun peekBinding(service: Intent?): IntentBindRecord? {
        synchronized(bindings) {
            for (bindRecord in bindings) {
                if (bindRecord.intent!!.filterEquals(service)) {
                    return bindRecord
                }
            }
        }
        return null
    }

    fun addToBoundIntent(intent: Intent, connection: IServiceConnection) {
        var record = peekBinding(intent)
        if (record == null) {
            record = IntentBindRecord()
            record.intent = intent
            synchronized(bindings) {
                bindings.add(record)
            }
        }
        record.addConnection(connection)
    }

    class IntentBindRecord {
        val connections: MutableList<IServiceConnection> =
            Collections.synchronizedList(
                ArrayList<IServiceConnection?>()
            )
        var binder: IBinder? = null
        var intent: Intent? = null
        var doRebind: Boolean = false

        fun containConnection(connection: IServiceConnection?): Boolean {
            if (connection == null) {
                return false
            }

            for (con in connections) {
                if (con.asBinder() === connection.asBinder()) {
                    return true
                }
            }
            return false
        }

        fun addConnection(connection: IServiceConnection) {
            if (!containConnection(connection)) {
                connections.add(connection)
                try {
                    connection.asBinder().linkToDeath(DeathRecipient(this, connection), 0)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }

        fun removeConnection(connection: IServiceConnection?) {
            synchronized(connections) {
                val iterator = connections.iterator()
                while (iterator.hasNext()) {
                    val conn = iterator.next()
                    if (conn.asBinder() === connection?.asBinder()) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    private class DeathRecipient(
        private val bindRecord: IntentBindRecord,
        private val connection: IServiceConnection
    ) : IBinder.DeathRecipient {
        override fun binderDied() {
            bindRecord.removeConnection(connection)
            connection.asBinder().unlinkToDeath(this, 0)
        }
    }
}
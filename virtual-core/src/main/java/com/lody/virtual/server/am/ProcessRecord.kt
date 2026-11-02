package com.lody.virtual.server.am

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.ConditionVariable
import android.os.IInterface
import com.lody.virtual.client.IVClient
import com.lody.virtual.os.VUserHandle
import java.util.concurrent.CopyOnWriteArraySet

class ProcessRecord(// all about the first app in the process
    val info: ApplicationInfo?, // name of the process
    val processName: String?, var vuid: Int, var vpid: Int
) : Binder(), Comparable<ProcessRecord?> {
    val lock: ConditionVariable = ConditionVariable()
    val pkgList: CopyOnWriteArraySet<String?> = CopyOnWriteArraySet() // List of packages
    @Volatile
    var client: IVClient? = null
    @Volatile
    var appThread: IInterface? = null
    @Volatile
    var pid: Int = 0
    var userId: Int = VUserHandle.getUserId(vuid)
    @Volatile
    var doneExecuting: Boolean = false
    var priority: Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val record = other as ProcessRecord
        return if (processName != null) (processName == record.processName) else record.processName == null
    }

    override fun compareTo(other: ProcessRecord?): Int {
        return this.priority - (other?.priority ?: 0)
    }

    override fun hashCode(): Int {
        var result = vuid
        result = 31 * result + vpid
        result = 31 * result + pid
        result = 31 * result + userId
        result = 31 * result + doneExecuting.hashCode()
        result = 31 * result + priority
        result = 31 * result + info.hashCode()
        result = 31 * result + processName.hashCode()
        result = 31 * result + lock.hashCode()
        result = 31 * result + pkgList.hashCode()
        result = 31 * result + client.hashCode()
        result = 31 * result + appThread.hashCode()
        return result
    }
}

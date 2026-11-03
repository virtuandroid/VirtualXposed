package com.lody.virtual.server.am

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf


class ProcessTable private constructor(
    /** Single source of truth. Maps PID to ProcessRecord */
    private val byPid: PersistentMap<Int, ProcessRecord>,
    /** Maps ProcessIdentifier to PID */
    private val pidByName: PersistentMap<ProcessIdentifier, Int>,
) {
    val size get() = byPid.size

    private data class ProcessIdentifier(val name: String?, val vuid: Int)

    fun get(pid: Int): ProcessRecord? = byPid[pid]
    fun get(name: String?, vuid: Int): ProcessRecord? =
        pidByName[ProcessIdentifier(name, vuid)]?.let(byPid::get)

    val all: Collection<ProcessRecord> get() = byPid.values

    fun set(rec: ProcessRecord): ProcessTable {
        val identifier = ProcessIdentifier(rec.processName, rec.vuid)
        val names = pidByName.putting(identifier, rec.pid)
        return ProcessTable(byPid.putting(rec.pid, rec), names)
    }

    fun remove(rec: ProcessRecord): ProcessTable {
        val identifier = ProcessIdentifier(rec.processName, rec.vuid)
        return ProcessTable(byPid.removing(rec.pid, rec), pidByName.removing(identifier, rec.pid))
    }

    companion object {
        val EMPTY = ProcessTable(persistentHashMapOf(), persistentHashMapOf())
    }
}
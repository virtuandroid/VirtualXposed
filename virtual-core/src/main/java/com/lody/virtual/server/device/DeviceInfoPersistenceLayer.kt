package com.lody.virtual.server.device

import android.os.Parcel
import com.lody.virtual.helper.PersistenceLayer
import com.lody.virtual.helper.collection.SparseArray
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.remote.VDeviceInfo

/**
 * @author Lody
 */
class DeviceInfoPersistenceLayer(private val mService: VDeviceManagerService) :
    PersistenceLayer(VEnvironment.getDeviceInfoFile()) {
    override fun getCurrentVersion(): Int {
        return 1
    }

    override fun writeMagic(p: Parcel?) {
    }

    override fun verifyMagic(p: Parcel?): Boolean {
        return true
    }

    override fun writePersistenceData(p: Parcel) {
        val infos = mService.deviceInfos
        val size = infos.size
        p.writeInt(size)
        infos.forEach { (userId, info) ->
            p.writeInt(userId)
            info.writeToParcel(p, 0)
        }
    }

    override fun readPersistenceData(p: Parcel) {
        val infos = mService.deviceInfos
        infos.clear()
        var size = p.readInt()
        while (size-- > 0) {
            val userId = p.readInt()
            val info = VDeviceInfo(p)
            infos[userId] = info
        }
    }

    override fun onVersionConflict(fileVersion: Int, currentVersion: Int): Boolean {
        return false
    }

    override fun onPersistenceFileDamage() {
        persistenceFile.delete()
    }
}

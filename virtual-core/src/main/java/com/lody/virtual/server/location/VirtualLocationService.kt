package com.lody.virtual.server.location

import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import com.lody.virtual.helper.PersistenceLayer
import com.lody.virtual.helper.collection.SparseArray
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.remote.vloc.VCell
import com.lody.virtual.remote.vloc.VLocation
import com.lody.virtual.server.IVirtualLocationManager
import com.lody.virtual.server.security.SecurityUtils.assertPackageMatchesCallingUid
import com.lody.virtual.server.security.SecurityUtils.enforceCallingUser

/**
 * @author Lody
 */
class VirtualLocationService private constructor() : IVirtualLocationManager.Stub() {
    private val mLocConfigs: SparseArray<MutableMap<String, VLocConfig>> = SparseArray()
    private val mGlobalConfig = VLocConfig()

    private class VLocConfig : Parcelable {
        var mode: Int = 0
        var cell: VCell? = null
        var allCell: List<VCell?>? = null
        var neighboringCell: List<VCell?>? = null
        var location: VLocation? = null

        fun set(other: VLocConfig) {
            this.mode = other.mode
            this.cell = other.cell
            this.allCell = other.allCell
            this.neighboringCell = other.neighboringCell
            this.location = other.location
        }

        constructor()

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(this.mode)
            dest.writeParcelable(this.cell, flags)
            dest.writeTypedList(this.allCell)
            dest.writeTypedList(this.neighboringCell)
            dest.writeParcelable(this.location, flags)
        }

        constructor(p: Parcel) {
            this.mode = p.readInt()
            this.cell = p.readParcelable<VCell?>(VCell::class.java.getClassLoader())
            this.allCell = p.createTypedArrayList<VCell?>(VCell.CREATOR)
            this.neighboringCell = p.createTypedArrayList<VCell?>(VCell.CREATOR)
            this.location = p.readParcelable<VLocation?>(VLocation::class.java.getClassLoader())
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<VLocConfig?> =
                object : Parcelable.Creator<VLocConfig?> {
                    override fun createFromParcel(source: Parcel): VLocConfig {
                        return VLocConfig(source)
                    }

                    override fun newArray(size: Int): Array<VLocConfig?> {
                        return arrayOfNulls(size)
                    }
                }
        }
    }

    private val mPersistenceLayer: PersistenceLayer =
        object : PersistenceLayer(VEnvironment.getVirtualLocationFile()) {
            override fun getCurrentVersion(): Int {
                return 1
            }

            override fun writePersistenceData(p: Parcel) {
                mGlobalConfig.writeToParcel(p, 0)
                p.writeInt(mLocConfigs.size())
                for (i in 0..<mLocConfigs.size()) {
                    val userId = mLocConfigs.keyAt(i)
                    val packages = mLocConfigs.valueAt(i)
                    p.writeInt(userId)
                    p.writeMap(packages)
                }
            }

            override fun readPersistenceData(p: Parcel) {
                mGlobalConfig.set(VLocConfig(p))
                mLocConfigs.clear()
                var size = p.readInt()
                while (size-- > 0) {
                    val userId = p.readInt()
                    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        p.readHashMap( javaClass.classLoader, String::class.java, VLocConfig::class.java)
                    } else {
                        p.readHashMap(javaClass.getClassLoader())?.toMutableMap() as? MutableMap<String, VLocConfig>
                    }
                    mLocConfigs.put(userId, packages)
                }
            }
        }

    init {
        mPersistenceLayer.read()
    }

    @Throws(RemoteException::class)
    override fun getMode(userId: Int, pkg: String?): Int {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        synchronized(mLocConfigs) {
            val config = getOrCreateConfig(userId, pkg)
            mPersistenceLayer.save()
            return config.mode
        }
    }

    @Throws(RemoteException::class)
    override fun setMode(userId: Int, pkg: String?, mode: Int) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        synchronized(mLocConfigs) {
            getOrCreateConfig(userId, pkg).mode = mode
            mPersistenceLayer.save()
        }
    }

    private fun getOrCreateConfig(userId: Int, pkg: String): VLocConfig {
        var packages = mLocConfigs.get(userId)
        if (packages == null) {
            packages = HashMap()
            mLocConfigs.put(userId, packages)
        }
        var config = packages[pkg]
        if (config == null) {
            config = VLocConfig()
            config.mode = MODE_CLOSE
            packages[pkg] = config
        }
        return config
    }

    @Throws(RemoteException::class)
    override fun setCell(userId: Int, pkg: String?, cell: VCell?) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        getOrCreateConfig(userId, pkg).cell = cell
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun setAllCell(userId: Int, pkg: String?, cell: List<VCell?>?) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        getOrCreateConfig(userId, pkg).allCell = cell
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun setNeighboringCell(userId: Int, pkg: String?, cell: List<VCell?>?) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        getOrCreateConfig(userId, pkg).neighboringCell = cell
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun setGlobalCell(cell: VCell?) {
        mGlobalConfig.cell = cell
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun setGlobalAllCell(cell: List<VCell?>?) {
        mGlobalConfig.allCell = cell
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun setGlobalNeighboringCell(cell: List<VCell?>?) {
        mGlobalConfig.neighboringCell = cell
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun getCell(userId: Int, pkg: String?): VCell? {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        val config = getOrCreateConfig(userId, pkg)
        mPersistenceLayer.save()
        return when (config.mode) {
            MODE_USE_SELF -> config.cell
            MODE_USE_GLOBAL -> mGlobalConfig.cell
            MODE_CLOSE -> null
            else -> null
        }
    }

    @Throws(RemoteException::class)
    override fun getAllCell(userId: Int, pkg: String?): List<VCell?>? {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        val config = getOrCreateConfig(userId, pkg)
        mPersistenceLayer.save()
        return when (config.mode) {
            MODE_USE_SELF -> config.allCell
            MODE_USE_GLOBAL -> mGlobalConfig.allCell
            MODE_CLOSE -> null
            else -> null
        }
    }

    @Throws(RemoteException::class)
    override fun getNeighboringCell(userId: Int, pkg: String?): List<VCell?>? {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        val config = getOrCreateConfig(userId, pkg)
        mPersistenceLayer.save()
        return when (config.mode) {
            MODE_USE_SELF -> config.neighboringCell
            MODE_USE_GLOBAL -> mGlobalConfig.neighboringCell
            MODE_CLOSE -> null
            else -> null
        }
    }

    @Throws(RemoteException::class)
    override fun setLocation(userId: Int, pkg: String?, loc: VLocation?) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        getOrCreateConfig(userId, pkg).location = loc
        mPersistenceLayer.save()
    }

    @Throws(RemoteException::class)
    override fun getLocation(userId: Int, pkg: String?): VLocation? {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        val config = getOrCreateConfig(userId, pkg)
        mPersistenceLayer.save()
        return when (config.mode) {
            MODE_USE_SELF -> config.location
            MODE_USE_GLOBAL -> mGlobalConfig.location
            MODE_CLOSE -> null
            else -> null
        }
    }

    @Throws(RemoteException::class)
    override fun setGlobalLocation(loc: VLocation?) {
        mGlobalConfig.location = loc
    }

    @Throws(RemoteException::class)
    override fun getGlobalLocation(): VLocation? {
        return mGlobalConfig.location
    }

    companion object {
        private val sInstance = VirtualLocationService()
        private const val MODE_CLOSE = 0
        private const val MODE_USE_GLOBAL = 1
        private const val MODE_USE_SELF = 2

        @JvmStatic
        fun get(): VirtualLocationService {
            return sInstance
        }
    }
}

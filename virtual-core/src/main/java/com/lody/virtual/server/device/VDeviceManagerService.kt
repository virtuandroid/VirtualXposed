package com.lody.virtual.server.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.RemoteException
import android.provider.Settings
import android.telephony.TelephonyManager
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.remote.VDeviceInfo
import com.lody.virtual.server.IDeviceInfoManager
import kotlin.random.Random

/**
 * @author Lody
 */
@Suppress("DEPRECATION")
class VDeviceManagerService : IDeviceInfoManager.Stub() {
    val deviceInfos: MutableMap<Int, VDeviceInfo> = mutableMapOf()
    private val mPersistenceLayer = DeviceInfoPersistenceLayer(this)
    private val mPool = UsedDeviceInfoPool()

    private class UsedDeviceInfoPool {
        var deviceIds: MutableList<String?> = ArrayList()
        var androidIds: MutableList<String?> = ArrayList()
        var wifiMacs: MutableList<String?> = ArrayList()
        var bluetoothMacs: MutableList<String?> = ArrayList()
        var iccIds: MutableList<String?> = ArrayList()
    }

    init {
        mPersistenceLayer.read()
        deviceInfos.forEach { (i, info) ->
            addDeviceInfoToPool(info)
        }
    }

    private fun addDeviceInfoToPool(info: VDeviceInfo) {
        mPool.deviceIds.add(info.deviceId)
        mPool.androidIds.add(info.androidId)
        mPool.wifiMacs.add(info.wifiMac)
        mPool.bluetoothMacs.add(info.bluetoothMac)
        mPool.iccIds.add(info.iccId)
    }

    @Throws(RemoteException::class)
    override fun getDeviceInfo(userId: Int): VDeviceInfo {
        var info: VDeviceInfo?
        synchronized(this.deviceInfos) {
            info = deviceInfos[userId]
            if (info == null) {
                info = generateDeviceInfo()
                deviceInfos[userId] = info
                mPersistenceLayer.save()
            }
        }
        return info!!
    }

    @Throws(RemoteException::class)
    override fun updateDeviceInfo(userId: Int, info: VDeviceInfo?) {
        synchronized(this.deviceInfos) {
            if (info != null) {
                deviceInfos[userId] = info
                mPersistenceLayer.save()
            }
        }
    }

    private fun generateRandomDeviceInfo(): VDeviceInfo {
        val info = VDeviceInfo()
        var value: String?
        do {
            value = generate10(15)
            info.deviceId = value
        } while (mPool.deviceIds.contains(value))
        do {
            value = generate16(16)
            info.androidId = value
        } while (mPool.androidIds.contains(value))
        do {
            value = generateMac()
            info.wifiMac = value
        } while (mPool.wifiMacs.contains(value))
        do {
            value = generateMac()
            info.bluetoothMac = value
        } while (mPool.bluetoothMacs.contains(value))

        do {
            value = generate10(20)
            info.iccId = value
        } while (mPool.iccIds.contains(value))

        info.serial = generateSerial()

        addDeviceInfoToPool(info)
        return info
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun generateDeviceInfo(): VDeviceInfo {
        val info = generateRandomDeviceInfo()
        val context = VirtualCore.get().context ?: return info

        try {
            var deviceId: String? = null
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
                if (tm != null) {
                    deviceId = tm.deviceId
                }
                if (deviceId != null) {
                    info.deviceId = deviceId
                }
                val androidId = Settings.System.getString(
                    context.contentResolver,
                    Settings.System.ANDROID_ID
                )
                if (androidId != null) {
                    info.androidId = androidId
                }
            } else {
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                if (androidId != null) {
                    info.androidId = androidId
                }
            }

            info.serial = Build.SERIAL
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return info
    }

    companion object {
        private val sInstance = VDeviceManagerService()

        @JvmStatic
        fun get(): VDeviceManagerService {
            return sInstance
        }

        private fun generate10(length: Int): String {
            return buildString(length) {
                repeat(length) {
                    append("0123456789".random())
                }
            }
        }

        private fun generate16(length: Int): String {
            return buildString(length) {
                repeat(length) {
                    append("0123456789abcdef".random())
                }
            }
        }

        // Random MAC address such as ac:bd:e1:f7:63:d4
        private fun generateMac(): String =
            (1..6)
                .joinToString(":") {
                    "%02x".format(Random.nextInt(256))
                }

        @SuppressLint("HardwareIds")
        private fun generateSerial(): String {
            return (Build.SERIAL.takeUnless { it.isNullOrEmpty() } ?: "0123456789ABCDEF")
                .toList()
                .shuffled()
                .joinToString("")
        }
    }
}

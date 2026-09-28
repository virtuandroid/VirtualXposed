package com.lody.virtual.client

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.WorkerThread
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.VLoggingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.reflect.Method
import kotlin.time.Duration.Companion.seconds

class FingerprintListener(val context: Context) {
    private data class DeviceFingerprint(
        // Build Metadata
        val buildFingerprint: String,
        val bootloader: String,
        val brand: String,
        val device: String,
        val display: String,
        val hardware: String,
        val host: String,
        val id: String,
        val manufacturer: String,
        val model: String,
        val product: String,
        val radioVersion: String,
        val tags: String,
        val time: Long,
        val type: String,
        val user: String,

        // Version Info
        val incremental: String,
        val release: String,
        val sdkInt: Int,
        val securityPatch: String,

        // Low-Level Architecture & CPU
        val supportedAbis: List<String>,
        val cpuAbi: String,
        val cpuAbi2: String,

        // Android System Identifiers
        val androidId: String?,

        // Key System Properties (Frequently targeted by spoofing modules)
        val roBuildFlavor: String,
        val roBuildCharacteristics: String,
        val roProductDevice: String,
        val roProductModel: String,
        val roProductBrand: String,
        val roProductManufacturer: String,
        val roHardware: String,
        val roBootVerifiedBootState: String,

        // Runtime Environment Signals
        val isDebuggable: Boolean,
    ) {
        /**
         * Compares this fingerprint with another and returns a map of changed field names
         * to a Pair(oldValue, newValue).
         */
        fun diff(other: DeviceFingerprint): Map<String, Pair<Any?, Any?>> {
            val diffs = mutableMapOf<String, Pair<Any?, Any?>>()
            DeviceFingerprint::class.java.declaredFields.forEach { field ->
                field.isAccessible = true
                val v1 = field.get(this)
                val v2 = field.get(other)
                if (v1 != v2) {
                    diffs[field.name] = Pair(v1, v2)
                }
            }
            return diffs
        }
    }

    private fun getFingerprint(context: Context): DeviceFingerprint {
        return DeviceFingerprint(
            buildFingerprint = Build.FINGERPRINT,
            bootloader = Build.BOOTLOADER,
            brand = Build.BRAND,
            device = Build.DEVICE,
            display = Build.DISPLAY,
            hardware = Build.HARDWARE,
            host = Build.HOST,
            id = Build.ID,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            radioVersion = runCatching { Build.getRadioVersion() }.getOrNull() ?: "UNKNOWN",
            tags = Build.TAGS,
            time = Build.TIME,
            type = Build.TYPE,
            user = Build.USER,

            incremental = Build.VERSION.INCREMENTAL,
            release = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "N/A",

            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cpuAbi = @Suppress("DEPRECATION") Build.CPU_ABI,
            cpuAbi2 = @Suppress("DEPRECATION") Build.CPU_ABI2,

            androidId = runCatching {
                @SuppressLint("HardwareIds")
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull(),

            // Raw Property checks bypass basic Build.java reflection hooks
            roBuildFlavor = getSystemProperty("ro.build.flavor"),
            roBuildCharacteristics = getSystemProperty("ro.build.characteristics"),
            roProductDevice = getSystemProperty("ro.product.device"),
            roProductModel = getSystemProperty("ro.product.model"),
            roProductBrand = getSystemProperty("ro.product.brand"),
            roProductManufacturer = getSystemProperty("ro.product.manufacturer"),
            roHardware = getSystemProperty("ro.hardware"),
            roBootVerifiedBootState = getSystemProperty("ro.boot.verifiedbootstate"),

            isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }

    private fun getSystemProperty(key: String): String {
        return runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod: Method =
                systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, "UNKNOWN") as String
        }.getOrDefault("UNKNOWN")
    }

    private var lastFingerprint: DeviceFingerprint? = null

    fun start() {
        lastFingerprint = getFingerprint(context)
        startFingerprintPolling()
    }

    @Synchronized
    @WorkerThread
    private fun check() {
        val newFingerprint = getFingerprint(context)
        val fields = lastFingerprint?.diff(newFingerprint)?.keys ?: emptySet()
        if (fields.isNotEmpty()) {
            VLoggingClient.get().log(LogMessage.FingerprintChange(fields))
        }
        lastFingerprint = newFingerprint
    }

    private fun startFingerprintPolling() {
        CoroutineScope(Dispatchers.IO).launch {
            // The check function is sufficiently fast (0-10ms) to run every 10 seconds. Also runs on separate thread.
            // Higher delays loses granularity and can make it possible to temporarily change the fingerprint
            while (true) {
                delay(10.seconds)
                check()
            }
        }.invokeOnCompletion { throwable ->
            if (throwable != null) {
                Timber.e(throwable, "Fingerprint polling failed!")
            }
        }
    }
}
package com.lody.virtual.remote.logging

import android.annotation.SuppressLint
import android.system.Os
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VEnvironment.getPackageResourcePath
import com.lody.virtual.server.pm.VPackageManagerService
import com.virtualxposed.log.client.LogMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

/**
 * This class wraps over the log message to provide additional metadata about the log message.
 *
 * It is assumed that an attacker can send forged log messages to the logging service,
 * therefore this holder determines additional metadata about the sender such as the PID and package name.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
class LogMessageHolder private constructor(
    val logMessage: LogMessage?,
    val pid: Int,
    // val hostPid: Int,
    // TODO Handle cloned package names
    val packageName: String?,
    val timestamp: Long,
    val dangerous: Boolean
) {
    class Builder(private val logMessage: LogMessage) {
        private var pid: Int = VBinder.getCallingPid()

        // private var hostPid: Int = Os.getpid()
        private var packageName: String? =
            VPackageManagerService().getNameForUid(VBinder.getCallingUid())
        private var timestamp: Long = System.currentTimeMillis()

        fun setPid(pid: Int): Builder {
            this.pid = pid
            return this
        }

        fun setPackageName(packageName: String): Builder {
            this.packageName = packageName
            return this
        }

        fun setHostPackage(): Builder {
            if (VirtualCore.get().isServerProcess) {
                this.packageName = VirtualCore.get().processName
                this.pid = Os.getpid()
            }
            return this
        }

        fun build(): LogMessageHolder {
            val dangerous = when (logMessage) {
                is LogMessage.CodeLoad -> {
                    val appDir = VEnvironment.getDataAppDirectory().absolutePath

                    // System file loading is not dangerous
                    val isSystem =
                        logMessage.path.startsWith("/system") || (logMessage.path.startsWith("/vendor"))

                    val pathSuffix = logMessage.path.removePrefix("$appDir/").substringAfter("/")
                    // Dynamic code loading from storage is classified as dangerous
                    // Apps should only use bundled native code
                    val isBundledCode =
                        logMessage.path.startsWith(appDir) && pathSuffix == "base.apk" || pathSuffix.startsWith("base.apk!")

                    !isSystem && !isBundledCode
                }
                // Apps should never access each others non-exported services, it breaks the Android Sandbox
                is LogMessage.GetService -> {
                    logMessage.packageName != packageName && !logMessage.isExported
                }

                is LogMessage.BindService -> {
                    logMessage.packageName != packageName && !logMessage.isExported
                }

                else -> false
            }
            return LogMessageHolder(logMessage, pid, packageName, timestamp, dangerous)
        }
    }


    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val prettyJson = Json {
            prettyPrint = false
            classDiscriminatorMode = ClassDiscriminatorMode.NONE
            encodeDefaults = true
            explicitNulls = false
        }
    }

    fun shouldBeDisplayed(): Boolean {
        // It is not interesting that an app loads its own base apk
        // It is expected behavior on every app startup, and already captured by AppLoad
        if (logMessage is LogMessage.CodeLoad) {
            val basePackage = getPackageResourcePath(packageName).absolutePath
            if (basePackage == logMessage.path) {
                return false
            }
        }

        return true
    }

    fun toPrettyJson(): String {
        return prettyJson.encodeToString(this)
    }
}
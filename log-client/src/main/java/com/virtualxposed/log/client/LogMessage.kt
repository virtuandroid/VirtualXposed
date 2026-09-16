package com.virtualxposed.log.client

import android.annotation.SuppressLint
import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator


// Awaiting @PolymorphicSealed from kotlin update to automatically manage parcelable
/** Parcelable message to send to the logging service.
 * Parcelable instead of serializable to increase security against malicious log messages */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class LogMessage(
    val logType: LogType,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(logType, 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LogMessage> = object : Parcelable.Creator<LogMessage> {
            override fun createFromParcel(parcel: Parcel): LogMessage? {
                return runCatching {
                    val type = parcel.readParcelable<LogType>(LogType::class.java.classLoader)
                    when (type) {
                        LogType.HookAttach -> HookAttach(parcel)
                        LogType.AppLoad -> AppLoad()
                        LogType.AppKill -> AppKill()
                        LogType.FileOpen -> FileOpen(parcel)
                        LogType.HookExecution -> HookExecution(parcel)
                        LogType.ModuleLoad -> ModuleLoad(parcel)
                        LogType.BroadcastReceived -> BroadCastReceived(parcel)
                        LogType.CodeLoad -> CodeLoad(parcel)
                        LogType.BindService -> BindService(parcel)
                        LogType.GetService -> GetService(parcel)
                        null -> null
                    }
                }.getOrNull()
            }

            override fun newArray(size: Int): Array<LogMessage?> = arrayOfNulls(size)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class HookAttach(
        val method: String
    ) : LogMessage(LogType.HookAttach) {
        constructor(parcel: Parcel) : this(parcel.readString()!!)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(method)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class HookExecution(
        val method: String
    ) : LogMessage(LogType.HookExecution) {
        constructor(parcel: Parcel) : this(parcel.readString()!!)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(method)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class FileOpen(
        val path: String
    ) : LogMessage(LogType.FileOpen) {
        constructor(parcel: Parcel) : this(parcel.readString()!!)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(path)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    class AppLoad : LogMessage(LogType.AppLoad)


    @OptIn(InternalSerializationApi::class)
    @Serializable
    class AppKill : LogMessage(LogType.AppKill)


    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class ModuleLoad(
        val path: String
    ) : LogMessage(LogType.ModuleLoad) {
        constructor(parcel: Parcel) : this(parcel.readString()!!)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(path)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class BroadCastReceived(
        // This could be a real Intent, but that makes JSON serialization tricky
        val action: String,
        val destinationPackage: String?,
    ) : LogMessage(LogType.BroadcastReceived) {
        constructor(parcel: Parcel) : this(parcel.readString()!!, parcel.readString()!!)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(action)
            parcel.writeString(destinationPackage)
        }
    }


    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class CodeLoad(
        // This could be a real Intent, but that makes JSON serialization tricky
        val path: String,
    ) : LogMessage(LogType.CodeLoad) {
        constructor(parcel: Parcel) : this(parcel.readString()!!)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(path)
        }
    }



    @Serializable
    data class BindService(
        val packageName: String,
        val componentName: String,
        val isExported: Boolean,
    ) : LogMessage(LogType.BindService) {
        constructor(parcel: Parcel) : this(parcel.readString()!!, parcel.readString()!!, parcel.readInt() == 1)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(packageName)
            parcel.writeString(componentName)
            parcel.writeInt(if (isExported) 1 else 0)
        }
    }


    @Serializable
    data class GetService(
        val packageName: String,
        val componentName: String,
        val isExported: Boolean,
    ) : LogMessage(LogType.GetService) {
        constructor(parcel: Parcel) : this(parcel.readString()!!, parcel.readString()!!, parcel.readInt() == 1)

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeString(packageName)
            parcel.writeString(componentName)
            parcel.writeInt(if (isExported) 1 else 0)
        }
    }
}


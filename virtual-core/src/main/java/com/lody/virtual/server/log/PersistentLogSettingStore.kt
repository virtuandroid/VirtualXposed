package com.lody.virtual.server.log

import android.annotation.SuppressLint
import com.virtualxposed.log.server.IVLoggingService
import kotlinx.serialization.Serializable
import org.matrix.vector.service.GenericStore
import java.io.File

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class LogSettings(
    val fingerprintAction: Int = IVLoggingService.LOG_ONLY
)

object PersistentLogSettingStore : GenericStore<LogSettings>(LogSettings.serializer())  {
    override fun writeBlankStore(backingFile: File) {
        val store = LogSettings()
        writeStore(backingFile, store)
    }
}
package org.matrix.vector.service

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

abstract class GenericStore<T>(
    private val serializer: KSerializer<T>
) {
    open val json = Json {
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    abstract fun writeBlankStore(backingFile: File)

    @Synchronized
    fun writeStore(backingFile: File, store: T) {
        runCatching {
            Timber.d("Saving store: $store")
            val storeJson = json.encodeToString(serializer, store)
            backingFile.writeText(storeJson)
        }.onFailure { e ->
            Timber.e(e, "Failed to write store")
        }
    }

    @Throws
    private fun parseStore(backingFile: File): T {
        val text = backingFile.readText()
        return json.decodeFromString(serializer, text)
    }

    fun getStore(backingFile: File): T? {
        return runCatching {
            if (!backingFile.exists()) {
                backingFile.parentFile?.mkdirs()
                backingFile.createNewFile()
                writeBlankStore(backingFile)
            }

            parseStore(backingFile)
        }.onFailure { e ->
            Timber.e(e, "Failed to read store!")
        }.getOrElse {
            null
        }
    }
}
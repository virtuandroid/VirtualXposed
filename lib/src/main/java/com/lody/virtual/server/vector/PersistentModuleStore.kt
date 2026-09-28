package com.lody.virtual.server.vector

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import kotlin.jvm.Throws

@InternalSerializationApi
@Serializable
data class ModuleStore(
    val enabledModules: Set<String> = emptySet(),
    val moduleScopes: Map<String, Set<String>> = emptyMap()
)

object PersistentModuleStore {
    private val json = Json
    @OptIn(InternalSerializationApi::class)
    @Throws
    fun writeStore(backingFile: File, store: ModuleStore) {
        val storeJson = json.encodeToString(store)
        backingFile.writeText(storeJson)
    }

    @OptIn(InternalSerializationApi::class)
    @Throws
    private fun parseStore(backingFile: File): ModuleStore {
        val text = backingFile.readText()
        return json.decodeFromString<ModuleStore>(text)
    }

    @OptIn(InternalSerializationApi::class)
    fun getStore(backingFile: File): ModuleStore? {
        return runCatching {
            if (!backingFile.exists()) {
                backingFile.parentFile?.mkdirs()
                backingFile.createNewFile()
                val store = ModuleStore()
                writeStore(backingFile, store)
                return store
            }

            return parseStore(backingFile)
        }.onFailure { e ->
            Timber.e(e)
        }.getOrElse {
            null
        }
    }
}
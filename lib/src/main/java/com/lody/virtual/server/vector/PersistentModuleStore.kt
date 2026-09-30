package com.lody.virtual.server.vector

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import kotlin.jvm.Throws

@Serializable
data class ModuleStore(
    val enabledModules: Set<String> = emptySet(),
    val moduleAppScopes: Map<String, Set<String>> = emptyMap(),
    val allowListHookScopes: Map<String, Set<String>> = emptyMap(),
    val blockListHookScopes: Map<String, Set<String>> = emptyMap(),
)

object PersistentModuleStore {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun writeStore(backingFile: File, store: ModuleStore) {
        runCatching {
            Timber.d("Saving store: $store")
            val storeJson = json.encodeToString(store)
            backingFile.writeText(storeJson)
        }.onFailure { e ->
            Timber.e(e, "Failed to write store")
        }
    }

    @Throws
    private fun parseStore(backingFile: File): ModuleStore {
        val text = backingFile.readText()
        return json.decodeFromString<ModuleStore>(text)
    }

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
            Timber.e(e, "Failed to read store!")
        }.getOrElse {
            null
        }
    }
}
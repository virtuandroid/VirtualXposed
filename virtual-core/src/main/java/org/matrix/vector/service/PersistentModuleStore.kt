package org.matrix.vector.service

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.matrix.vector.ipc.HookScope
import timber.log.Timber
import java.io.File


@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ModuleStore(
    // Separate field for convenience
    val enabledModules: Set<String> = emptySet(),
    val moduleSettings: Map<String, ModuleSettings> = emptyMap(),
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
// Simple extensible data storage for module settings
data class ModuleSettings(
    val methodRewriteAllowed: Boolean,
    val guestVirtualizationAllowed: Boolean,
    val appScopes: Set<String>,
    val hookScopes: List<HookScope>
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
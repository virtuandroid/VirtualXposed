package org.matrix.vector.service

import android.annotation.SuppressLint
import com.virtualxposed.hook.PackageID
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
    val guestVirtualizationAllowed: Boolean,
    val appScopes: Set<PackageID>,
    val hookScopes: List<HookScope>
)

object PersistentModuleStore : GenericStore<ModuleStore>(ModuleStore.serializer()) {
    override fun writeBlankStore(backingFile: File) {
        val store = ModuleStore()
        writeStore(backingFile, store)
    }
}
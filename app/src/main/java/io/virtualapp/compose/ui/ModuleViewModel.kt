package io.virtualapp.compose.ui

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lody.virtual.server.vector.VectorManagerService
import org.matrix.vector.ipc.IManagerService

@Immutable
data class ModuleState(
    val modules: List<ModuleInfo> = emptyList(),
    // Changeable properties
    val moduleProperties: Map<String, ModuleProperty> = emptyMap(),
    val searchQuery: String = "",
    val selectedModule: ModuleInfo? = null
)

data class ModuleProperty(
    val isEnabled: Boolean
)

sealed interface MainIntent {
    data class OnSearchQueryChanged(val query: String) : MainIntent
    data class OnItemClicked(val item: ModuleInfo?) : MainIntent
    data class OnModuleChange(val enabled: Boolean, val item: ModuleInfo) : MainIntent
}

class ModuleViewModel : ViewModel() {
    private var binder: IManagerService? = null
    private val _uiState = MutableStateFlow(ModuleState())
    val uiState: StateFlow<ModuleState> = _uiState.asStateFlow()
    private var allModules: List<ModuleInfo> = emptyList()

    private fun handleSearch(newQuery: String) {
        _uiState.update {
            val newModules = if (newQuery.isBlank()) {
                allModules
            } else {
                allModules.filter { moduleInfo ->
                    moduleInfo.appName.contains(newQuery, ignoreCase = true) ||
                            moduleInfo.packageInfo.packageName.contains(
                                newQuery,
                                ignoreCase = true
                            )
                }
            }
            it.copy(searchQuery = newQuery, modules = newModules)
        }
    }

    private fun openItem(item: ModuleInfo?) {
        _uiState.update { it.copy(selectedModule = item) }
    }


    private fun getXposedProperties( modules: List<ModuleInfo>): Map<String, ModuleProperty> {
        val enabledModules = binder?.enabledModules
        return modules.associate {
            val isEnabled = enabledModules?.contains(it.packageName) == true
            it.packageName to ModuleProperty(isEnabled)
        }
    }

    private fun getXposedPackages(
        packages: List<PackageInfo>,
        packageManager: PackageManager
    ): List<ModuleInfo> {
        return packages.mapNotNull { pkg ->
            val metaData = pkg.applicationInfo?.metaData
            val isXposed = metaData?.getBoolean("xposedmodule", false) ?: false

            if (isXposed) {
                val appName =
                    pkg.applicationInfo?.loadLabel(packageManager)?.toString() ?: pkg.packageName
                val icon = pkg.applicationInfo?.loadIcon(packageManager)

                val minVersion =
                    metaData.getInt("xposedminversion").takeIf { it > 0 }?.toString() ?: "Unknown"

                // Description can be a String or a Resource ID
                val descriptionRes = metaData.getInt("xposeddescription", 0)
                val description = if (descriptionRes != 0 && pkg.applicationInfo != null) {
                    runCatching {
                        packageManager.getText(pkg.packageName, descriptionRes, pkg.applicationInfo)
                            .toString()
                    }
                        .getOrDefault("N/A")
                } else {
                    metaData.getString("xposeddescription") ?: "N/A"
                }

                ModuleInfo(
                    packageInfo = pkg,
                    appName = appName,
                    icon = icon,
                    xposedMinVersion = minVersion,
                    xposedDescription = description
                )
            } else null
        }
    }

    fun loadData(packageManger: PackageManager) {
        viewModelScope.launch {
            val binder =
                VectorManagerService.getService().also { this@ModuleViewModel.binder = it }

            val packages = binder.getInstalledPackagesFromAllUsers(
                PackageManager.GET_META_DATA,
                false
            ).list.filterNotNull()

            val xposedModules = getXposedPackages(packages, packageManger)
            val properties = getXposedProperties(xposedModules)

            _uiState.update {
                allModules = xposedModules
                it.copy(modules = xposedModules, moduleProperties = properties)
            }
        }
    }

    fun changeModuleEnabled(enable: Boolean, item: ModuleInfo) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setModuleEnabled(packageName, enable) == true

        if (success) {
            _uiState.update {
                val currentProperties = it.moduleProperties[packageName] ?: return@update it
                val newMap = mapOf(packageName to currentProperties.copy(isEnabled = enable))
                it.copy(moduleProperties = it.moduleProperties + newMap)
            }
        }
    }

    fun processIntent(intent: MainIntent) {
        when (intent) {
            is MainIntent.OnSearchQueryChanged -> handleSearch(intent.query)
            is MainIntent.OnItemClicked -> openItem(intent.item)
            is MainIntent.OnModuleChange -> changeModuleEnabled(intent.enabled, intent.item)
        }
    }
}
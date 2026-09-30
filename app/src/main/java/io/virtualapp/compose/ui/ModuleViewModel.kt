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
import timber.log.Timber

@Immutable
data class ModuleState(
    val modules: List<ModuleInfo> = emptyList(),
    val searchQuery: String = "",
    val selectedModule: ModuleInfo? = null
)

sealed interface MainIntent {
    data class OnSearchQueryChanged(val query: String) : MainIntent
    data class OnItemClicked(val item: ModuleInfo?) : MainIntent
    data class OnModuleEnable(val enabled: Boolean, val item: ModuleInfo) : MainIntent
    data class OnModuleBlock(val scopes: Set<String>, val item: ModuleInfo) : MainIntent
    data class OnModuleAllow(val scopes: Set<String>, val item: ModuleInfo) : MainIntent
}

class ModuleViewModel : ViewModel() {
    private var binder: IManagerService? = null
    private val _uiState = MutableStateFlow(ModuleState())
    val uiState: StateFlow<ModuleState> = _uiState.asStateFlow()
    private var allModules: List<ModuleInfo> = emptyList()

    private fun handleSearch(newQuery: String) {
        _uiState.update { state ->
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
            state.copy(searchQuery = newQuery, modules = newModules.sortedModules())
        }
    }

    private fun List<ModuleInfo>.sortedModules(): List<ModuleInfo> {
        return this.sortedWith(compareBy<ModuleInfo> {
           !it.isEnabled
        }.thenBy { it.appName })
    }

    private fun openItem(item: ModuleInfo?) {
        _uiState.update { it.copy(selectedModule = item) }
    }

    private fun getXposedPackages(
        packages: List<PackageInfo>,
        packageManager: PackageManager
    ): List<ModuleInfo> {
        return packages.mapNotNull { pkg ->
            val metaData = pkg.applicationInfo?.metaData
            val isXposed = metaData?.getBoolean("xposedmodule", false) ?: false

            if (isXposed) {
                val packageName = pkg.packageName
                val appName =
                    pkg.applicationInfo?.loadLabel(packageManager)?.toString() ?: packageName
                val icon = pkg.applicationInfo?.loadIcon(packageManager)

                val minVersion =
                    metaData.getInt("xposedminversion").takeIf { it > 0 }?.toString() ?: "Unknown"

                // Description can be a String or a Resource ID
                val descriptionRes = metaData.getInt("xposeddescription", 0)
                val description = if (descriptionRes != 0 && pkg.applicationInfo != null) {
                    runCatching {
                        packageManager.getText(packageName, descriptionRes, pkg.applicationInfo)
                            .toString()
                    }
                        .getOrDefault("N/A")
                } else {
                    metaData.getString("xposeddescription") ?: "N/A"
                }

                val blocked = binder?.getBlockedHookScopes(packageName)?.toSet() ?: emptySet()
                val allowed = binder?.getAllowedHookScopes(packageName)?.toSet() ?: emptySet()
                val isEnabled = binder?.enabledModules?.contains(packageName) == true

                ModuleInfo(
                    packageInfo = pkg,
                    appName = appName,
                    icon = icon,
                    xposedMinVersion = minVersion,
                    xposedDescription = description,
                    blockListHookScopes = blocked,
                    allowListHookScopes = allowed,
                    isEnabled = isEnabled
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

            _uiState.update {
                allModules = xposedModules
                it.copy(modules = xposedModules.sortedModules())
            }
        }
    }

    fun changeModuleEnabled(item: ModuleInfo, enable: Boolean) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setModuleEnabled(packageName, enable) == true

        if (success) {
            updateModule(item.copy(isEnabled = enable))
        }
    }

    private fun changeModuleBlock(item: ModuleInfo, newScope: Set<String>) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setBlockHookScopes(packageName, newScope.toList()) == true

        if (success) {
            updateModule(item.copy(blockListHookScopes = newScope))
        }
    }

    private fun changeModuleAllow(item: ModuleInfo, newScope: Set<String>) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setAllowHookScopes(packageName, newScope.toList()) == true

        if (success) {
            updateModule(item.copy(allowListHookScopes = newScope))
        }
    }

    private fun updateModule(newModule: ModuleInfo) {
        _uiState.update { state ->
            val newList = state.modules.toMutableList()
            state.modules.firstOrNull { it.packageName == newModule.packageName }?.let {
                newList.remove(it)
                newList.add(newModule)
            }
            if (state.selectedModule?.packageName == newModule.packageName) {
                state.copy(modules = newList.sortedModules(), selectedModule = newModule)
            } else {
                state.copy(modules = newList.sortedModules())
            }
        }
    }

    fun processIntent(intent: MainIntent) {
        Timber.i("Process intent: $intent")
        when (intent) {
            is MainIntent.OnSearchQueryChanged -> handleSearch(intent.query)
            is MainIntent.OnItemClicked -> openItem(intent.item)
            is MainIntent.OnModuleEnable -> changeModuleEnabled(intent.item, intent.enabled)
            is MainIntent.OnModuleAllow -> changeModuleAllow(intent.item, intent.scopes)
            is MainIntent.OnModuleBlock -> changeModuleBlock(intent.item, intent.scopes)
        }
    }
}
package io.virtualapp.compose.ui

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.vector.ipc.HookScope
import org.matrix.vector.ipc.HookScope.Companion.sortedStable
import org.matrix.vector.ipc.HookScope.Companion.reindexScopes
import org.matrix.vector.ipc.IManagerService
import timber.log.Timber
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Immutable
import com.lody.virtual.os.VUserHandle.PER_USER_RANGE
import com.virtualxposed.hook.PackageID
import com.virtualxposed.hook.VHookClient
import io.virtualapp.compose.ui.PackageInfoLite.Companion.toPackageInfoLite
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.matrix.vector.ipc.ScopeEntry

@Immutable
data class ModuleScreenState(
    val modules: PersistentList<ModuleInfo> = persistentListOf(),
    val searchQuery: String = "",
    val selectedModule: ModuleInfo? = null,
    val allApps: PersistentList<PackageInfoLite> = persistentListOf()
)

@Immutable
data class PackageInfoLite(
    val packageId: PackageID,
    val label: String,
    val icon: Drawable?,
    val versionName: String?,
    val versionCode: Long,
    val targetSdkVersion: Int?,
    val minSdkVersion: Int?,
    val sourceDir: String?
) {
    val packageName = packageId.packageName

    companion object {
        private fun getUserIdFromPackageInfo(packageInfo: PackageInfo): Int? {
            val uid = packageInfo.applicationInfo?.uid
            if (uid != null) {
                return uid / PER_USER_RANGE
            }
            return null
        }

        fun PackageInfo.toPackageInfoLite(packageManger: PackageManager): PackageInfoLite {
            val name =
                this.applicationInfo?.let { packageManger.getApplicationLabel(it) }?.toString()
                    ?: this.packageName
            val drawable = this.applicationInfo?.loadIcon(packageManger)
            val userId = getUserIdFromPackageInfo(this) ?: -1

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                this.longVersionCode
            } else {
                @Suppress("DEPRECATION") this.versionCode.toLong()
            }

            return PackageInfoLite(
                PackageID(this.packageName, userId),
                name,
                drawable,
                this.versionName,
                versionCode,
                this.applicationInfo?.targetSdkVersion,
                this.applicationInfo?.minSdkVersion,
                this.applicationInfo?.sourceDir
            )
        }
    }
}

sealed interface MainIntent {
    data class OnSearchQueryChanged(val query: String) : MainIntent
    data class OnItemClicked(val item: ModuleInfo?) : MainIntent
    data class OnModuleEnable(val enabled: Boolean, val item: ModuleInfo) : MainIntent
    data class OnModuleScope(val scopes: List<HookScope>, val item: ModuleInfo) : MainIntent
    data class OnModuleGuestSetting(val enabled: Boolean, val item: ModuleInfo) : MainIntent
    data class AddRecommendedScopes(val item: ModuleInfo) : MainIntent
    data class OnModuleAppScope(val scopes: List<PackageID>, val item: ModuleInfo) : MainIntent
}

data class ModuleInfo(
    val packageInfo: PackageInfoLite,
    val appName: String,
    val icon: Drawable?,
    val xposedMinVersion: String,
    val xposedDescription: String,
    val isEnabled: Boolean,
    val fullGuestAccess: Boolean,
    /** This should always be sorted, for convenience and to prevent re-sorting on recompositions */
    val hookScopes: PersistentList<HookScope>,
    val appScopes: PersistentList<PackageID>,
) {
    // Shorthand
    val packageName = packageInfo.packageId.packageName
}

class ModuleViewModel : ViewModel() {
    companion object {
        private fun List<ModuleInfo>.sortedModules(): List<ModuleInfo> {
            return this.sortedWith(compareBy<ModuleInfo> {
                !it.isEnabled
            }.thenBy { it.appName })
        }

        private val recommendedScopes = listOf(
            HookScope(
                HookScope.ACTION_BLOCK,
                "java.**",
                "Block the Java standard library",
                true,
                0,
                "6bef6d94-9903-4fc7-a386-20e09dc5c7bf"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "javax.**",
                "Block the Java extended library",
                true,
                1,
                "7adb70ba-eb88-4b0a-81a5-d29baae6dba1"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "android.**",
                "Block core android libraries",
                true,
                2,
                "12a5413d-96f6-4bcd-9115-d9e7ba05a3ca"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "mirror.**",
                "Block VirtualApp internal classes",
                true,
                3,
                "7389d10b-7e61-4691-8221-3525904904d3"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "com.lody.virtual.**",
                "Block VirtualApp internal classes",
                true,
                4,
                "e739087a-1adc-4b48-b0e9-599241720263"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "com.virtualxposed.lsplantbridge.**",
                "Block VirtualXposed internal classes",
                true,
                5,
                "341ac0c0-5a8f-4465-940b-b7ca7724289d"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "com.virtualxposed.log.**",
                "Block VirtualXposed internal classes",
                true,
                6,
                "10a1bee1-ebcb-441d-8e7c-cd92f7439842"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "org.chickenhook.restrictionbypass.**",
                "Block VirtualXposed internal classes",
                true,
                7,
                "13b856af-5f71-429f-b26f-aa1426996e79"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "de.robv.android.**",
                "Block Xposed internal classes",
                true,
                8,
                "ff7297fc-8bf6-4b18-b484-732ea4526a00"
            ),
            HookScope(
                HookScope.ACTION_BLOCK,
                "me.weishu.exposed.**",
                "Block Xposed internal classes",
                true,
                9,
                "77416862-1baa-450f-abe9-3fab33dd968c"
            ),
        )
    }

    private var binder: IManagerService? = null
    private val _uiState = MutableStateFlow(ModuleScreenState())
    val uiState: StateFlow<ModuleScreenState> = _uiState.asStateFlow()
    private var allModules: List<ModuleInfo> = persistentListOf()

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

            state.copy(
                searchQuery = newQuery,
                modules = newModules.sortedModules().toPersistentList()
            )
        }
    }

    private fun openItem(item: ModuleInfo?) {
        _uiState.update { it.copy(selectedModule = item) }
    }

    private fun getXposedPackages(
        packages: List<PackageInfo>,
        packageManager: PackageManager
    ): List<ModuleInfo> {
        val enabledModules = binder?.enabledModules
        val fullGuestAccessList = binder?.guestVirtualizationAllowed

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

                val scopes = binder?.getHookScopes(packageName)?.sortedStable() ?: emptyList()
                val isEnabled = enabledModules?.contains(packageName) ?: false
                val guestAccess = fullGuestAccessList?.contains(packageName) ?: false
                val appScopes = binder?.getModuleScope(packageName)
                    ?.map { PackageID(it.packageName, it.userId) }
                    ?.sortedWith(compareByDescending<PackageID> {
                        it.packageName == pkg.packageName
                    }
                        .thenBy { it.packageName }
                    ) ?: emptyList()

                ModuleInfo(
                    packageInfo = pkg.toPackageInfoLite(packageManager),
                    appName = appName,
                    icon = icon,
                    xposedMinVersion = minVersion,
                    xposedDescription = description,
                    isEnabled = isEnabled,
                    fullGuestAccess = guestAccess,
                    hookScopes = scopes.toPersistentList(),
                    appScopes = appScopes.toPersistentList()
                )
            } else null
        }
    }

    fun loadData(packageManager: PackageManager) {
        viewModelScope.launch {
            val binder = VHookClient.getVectorManager() ?: return@launch
            val manager = IManagerService.Stub.asInterface(binder).also {
                this@ModuleViewModel.binder = it
            }

            val packages = manager.getInstalledPackagesFromAllUsers(
                PackageManager.GET_META_DATA,
                false
            ).list.filterNotNull()

            val xposedModules = getXposedPackages(packages, packageManager)
            val allApps = packages.map { pkg ->
                pkg.toPackageInfoLite(packageManager)
            }

            _uiState.update {
                allModules = xposedModules
                it.copy(
                    modules = xposedModules.sortedModules().toPersistentList(),
                    allApps = allApps.toPersistentList()
                )
            }
        }
    }

    fun changeModuleGuestAccess(item: ModuleInfo, enable: Boolean) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setGuestVirtualizationAllowed(packageName, enable) == true

        if (success) {
            updateModule(item.copy(fullGuestAccess = enable))
        }
    }

    fun changeModuleEnabled(item: ModuleInfo, enable: Boolean) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setModuleEnabled(packageName, enable) == true

        if (success) {
            updateModule(item.copy(isEnabled = enable))
        }
    }

    private fun changeModuleScopes(item: ModuleInfo, newScope: List<HookScope>) {
        val packageName = item.packageInfo.packageName
        val success = binder?.setHookScopes(packageName, newScope) == true

        if (success) {
            updateModule(item.copy(hookScopes = newScope.sortedStable().toPersistentList()))
        }
    }

    private fun addRecommendedScopes(item: ModuleInfo) {
        val currentScopes = item.hookScopes.sortedStable()
        val newScopes =
            (recommendedScopes + currentScopes).distinctBy { it.id }.reindexScopes().sortedStable()
                .toPersistentList()
        updateModule(item.copy(hookScopes = newScopes))
    }

    private fun updateModule(newModule: ModuleInfo) {
        _uiState.update { state ->
            val newList = state.modules.toMutableList()
            state.modules.firstOrNull { it.packageName == newModule.packageName }?.let {
                newList.remove(it)
                newList.add(newModule)
            }
            val persistentList = newList.sortedModules().toPersistentList()
            if (state.selectedModule?.packageName == newModule.packageName) {
                state.copy(modules = persistentList, selectedModule = newModule)
            } else {
                state.copy(modules = persistentList)
            }
        }
    }

    fun changeModuleAppScopes(item: ModuleInfo, scopes: List<PackageID>) {
        val packageName = item.packageInfo.packageName
        val binderScopes = scopes.map {
            ScopeEntry().apply {
                this.packageName = it.packageName
                this.userId = it.userId
            }
        }
        val success = binder?.setModuleScope(packageName, binderScopes) == true

        if (success) {
            val sorted = scopes.sortedWith(compareByDescending<PackageID> {
                it.packageName == item.packageName
            }
                .thenBy { it.packageName }
            )

            updateModule(
                item.copy(
                    appScopes = sorted
                        .toPersistentList()
                )
            )
        }
    }

    fun processIntent(intent: MainIntent) {
        Timber.i("Process intent: $intent")
        when (intent) {
            is MainIntent.OnSearchQueryChanged -> handleSearch(intent.query)
            is MainIntent.OnItemClicked -> openItem(intent.item)
            is MainIntent.OnModuleEnable -> changeModuleEnabled(intent.item, intent.enabled)
            is MainIntent.OnModuleScope -> changeModuleScopes(intent.item, intent.scopes)
            is MainIntent.AddRecommendedScopes -> addRecommendedScopes(intent.item)
            is MainIntent.OnModuleGuestSetting -> changeModuleGuestAccess(
                intent.item,
                intent.enabled
            )

            is MainIntent.OnModuleAppScope -> changeModuleAppScopes(intent.item, intent.scopes)
        }
    }
}
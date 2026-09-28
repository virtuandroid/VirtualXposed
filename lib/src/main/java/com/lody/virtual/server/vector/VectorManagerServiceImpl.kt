package com.lody.virtual.server.vector

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.ParcelFileDescriptor
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VUserManager
import com.lody.virtual.server.am.BroadcastSystem
import com.lody.virtual.server.am.VActivityManagerService
import com.lody.virtual.server.pm.VAppManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.InternalSerializationApi
import org.matrix.vector.ipc.DeviceUser
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.ipc.ModuleLoadFailure
import org.matrix.vector.ipc.ScopeEntry
import rikka.parcelablelist.ParcelableListSlice
import timber.log.Timber
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class VectorManagerServiceImpl(val backingFile: File) : IManagerService.Stub() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(InternalSerializationApi::class)
    fun start() {
        val store = PersistentModuleStore.getStore(backingFile) ?: return
        _enabledModules.value = store.enabledModules.toSet()
        _moduleScopes.value = store.moduleScopes.mapValues { it.value.toSet() }
        setupPersistencePipeline()
    }

    @OptIn(InternalSerializationApi::class, FlowPreview::class)
    private fun setupPersistencePipeline() {
        combine(_enabledModules, _moduleScopes) { enabled, scopes ->
            ModuleStore(enabled, scopes)
        }
            .drop(1) // Drop initial state
            .debounce(500.milliseconds)
            .onEach { store -> persistData(store) }
            .launchIn(scope)
    }

    @OptIn(InternalSerializationApi::class)
    private fun persistData(store: ModuleStore) {
        runCatching {
            PersistentModuleStore.writeStore(backingFile, store)
        }.onFailure { e ->
            Timber.e(e, "Failed to persist module data!")
        }
    }

    private val _enabledModules = MutableStateFlow<Set<String>>(emptySet())
    private val _moduleScopes = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private var isVerboseLog = false

    override fun getProtocolVersion() = PROTOCOL_VERSION

    override fun getFrameworkVersionCode() = 1L

    override fun getFrameworkVersionName(): String {
        return "1.0.0-VirtualXposed"
    }

    override fun getBuildStamp(): String {
        // TODO Implement real build stamps
        return "N/A"
    }

    override fun getLibxposedApiVersion(): Int {
        return 102
    }

    override fun isSystemServerAttached(): Boolean {
        return true
    }

    override fun isSepolicyLoaded(): Boolean {
        return true
    }

    override fun getDex2OatWrapperState(): Int {
        return DEX2OAT_OK
    }

    override fun isDex2OatInliningDisabled(): Boolean {
        return false
    }

    override fun getEnabledModules(): List<String?> {
        return _enabledModules.value.toList()
    }

    override fun setModuleEnabled(
        packageName: String?,
        enabled: Boolean
    ): Boolean {
        if (packageName == null) return false
        _enabledModules.update {
            it + packageName
        }
        return true
    }

    override fun getModuleScope(packageName: String?): List<ScopeEntry?> {
        if (packageName == null) return emptyList()
        val scope = ((_moduleScopes.value[packageName]) ?: emptySet()) + packageName
        return scope.map { ScopeEntry().apply { this.packageName = it } }
    }

    override fun setModuleScope(
        packageName: String?,
        scope: List<ScopeEntry?>?
    ): Boolean {
        val fixedScope = scope?.filterNotNull() ?: return false
        if (packageName == null) return false
        // TODO Fix missing userId
        val newScope = mapOf(packageName to fixedScope.map { it.packageName }.toSet())
        _moduleScopes.update { scopes ->
            scopes + newScope
        }
        return true
    }

    override fun getIncludeNewApps(packageName: String?): Boolean {
        return false
    }

    override fun setIncludeNewApps(
        packageName: String?,
        enable: Boolean
    ): Boolean {
        return false
    }

    override fun getModuleLoadFailures(): List<ModuleLoadFailure?> {
        return listOf()
    }

    override fun isStatusNotificationEnabled(): Boolean {
        return false
    }

    override fun setStatusNotificationEnabled(enabled: Boolean) {
    }

    override fun isVerboseLogEnabled(): Boolean {
        return isVerboseLog
    }

    override fun setVerboseLogEnabled(enabled: Boolean) {
        isVerboseLog = enabled
    }

    override fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? {
        return null
    }

    override fun getLogParts(verbose: Boolean): List<String?>? {
        return null
    }

    override fun getLogPart(
        verbose: Boolean,
        name: String?
    ): ParcelFileDescriptor? {
        return null
    }

    override fun startNewLogPart(verbose: Boolean) {

    }

    override fun writeBugReport(zipFd: ParcelFileDescriptor?) {
        return
    }

    override fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean
    ): ParcelableListSlice<PackageInfo?> {
        val packages = VPackageManager.get().getInstalledPackages(flags, 0)
        return ParcelableListSlice(packages)
    }

    override fun queryIntentActivitiesAsUser(
        intent: Intent?,
        flags: Int,
        userId: Int
    ): ParcelableListSlice<ResolveInfo?> {
        val mockResolveInfo = listOf(
            ResolveInfo().apply {

            }
        )
        return ParcelableListSlice(mockResolveInfo)
    }

    override fun getUsers(): List<DeviceUser?> {
        return VUserManager.get().users.map {
            DeviceUser().apply {
                id = it.id
                name = it.name
            }
        }
    }

    override fun startActivityAsUser(
        intent: Intent?,
        userId: Int,
        noUserSwitch: Boolean
    ): Int {
        return 0
    }

    override fun forceStopPackage(packageName: String?, userId: Int) {
        BroadcastSystem.get().stopApp(packageName)
        VActivityManagerService.get().killAppByPkg(packageName, userId)
    }

    override fun uninstallPackage(packageName: String?, userId: Int): Boolean {
        return VAppManagerService.get().uninstallPackageAsUser(packageName, userId)
    }

    override fun optimizePackage(packageName: String?): Boolean {
        return true
    }

    override fun softReboot() {
        VirtualCore.get().killAllApps()
    }

    override fun reboot() {
        softReboot()
    }

    override fun isForcedLauncherIcons(): Boolean {
        return false
    }

    override fun setForcedLauncherIcons(force: Boolean) {
    }

    override fun getRootImplementation(): Int {
        return ROOT_UNKNOWN
    }

    override fun installFrameworkZip(
        zipPath: String?,
        receiver: IFrameworkInstallReceiver?
    ) {
    }

    override fun getManagerApk(): ParcelFileDescriptor? {
        return null
    }
}
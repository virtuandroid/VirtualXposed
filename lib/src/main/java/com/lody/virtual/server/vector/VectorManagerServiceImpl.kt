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
import org.matrix.vector.ipc.DeviceUser
import org.matrix.vector.ipc.IFrameworkInstallReceiver
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.ipc.ModuleLoadFailure
import org.matrix.vector.ipc.ScopeEntry
import rikka.parcelablelist.ParcelableListSlice

class VectorManagerServiceImpl : IManagerService.Stub() {

    private var isVerboseLog = false
    private val enabledModulesMap = mutableMapOf(
        "com.example.fake.module.one" to true,
        "com.example.fake.module.two" to false
    )
    private val moduleScopesMap = mutableMapOf<String, List<ScopeEntry?>>(
        "com.example.fake.module.one" to listOf(

        )
    )
    private val includeNewAppsMap = mutableMapOf(
        "com.example.fake.module.one" to true
    )

    override fun getProtocolVersion() = PROTOCOL_VERSION

    override fun getFrameworkVersionCode() = 1L

    override fun getFrameworkVersionName(): String {
        return "1.0.0-mock"
    }

    override fun getBuildStamp(): String {
        // TODO Implement real build stamps
        return "2026-09-28-mock-build-001"
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
        return enabledModulesMap.filterValues { it }.keys.toList()
    }

    override fun setModuleEnabled(
        packageName: String?,
        enabled: Boolean
    ): Boolean {
        if (packageName == null) return false
        enabledModulesMap[packageName] = enabled
        return true
    }

    override fun getModuleScope(packageName: String?): List<ScopeEntry?> {
        return moduleScopesMap[packageName] ?: emptyList()
    }

    override fun setModuleScope(
        packageName: String?,
        scope: List<ScopeEntry?>?
    ): Boolean {
        if (packageName == null) return false
        moduleScopesMap[packageName] = scope ?: emptyList()
        return true
    }

    override fun getIncludeNewApps(packageName: String?): Boolean {
        return includeNewAppsMap[packageName] ?: false
    }

    override fun setIncludeNewApps(
        packageName: String?,
        enable: Boolean
    ): Boolean {
        if (packageName == null) return false
        includeNewAppsMap[packageName] = enable
        return true
    }

    override fun getModuleLoadFailures(): List<ModuleLoadFailure?> {
        return listOf(
            //("com.example.broken.module", "ClassNotFoundException: Failed to resolve entry point", System.currentTimeMillis())
        )
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
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
            stream.write("[MOCK LOG] Live streaming initialized\n".toByteArray())
        }
        return pipe[0]
    }

    override fun getLogParts(verbose: Boolean): List<String?>? {
        return listOf("system.log", "xposed.log", "crash.log")
    }

    override fun getLogPart(
        verbose: Boolean,
        name: String?
    ): ParcelFileDescriptor? {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
            stream.write("[MOCK LOG] Contents for log part: $name\n".toByteArray())
        }
        return pipe[0]
    }

    override fun startNewLogPart(verbose: Boolean) {

    }

    override fun writeBugReport(zipFd: ParcelFileDescriptor?) {
        zipFd?.let {
            ParcelFileDescriptor.AutoCloseOutputStream(it).use { stream ->
                stream.write("Fake Bug Report content ZIP payload".toByteArray())
            }
        }
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
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
            stream.write("Fake APK binary data".toByteArray())
        }
        return pipe[0]
    }
}
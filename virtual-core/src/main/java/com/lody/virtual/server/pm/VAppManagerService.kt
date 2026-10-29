package com.lody.virtual.server.pm

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.core.net.toUri
import com.lody.virtual.client.core.InstallStrategy
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.helper.compat.NativeLibraryHelperCompat
import com.lody.virtual.helper.utils.ArrayUtils
import com.lody.virtual.helper.utils.FileUtils
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.remote.InstallResult
import com.lody.virtual.remote.InstalledAppInfo
import com.lody.virtual.server.IAppManager
import com.lody.virtual.server.accounts.VAccountManagerService
import com.lody.virtual.server.am.BroadcastSystem
import com.lody.virtual.server.am.UidSystem
import com.lody.virtual.server.am.VActivityManagerService
import com.lody.virtual.server.interfaces.IAppRequestListener
import com.lody.virtual.server.interfaces.IPackageObserver
import com.lody.virtual.server.permission.VPermissionManager
import com.lody.virtual.server.pm.parser.PackageParserEx
import com.lody.virtual.server.pm.parser.VPackage
import com.lody.virtual.server.security.SecurityUtils.assertPackageMatchesCallingUid
import com.lody.virtual.server.security.SecurityUtils.assertSystemCaller
import com.lody.virtual.server.security.SecurityUtils.enforceCallingUser
import com.virtualxposed.log.client.LogMessage.AppInstall
import com.virtualxposed.log.client.LogMessage.AppUninstall
import com.virtualxposed.log.client.VLoggingClient
import timber.log.Timber
import timber.log.Timber.Forest.e
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * @author Lody
 */
class VAppManagerService : IAppManager.Stub() {
    private val mUidSystem = UidSystem()
    private val mPersistenceLayer = PackagePersistenceLayer(this)
    private val mVisibleOutsidePackages: MutableSet<String?> = HashSet()
    private val mPermissionManager: VPermissionManager = VPermissionManager.get()
    var isBooting: Boolean = false
        private set
    private val mRemoteCallbackList = RemoteCallbackList<IPackageObserver?>()
    private var mAppRequestListener: IAppRequestListener? = null

    override fun scanApps() {
        if (this.isBooting) {
            return
        }
        synchronized(this) {
            this.isBooting = true
            mPersistenceLayer.read()
            PrivilegeAppOptimizer.get().performOptimizeAllApps()
            this.isBooting = false
        }
    }

    private fun cleanUpResidualFiles(ps: PackageSetting) {
        VLog.w(TAG, "cleanUpResidualFiles: " + ps.packageName)
        val dataAppDir = VEnvironment.getDataAppPackageDirectory(ps.packageName)
        FileUtils.deleteDir(dataAppDir)

        // We shouldn't remove user data here!!! Just remove the package.
        // for (int userId : VUserManagerService.get().getUserIds()) {
        //     FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, ps.packageName));
        // }
    }


    @Synchronized
    fun loadPackage(setting: PackageSetting) {
        if (!loadPackageInnerLocked(setting)) {
            cleanUpResidualFiles(setting)
        }
    }

    private fun loadPackageInnerLocked(ps: PackageSetting): Boolean {
        if (ps.dependSystem) {
            if (!VirtualCore.get().isOutsideInstalled(ps.packageName)) {
                return false
            }
        }
        val cacheFile = VEnvironment.getPackageCacheFile(ps.packageName)
        var pkg: VPackage? = null
        try {
            pkg = PackageParserEx.readPackageCache(ps.packageName)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        if (pkg?.packageName == null) {
            return false
        }
        chmodPackageDictionary(cacheFile)
        PackageCacheManager.put(pkg, ps)
        BroadcastSystem.get().startApp(pkg)
        return true
    }

    override fun isOutsidePackageVisible(pkg: String?): Boolean {
        return pkg != null && mVisibleOutsidePackages.contains(pkg)
    }

    override fun addVisibleOutsidePackage(pkg: String?) {
        if (pkg != null) {
            mVisibleOutsidePackages.add(pkg)
        }
    }

    override fun removeVisibleOutsidePackage(pkg: String?) {
        if (pkg != null) {
            mVisibleOutsidePackages.remove(pkg)
        }
    }

    override fun installPackage(path: String?, flags: Int): InstallResult {
        // TODO Permission check
        return installPackage(path, flags, true)
    }

    @Synchronized
    fun installPackage(path: String?, flags: Int, notify: Boolean): InstallResult {
        val installTime = System.currentTimeMillis()
        if (path == null) {
            return InstallResult.makeFailure("path = NULL")
        }
        var packageFile = File(path)
        if (!packageFile.exists()) {
            return InstallResult.makeFailure("Package File does not exist.")
        }
        var pkg: VPackage? = null
        try {
            pkg = PackageParserEx.parsePackage(packageFile)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        if (pkg?.packageName == null) {
            return InstallResult.makeFailure("Unable to parse the package.")
        }

        VLoggingClient.get().log(AppInstall(pkg.packageName))

        val res = InstallResult()
        res.packageName = pkg.packageName
        // PackageCache holds all packages, try to check if we need to update.
        val existOne = PackageCacheManager.get(pkg.packageName)
        val existSetting = if (existOne != null) existOne.mExtras else null
        if (existOne != null) {
            if ((flags and InstallStrategy.IGNORE_NEW_VERSION) != 0) {
                res.isUpdate = true
                return res
            }
            if (!canUpdate(existOne, pkg, flags)) {
                return InstallResult.makeFailure("Can not update the package (such as version downrange).")
            }
            res.isUpdate = true
        }
        val appDir = VEnvironment.getDataAppPackageDirectory(pkg.packageName)
        val libDir = File(appDir, "lib")
        if (res.isUpdate) {
            FileUtils.deleteDir(libDir)
            VEnvironment.getOdexFile(pkg.packageName).delete()
            VActivityManagerService.get().killAppByPkg(pkg.packageName, VUserHandle.USER_ALL)
        }
        if (!libDir.exists() && !libDir.mkdirs()) {
            return InstallResult.makeFailure("Unable to create lib dir.")
        }
        var dependSystem = (flags and InstallStrategy.DEPEND_SYSTEM_IF_EXIST) != 0
                && VirtualCore.get().isOutsideInstalled(pkg.packageName)

        if (existSetting != null && existSetting.dependSystem) {
            dependSystem = false
        }

        var splitCodePaths: Array<String?>? = null

        if (!dependSystem) {
            val privatePackageFile = File(appDir, "base.apk")
            val parentFolder = privatePackageFile.getParentFile()
            if (!parentFolder!!.exists() && !parentFolder.mkdirs()) {
                Timber.w("Warning: unable to create folder : %s", privatePackageFile.path)
            } else if (privatePackageFile.exists() && !privatePackageFile.delete()) {
                Timber.w("Warning: unable to delete file : %s", privatePackageFile.path)
            }
            val baseApkFile = if (packageFile.isFile()) packageFile else File(pkg.baseCodePath)
            try {
                FileUtils.copyFile(baseApkFile, privatePackageFile)
            } catch (_: IOException) {
                privatePackageFile.delete()
                return InstallResult.makeFailure("Unable to copy the package file.")
            }
            // copy lib in base apk
            NativeLibraryHelperCompat.copyNativeBinaries(baseApkFile, libDir)

            packageFile = privatePackageFile

            if (pkg.splitNames != null) {
                val length = pkg.splitNames.size
                splitCodePaths = arrayOfNulls(length)

                for (i in 0..<length) {
                    val splitName = pkg.splitNames[i]
                    val privateSplitFile = File(appDir, "$splitName.apk")
                    try {
                        FileUtils.copyFile(File(pkg.splitCodePaths[i]), privateSplitFile)

                        // copy lib in split apk
                        NativeLibraryHelperCompat.copyNativeBinaries(privateSplitFile, libDir)
                    } catch (_: IOException) {
                        privateSplitFile.delete()
                        return InstallResult.makeFailure("Unable to copy split: $splitName")
                    }
                    splitCodePaths[i] = privateSplitFile.path
                }
            }
        }

        if (existOne != null) {
            PackageCacheManager.remove(pkg.packageName)
        }
        chmodPackageDictionary(packageFile)
        val ps = existSetting ?: PackageSetting()
        ps.dependSystem = dependSystem
        ps.apkPath = packageFile.path
        ps.libPath = libDir.path
        ps.packageName = pkg.packageName
        ps.appId = VUserHandle.getAppId(mUidSystem.getOrCreateUid(0, pkg))
        if (res.isUpdate) {
            ps.lastUpdateTime = installTime
        } else {
            ps.firstInstallTime = installTime
            ps.lastUpdateTime = installTime
            for (userId in VUserManagerService.get().userIds) {
                val installed = userId == 0
                ps.setUserState(userId, false,  /*launched*/false,  /*hidden*/installed)
            }
        }
        ps.splitCodePaths = splitCodePaths
        PackageParserEx.savePackageCache(pkg)
        PackageCacheManager.put(pkg, ps)
        mPersistenceLayer.save()
        try {
            mPermissionManager.initPermissionsForUid(ps.appId, pkg)
        } catch (e: Throwable) {
            e.printStackTrace()
            return InstallResult.makeFailure("Failed to set permissions")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appDir?.listFiles()?.forEach { file ->
                // Fix 'writable dex file is not allowed' error on Android 13+
                file.setReadOnly()
            }
        }
        BroadcastSystem.get().startApp(pkg)
        if (notify) {
            notifyAppInstalled(ps, -1)
        }
        res.isSuccess = true
        return res
    }


    @Synchronized
    override fun installPackageAsUser(userId: Int, packageName: String?): Boolean {
        enforceCallingUser(userId)
        // TODO implement install package permissions
        var ps = PackageCacheManager.getSetting(packageName)
        if (!VUserManagerService.get().exists(userId) || ps == null || ps.isInstalled(userId)) {
            return false
        }
        ps.setInstalled(userId, true)
        val packageFile = File(ps.apkPath)
        if (!packageFile.exists()) {
            return false
        }
        var pkg: VPackage? = null
        try {
            pkg = PackageParserEx.parsePackage(packageFile)
        } catch (e: Throwable) {
            e(e)
        }

        if (pkg?.packageName == null) {
            return false
        }

        VLoggingClient.get().log(AppInstall(pkg.packageName))

        try {
            ps = ps.clone() as PackageSetting
        } catch (e: Exception) {
            e(e)
        }
        ps.appId = mUidSystem.getOrCreateUid(userId, pkg)
        try {
            mPermissionManager.initPermissionsForUid(ps.appId, pkg)
        } catch (e: Throwable) {
            e(e)
            return false
        }
        notifyAppInstalled(ps, userId)
        mPersistenceLayer.save()
        return true
    }

    private fun chmodPackageDictionary(packageFile: File?) {
        try {
            if (FileUtils.isSymlink(packageFile)) {
                return
            }
            FileUtils.chmod(
                packageFile?.getParentFile()!!.absolutePath,
                FileUtils.FileMode.MODE_755
            )
            FileUtils.chmod(packageFile.absolutePath, FileUtils.FileMode.MODE_755)
        } catch (e: Exception) {
            e(e)
        }
    }

    private fun canUpdate(existOne: VPackage, newOne: VPackage, flags: Int): Boolean {
        if ((flags and InstallStrategy.COMPARE_VERSION) != 0) {
            if (existOne.mVersionCode < newOne.mVersionCode) {
                return true
            }
        }
        if ((flags and InstallStrategy.TERMINATE_IF_EXIST) != 0) {
            return false
        }
        if ((flags and InstallStrategy.UPDATE_IF_EXIST) != 0) {
            return true
        }
        return false
    }


    @Synchronized
    override fun uninstallPackage(packageName: String?): Boolean {
        assertPackageMatchesCallingUid(packageName)
        val ps = PackageCacheManager.getSetting(packageName)
        if (ps != null) {
            uninstallPackageFully(ps)
            mPermissionManager.removePermissionsForUid(ps.appId)
            return true
        }
        return false
    }

    @Throws(RemoteException::class)
    override fun clearPackageAsUser(userId: Int, packageName: String?): Boolean {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(packageName)
        if (!VUserManagerService.get().exists(userId)) {
            return false
        }
        val ps = PackageCacheManager.getSetting(packageName)
        if (ps != null) {
            val userIds = getPackageInstalledUsers(packageName)
            if (!ArrayUtils.contains(userIds, userId)) {
                return false
            }
            if (userIds.size == 1) {
                clearPackage(packageName)
            } else {
                // Just hidden it
                VActivityManagerService.get().killAppByPkg(packageName, userId)
                ps.setInstalled(userId, false)
                mPersistenceLayer.save()
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, packageName))
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(userId, packageName))
            }
            return true
        }
        return false
    }

    @Throws(RemoteException::class)
    override fun clearPackage(packageName: String?): Boolean {
        if (getPackageInstalledUsers(packageName).size > 1) {
            // Only system can clear multiple packages
            assertSystemCaller()
        } else {
            assertPackageMatchesCallingUid(packageName)
        }
        try {
            BroadcastSystem.get().stopApp(packageName)
            VActivityManagerService.get().killAppByPkg(packageName, VUserHandle.USER_ALL)

            for (id in VUserManagerService.get().userIds) {
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName))
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(id, packageName))
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    @Synchronized
    override fun uninstallPackageAsUser(packageName: String?, userId: Int): Boolean {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(packageName)
        if (!VUserManagerService.get().exists(userId)) {
            return false
        }
        val ps = PackageCacheManager.getSetting(packageName)
        if (ps != null) {
            val userIds = getPackageInstalledUsers(packageName)
            if (!ArrayUtils.contains(userIds, userId)) {
                return false
            }
            if (userIds.size == 1) {
                uninstallPackageFully(ps)
            } else {
                // Just hidden it
                VActivityManagerService.get().killAppByPkg(packageName, userId)
                ps.setInstalled(userId, false)
                notifyAppUninstalled(ps, userId)
                mPersistenceLayer.save()
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(userId, packageName))
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(userId, packageName))
            }
            val uid = VPackageManager.get().getPackageUid(packageName, userId)
            mPermissionManager.removePermissionsForUid(uid)
            return true
        }
        return false
    }

    private fun uninstallPackageFully(ps: PackageSetting) {
        val packageName = ps.packageName
        try {
            BroadcastSystem.get().stopApp(packageName)
            VActivityManagerService.get().killAppByPkg(packageName, VUserHandle.USER_ALL)
            VEnvironment.getPackageResourcePath(packageName).delete()
            FileUtils.deleteDir(VEnvironment.getDataAppPackageDirectory(packageName))
            VEnvironment.getOdexFile(packageName).delete()
            for (id in VUserManagerService.get().userIds) {
                FileUtils.deleteDir(VEnvironment.getDataUserPackageDirectory(id, packageName))
                FileUtils.deleteDir(VEnvironment.getVirtualPrivateStorageDir(id, packageName))
            }
            PackageCacheManager.remove(packageName)
        } catch (e: Exception) {
            e(e)
        } finally {
            notifyAppUninstalled(ps, -1)
        }
    }

    override fun getPackageInstalledUsers(packageName: String?): IntArray {
        val ps = PackageCacheManager.getSetting(packageName)
        if (ps != null) {
            val userIds = VUserManagerService.get().userIds
            val installedUsers = userIds.filter { ps.readUserState(it).installed }
            return installedUsers.toIntArray()
        }
        return IntArray(0)
    }

    override fun getInstalledApps(flags: Int): List<InstalledAppInfo> {
        // TODO implement scoping
        return PackageCacheManager.PACKAGE_CACHE.values.mapNotNull { p ->
            val setting = p.mExtras
            setting?.appInfo
        }
    }

    override fun getInstalledAppsAsUser(userId: Int, flags: Int): List<InstalledAppInfo> {
        enforceCallingUser(userId)
        val infoList: MutableList<InstalledAppInfo> =
            ArrayList(installedAppCount)
        for (p in PackageCacheManager.PACKAGE_CACHE.values) {
            val setting = p.mExtras as PackageSetting
            var visible = setting.isInstalled(userId)
            if ((flags and VirtualCore.GET_HIDDEN_APP) == 0 && setting.isHidden(userId)) {
                visible = false
            }
            if (visible) {
                infoList.add(setting.appInfo)
            }
        }
        return infoList
    }

    override fun getInstalledAppCount(): Int {
        return PackageCacheManager.PACKAGE_CACHE.size
    }

    override fun isAppInstalled(packageName: String?): Boolean {
        return packageName != null && PackageCacheManager.PACKAGE_CACHE.containsKey(packageName)
    }

    override fun isAppInstalledAsUser(userId: Int, packageName: String?): Boolean {
        enforceCallingUser(userId)
        if (packageName == null || !VUserManagerService.get().exists(userId)) {
            return false
        }
        val setting = PackageCacheManager.getSetting(packageName)
        return setting != null && setting.isInstalled(userId)
    }

    private fun notifyAppInstalled(setting: PackageSetting, userId: Int) {
        val pkg = setting.packageName
        var N = mRemoteCallbackList.beginBroadcast()
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    sendInstalledBroadcast(pkg)
                    mRemoteCallbackList.getBroadcastItem(N)!!.onPackageInstalled(pkg)
                    mRemoteCallbackList.getBroadcastItem(N)!!.onPackageInstalledAsUser(0, pkg)
                } else {
                    mRemoteCallbackList.getBroadcastItem(N)!!.onPackageInstalledAsUser(userId, pkg)
                }
            } catch (e: RemoteException) {
                e(e)
            }
        }
        mRemoteCallbackList.finishBroadcast()
        VAccountManagerService.get().refreshAuthenticatorCache(null)
    }

    private fun notifyAppUninstalled(setting: PackageSetting, userId: Int) {
        VLoggingClient.get().log(AppUninstall(setting.packageName))

        val pkg = setting.packageName
        var N = mRemoteCallbackList.beginBroadcast()
        while (N-- > 0) {
            try {
                if (userId == -1) {
                    sendUninstalledBroadcast(pkg)
                    mRemoteCallbackList.getBroadcastItem(N)!!.onPackageUninstalled(pkg)
                    mRemoteCallbackList.getBroadcastItem(N)!!.onPackageUninstalledAsUser(0, pkg)
                } else {
                    mRemoteCallbackList.getBroadcastItem(N)!!
                        .onPackageUninstalledAsUser(userId, pkg)
                }
            } catch (e: RemoteException) {
                e(e)
            }
        }
        mRemoteCallbackList.finishBroadcast()
        VAccountManagerService.get().refreshAuthenticatorCache(null)
    }


    private fun sendInstalledBroadcast(packageName: String?) {
        val intent = Intent(Intent.ACTION_PACKAGE_ADDED)
        intent.setData(("package:$packageName").toUri())
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle.ALL)
    }

    private fun sendUninstalledBroadcast(packageName: String?) {
        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED)
        intent.setData(("package:$packageName").toUri())
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle.ALL)
    }

    override fun registerObserver(observer: IPackageObserver?) {
        assertSystemCaller()
        try {
            mRemoteCallbackList.register(observer)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun unregisterObserver(observer: IPackageObserver?) {
        assertSystemCaller()
        try {
            mRemoteCallbackList.unregister(observer)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    // Guest apps need to invoke mAppRequestListener.onRequestInstall()
    override fun getAppRequestListener(): IAppRequestListener? {
        return mAppRequestListener
    }

    override fun setAppRequestListener(listener: IAppRequestListener?) {
        assertSystemCaller()
        this.mAppRequestListener = listener
        if (listener != null) {
            try {
                listener.asBinder().linkToDeath(object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        listener.asBinder().unlinkToDeath(this, 0)
                        this@VAppManagerService.mAppRequestListener = null
                    }
                }, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }

    override fun clearAppRequestListener() {
        assertSystemCaller()
        this.mAppRequestListener = null
    }

    override fun getInstalledAppInfo(packageName: String?, flags: Int): InstalledAppInfo? {
        synchronized(PackageCacheManager::class.java) {
            if (packageName != null) {
                val setting = PackageCacheManager.getSetting(packageName)
                if (setting != null) {
                    return setting.appInfo
                }
            }
            return null
        }
    }

    override fun isPackageLaunched(userId: Int, packageName: String?): Boolean {
        val ps = PackageCacheManager.getSetting(packageName)
        return ps != null && ps.isLaunched(userId)
    }

    override fun setPackageHidden(userId: Int, packageName: String?, hidden: Boolean) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(packageName)
        val ps = PackageCacheManager.getSetting(packageName)
        if (ps != null && VUserManagerService.get().exists(userId)) {
            ps.setHidden(userId, hidden)
            mPersistenceLayer.save()
        }
    }

    fun getAppId(packageName: String?): Int {
        val setting = PackageCacheManager.getSetting(packageName)
        return setting?.appId ?: -1
    }


    fun restoreFactoryState() {
        VLog.w(TAG, "Warning: Restore the factory state...")
        VEnvironment.getDalvikCacheDirectory().delete()
        VEnvironment.getUserSystemDirectory().delete()
        VEnvironment.getDataAppDirectory().delete()
    }

    fun savePersistenceData() {
        mPersistenceLayer.save()
    }

    companion object {
        private val TAG: String = VAppManagerService::class.java.getSimpleName()
        private val sService = AtomicReference<VAppManagerService>()

        @JvmStatic
        fun get(): VAppManagerService {
            return sService.get()
        }

        @JvmStatic
        fun systemReady() {
            VEnvironment.systemReady()
            val instance = VAppManagerService()
            instance.mUidSystem.initUidList()
            sService.set(instance)
        }
    }
}

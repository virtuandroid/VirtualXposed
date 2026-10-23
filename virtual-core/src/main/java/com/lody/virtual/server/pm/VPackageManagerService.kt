package com.lody.virtual.server.pm

import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Parcel
import android.os.RemoteException
import android.text.TextUtils
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.fixer.ComponentFixer
import com.lody.virtual.client.stub.VASettings
import com.lody.virtual.helper.compat.ObjectsCompat
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.remote.VParceledListSlice
import com.lody.virtual.remote.logging.LogMessageHolder
import com.lody.virtual.server.IPackageInstaller
import com.lody.virtual.server.IPackageManager
import com.lody.virtual.server.log.VLoggingManagerService
import com.lody.virtual.server.permission.VPermissionManager
import com.lody.virtual.server.pm.installer.VPackageInstallerService
import com.lody.virtual.server.pm.parser.PackageParserEx
import com.lody.virtual.server.pm.parser.VPackage
import com.lody.virtual.server.pm.parser.VPackage.ActivityComponent
import com.lody.virtual.server.pm.parser.VPackage.PermissionComponent
import com.lody.virtual.server.pm.parser.VPackage.PermissionGroupComponent
import com.lody.virtual.server.pm.parser.VPackage.ProviderComponent
import com.lody.virtual.server.pm.parser.VPackage.ServiceComponent
import com.lody.virtual.server.security.SecurityUtils.enforceCallingUser
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.LogMessage.GetProvider
import com.virtualxposed.log.client.LogMessage.GetService
import com.virtualxposed.log.client.VLoggingClient
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * @author Lody
 */
class VPackageManagerService : IPackageManager.Stub() {
    private val mResolveInfo: ResolveInfo?

    private val mActivities = ActivityIntentResolver()
    private val mServices = ServiceIntentResolver()
    private val mReceivers = ActivityIntentResolver()
    private val mProviders =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) ProviderIntentResolver() else null

    private val mProvidersByComponent = HashMap<ComponentName?, ProviderComponent>()

    private val mPermissions = HashMap<String?, PermissionComponent?>()
    private val mPermissionGroups = HashMap<String?, PermissionGroupComponent>()
    private val mProvidersByAuthority = HashMap<String?, ProviderComponent?>()

    private val mPackages: MutableMap<String?, VPackage> = PackageCacheManager.PACKAGE_CACHE


    init {
        val intent = Intent()
        intent.setClassName(VirtualCore.get().hostPkg, VASettings.RESOLVER_ACTIVITY)
        mResolveInfo = VirtualCore.get().unHookPackageManager.resolveActivity(intent, 0)
    }

    fun analyzePackageLocked(pkg: VPackage) {
        var N = pkg.activities.size
        for (i in 0..<N) {
            val a = pkg.activities[i]
            if (a.info.processName == null) {
                a.info.processName = a.info.packageName
            }
            mActivities.addActivity(a, "activity")
        }
        N = pkg.services.size
        for (i in 0..<N) {
            val a = pkg.services[i]
            if (a.info.processName == null) {
                a.info.processName = a.info.packageName
            }
            mServices.addService(a)
        }
        N = pkg.receivers.size
        for (i in 0..<N) {
            val a = pkg.receivers[i]
            if (a.info.processName == null) {
                a.info.processName = a.info.packageName
            }
            mReceivers.addActivity(a, "receiver")
        }

        N = pkg.providers.size
        for (i in 0..<N) {
            val p = pkg.providers[i]
            if (p.info.processName == null) {
                p.info.processName = p.info.packageName
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mProviders!!.addProvider(p)
            }
            val names: Array<String?> =
                p.info.authority.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (name in names) {
                if (!mProvidersByAuthority.containsKey(name)) {
                    mProvidersByAuthority[name] = p
                }
            }
            mProvidersByComponent[p.getComponentName()] = p
        }

        N = pkg.permissions.size
        for (i in 0..<N) {
            val permission = pkg.permissions[i]
            mPermissions[permission.className] = permission
        }
        N = pkg.permissionGroups.size
        for (i in 0..<N) {
            val group = pkg.permissionGroups[i]
            mPermissionGroups[group.className] = group
        }
    }

    fun deletePackageLocked(packageName: String?) {
        val pkg = mPackages[packageName] ?: return
        var N = pkg.activities.size
        for (i in 0..<N) {
            val a = pkg.activities[i]
            mActivities.removeActivity(a, "activity")
        }
        N = pkg.services.size
        for (i in 0..<N) {
            val a = pkg.services[i]
            mServices.removeService(a)
        }
        N = pkg.receivers.size
        for (i in 0..<N) {
            val a = pkg.receivers[i]
            mReceivers.removeActivity(a, "receiver")
        }

        N = pkg.providers.size
        for (i in 0..<N) {
            val p = pkg.providers[i]
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mProviders!!.removeProvider(p)
            }
            val names: Array<String?> =
                p.info.authority.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (name in names) {
                mProvidersByAuthority.remove(name)
            }
            mProvidersByComponent.remove(p.getComponentName())
        }

        N = pkg.permissions.size
        for (i in 0..<N) {
            val permission = pkg.permissions[i]
            mPermissions.remove(permission.className)
        }
        N = pkg.permissionGroups.size
        for (i in 0..<N) {
            val group = pkg.permissionGroups[i]
            mPermissionGroups.remove(group.className)
        }
    }

    override fun getSharedLibraries(packageName: String?): MutableList<String?>? {
        synchronized(mPackages) {
            val p = mPackages[packageName]
            if (p != null) {
                val list = ArrayList<String?>()
                if (p.usesLibraries != null) {
                    list.addAll(p.usesLibraries)
                }
                if (p.usesOptionalLibraries != null) {
                    list.addAll(p.usesOptionalLibraries)
                }
                return list
            }
            return null
        }
    }

    override fun checkPermission(permName: String?, pkgName: String?, userId: Int): Int {
        if ("android.permission.INTERACT_ACROSS_USERS" == permName
            || "android.permission.INTERACT_ACROSS_USERS_FULL" == permName
        ) {
            return PackageManager.PERMISSION_DENIED
        }
        return VPermissionManager.get()
            .checkPermission(permName, getPackageUid(pkgName, userId))
    }

    override fun getPackageInfo(packageName: String?, flags: Int, userId: Int): PackageInfo? {
        checkUserId(userId)
        synchronized(mPackages) {
            val p = mPackages[packageName]
            if (p != null) {
                val ps = p.mExtras
                return generatePackageInfo(p, ps, flags, userId)
            }
        }
        return null
    }

    private fun generatePackageInfo(
        p: VPackage?,
        ps: PackageSetting,
        flags: Int,
        userId: Int
    ): PackageInfo? {
        var flags = flags
        flags = updateFlagsNought(flags)
        val packageInfo = PackageParserEx.generatePackageInfo(
            p, flags,
            ps.firstInstallTime, ps.lastUpdateTime, ps.readUserState(userId), userId
        )
        return packageInfo
    }

    private fun updateFlagsNought(flags: Int): Int {
        var flags = flags
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return flags
        }
        if ((flags and (PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    or PackageManager.MATCH_DIRECT_BOOT_AWARE)) != 0
        ) {
            // Caller expressed an explicit opinion about what encryption
            // aware/unaware components they want to see, so fall through and
            // give them what they want
        } else {
            // Caller expressed no opinion, so match based on user state
            flags =
                flags or (PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
        }
        return flags
    }

    @Throws(SecurityException::class)
    private fun checkUserId(userId: Int) {
        if (!VUserManagerService.get().exists(userId)) {
            throw SecurityException("Invalid userId $userId")
        }
    }

    override fun getActivityInfo(component: ComponentName, flags: Int, userId: Int): ActivityInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)
        flags = updateFlagsNought(flags)
        synchronized(mPackages) {
            val p = mPackages[component.packageName]
            if (p != null) {
                val ps = p.mExtras
                val a = mActivities.mActivities[component]
                if (a != null) {
                    val activityInfo = PackageParserEx.generateActivityInfo(
                        a,
                        flags,
                        ps.readUserState(userId),
                        userId
                    )
                    ComponentFixer.fixComponentInfo(ps, activityInfo, userId)
                    return activityInfo
                }
            }
        }
        return null
    }

    override fun activitySupportsIntent(
        component: ComponentName?,
        intent: Intent,
        resolvedType: String?
    ): Boolean {
        synchronized(mPackages) {
            val a = mActivities.mActivities[component] ?: return false
            for (i in a.intents.indices) {
                if (a.intents[i].filter.match(
                        intent.action, resolvedType, intent.scheme, intent.data,
                        intent.categories, TAG
                    ) >= 0
                ) {
                    return true
                }
            }
            return false
        }
    }

    override fun getReceiverInfo(component: ComponentName, flags: Int, userId: Int): ActivityInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)
        flags = updateFlagsNought(flags)
        synchronized(mPackages) {
            val p = mPackages[component.packageName]
            if (p != null) {
                val ps = p.mExtras
                val a = mReceivers.mActivities[component]
                if (a != null) {
                    val receiverInfo = PackageParserEx.generateActivityInfo(
                        a,
                        flags,
                        ps.readUserState(userId),
                        userId
                    )
                    ComponentFixer.fixComponentInfo(ps, receiverInfo, userId)
                    return receiverInfo
                }
            }
        }
        return null
    }

    @Throws(SecurityException::class)
    override fun getServiceInfo(component: ComponentName, flags: Int, userId: Int): ServiceInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        synchronized(mPackages) {
            val p = mPackages[component.packageName]
            if (p != null) {
                val ps = p.mExtras
                val s = mServices.mServices[component]

                if (s != null) {
                    val callingPackage = VBinder.getCallingPackageName()

                    val logMessage: LogMessage = GetService(
                        component.packageName,
                        component.className,
                        s.info.exported
                    )
                    val holder = LogMessageHolder.Builder(logMessage).build()
                    VLoggingManagerService.get().log(holder)

                    if (!s.info.exported && callingPackage != p.packageName) {
                        throw SecurityException("Unable to access non-exported service in " + p.packageName + " from " + callingPackage)
                    }

                    val serviceInfo = PackageParserEx.generateServiceInfo(
                        s,
                        flags,
                        ps.readUserState(userId),
                        userId
                    )
                    ComponentFixer.fixComponentInfo(ps, serviceInfo, userId)
                    return serviceInfo
                }
            }
        }
        return null
    }

    override fun getProviderInfo(component: ComponentName, flags: Int, userId: Int): ProviderInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        synchronized(mPackages) {
            val p = mPackages[component.packageName]
            if (p != null) {
                val ps = p.mExtras
                val provider = mProvidersByComponent[component]
                if (provider != null) {
                    val callingPackage = VBinder.getCallingPackageName()

                    val logMessage: LogMessage = LogMessage.BindService(
                        provider.owner.packageName,
                        component.className,
                        provider.info.exported
                    )
                    val holder = LogMessageHolder.Builder(logMessage).build()
                    VLoggingManagerService.get().log(holder)

                    if (!provider.info.exported && callingPackage != p.packageName) {
                        throw SecurityException("Unable to access non-exported provider in " + p.packageName + " from " + callingPackage)
                    }

                    val providerInfo = PackageParserEx.generateProviderInfo(
                        provider,
                        flags,
                        ps.readUserState(userId),
                        userId
                    )
                    ComponentFixer.fixComponentInfo(ps, providerInfo, userId)
                    return providerInfo
                }
            }
        }
        return null
    }

    override fun resolveIntent(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): ResolveInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)
        flags = updateFlagsNought(flags)
        val query = queryIntentActivities(intent, resolvedType, flags, 0)
        return chooseBestActivity(intent, resolvedType, flags, query)
    }

    private fun chooseBestActivity(
        intent: Intent?,
        resolvedType: String?,
        flags: Int,
        query: List<ResolveInfo>?
    ): ResolveInfo? {
        if (query != null) {
            val N = query.size
            if (N == 1) {
                return query[0]
            } else if (N > 1) {
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                val r0 = query[0]
                val r1 = query[1]
                // If the first activity has a higher priority, or a different
                // default, then it is always desirable to pick it.
                if (r0.priority != r1.priority || r0.preferredOrder != r1.preferredOrder || r0.isDefault != r1.isDefault) {
                    return query[0]
                }

                // If we have saved a preference for a preferred activity for
                // this Intent, use that.
                val ri = findPreferredActivity(
                    intent, resolvedType,
                    flags, query, r0.priority
                )
                if (ri != null) {
                    return ri
                }
                return query[0]
            }
        }
        return null
    }

    private fun findPreferredActivity(
        intent: Intent?,
        resolvedType: String?,
        flags: Int,
        query: List<ResolveInfo>?,
        priority: Int
    ): ResolveInfo? {
        return null
    }

    override fun queryIntentActivities(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): List<ResolveInfo>? {
        var intent = intent
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        var comp = intent.component
        if (comp == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (intent.selector != null) {
                    intent = intent.selector!!
                    comp = intent.component
                }
            }
        }
        if (comp != null) {
            val list: MutableList<ResolveInfo> = ArrayList(1)
            val ai = getActivityInfo(comp, flags, userId)
            if (ai != null) {
                val ri = ResolveInfo()
                ri.activityInfo = ai
                list.add(ri)
            }
            return list
        }

        // reader
        synchronized(mPackages) {
            val pkgName = intent.getPackage() ?: return mActivities.queryIntent(
                intent,
                resolvedType,
                flags,
                userId
            )
            val pkg = mPackages[pkgName]
            if (pkg != null) {
                return mActivities.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.activities,
                    userId
                )
            }
            return emptyList()
        }
    }

    override fun queryIntentReceivers(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): List<ResolveInfo?>? {
        var intent = intent
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        var comp = intent.component
        if (comp == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (intent.selector != null) {
                    intent = intent.selector!!
                    comp = intent.component
                }
            }
        }
        if (comp != null) {
            val list: MutableList<ResolveInfo> = ArrayList(1)
            val ai = getReceiverInfo(comp, flags, userId)
            if (ai != null) {
                val ri = ResolveInfo()
                ri.activityInfo = ai
                list.add(ri)
            }
            return list
        }

        // reader
        synchronized(mPackages) {
            val pkgName = intent.getPackage() ?: return mReceivers.queryIntent(
                intent,
                resolvedType,
                flags,
                userId
            )
            val pkg = mPackages[pkgName]
            if (pkg != null) {
                return mReceivers.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.receivers,
                    userId
                )
            }
            return emptyList()
        }
    }

    override fun resolveService(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): ResolveInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        val query = queryIntentServices(intent, resolvedType, flags, userId)
        if (query != null) {
            if (query.isNotEmpty()) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query[0]
            }
        }
        return null
    }

    override fun queryIntentServices(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): List<ResolveInfo>? {
        var intent = intent
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)
        flags = updateFlagsNought(flags)
        var comp = intent.component
        if (comp == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (intent.selector != null) {
                    intent = intent.selector!!
                    comp = intent.component
                }
            }
        }
        if (comp != null) {
            val list: MutableList<ResolveInfo> = ArrayList(1)
            val si = getServiceInfo(comp, flags, userId)
            if (si != null) {
                val ri = ResolveInfo()
                ri.serviceInfo = si
                list.add(ri)
            }
            return list
        }

        // reader
        synchronized(mPackages) {
            val pkgName = intent.getPackage() ?: return mServices.queryIntent(
                intent,
                resolvedType,
                flags,
                userId
            )
            val pkg = mPackages[pkgName]
            if (pkg != null) {
                return mServices.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.services,
                    userId
                )
            }
            return mutableListOf()
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    override fun queryIntentContentProviders(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): MutableList<ResolveInfo?>? {
        var intent = intent
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        var comp = intent.component
        if (comp == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                if (intent.selector != null) {
                    intent = intent.selector!!
                    comp = intent.component
                }
            }
        }
        if (comp != null) {
            val list: MutableList<ResolveInfo?> = ArrayList(1)
            val pi = getProviderInfo(comp, flags, userId)
            if (pi != null) {
                val ri = ResolveInfo()
                ri.providerInfo = pi
                list.add(ri)
            }
            return list
        }
        // reader
        synchronized(mPackages) {
            val pkgName = intent.getPackage() ?: return mProviders!!.queryIntent(
                intent,
                resolvedType,
                flags,
                userId
            )
            val pkg = mPackages[pkgName]
            if (pkg != null) {
                return mProviders!!.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.providers,
                    userId
                )
            }
            return mutableListOf()
        }
    }

    override fun queryContentProviders(
        processName: String?,
        vuid: Int,
        flags: Int
    ): VParceledListSlice<ProviderInfo?> {
        var flags = flags
        val userId = VUserHandle.getUserId(vuid)
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        val finalList = ArrayList<ProviderInfo?>(3)
        // reader
        synchronized(mPackages) {
            for (p in mProvidersByComponent.values) {
                val ps = p.owner.mExtras
                if (processName == null || ps.appId == VUserHandle.getAppId(vuid) && p.info.processName == processName) {
                    val providerInfo = PackageParserEx.generateProviderInfo(
                        p,
                        flags,
                        ps.readUserState(userId),
                        userId
                    )
                    finalList.add(providerInfo)
                }
            }
        }
        if (finalList.isNotEmpty()) {
            Collections.sort(finalList, sProviderInitOrderSorter)
        }
        return VParceledListSlice(finalList)
    }

    override fun getInstalledPackages(flags: Int, userId: Int): VParceledListSlice<PackageInfo?> {
        checkUserId(userId)
        enforceCallingUser(userId)

        val pkgList = ArrayList<PackageInfo?>(mPackages.size)
        synchronized(mPackages) {
            for (p in mPackages.values) {
                val ps = p.mExtras
                val info = generatePackageInfo(p, ps, flags, userId)
                if (info != null) {
                    pkgList.add(info)
                }
            }
        }
        return VParceledListSlice(pkgList)
    }

    override fun getInstalledApplications(
        flags: Int,
        userId: Int
    ): VParceledListSlice<ApplicationInfo?> {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        val list = ArrayList<ApplicationInfo?>(mPackages.size)
        synchronized(mPackages) {
            for (p in mPackages.values) {
                val ps = p.mExtras
                val info = PackageParserEx.generateApplicationInfo(
                    p,
                    flags,
                    ps.readUserState(userId),
                    userId
                )
                list.add(info)
            }
        }
        return VParceledListSlice(list)
    }

    override fun getPermissionInfo(name: String?, flags: Int): PermissionInfo? {
        synchronized(mPackages) {
            val p = mPermissions[name]
            if (p != null) {
                return PermissionInfo(p.info)
            }
        }
        return null
    }

    override fun queryPermissionsByGroup(
        group: String?,
        flags: Int
    ): MutableList<PermissionInfo?>? {
        synchronized(mPackages) {
            return null
        }
    }

    override fun getPermissionGroupInfo(name: String?, flags: Int): PermissionGroupInfo? {
        synchronized(mPackages) {
            val p = mPermissionGroups[name]
            if (p != null) {
                return PermissionGroupInfo(p.info)
            }
        }
        return null
    }

    override fun getAllPermissionGroups(flags: Int): MutableList<PermissionGroupInfo?> {
        synchronized(mPackages) {
            val N = mPermissionGroups.size
            val out = ArrayList<PermissionGroupInfo?>(N)
            for (pg in mPermissionGroups.values) {
                out.add(PermissionGroupInfo(pg.info))
            }
            return out
        }
    }

    @Throws(SecurityException::class)
    override fun resolveContentProvider(name: String?, flags: Int, userId: Int): ProviderInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        synchronized(mPackages) {
            val provider = mProvidersByAuthority[name]
            if (provider != null) {
                val ps = provider.owner.mExtras
                val providerInfo = PackageParserEx.generateProviderInfo(
                    provider,
                    flags,
                    ps.readUserState(userId),
                    userId
                )
                if (providerInfo != null) {
                    VLoggingClient.get().log(
                        GetProvider(
                            providerInfo.packageName,
                            provider.getComponentName().className,
                            providerInfo.exported
                        )
                    )
                    val p = mPackages[providerInfo.packageName]

                    val callingPackage = VBinder.getCallingPackageName()

                    if (!provider.info.exported && callingPackage != providerInfo.packageName) {
                        throw SecurityException("Unable to access non-exported provider in " + providerInfo.packageName + " from " + callingPackage)
                    }

                    val settings = p!!.mExtras
                    ComponentFixer.fixComponentInfo(settings, providerInfo, userId)
                    return providerInfo
                }
            }
        }
        return null
    }

    override fun getApplicationInfo(
        packageName: String?,
        flags: Int,
        userId: Int
    ): ApplicationInfo? {
        var flags = flags
        checkUserId(userId)
        enforceCallingUser(userId)

        flags = updateFlagsNought(flags)
        synchronized(mPackages) {
            val p = mPackages[packageName]
            if (p != null) {
                val ps = p.mExtras
                return PackageParserEx.generateApplicationInfo(
                    p,
                    flags,
                    ps.readUserState(userId),
                    userId
                )
            }
        }
        return null
    }

    override fun getPackagesForUid(uid: Int): Array<String?> {
        val userId = VUserHandle.getUserId(uid)
        checkUserId(userId)
        enforceCallingUser(userId)

        synchronized(this) {
            val pkgList: MutableList<String?> = ArrayList(2)
            for (p in mPackages.values) {
                val settings = p.mExtras
                if (VUserHandle.getUid(userId, settings.appId) == uid) {
                    pkgList.add(p.packageName)
                }
            }
            return pkgList.toTypedArray<String?>()
        }
    }

    override fun getPackageUid(packageName: String?, userId: Int): Int {
        checkUserId(userId)
        enforceCallingUser(userId)

        synchronized(mPackages) {
            val p = mPackages[packageName]
            if (p != null) {
                val ps = p.mExtras
                return VUserHandle.getUid(userId, ps.appId)
            }
            return -1
        }
    }

    override fun getNameForUid(uid: Int): String? {
        val appId = VUserHandle.getAppId(uid)
        synchronized(mPackages) {
            for (p in mPackages.values) {
                val ps = p.mExtras
                if (ps.appId == appId) {
                    return ps.packageName
                }
            }
            return null
        }
    }


    override fun querySharedPackages(packageName: String?): List<String?> {
        synchronized(mPackages) {
            val p = mPackages[packageName]
            if (p?.mSharedUserId == null) {
                return emptyList()
            }
            val list = ArrayList<String?>()
            for (one in mPackages.values) {
                if (TextUtils.equals(one.mSharedUserId, p.mSharedUserId)) {
                    list.add(one.packageName)
                }
            }
            return list
        }
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    override fun getPackageInstaller(): IPackageInstaller? {
        return VPackageInstallerService.get()
    }

    @Throws(RemoteException::class)
    override fun canRequestPackageInstalls(): Boolean {
        return true
    }

    fun createNewUser(userId: Int, userPath: File?) {
        for (p in mPackages.values) {
            val setting = p.mExtras
            setting.modifyUserState(userId)
        }
    }

    fun cleanUpUser(userId: Int) {
        for (p in mPackages.values) {
            val ps = p.mExtras
            ps.removeUser(userId)
        }
    }

    private class ActivityIntentResolver :
        IntentResolver<VPackage.ActivityIntentInfo, ResolveInfo>() {
        // Keys are String (activity class name), values are Activity.
        val mActivities = HashMap<ComponentName, ActivityComponent>()
        private var mFlags = 0

        override fun queryIntent(
            intent: Intent,
            resolvedType: String?,
            defaultOnly: Boolean,
            userId: Int
        ): MutableList<ResolveInfo?>? {
            mFlags = if (defaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0
            return super.queryIntent(intent, resolvedType, defaultOnly, userId)
        }

        fun queryIntent(
            intent: Intent,
            resolvedType: String?,
            flags: Int,
            userId: Int
        ): List<ResolveInfo>? {
            mFlags = flags
            return super.queryIntent(
                intent,
                resolvedType,
                (flags and PackageManager.MATCH_DEFAULT_ONLY) != 0,
                userId
            )
        }

        fun queryIntentForPackage(
            intent: Intent?, resolvedType: String?, flags: Int,
            packageActivities: ArrayList<ActivityComponent>?, userId: Int
        ): List<ResolveInfo>? {
            if (packageActivities == null) {
                return null
            }
            mFlags = flags
            val defaultOnly = (flags and PackageManager.MATCH_DEFAULT_ONLY) != 0
            val N = packageActivities.size
            val listCut = ArrayList<Array<VPackage.ActivityIntentInfo?>?>(
                N
            )

            var intentFilters: ArrayList<VPackage.ActivityIntentInfo>?
            for (i in 0..<N) {
                intentFilters = packageActivities[i].intents
                if (!intentFilters.isNullOrEmpty()) {
                    val array = arrayOfNulls<VPackage.ActivityIntentInfo>(
                        intentFilters
                            .size
                    )
                    intentFilters.toArray(array)
                    listCut.add(array)
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId)
        }

        fun addActivity(a: ActivityComponent, type: String?) {
            mActivities[a.getComponentName()] = a
            val NI = a.intents.size
            for (j in 0..<NI) {
                val intent = a.intents[j]
                if (intent.filter.priority > 0 && "activity" == type) {
                    intent.filter.priority = 0
                    Timber.tag(TAG).w(("Package " + a.info.applicationInfo.packageName + " has activity " + a.className
                            + " with priority > 0, forcing to 0")
                    )
                }
                addFilter(intent)
            }
        }

        fun removeActivity(a: ActivityComponent, type: String?) {
            mActivities.remove(a.getComponentName())
            val NI = a.intents.size
            for (j in 0..<NI) {
                val intent = a.intents[j]
                removeFilter(intent)
            }
        }

        override fun allowFilterResult(
            filter: VPackage.ActivityIntentInfo?,
            dest: MutableList<ResolveInfo?>
        ): Boolean {
            if (filter == null) {
                return false
            }
            val filterAi = filter.activity.info
            for (i in dest.indices.reversed()) {
                val destAi = dest[i]!!.activityInfo
                if (ObjectsCompat.equals(
                        destAi.name,
                        filterAi.name
                    ) && ObjectsCompat.equals(destAi.packageName, filterAi.packageName)
                ) {
                    return false
                }
            }
            return true
        }

        override fun newArray(size: Int): Array<VPackage.ActivityIntentInfo?> {
            return arrayOfNulls(size)
        }

        override fun isFilterStopped(filter: VPackage.ActivityIntentInfo?): Boolean {
            return false
        }

        override fun isPackageForFilter(
            packageName: String,
            info: VPackage.ActivityIntentInfo?
        ): Boolean {
            return packageName == info?.activity?.owner?.packageName
        }

        protected override fun newResult(
            filter: VPackage.ActivityIntentInfo?,
            match: Int,
            userId: Int
        ): ResolveInfo? {
            enforceCallingUser(userId)
            if (filter == null) {
                return null
            }

            val activity = filter.activity
            val ps = activity?.owner?.mExtras
            val ai = PackageParserEx.generateActivityInfo(
                activity,
                mFlags,
                ps?.readUserState(userId),
                userId
            )
            if (ai == null) {
                return null
            }
            val res = ResolveInfo()
            res.activityInfo = ai
            if ((mFlags and PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter.filter
            }
            res.priority = filter.filter.priority
            res.preferredOrder = activity?.owner?.mPreferredOrder ?: 0
            res.match = match
            res.isDefault = filter.hasDefault
            res.labelRes = filter.labelRes
            res.nonLocalizedLabel = filter.nonLocalizedLabel
            res.icon = filter.icon
            return res
        }

        protected override fun sortResults(results: MutableList<ResolveInfo?>) {
            Collections.sort(results, sResolvePrioritySorter)
        }

        override fun dumpFilter(
            out: PrintWriter?,
            prefix: String?,
            filter: VPackage.ActivityIntentInfo?
        ) {
        }

        override fun filterToLabel(filter: VPackage.ActivityIntentInfo?): Any? {
            return filter?.activity
        }

        override fun dumpFilterLabel(out: PrintWriter?, prefix: String?, label: Any?, count: Int) {
        }
    }

    private class ServiceIntentResolver :
        IntentResolver<VPackage.ServiceIntentInfo?, ResolveInfo?>() {
        // Keys are String (activity class name), values are Activity.
        val mServices = HashMap<ComponentName?, ServiceComponent?>()
        private var mFlags = 0

        override fun queryIntent(
            intent: Intent,
            resolvedType: String?,
            defaultOnly: Boolean,
            userId: Int
        ): MutableList<ResolveInfo?>? {
            mFlags = if (defaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0
            return super.queryIntent(intent, resolvedType, defaultOnly, userId)
        }

        fun queryIntent(
            intent: Intent,
            resolvedType: String?,
            flags: Int,
            userId: Int
        ): List<ResolveInfo>? {
            mFlags = flags
            return super.queryIntent(
                intent,
                resolvedType,
                (flags and PackageManager.MATCH_DEFAULT_ONLY) != 0,
                userId
            )?.filterNotNull()
        }

        fun queryIntentForPackage(
            intent: Intent?, resolvedType: String?, flags: Int,
            packageServices: ArrayList<ServiceComponent>?, userId: Int
        ): List<ResolveInfo>? {
            if (packageServices == null) {
                return null
            }
            mFlags = flags
            val defaultOnly = (flags and PackageManager.MATCH_DEFAULT_ONLY) != 0
            val N = packageServices.size
            val listCut = ArrayList<Array<VPackage.ServiceIntentInfo?>?>(N)

            var intentFilters: ArrayList<VPackage.ServiceIntentInfo?>?
            for (i in 0..<N) {
                intentFilters = packageServices[i].intents
                if (!intentFilters.isNullOrEmpty()) {
                    val array = arrayOfNulls<VPackage.ServiceIntentInfo>(intentFilters.size)
                    intentFilters.toArray(array)
                    listCut.add(array)
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId)?.filterNotNull()
        }

        fun addService(s: ServiceComponent) {
            mServices[s.getComponentName()] = s
            val NI = s.intents.size
            var j = 0
            while (j < NI) {
                val intent = s.intents[j]
                addFilter(intent)
                j++
            }
        }

        fun removeService(s: ServiceComponent) {
            mServices.remove(s.getComponentName())
            val NI = s.intents.size
            var j = 0
            while (j < NI) {
                val intent = s.intents[j]
                removeFilter(intent)
                j++
            }
        }

        override fun allowFilterResult(
            filter: VPackage.ServiceIntentInfo?,
            dest: MutableList<ResolveInfo?>
        ): Boolean {
            if (filter == null) {
                return false
            }

            val filterSi = filter.service.info
            for (i in dest.indices.reversed()) {
                val destAi = dest[i]!!.serviceInfo
                if (ObjectsCompat.equals(destAi.name, filterSi.name)
                    && ObjectsCompat.equals(destAi.packageName, filterSi.packageName)
                ) {
                    return false
                }
            }
            return true
        }

        override fun newArray(size: Int): Array<VPackage.ServiceIntentInfo?> {
            return arrayOfNulls(size)
        }

        override fun isFilterStopped(filter: VPackage.ServiceIntentInfo?): Boolean {
            return false
        }

        override fun isPackageForFilter(
            packageName: String,
            filter: VPackage.ServiceIntentInfo?
        ): Boolean {
            return packageName == filter?.service?.owner?.packageName
        }

        protected override fun newResult(
            filter: VPackage.ServiceIntentInfo?,
            match: Int,
            userId: Int
        ): ResolveInfo? {
            enforceCallingUser(userId)
            if (filter == null) {
                return null
            }

            val service = filter.service
            val ps = service.owner.mExtras
            val si = PackageParserEx.generateServiceInfo(
                service,
                mFlags,
                ps.readUserState(userId),
                userId
            )
            if (si == null) {
                return null
            }
            val res = ResolveInfo()
            res.serviceInfo = si
            if ((mFlags and PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter.filter
            }
            res.priority = filter.filter.priority
            res.preferredOrder = service.owner.mPreferredOrder
            res.match = match
            res.isDefault = filter.hasDefault
            res.labelRes = filter.labelRes
            res.nonLocalizedLabel = filter.nonLocalizedLabel
            res.icon = filter.icon
            return res
        }

        protected override fun sortResults(results: MutableList<ResolveInfo?>) {
            Collections.sort(results, sResolvePrioritySorter)
        }

        override fun dumpFilter(
            out: PrintWriter?,
            prefix: String?,
            filter: VPackage.ServiceIntentInfo?
        ) {
        }

        override fun filterToLabel(filter: VPackage.ServiceIntentInfo?): Any? {
            return filter?.service
        }

        override fun dumpFilterLabel(out: PrintWriter?, prefix: String?, label: Any?, count: Int) {
        }
    }

    companion object {
        const val TAG: String = "PackageManager"
        @JvmField
        val sResolvePrioritySorter: Comparator<ResolveInfo> = object : Comparator<ResolveInfo> {
            override fun compare(r1: ResolveInfo, r2: ResolveInfo): Int {
                var v1 = r1.priority
                var v2 = r2.priority
                if (v1 != v2) {
                    return if (v1 > v2) -1 else 1
                }
                v1 = r1.preferredOrder
                v2 = r2.preferredOrder
                if (v1 != v2) {
                    return if (v1 > v2) -1 else 1
                }
                if (r1.isDefault != r2.isDefault) {
                    return if (r1.isDefault) -1 else 1
                }
                v1 = r1.match
                v2 = r2.match
                if (v1 != v2) {
                    return if (v1 > v2) -1 else 1
                }
                return 0
            }
        }
        private val gService = AtomicReference<VPackageManagerService>()
        private val sProviderInitOrderSorter: Comparator<ProviderInfo> =
            Comparator { p1, p2 ->
                val v1 = p1.initOrder
                val v2 = p2.initOrder
                if (v1 > v2) -1 else (if (v1 < v2) 1 else 0)
            }

        @JvmStatic
        fun systemReady() {
            val instance = VPackageManagerService()
            VUserManagerService(
                VirtualCore.get().context,
                instance,
                CharArray(0),
                instance.mPackages
            )
            gService.set(instance)
        }

        @JvmStatic
        fun get(): VPackageManagerService {
            return gService.get()
        }
    }
}

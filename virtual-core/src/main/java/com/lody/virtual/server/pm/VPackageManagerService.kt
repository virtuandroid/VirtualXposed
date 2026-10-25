package com.lody.virtual.server.pm

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
    private val mProviders = ProviderIntentResolver()

    // mMap[packageName][identifier]
    private val mProvidersByComponent = HashMap<String, HashMap<ComponentName, ProviderComponent>>()
    private val mPermissions = HashMap<String, HashMap<String, PermissionComponent>>()
    private val mPermissionGroups = HashMap<String, HashMap<String, PermissionGroupComponent>>()
    private val mProvidersByAuthority = HashMap<String, HashMap<String, ProviderComponent>>()

    private val mPackages: MutableMap<String?, VPackage> = PackageCacheManager.PACKAGE_CACHE


    init {
        val intent = Intent()
        intent.setClassName(VirtualCore.get().hostPkg, VASettings.RESOLVER_ACTIVITY)
        mResolveInfo = VirtualCore.get().unHookPackageManager.resolveActivity(intent, 0)
    }

    fun analyzePackageLocked(pkg: VPackage) {
        pkg.activities.forEach { a ->
            if (a.info.processName == null) {
                a.info.processName = a.info.packageName
            }
            mActivities.addActivity(a, "activity")
        }

        pkg.services.forEach { a ->
            if (a.info.processName == null) {
                a.info.processName = a.info.packageName
            }
            mServices.addService(a)
        }

        pkg.receivers.forEach { a ->
            if (a.info.processName == null) {
                a.info.processName = a.info.packageName
            }
            mReceivers.addActivity(a, "receiver")
        }

        pkg.providers.forEach { p ->
            if (p.info.processName == null) {
                p.info.processName = p.info.packageName
            }
            mProviders.addProvider(p)
            val names: Array<String> =
                p.info.authority.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            mProvidersByAuthority[pkg.packageName] = HashMap()
            names.forEach { name ->
                mProvidersByAuthority[pkg.packageName]?.set(name, p)
            }
            mProvidersByComponent[pkg.packageName] = HashMap()
            mProvidersByComponent[pkg.packageName]?.set(p.getComponentName(), p)
        }

        mPermissions[pkg.packageName] = HashMap()
        pkg.permissions.forEach { permission ->
            mPermissions[pkg.packageName]?.set(permission.className, permission)
        }

        mPermissionGroups[pkg.packageName] = HashMap()
        pkg.permissionGroups.forEach { group ->
            mPermissionGroups[pkg.packageName]?.set(group.className, group)
        }
    }

    fun deletePackageLocked(packageName: String?) {
        val pkg = mPackages[packageName] ?: return
        pkg.activities.forEach { a ->
            mActivities.removeActivity(a, "activity")
        }

        pkg.services.forEach { a ->
            mServices.removeService(a)
        }

        pkg.receivers.forEach { a ->
            mReceivers.removeActivity(a, "receiver")
        }

        mProvidersByAuthority.remove(pkg.packageName)
        mProvidersByComponent.remove(pkg.packageName)
        pkg.providers.forEach { p ->
            mProviders.removeProvider(p)
        }

        mPermissions.remove(pkg.packageName)
        mPermissionGroups.remove(pkg.packageName)
    }

    override fun getSharedLibraries(packageName: String?): List<String?>? {
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
        enforceCallingUser(userId)
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
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val callingPackage = VBinder.getCallingPackageName()

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
                    // TODO Log non-exported activities

                    if (!activityInfo.exported && callingPackage != p.packageName) {
                        throw SecurityException("Unable to access non-exported activity in " + p.packageName + " from " + callingPackage)
                    }

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
        checkUserId(userId)
        enforceCallingUser(userId)
        val flags = updateFlagsNought(flags)
        val callingPackage = VBinder.getCallingPackageName()

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

                    // TODO Log receivers

                    if (!receiverInfo.exported && callingPackage != p.packageName) {
                        throw SecurityException("Unable to access non-exported receiver in " + p.packageName + " from " + callingPackage)
                    }

                    ComponentFixer.fixComponentInfo(ps, receiverInfo, userId)
                    return receiverInfo
                }
            }
        }
        return null
    }

    @Throws(SecurityException::class)
    override fun getServiceInfo(component: ComponentName, flags: Int, userId: Int): ServiceInfo? {
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val callingPackage = VBinder.getCallingPackageName()

        synchronized(mPackages) {
            val p = mPackages[component.packageName]
            if (p != null) {
                val ps = p.mExtras
                val s = mServices.mServices[component]

                if (s != null) {
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
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val callingPackage = VBinder.getCallingPackageName()

        synchronized(mPackages) {
            val p = mPackages[component.packageName]
            if (p != null) {
                val ps = p.mExtras
                val provider = mProvidersByComponent[component.packageName]?.get(component)
                if (provider != null) {
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
        checkUserId(userId)
        enforceCallingUser(userId)
        val newFlags = updateFlagsNought(flags)
        val query = queryIntentActivities(intent, resolvedType, newFlags, userId)
        return chooseBestActivity(intent, resolvedType, newFlags, query)
    }

    private fun chooseBestActivity(
        intent: Intent?,
        resolvedType: String?,
        flags: Int,
        query: List<ResolveInfo>?
    ): ResolveInfo? {
        if (query != null) {
            val size = query.size
            if (size == 1) {
                return query[0]
            } else if (size > 1) {
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
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val comp = intent.component ?: intent.selector?.component

        // Return the exact activity when a component is specified.
        if (comp != null) {
            val ai = getActivityInfo(comp, flags, userId)
            return if (ai != null) {
                val ri = ResolveInfo()
                ri.activityInfo = ai
                listOf(ri)
            } else {
                emptyList()
            }
        }

        val callingPackage = VBinder.getCallingPackageName()
        synchronized(mPackages) {
            val pkgName = intent.getPackage()
            val pkg = mPackages[pkgName]
            val list = if (pkg != null) {
                mActivities.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.activities,
                    userId
                )
            } else {
                mActivities.queryIntent(
                    intent,
                    resolvedType,
                    flags,
                    userId
                )
            }
            // TODO Missing permission checks
            return list?.filter {
                val info = it.activityInfo ?: it.serviceInfo ?: it.providerInfo
                info.exported || info.packageName == callingPackage
            }
        }
    }

    override fun queryIntentReceivers(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): List<ResolveInfo?>? {
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val comp = intent.component ?: intent.selector?.component

        if (comp != null) {
            val ai = this.getReceiverInfo(comp, flags, userId)
            return if (ai != null) {
                val ri = ResolveInfo()
                ri.activityInfo = ai
                listOf(ri)
            } else {
                emptyList()
            }
        }
        val callingPackage = VBinder.getCallingPackageName()

        synchronized(mPackages) {
            val pkgName = intent.getPackage()
            val pkg = mPackages[pkgName]
            val list = if (pkg != null) {
                mReceivers.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.receivers,
                    userId
                )
            } else {
                mReceivers.queryIntent(
                    intent,
                    resolvedType,
                    flags,
                    userId
                )
            }
            return list?.filter {
                val info = it.activityInfo ?: it.serviceInfo ?: it.providerInfo
                info.exported || info.packageName == callingPackage
            }
        }
    }

    override fun resolveService(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): ResolveInfo? {
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val query = this.queryIntentServices(intent, resolvedType, flags, userId)
        if (query != null) {
            if (query.isNotEmpty()) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.first()
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
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val comp = intent.component ?: intent.selector?.component

        if (comp != null) {
            // The results from this are safe
            val si = this.getServiceInfo(comp, flags, userId)
            return if (si != null) {
                val ri = ResolveInfo()
                ri.serviceInfo = si
                listOf(ri)
            } else {
                emptyList()
            }
        }

        val callingPackage = VBinder.getCallingPackageName()
        synchronized(mPackages) {
            val pkgName = intent.getPackage()
            val pkg = mPackages[pkgName]
            val list = if (pkg != null) {
                mServices.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.services,
                    userId
                )
            } else {
                mServices.queryIntent(
                    intent,
                    resolvedType,
                    flags,
                    userId
                )
            }
            return list?.filter {
                val info = it.serviceInfo ?: it.activityInfo ?: it.providerInfo
                info.exported || info.packageName == callingPackage
            }
        }
    }

    override fun queryIntentContentProviders(
        intent: Intent,
        resolvedType: String?,
        flags: Int,
        userId: Int
    ): List<ResolveInfo>? {
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val comp = intent.component ?: intent.selector?.component

        if (comp != null) {
            // The results from this are safe
            val pi = getProviderInfo(comp, flags, userId)
            return if (pi != null) {
                val ri = ResolveInfo()
                ri.providerInfo = pi
                listOf(ri)
            } else {
                emptyList()
            }
        }

        val callingPackage = VBinder.getCallingPackageName()
        synchronized(mPackages) {
            val pkgName = intent.getPackage()
            val pkg = mPackages[pkgName]
            val list = if (pkg != null) {
                mProviders.queryIntentForPackage(
                    intent,
                    resolvedType,
                    flags,
                    pkg.providers,
                    userId
                )
            } else {
                mProviders.queryIntent(
                    intent,
                    resolvedType,
                    flags,
                    userId
                )
            }
            return list?.filter {
                val info = it.providerInfo ?: it.serviceInfo ?: it.activityInfo
                info.exported || info.packageName == callingPackage
            }
        }
    }

    override fun queryContentProviders(
        processName: String?,
        uid: Int,
        flags: Int
    ): VParceledListSlice<ProviderInfo?> {
        val userId = VUserHandle.getUserId(uid)
        checkUserId(userId)
        enforceCallingUser(userId)

        val callingPackage = VBinder.getCallingPackageName()
        val newFlags = updateFlagsNought(flags)
        val providerList = mutableListOf<ProviderInfo>()

        synchronized(mPackages) {
            mProvidersByComponent.values.forEach {
                it.values.forEach { p ->
                    val ps = p.owner.mExtras
                    if (processName == null || ps.appId == VUserHandle.getAppId(uid) && p.info.processName == processName) {
                        val providerInfo = PackageParserEx.generateProviderInfo(
                            p,
                            newFlags,
                            ps.readUserState(userId),
                            userId
                        )
                        providerList.add(providerInfo)
                    }
                }
            }
        }
        if (providerList.isNotEmpty()) {
            Collections.sort(providerList, sProviderInitOrderSorter)
        }

        // Do not return non-exported providers
        val finalList = providerList.filter { it.exported || it.packageName == callingPackage }
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

    // TODO implement package scoping
    override fun getInstalledApplications(
        flags: Int,
        userId: Int
    ): VParceledListSlice<ApplicationInfo?> {
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
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
        val callingPackage = VBinder.getCallingPackageName()
        synchronized(mPackages) {
            val p = mPermissions[callingPackage]?.get(name)
            if (p != null) {
                @Suppress("DEPRECATION")
                return PermissionInfo(p.info)
            }
        }
        return null
    }

    override fun queryPermissionsByGroup(
        group: String?,
        flags: Int
    ): List<PermissionInfo?>? {
        synchronized(mPackages) {
            return null
        }
    }

    override fun getPermissionGroupInfo(name: String?, flags: Int): PermissionGroupInfo? {
        val callingPackage = VBinder.getCallingPackageName()
        synchronized(mPackages) {
            val p = mPermissionGroups[callingPackage]?.get(name)
            if (p != null) {
                @Suppress("DEPRECATION")
                return PermissionGroupInfo(p.info)
            }
        }
        return null
    }

    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo>? {
        val callingPackage = VBinder.getCallingPackageName()
        synchronized(mPackages) {
            return mPermissionGroups[callingPackage]?.values?.map { pg ->
                @Suppress("DEPRECATION")
                PermissionGroupInfo(pg.info)
            }
        }
    }

    @Throws(SecurityException::class)
    override fun resolveContentProvider(name: String?, flags: Int, userId: Int): ProviderInfo? {
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
        val callingPackage = VBinder.getCallingPackageName()

        synchronized(mPackages) {
            val provider = mProvidersByAuthority[callingPackage]?.get(name) ?: mProvidersByAuthority.firstNotNullOfOrNull {
                it.value[name]
            }

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

                    if (!provider.info.exported && callingPackage != providerInfo.packageName) {
                        throw SecurityException("Unable to access non-exported provider in " + providerInfo.packageName + " from " + callingPackage)
                    }

                    val settings = p?.mExtras
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
        checkUserId(userId)
        enforceCallingUser(userId)

        val flags = updateFlagsNought(flags)
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
            return mPackages.values.filter { p ->
                val settings = p.mExtras
                VUserHandle.getUid(userId, settings.appId) == uid
            }.map { it.packageName }.toTypedArray()
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
        ): List<ResolveInfo>? {
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

            val listCut = packageActivities.mapNotNull { activityComponent ->
                val intentFilters =
                    activityComponent.intents.ifEmpty { null } ?: return@mapNotNull null
                intentFilters.toTypedArray()
            }

            return super.queryIntentFromList(
                intent,
                resolvedType,
                defaultOnly,
                ArrayList(listCut),
                userId
            )
        }

        fun addActivity(a: ActivityComponent, type: String?) {
            mActivities[a.getComponentName()] = a
            a.intents.forEach { intent ->
                if (intent.filter.priority > 0 && "activity" == type) {
                    intent.filter.priority = 0
                    Timber.tag(TAG).w(
                        ("Package " + a.info.applicationInfo.packageName + " has activity " + a.className
                                + " with priority > 0, forcing to 0")
                    )
                }
                addFilter(intent)
            }
        }

        fun removeActivity(a: ActivityComponent, type: String?) {
            mActivities.remove(a.getComponentName())
            a.intents.forEach { intent ->
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
        ): List<ResolveInfo>? {
            mFlags = if (defaultOnly) PackageManager.MATCH_DEFAULT_ONLY else 0
            return super.queryIntent(intent, resolvedType, defaultOnly, userId)?.filterNotNull()
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
            val caller = VBinder.getCallingPackageName()
            mFlags = flags
            val defaultOnly = (flags and PackageManager.MATCH_DEFAULT_ONLY) != 0
            val listCut = packageServices.mapNotNull { serviceComponent ->
                val intentFilters =
                    serviceComponent.intents.ifEmpty { null } ?: return@mapNotNull null
                intentFilters.toTypedArray()
            }

            return super.queryIntentFromList(
                intent,
                resolvedType,
                defaultOnly,
                ArrayList(listCut),
                userId
            )?.filterNotNull()?.filter {
                val info = it.activityInfo ?: it.serviceInfo ?: it.providerInfo
                info.exported || info.packageName == caller
            }
        }

        fun addService(s: ServiceComponent) {
            mServices[s.getComponentName()] = s
            s.intents.forEach {
                addFilter(it)
            }
        }

        fun removeService(s: ServiceComponent) {
            mServices.remove(s.getComponentName())
            s.intents.forEach {
                removeFilter(it)
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

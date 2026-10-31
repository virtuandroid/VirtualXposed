package com.lody.virtual.server.permission

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.server.pm.SignUtils
import com.lody.virtual.server.pm.parser.VPackage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * @author Alberto Lazari
 */
class VPermissionManager private constructor() {
    private val permissionCache = PermissionCache(
        VEnvironment.getPermissionFile(), PermissionFileParser()
    )

    /**
     * Prepare the environment.
     * To call during a virtual app startup process.
     */
    @Synchronized
    fun systemReady() {
        // Initialize the permission cache
        permissionCache.init()

        // Reset granted once status from all runtime permissions
        permissionCache.update { permissions: MutableMap<Int, AppPermissions>? ->
            // Iterate on all virtual apps' permissions
            permissions!!.values.forEach { appPermissions: AppPermissions? ->
                appPermissions!!.getAllPermissions(RuntimePermission::class.java)
                    .forEach { (_: String?, permission: RuntimePermission?) ->
                        if (permission!!.isGrantedOnce) {
                            // Reset status to revert granted once
                            permission.setStatus(Permission.Status.ALWAYS_ASK)
                        }
                    }
            }
        }
    }


    /**
     * @return App permissions for @uid.
     * @throws SecurityException if a VUID is trying to access permissions for a different @uid.
     */
    fun getAppPermissions(uid: Int): AppPermissions? {
        return permissionCache.read(uid) { it }
    }

    /**
     * @return @permission for @uid, or `null` if not declared.
     */
    fun getPermission(permission: String?, uid: Int): Permission? {
        return getAppPermissions(uid)!!.getPermission(permission)
    }

    fun getDeclaredPermission(permission: String): Pair<Int, InstallPermission>? {
        return permissionCache.readAll()
            ?.firstNotNullOfOrNull { (uid, appPermissions) ->
                val allAppPermissions =
                    appPermissions.getAllPermissions(InstallPermission::class.java)
                val matchingPermission = allAppPermissions.values.firstOrNull {
                    it?.name == permission && it.getStatus() == Permission.Status.DECLARED
                }
                matchingPermission?.let { uid to it }
            }
    }

    /**
     * @return @permission for @uid, or `null` if not declared.
     */
    fun <T : Permission> getPermission(permission: String?, uid: Int, type: Class<T>): T? {
        return getAppPermissions(uid)!!.getPermission(permission) as? T
    }

    /**
     * @return @permission of @type for @uid, or `null` if not declared.
     */
    @JvmName("getPermissionInline")
    inline fun <reified T : Permission> getPermission(
        permission: String?, uid: Int,
    ): T? {
        return getAppPermissions(uid)!!.getPermission<T?>(permission)
    }

    /**
     * Initialize permissions for a new @uid, reading them from the application Manifest XML.
     */
    fun initPermissionsForUid(uid: Int, pkg: VPackage) {
        permissionCache.update { permissions: MutableMap<Int, AppPermissions>? ->
            val set = pkg.requestedPermissions.toSet()
            val appPermissions = createAppPermissions(uid, set, pkg.permissions)
            permissions!![uid] = appPermissions
        }
    }

    /**
     * Remove permissions for @uid.
     */
    fun removePermissionsForUid(uid: Int) {
        permissionCache.update { permissions: MutableMap<Int, AppPermissions>? ->
            permissions!!.remove(uid)
        }
    }

    /**
     * Check whether @permission of any type is granted for @uid.
     * 
     * @return - PackageManager.PERMISSION_GRANTED if permission is currently granted
     * - PackageManager.PERMISSION_DENIED otherwise
     */
    fun checkPermission(permission: String?, uid: Int): Int {
        val realPermission = getPermission(permission, uid)
        if (permission == null || realPermission == null) {
            return PackageManager.PERMISSION_DENIED
        }
        return if (realPermission.isGranted())
            PackageManager.PERMISSION_GRANTED
        else
            PackageManager.PERMISSION_DENIED
    }

    /**
     * @param permissionName Runtime permission to investigate.
     * @return true if an explanation about why the app needs a particular permission should be
     * shown: i.e. the user already denied the permission once (but not permanently).
     */
    fun shouldShowRequestPermissionRationale(
        permissionName: String?,
        uid: Int
    ): Boolean {
        val permission = getPermission<RuntimePermission>(permissionName, uid)
        return permission != null && permission.shouldShowRequestPermissionRationale()
    }

    /**
     * Update @permissionName for a specific @uid.
     */
    fun <T : Permission> updatePermission(
        type: Class<T>,
        permissionName: String?, uid: Int,
        operation: (T) -> Unit?
    ) {
        updatePermission(type, permissionName, uid, operation)
    }

    /**
     * Update @permissionName of @type for a specific @uid.
     */
    @JvmName("updatePermissionInline")
    private inline fun <reified T : Permission> updatePermission(
        permissionName: String?, uid: Int, crossinline operation: (T) -> Unit
    ) {
        permissionCache.update(uid) { appPermissions: AppPermissions ->
            val permission = appPermissions.getPermission<T>(permissionName)
                ?: // Do not perform operation if permission not found
                return@update

            operation.invoke(permission)

            if (AUTO_GRANT_MAP.containsKey(permissionName)
                && permission is RuntimePermission
            ) {
                // Weaker permission should be set to the same exact status as the strong one
                val weakPermission = appPermissions.getPermission<RuntimePermission>(
                    AUTO_GRANT_MAP[permissionName]
                )
                if (weakPermission != null && (!weakPermission.isGranted() || permission.getStatus() == Permission.Status.GRANTED)) {
                    // Update weak permission to match stronger one
                    appPermissions.updatePermission(
                        RuntimePermission(
                            weakPermission.name,
                            weakPermission.permissionGroup, permission
                        )
                    )
                }
            }
            if (AUTO_GRANT_MAP.containsValue(permissionName)
                && permission is RuntimePermission
            ) {
                // Stronger permissions should not be allowed as well
                AUTO_GRANT_MAP.forEach { (strongPermissionName: String?, weakPermissionName: String?) ->
                    if (weakPermissionName != permissionName) {
                        return@forEach
                    }
                    val strongPermission = appPermissions.getPermission<RuntimePermission?>(
                        strongPermissionName
                    )
                    if (strongPermission != null && strongPermission.isGranted()) {
                        strongPermission.setDeniedOnce()
                    }
                }
            }
        }
    }

    /**
     * Allow a runtime permission (or its group).
     */
    fun allowPermission(permissionName: String?, uid: Int) {
        updatePermission(permissionName, uid) { permission: Permission? ->
            permission!!.setStatus(Permission.Status.GRANTED)
            if (permission is PermissionGroup) {
                permission.getPermissions()
                    .filter { runtimePermission: RuntimePermission -> !runtimePermission.isOverridden }
                    .forEach { runtimePermission: RuntimePermission ->
                        // When granting a group, grant all permissions inside of it
                        runtimePermission.setStatus(Permission.Status.GRANTED)
                    }
            }
        }
    }

    /**
     * Allow a runtime permission for the current app execution (or its group).
     */
    fun allowPermissionOnce(permissionName: String?, uid: Int) {
        updatePermission<RuntimePermission>(
            permissionName,
            uid
        ) { permission: RuntimePermission? ->
            permission!!.grantOnce()
        }
    }

    /**
     * Reflect the intention of the user of not allowing a permission (or a group).
     * If a runtime permission has been denied once in the past it will be permanently denied.
     */
    fun doNotAllowPermission(permissionName: String?, uid: Int) {
        updatePermission(permissionName, uid) { permission: Permission? ->
            if (permission is InstallPermission) {
                // Simply deny install permissions
                permission.setStatus(Permission.Status.DENIED)
            } else if (permission is PermissionGroup) {
                // Set as unrequested, the individual runtime permissions will reflect the fact
                // that the group has been denied.
                // This allows to permissions to display the dialog, which is lines up with Android
                permission.setStatus(Permission.Status.UNREQUESTED)
                permission.getPermissions()
                    .filter { runtimePermission: RuntimePermission? -> !runtimePermission!!.isOverridden }
                    .forEach { runtimePermission: RuntimePermission? ->
                        // Set all (non-overridden) runtime permissions in the group as denied once
                        runtimePermission!!.setDeniedOnce()
                    }
            } else if (permission is RuntimePermission) {
                // Check if permission had been denied once
                if (permission.shouldShowRequestPermissionRationale()) {
                    // RuntimePermission's setStatus will automatically set the group status
                    permission.setStatus(Permission.Status.DENIED)
                } else {
                    permission.setDeniedOnce()
                }
            }
        }
    }


    /**
     * Build a proper `AppPermissions` object from a set of permissions declared in a manifest.
     * Steps to take:
     * - Get permission type based on which permissions are present on the system
     * - Set default status based on permission type
     * - Update @permissionGroupMap when finding a new permission group
     */
    // TODO Fix declared permissions
    private fun createAppPermissions(
        uid: Int,
        requestedPermissions: Set<String>,
        declaredPermissions: List<VPackage.PermissionComponent>
    ): AppPermissions {
        val appPermissions = HashMap<String?, Permission>()

        declaredPermissions.forEach { permissionComponent ->
            val protection = permissionComponent.info.protection
            val permissionName = permissionComponent.info.name
            val permission = when (protection) {
                PermissionInfo.PROTECTION_SIGNATURE, PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> {
                    val permission = getDeclaredPermission(permissionName)

                    if (permission != null && uid != permission.first) {
                        throw SecurityException("Cannot declare $permissionName multiple times!")
                    }

                    InstallPermission(
                        permissionName,
                        Permission.Status.DECLARED
                    )
                }

                else -> {
                    VLog.i(TAG, "Unsupported protection level for permission %s", permissionName)
                    InstallPermission(permissionName, Permission.Status.DENIED)
                }
            }
            appPermissions[permissionName] = permission
        }

        requestedPermissions.forEach { permissionName: String ->
            var protection: Int?
            try {
                protection = VirtualCore.getPM()
                    .getPermissionInfo(permissionName, 0)
                    ?.protection
            } catch (_: PackageManager.NameNotFoundException) {
                VLog.w(TAG, "Permission not found: %s", permissionName)
                // Unknown permissions are treated as denied install-time permissions
                appPermissions[permissionName] = InstallPermission(
                    permissionName, Permission.Status.DENIED
                )
                return@forEach
            }

            val permission = when (protection) {
                PermissionInfo.PROTECTION_NORMAL -> InstallPermission(
                    permissionName,
                    Permission.Status.GRANTED
                )

                PermissionInfo.PROTECTION_SIGNATURE, PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM -> {
                    val permission = getDeclaredPermission(permissionName)

                    val status = if (permission != null) {
                        if (SignUtils.signatureMatches(permission.first, uid)) {
                            Permission.Status.GRANTED
                        } else {
                            throw SecurityException("Package cannot request declared permission with mismatched signatures.")
                        }
                    } else {
                        Permission.Status.DENIED
                    }

                    InstallPermission(
                        permissionName,
                        status
                    )
                }

                PermissionInfo.PROTECTION_DANGEROUS -> {
                    val groupName: String? = getPermissionGroup(permissionName)
                    if (groupName == null // Manage location permissions individually
                        || groupName == Manifest.permission_group.LOCATION
                        || BACKGROUND_PERMISSIONS.contains(permissionName)
                    ) {
                        VLog.i(TAG, "%s not in a permission group", permissionName)
                        val runtimePermission = RuntimePermission(permissionName)
                        if (permissionName == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
                            // Background location should show rationale by default
                            runtimePermission.setDeniedOnce()
                        }
                        runtimePermission
                    } else {
                        val group = PermissionGroup(groupName)
                        appPermissions[groupName] = group
                        val runtimePermission = RuntimePermission(permissionName, group)
                        group.addPermission(runtimePermission)

                        runtimePermission
                    }
                }

                else -> {
                    VLog.i(TAG, "Unsupported protection level for permission %s", permissionName)
                    InstallPermission(permissionName, Permission.Status.DENIED)
                }
            }
            appPermissions[permissionName] = permission
        }
        return AppPermissions(appPermissions)
    }

    companion object {
        private val TAG: String = VPermissionManager::class.java.getSimpleName()
        private val INSTANCE = VPermissionManager()

        // Permissions exceptions
        @JvmField
        val LOCATION_PERMISSIONS = setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

        @JvmField
        val BACKGROUND_PERMISSIONS = setOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.BODY_SENSORS_BACKGROUND
        )

        // When key is being granted, value should be too
        @JvmField
        val AUTO_GRANT_MAP: Map<String, String> = mapOf(
            Manifest.permission.ACCESS_FINE_LOCATION to Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION to Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS_BACKGROUND to Manifest.permission.BODY_SENSORS,
        )

        @JvmStatic
        fun get(): VPermissionManager {
            return INSTANCE
        }

        /**
         * @return Group name of @permission, or `null` if not in any group
         */
        private fun getPermissionGroup(permission: String): String? {
            val groupFuture = CompletableFuture<String?>()
            val ctx = VirtualCore.get().context
            ctx.packageManager
                .getGroupOfPlatformPermission(
                    permission,
                    ctx.mainExecutor
                ) { group: String? ->
                    groupFuture.complete(group)
                }
            try {
                return groupFuture.get()
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            return null
        }
    }
}

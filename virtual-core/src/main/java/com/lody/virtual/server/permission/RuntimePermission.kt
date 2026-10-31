package com.lody.virtual.server.permission

import android.Manifest
import android.content.pm.PackageManager
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.utils.VLog

/**
 * @author Alberto Lazari
 * Runtime permissions are granted at runtime.
 * They inherit their permission group status (if any), unless explicitly overridden in settings.
 * They can have addtional flags.
 */
class RuntimePermission : Permission {
    val permissionGroup: PermissionGroup?
    private var flags = 0

    constructor(name: String?) : super(name) {
        status = Status.UNREQUESTED
        this.permissionGroup = null
        flags = flags or FLAG_OVERRIDE
    }

    constructor(name: String?, group: PermissionGroup?) : super(name) {
        status = Status.UNREQUESTED
        this.permissionGroup = group
        if (group == null) {
            flags = flags or FLAG_OVERRIDE
        }
    }

    constructor(
        name: String?, group: PermissionGroup?,
        permission: RuntimePermission
    ) : super(name) {
        status = permission.status
        this.permissionGroup = group
        flags = permission.flags
        if (group == null) {
            flags = flags or FLAG_OVERRIDE
        }
        ensureStatusValid()
    }

    constructor(name: String?, permission: RuntimePermission) : this(
        name,
        permission.permissionGroup,
        permission
    )

    internal constructor(
        name: String?, group: PermissionGroup?,
        statusString: String
    ) : super(name) {
        this.permissionGroup = group
        setStatusFromString(statusString)
    }

    override fun isValidStatus(status: Status?): Boolean {
        if (!hasPermissionGroup() && !this.isOverridden) {
            VLog.e(TAG, "Runtime permission without group is not overridden")
            return false
        }
        if (shouldShowRequestPermissionRationale() && status != Status.UNREQUESTED) {
            VLog.e(TAG, "Runtime permission has been denied once, but it's not UNREQUESTED")
            return false
        }
        if (this.isGrantedOnce && status != Status.ALWAYS_ASK) {
            VLog.e(TAG, "Runtime permission has been granted once, but it's not set to ALWAYS_ASK")
            return false
        }
        if (!this.isOverridden && permissionGroup!!.getStatus() == Status.DENIED && isGranted()) {
            VLog.e(TAG, "Runtime permission is granted, but its group is not")
            return false
        }
        // If not having a background permission it cannot be set to ALWAYS_ASK.
        // Ignore: this is true in Android, but for virtual apps allow finer control.
        // if (status == Status.ALWAYS_ASK && !hasBackgroundPermission()) {
        //     return false;
        // }
        return true
    }

    override fun setStatus(newStatus: Status) {
        if (!this.isOverridden) {
            permissionGroup!!.setStatus(newStatus)
        }
        // Clear flags when moving to a more permissive or restrictive status
        if (newStatus != Status.UNREQUESTED) {
            flags = flags and FLAG_DENIED_ONCE.inv()
        }
        flags = flags and FLAG_GRANTED_ONCE.inv()
        super.setStatus(newStatus)
    }

    override fun isGranted(): Boolean {
        if (this.permissionGroup == null) {
            return false
        }
        return when (status) {
            Status.GRANTED -> this.isOverridden || permissionGroup.isGranted()
            Status.ALWAYS_ASK -> this.isGrantedOnce
                    || (hasPermissionGroup() && permissionGroup.isGranted())

            else -> false
        }
    }

    fun shouldShowRequestPermissionRationale(): Boolean {
        return (flags and FLAG_DENIED_ONCE) != 0
    }

    val isGrantedOnce: Boolean
        get() = (flags and FLAG_GRANTED_ONCE) != 0

    val isOverridden: Boolean
        get() = (flags and FLAG_OVERRIDE) != 0

    fun setDeniedOnce() {
        setStatus(Status.UNREQUESTED)
        flags = flags or FLAG_DENIED_ONCE
    }

    fun grantOnce() {
        setStatus(Status.ALWAYS_ASK)
        flags = flags or FLAG_GRANTED_ONCE
    }

    fun override() {
        flags = flags or FLAG_OVERRIDE
        ensureStatusValid()
    }

    fun followGroup() {
        flags = flags and FLAG_OVERRIDE.inv()
        ensureStatusValid()
    }


    fun hasPermissionGroup(): Boolean {
        return this.permissionGroup != null
    }

    val groupIconRes: Int
        /**
         * @return permission group icon drawable resource identifier.
         */
        get() {
            var groupName: String? = null
            if (hasPermissionGroup()) {
                return permissionGroup!!.groupIconRes
                // Manage exceptions
            } else if (VPermissionManager.LOCATION_PERMISSIONS.contains(name)) {
                groupName = Manifest.permission_group.LOCATION
            } else if (name == Manifest.permission.BODY_SENSORS_BACKGROUND) {
                groupName = Manifest.permission_group.SENSORS
            }
            return PermissionGroup.getGroupIconRes(groupName)
        }

    fun hasBackgroundPermission(): Boolean {
        val pm = VirtualCore.getPM()
        try {
            val name = this.name ?: return false
            val permissionInfo = pm.getPermissionInfo(name, 0)
            return mirror.android.content.pm.PermissionInfo.backgroundPermission.get(permissionInfo) != null
        } catch (_: PackageManager.NameNotFoundException) {
        }
        return false
    }

    /**
     * @return true if the permission request dialog needs to be displayed.
     */
    fun needsRequestDialog(): Boolean {
        if (this.permissionGroup == null) return !isGranted()
        return when (status) {
            Status.UNREQUESTED -> this.isOverridden || !permissionGroup.isGranted()
            Status.DENIED -> false
            else -> !isGranted()
        }
    }

    override fun statusToString(): String {
        val statusString = StringBuilder()
        statusString.append(super.statusToString())
        if (shouldShowRequestPermissionRationale()) {
            statusString.append("|deniedOnce")
        }
        if (this.isGrantedOnce) {
            statusString.append("|grantedOnce")
        }
        if (this.isOverridden) {
            statusString.append("|override")
        }
        return statusString.toString()
    }

    override fun setStatusFromString(statusString: String?) {
        val parts: Array<String> =
            statusString?.split("\\|".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray()
                ?: emptyArray()
        // Clear flags
        flags = 0
        for (i in 1..<parts.size) {
            when (val flag: String = parts[i]) {
                "deniedOnce" -> {
                    flags = flags or FLAG_DENIED_ONCE
                }

                "grantedOnce" -> {
                    flags = flags or FLAG_GRANTED_ONCE
                }

                "override" -> {
                    flags = flags or FLAG_OVERRIDE
                }

                else -> {
                    throw IllegalArgumentException("Unknown flag: $flag")
                }
            }
        }
        if (this.permissionGroup == null) {
            flags = flags or FLAG_OVERRIDE
        }
        super.setStatusFromString(parts[0])
    }

    override fun toString(): String {
        return String.format(
            "%s{name='%s', status='%s', group=%s}", javaClass.getSimpleName(),
            name, statusToString(), if (hasPermissionGroup()) permissionGroup.toString() else "null"
        )
    }

    companion object {
        private const val FLAG_DENIED_ONCE = 1 shl 0
        private const val FLAG_GRANTED_ONCE = 1 shl 1
        private const val FLAG_OVERRIDE = 1 shl 2
    }
}

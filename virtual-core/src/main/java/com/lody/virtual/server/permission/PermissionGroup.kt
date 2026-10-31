package com.lody.virtual.server.permission

import com.lody.virtual.client.core.VirtualCore
import java.util.Locale

/**
 * @author Alberto Lazari
 * Permission groups are a set gathering runtime permissions that are similar in purpose.
 */
class PermissionGroup : Permission {
    // Runtime permissions referencing this group
   private val permissions: MutableSet<RuntimePermission>

    constructor(name: String?) : super(name, Status.UNREQUESTED) {
        permissions = HashSet()
    }

    constructor(name: String?, permissions: MutableSet<RuntimePermission>) : super(
        name,
        Status.UNREQUESTED
    ) {
        this.permissions = permissions
    }

    constructor(name: String?, permission: PermissionGroup) : super(name, permission.status!!) {
        // Use shallow copy of permissions instead of reference
        this.permissions = HashSet(permission.permissions)
    }

    internal constructor(name: String?, statusString: String?) : super(name, statusString) {
        permissions = HashSet()
    }

    override fun isValidStatus(status: Status?): Boolean {
        return true
    }

    val groupIconRes: Int
        /**
         * @return This group icon drawable resource identifier
         */
        get() = getGroupIconRes(name)

    fun getPermissions(): Set<RuntimePermission> {
        return HashSet(permissions)
    }

    fun addPermission(permission: RuntimePermission) {
        if (!VPermissionManager.BACKGROUND_PERMISSIONS.contains(permission.name)) {
            // Do not manage background permissions with groups
            permissions.add(permission)
        }
    }

    companion object {
        /**
         * @return @groupName icon drawable resource identifier, or the default one if group is `null`
         */
        @JvmStatic
        fun getGroupIconRes(group: String?): Int {
            val resources = VirtualCore.get()
                .context
                .resources
            val defaultId = resources.getIdentifier("ic_perm_device_info", "drawable", "android")
            if (group == null) {
                return defaultId
            }
            val groupName = group
                .replace("[a-z0-9.-]*".toRegex(), "")
                .lowercase(Locale.getDefault())
            // Handle exceptions
            val res = when (groupName) {
                "phone" -> "perm_group_phone_calls"
                "notifications" -> "ic_notifications_alerted"
                else -> "perm_group_$groupName"
            }
            val id = resources.getIdentifier(res, "drawable", "android")
            if (id == 0) {
                // Use default permission icon if id not found
                return defaultId
            }
            return id
        }
    }
}

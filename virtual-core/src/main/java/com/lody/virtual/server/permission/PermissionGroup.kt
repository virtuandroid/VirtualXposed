package com.lody.virtual.server.permission;

import androidx.annotation.Nullable;

import com.lody.virtual.client.core.VirtualCore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * @author Alberto Lazari
 * Permission groups are a set gathering runtime permissions that are similar in purpose.
 */
public class PermissionGroup extends Permission {

    // Runtime permissions referencing this group
    private final Set<RuntimePermission> permissions;

    public PermissionGroup(final String name) {
        super(name, Status.UNREQUESTED);
        permissions = new HashSet<>();
    }

    public PermissionGroup(final String name, final Set<RuntimePermission> permissions) {
        super(name, Status.UNREQUESTED);
        Objects.requireNonNull(permissions);
        this.permissions = permissions;
    }

    public PermissionGroup(final String name, final PermissionGroup permission) {
        super(name, permission.status);
        // Use shallow copy of permissions instead of reference
        this.permissions = new HashSet<>(permission.permissions);
    }

    PermissionGroup(final String name, final String statusString) {
        super(name, statusString);
        permissions = new HashSet<>();
    }

    @Override
    public boolean isValidStatus(final Status status) {
        return true;
    }

    /**
     * @return @groupName icon drawable resource identifier, or the default one if group is `null`
     */
    public static int getGroupIconRes(final @Nullable String group) {
        final var resources = VirtualCore.get()
            .getContext()
            .getResources();
        final int defaultId = resources.getIdentifier("ic_perm_device_info", "drawable", "android");
        if (group == null) {
            return defaultId;
        }
        final var groupName = group
            .replaceAll("[a-z0-9.-]*", "")
            .toLowerCase();
        // Handle exceptions
        final var res = switch (groupName) {
            case "phone" -> "perm_group_phone_calls";
            case "notifications" -> "ic_notifications_alerted";
            default -> "perm_group_" + groupName;
        };
        final int id = resources.getIdentifier(res, "drawable", "android");
        if (id == 0) {
            // Use default permission icon if id not found
            return defaultId;
        }
        return id;
    }

    /**
     * @return This group icon drawable resource identifier
     */
    public int getGroupIconRes() {
        return getGroupIconRes(name);
    }

    public Set<RuntimePermission> getPermissions() {
        return new HashSet<>(permissions);
    }

    public void addPermission(final RuntimePermission permission) {
        Objects.requireNonNull(permission);
        if (!VPermissionManager.BACKGROUND_PERMISSIONS.contains(permission)) {
            // Do not manage background permissions with groups
            permissions.add(permission);
        }
    }
}

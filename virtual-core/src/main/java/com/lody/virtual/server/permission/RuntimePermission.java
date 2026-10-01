package com.lody.virtual.server.permission;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.server.permission.VPermissionManager;

import mirror.android.content.pm.PermissionInfo;

import java.util.Objects;

/**
 * @author Alberto Lazari
 * Runtime permissions are granted at runtime.
 * They inherit their permission group status (if any), unless explicitly overridden in settings.
 * They can have addtional flags.
 */
public class RuntimePermission extends Permission {

    private static final int FLAG_DENIED_ONCE   = 1 << 0;
    private static final int FLAG_GRANTED_ONCE  = 1 << 1;
    private static final int FLAG_OVERRIDE      = 1 << 2;

    private final @Nullable PermissionGroup group;
    private int flags = 0;

    public RuntimePermission(final String name) {
        super(name);
        status = Status.UNREQUESTED;
        group = null;
        flags |= FLAG_OVERRIDE;
    }

    public RuntimePermission(final String name, final @Nullable PermissionGroup group) {
        super(name);
        status = Status.UNREQUESTED;
        this.group = group;
        if (group == null) {
            flags |= FLAG_OVERRIDE;
        }
    }

    public RuntimePermission(final String name, final @Nullable PermissionGroup group,
            final RuntimePermission permission) {

        super(name);
        status = permission.status;
        this.group = group;
        flags = permission.flags;
        if (group == null) {
            flags |= FLAG_OVERRIDE;
        }
        ensureStatusValid();
    }

    public RuntimePermission(final String name, final RuntimePermission permission) {
        this(name, permission.group, permission);
    }

    RuntimePermission(final String name, final @Nullable PermissionGroup group,
            final String statusString) {

        super(name);
        this.group = group;
        setStatusFromString(statusString);
    }

    @Override
    public boolean isValidStatus(final Status status) {
        if (!hasPermissionGroup() && !isOverridden()) {
            VLog.e(TAG, "Runtime permission without group is not overridden");
            return false;
        }
        if (shouldShowRequestPermissionRationale() && status != Status.UNREQUESTED) {
            VLog.e(TAG, "Runtime permission has been denied once, but it's not UNREQUESTED");
            return false;
        }
        if (isGrantedOnce() && status != Status.ALWAYS_ASK) {
            VLog.e(TAG, "Runtime permission has been granted once, but it's not set to ALWAYS_ASK");
            return false;
        }
        if (!isOverridden() && group.getStatus() == Status.DENIED && isGranted()) {
            VLog.e(TAG, "Runtime permission is granted, but its group is not");
            return false;
        }
        // If not having a background permission it cannot be set to ALWAYS_ASK.
        // Ignore: this is true in Android, but for virtual apps allow finer control.
        // if (status == Status.ALWAYS_ASK && !hasBackgroundPermission()) {
        //     return false;
        // }
        return true;
    }

    @Override
    public void setStatus(final Status newStatus) {
        if (!isOverridden()) {
            group.setStatus(newStatus);
        }
        // Clear flags when moving to a more permissive or restrictive status
        if (newStatus != Status.UNREQUESTED) {
            flags &= ~FLAG_DENIED_ONCE;
        }
        flags &= ~FLAG_GRANTED_ONCE;
        super.setStatus(newStatus);
    }

    @Override
    public boolean isGranted() {
        if (group == null) {
            return false;
        }
        return switch (status) {
            case Status.GRANTED -> isOverridden() || group.isGranted();
            case Status.ALWAYS_ASK -> isGrantedOnce()
                || ( hasPermissionGroup() && group.isGranted() );
            default -> false;
        };
    }

    public @Nullable PermissionGroup getPermissionGroup() {
        return group;
    }

    public boolean shouldShowRequestPermissionRationale() {
        return (flags & FLAG_DENIED_ONCE) != 0;
    }

    public boolean isGrantedOnce() {
        return (flags & FLAG_GRANTED_ONCE) != 0;
    }

    public boolean isOverridden() {
        return (flags & FLAG_OVERRIDE) != 0;
    }

    public void setDeniedOnce() {
        setStatus(Status.UNREQUESTED);
        flags |= FLAG_DENIED_ONCE;
    }

    public void grantOnce() {
        setStatus(Status.ALWAYS_ASK);
        flags |= FLAG_GRANTED_ONCE;
    }

    public void override() {
        flags |= FLAG_OVERRIDE;
        ensureStatusValid();
    }

    public void followGroup() {
        flags &= ~FLAG_OVERRIDE;
        ensureStatusValid();
    }


    public boolean hasPermissionGroup() {
        return group != null;
    }

    /**
     * @return permission group icon drawable resource identifier.
     */
    public int getGroupIconRes() {
        String groupName = null;
        if (hasPermissionGroup()) {
            return group.getGroupIconRes();
        // Manage exceptions
        } else if (VPermissionManager.LOCATION_PERMISSIONS.contains(name)) {
            groupName = Manifest.permission_group.LOCATION;
        } else if (name.equals(Manifest.permission.BODY_SENSORS_BACKGROUND)) {
            groupName = Manifest.permission_group.SENSORS;
        }
        return PermissionGroup.getGroupIconRes(groupName);
    }

    public boolean hasBackgroundPermission() {
        final var pm = VirtualCore.get().getPM();
        try {
            final var permissionInfo = pm.getPermissionInfo(name, 0);
            return PermissionInfo.backgroundPermission.get(permissionInfo) != null;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    /**
     * @return true if the permission request dialog needs to be displayed.
     */
    public boolean needsRequestDialog() {
        if (group == null) return !isGranted();
        return switch (status) {
            case Status.UNREQUESTED -> isOverridden() || !group.isGranted();
            case Status.DENIED -> false;
            default -> !isGranted();
        };
    }

    @Override
    public String statusToString() {
        final var statusString = new StringBuilder();
        statusString.append(super.statusToString());
        if (shouldShowRequestPermissionRationale()) {
            statusString.append("|deniedOnce");
        }
        if (isGrantedOnce()) {
            statusString.append("|grantedOnce");
        }
        if (isOverridden()) {
            statusString.append("|override");
        }
        return statusString.toString();
    }

    @Override
    void setStatusFromString(final String statusString) {
        final var parts = statusString.split("\\|");
        // Clear flags
        flags = 0;
        for (int i = 1; i < parts.length; ++i) {
            final var flag = parts[i];
            switch (flag) {
                case "deniedOnce" -> {
                    flags |= FLAG_DENIED_ONCE;
                }
                case "grantedOnce" -> {
                    flags |= FLAG_GRANTED_ONCE;
                }
                case "override" -> {
                    flags |= FLAG_OVERRIDE;
                }
                default -> {
                    throw new IllegalArgumentException("Unknown flag: " + flag);
                }
            };
        }
        if (group == null) {
            flags |= FLAG_OVERRIDE;
        }
        super.setStatusFromString(parts[0]);
    }

    @Override
    public String toString() {
        return String.format("%s{name='%s', status='%s', group=%s}", getClass().getSimpleName(),
                name, statusToString(), hasPermissionGroup() ? group.toString() : "null");
    }
}

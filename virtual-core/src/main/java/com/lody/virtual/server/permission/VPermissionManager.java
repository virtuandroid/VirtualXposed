package com.lody.virtual.server.permission;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.PERMISSION_DENIED;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import androidx.annotation.Nullable;

import static com.lody.virtual.server.permission.Permission.Status;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.pm.parser.VPackage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author Alberto Lazari
 */
public final class VPermissionManager {

    private static final String TAG = VPermissionManager.class.getSimpleName();
    private static final VPermissionManager INSTANCE = new VPermissionManager();

    // Permissions exceptions
    public static final Set<String> LOCATION_PERMISSIONS = Set.of(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
    );
    public static final Set<String> BACKGROUND_PERMISSIONS = Set.of(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.BODY_SENSORS_BACKGROUND
    );
    // When key is being granted, value should be too
    public static final Map<String, String> AUTO_GRANT_MAP = Map.of(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS_BACKGROUND, Manifest.permission.BODY_SENSORS
    );

    private final PermissionCache permissionCache = new PermissionCache(
            VEnvironment.getPermissionFile(), new PermissionFileParser());

    private VPermissionManager() {
    }

    public static VPermissionManager get() {
        return INSTANCE;
    }

    /**
     * Prepare the environment.
     * To call during a virtual app startup process.
     */
    public synchronized void systemReady() {
        // Initialize the permission cache
        permissionCache.init();

        // Reset granted once status from all runtime permissions
        permissionCache.update(permissions -> {
            // Iterate on all virtual apps' permissions
            permissions.values().forEach(appPermissions -> {
                appPermissions.getAll(RuntimePermission.class).forEach((name, permission) -> {
                    if (permission.isGrantedOnce()) {
                        // Reset status to revert granted once
                        permission.setStatus(Status.ALWAYS_ASK);
                    }
                });
            });
        });
    }


    /**
     * @return App permissions for @uid.
     * @throws SecurityException if a VUID is trying to access permissions for a different @uid.
     */
    public AppPermissions getAppPermissions(final int uid) {
        return permissionCache.read(uid, Function.identity());
    }

    /**
     * @return @permission for @uid, or `null` if not declared.
     */
    public @Nullable Permission getPermission(final String permission, final int uid) {
        return getAppPermissions(uid).getPermission(permission);
    }

    /**
     * @return @permission of @type for @uid, or `null` if not declared.
     */
    public @Nullable <T extends Permission> T getPermission(final String permission, final int uid,
                                                            final Class<T> type) {

        return getAppPermissions(uid).getPermission(permission, type);
    }

    /**
     * Initialize permissions for a new @uid, reading them from the application Manifest XML.
     */
    public void initPermissionsForUid(final int uid, final VPackage pkg) {
        permissionCache.update(permissions -> {
            final var appPermissions = createAppPermissions(pkg.requestedPermissions);
            permissions.put(uid, appPermissions);
        });
    }

    /**
     * Remove permissions for @uid.
     */
    public void removePermissionsForUid(final int uid) {
        permissionCache.update(permissions -> {
            permissions.remove(uid);
        });
    }

    /**
     * Check whether @permission of any type is granted for @uid.
     *
     * @return - PackageManager.PERMISSION_GRANTED if permission is currently granted
     * - PackageManager.PERMISSION_DENIED otherwise
     */
    public int checkPermission(final String permission, final int uid) {
        Permission realPermission = getPermission(permission, uid);
        if (permission == null || realPermission == null) {
            return PERMISSION_DENIED;
        }
        return realPermission.isGranted()
                ? PERMISSION_GRANTED
                : PERMISSION_DENIED;
    }

    /**
     * @param permission Runtime permission to investigate.
     * @return true if an explanation about why the app needs a particular permission should be
     * shown: i.e. the user already denied the permission once (but not permanently).
     */
    public boolean shouldShowRequestPermissionRationale(final String permissionName,
                                                        final int uid) {

        final var permission = getPermission(permissionName, uid, RuntimePermission.class);
        return permission != null && permission.shouldShowRequestPermissionRationale();
    }

    /**
     * Update @permissionName for a specific @uid.
     */
    public void updatePermission(final String permissionName, final int uid,
                                 final Consumer<Permission> operation) {

        updatePermission(Permission.class, permissionName, uid, operation);
    }

    /**
     * Update @permissionName of @type for a specific @uid.
     */
    public <T extends Permission> void updatePermission(final Class<T> type,
                                                        final String permissionName, final int uid, final Consumer<T> operation) {

        permissionCache.update(uid, appPermissions -> {
            final var permission = appPermissions.getPermission(permissionName, type);
            if (permission == null) {
                // Do not perform operation if permission not found
                return;
            }
            operation.accept(permission);
            if (AUTO_GRANT_MAP.containsKey(permissionName)
                    && permission instanceof RuntimePermission strongPermission) {
                // Weaker permission should be set to the same exact status as the strong one
                final var weakPermission = appPermissions.getPermission(
                        AUTO_GRANT_MAP.get(permissionName), RuntimePermission.class);
                if (weakPermission != null && (!weakPermission.isGranted() || strongPermission.getStatus() == Status.GRANTED)) {
                    // Update weak permission to match stronger one
                    appPermissions.updatePermission(new RuntimePermission(weakPermission.getName(),
                            weakPermission.getPermissionGroup(), strongPermission));
                }
            }
            if (AUTO_GRANT_MAP.containsValue(permissionName)
                    && permission instanceof RuntimePermission weakPermission) {
                // Stronger permissions should not be allowed as well
                AUTO_GRANT_MAP.forEach((strongPermissionName, weakPermissionName) -> {
                    if (!weakPermissionName.equals(permissionName)) {
                        return;
                    }
                    final var strongPermission = appPermissions.getPermission(
                            strongPermissionName, RuntimePermission.class);
                    if (strongPermission != null && strongPermission.isGranted()) {
                        strongPermission.setDeniedOnce();
                    }
                });
            }
        });
    }

    /**
     * Allow a runtime permission (or its group).
     */
    public void allowPermission(final String permissionName, final int uid) {
        updatePermission(permissionName, uid, permission -> {
            permission.setStatus(Status.GRANTED);
            if (permission instanceof PermissionGroup permissionGroup) {
                permissionGroup.getPermissions().stream()
                        .filter(runtimePermission -> !runtimePermission.isOverridden())
                        .forEach(runtimePermission -> {
                            // When granting a group, grant all permissions inside of it
                            runtimePermission.setStatus(Status.GRANTED);
                        });
            }
        });
    }

    /**
     * Allow a runtime permission for the current app execution (or its group).
     */
    public void allowPermissionOnce(final String permissionName, final int uid) {
        updatePermission(RuntimePermission.class, permissionName, uid, permission -> {
            permission.grantOnce();
        });
    }

    /**
     * Reflect the intention of the user of not allowing a permission (or a group).
     * If a runtime permission has been denied once in the past it will be permanently denied.
     */
    public void doNotAllowPermission(final String permissionName, final int uid) {
        updatePermission(permissionName, uid, permission -> {
            if (permission instanceof InstallPermission) {
                // Simply deny install permissions
                permission.setStatus(Status.DENIED);
            } else if (permission instanceof PermissionGroup permissionGroup) {
                // Set as unrequested, the individual runtime permissions will reflect the fact
                // that the group has been denied.
                // This allows to permissions to display the dialog, which is lines up with Android
                permissionGroup.setStatus(Status.UNREQUESTED);
                permissionGroup.getPermissions().stream()
                        .filter(runtimePermission -> !runtimePermission.isOverridden())
                        .forEach(runtimePermission -> {
                            // Set all (non-overridden) runtime permissions in the group as denied once
                            runtimePermission.setDeniedOnce();
                        });
            } else if (permission instanceof RuntimePermission runtimePermission) {
                // Check if permission had been denied once
                if (runtimePermission.shouldShowRequestPermissionRationale()) {
                    // RuntimePermission's setStatus will automatically set the group status
                    runtimePermission.setStatus(Status.DENIED);
                } else {
                    runtimePermission.setDeniedOnce();
                }
            }
        });
    }


    /**
     * @return Group name of @permission, or `null` if not in any group
     */
    private static @Nullable String getPermissionGroup(final String permission) {
        final var groupFuture = new CompletableFuture<String>();
        final var ctx = VirtualCore.get().getContext();
        ctx.getPackageManager()
                .getGroupOfPlatformPermission(permission, ctx.getMainExecutor(), group -> {
                    groupFuture.complete(group);
                });
        try {
            return groupFuture.get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Build a proper `AppPermissions` object from a set of permissions declared in a manifest.
     * Steps to take:
     * - Get permission type based on which permissions are present on the system
     * - Set default status based on permission type
     * - Update @permissionGroupMap when finding a new permission group
     */
    private AppPermissions createAppPermissions(final Collection<String> declaredPermissions) {
        final var appPermissions = new HashMap<String, Permission>();
        declaredPermissions.forEach(permissionName -> {
            int protection;
            try {
                protection = VirtualCore.get()
                        .getPM()
                        .getPermissionInfo(permissionName, 0)
                        .getProtection();
            } catch (PackageManager.NameNotFoundException e) {
                VLog.w(TAG, "Permission not found: %s", permissionName);
                // Unknown permissions are treated as denied install-time permissions
                appPermissions.put(permissionName, new InstallPermission(
                        permissionName, Status.DENIED));
                return;
            }

            final var permission = switch (protection) {
                case PermissionInfo.PROTECTION_NORMAL ->
                        new InstallPermission(permissionName, Status.GRANTED);
                case PermissionInfo.PROTECTION_DANGEROUS -> {
                    final var groupName = getPermissionGroup(permissionName);
                    if (groupName == null
                            // Manage location permissions individually
                            || groupName.equals(Manifest.permission_group.LOCATION)
                            || BACKGROUND_PERMISSIONS.contains(permissionName)) {

                        VLog.i(TAG, "%s not in a permission group", permissionName);
                        final var runtimePermission = new RuntimePermission(permissionName);
                        if (permissionName.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                            // Background location should show rationale by default
                            runtimePermission.setDeniedOnce();
                        }
                        yield runtimePermission;
                    }
                    final var group = new PermissionGroup(groupName);
                    appPermissions.put(groupName, group);
                    final var runtimePermission = new RuntimePermission(permissionName, group);
                    group.addPermission(runtimePermission);

                    yield runtimePermission;
                }
                // - Signature permissions in general are denied. While external signature
                //   permissions will not work anyway (unless an app uses VirtualXposed
                //   signature), the ones declared by virtual apps could be used. Those are not
                //   supported for the time being.
                // - System and internal permissions are denied. This should make sense, since
                //   those are also denied for VirtualXposed itself.
                default -> {
                    VLog.i(TAG, "Unsupported protection level for permission %s", permissionName);
                    yield new InstallPermission(permissionName, Status.DENIED);
                }
            };
            appPermissions.put(permissionName, permission);
        });
        return new AppPermissions(appPermissions);
    }

}

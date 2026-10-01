package com.lody.virtual.server.permission;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class AppPermissions {

    private final Map<String, Permission> permissions;

    public AppPermissions() {
        permissions = new HashMap<>();
    }

    public AppPermissions(final Map<String, Permission> permissions) {
        this.permissions = permissions;
    }

    /**
     * @return Reference of all permissions
     */
    public Map<String, Permission> getAll() {
        return permissions;
    }

    /**
     * @return A copy of all type T permissions
     */
    public <T extends Permission> Map<String, T> getAll(final Class<T> type) {
        return permissions.entrySet()
            .stream()
            .filter(entry -> type.isInstance(entry.getValue()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> (T) entry.getValue()));
    }

    public @Nullable Permission getPermission(final String permissionName) {
        return permissions.get(permissionName);
    }

    public <T extends Permission> @Nullable T getPermission(final String permissionName,
            final Class<T> type) {

        final Permission permission = permissions.get(permissionName);
        return type.isInstance(permission)
            ? (T) permission
            : null;
    }

    public void updatePermission(final Permission newPermission) {
        final var permissionName = newPermission.getName();
        final var oldPermission = getPermission(permissionName, newPermission.getClass());
        // Set only if permission is declared
        if (oldPermission != null) {
            permissions.put(permissionName, newPermission);
        }
    }
}

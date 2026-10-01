package com.lody.virtual.server.permission;

/**
 * @author Alberto Lazari
 * Install-time permissions are granted by default on application install, with the exception
 * of signature and system permissions and permissions that cannot be found on the system.
 * Their status can be either GRANTED or DENIED.
 */
public class InstallPermission extends Permission {

    public InstallPermission(final String name, final Status status) {
        super(name, status);
    }

    public InstallPermission(final String name, final InstallPermission permission) {
        super(name, permission);
    }

    InstallPermission(final String name, final String statusString) {
        super(name, statusString);
    }

    @Override
    public boolean isValidStatus(final Status status) {
        return status == Status.GRANTED || status == Status.DENIED;
    }
}

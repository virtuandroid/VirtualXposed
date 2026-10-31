package com.lody.virtual.server.permission;

import java.util.Objects;

/**
 * @author Alberto Lazari
 * State of a permission.
 */
public abstract class Permission {

    protected static final String TAG = Permission.class.getSimpleName();

    /**
     * Grant status for the permission.
     */
    public enum Status {
        /**
         * Permission has been permanently granted from dialog or settings.
         */
        GRANTED,

        /**
         * Permission dialog has to be prompted.
         * Possible scenarios:
         * - User selected "Only this time" option from permission dialog
         * - User set permission status to "Ask every time" from settings
         * - Permission has been denied once and then granted only once from dialog
         */
        ALWAYS_ASK,

        /**
         * The permission has not been requested yet.
         * Possible scenarios:
         * - Permission has never been asked (default status)
         * - User dismissed the dialog (tapped outside of it)
         * - User denied the runtime permission from settings
         */
        UNREQUESTED,

        /**
         * Permission has been permanently denied.
         * Possible scenarios:
         * - User denied a runtime permission twice in a row from the permission dialog
         * - User denied an install-time permission
         */
        DENIED
    }

    protected final String name;
    protected Status status;

    protected Permission(final String name) {
        Objects.requireNonNull(name);
        this.name = name;
    }

    public Permission(final String name, final Status status) {
        this(name);
        Objects.requireNonNull(status);
        this.status = status;
        ensureStatusValid();
    }

    public Permission(final String name, final Permission permission) {
        this(name, permission.status);
    }

    Permission(final String name, final String statusString) {
        this(name);
        setStatusFromString(statusString);
    }

    /**
     * @return true if @status is valid for the current permission type
     */
    public abstract boolean isValidStatus(final Status status);

    public void ensureStatusValid() {
        if (!isValidStatus(status)) {
            throw new RuntimeException(String.format("Invalid status for %s", toString()));
        }
    }

    public String getName() {
        return name;
    }

    /**
     * @return Readable permission name
     * e.g. "android.permission.READ_CONTACTS" -> "Read contacts"
     */
    public String getReadableName() {
        if (name == null) {
            return "Unknown";
        }
        final String permissionName = name
            // Remove package
            .replaceAll("[a-z0-9.-]*", "")
            // Expand underscores
            .replace("_", " ")
            .toLowerCase();
        // Capitalize first letter
        return permissionName.substring(0, 1).toUpperCase()
            + permissionName.substring(1);
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(final Status newStatus) {
        status = newStatus;
        ensureStatusValid();
    }

    public boolean isGranted() {
        return status == Status.GRANTED;
    }

    /**
     * @return String representation of status
     * If the current permission is of type RUNTIME, the status will include eventual flags in the
     * following format: "STATUS|deniedOnce|grantedOnce|override"
     */
    public String statusToString() {
        return status.name();
    }

    void setStatusFromString(final String statusString) {
        Objects.requireNonNull(statusString);
        final var newStatus = Status.valueOf(statusString.toUpperCase());
        status = newStatus;
        ensureStatusValid();
    }

    @Override
    public String toString() {
        return String.format("%s{name='%s', status='%s'}", getClass().getSimpleName(), name,
                statusToString());
    }
}

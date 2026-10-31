package com.lody.virtual.server.permission

import java.util.Locale
import java.util.Objects

/**
 * @author Alberto Lazari
 * State of a permission.
 */
abstract class Permission protected constructor(name: String?) {
    /**
     * Grant status for the permission.
     */
    enum class Status {
        /**
         * Permission is created by app, equivalent to granted, but should not be revocable.
         */
        DECLARED,

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

    val name: String?

    @JvmField
    protected var status: Status? = null

    init {
        Objects.requireNonNull(name)
        this.name = name
    }

    constructor(name: String?, status: Status) : this(name) {
        Objects.requireNonNull<Status?>(status)
        this.status = status
        ensureStatusValid()
    }

    constructor(name: String?, permission: Permission) : this(name, permission.status!!)

    internal constructor(name: String?, statusString: String?) : this(name) {
        setStatusFromString(statusString)
    }

    /**
     * @return true if @status is valid for the current permission type
     */
    abstract fun isValidStatus(status: Status?): Boolean

    fun ensureStatusValid() {
        if (!isValidStatus(status)) {
            throw RuntimeException(String.format("Invalid status for %s", toString()))
        }
    }

    val readableName: String
        /**
         * @return Readable permission name
         * e.g. "android.permission.READ_CONTACTS" -> "Read contacts"
         */
        get() {
            if (name == null) {
                return "Unknown"
            }
            val permissionName = name // Remove package
                .replace("[a-z0-9.-]*".toRegex(), "") // Expand underscores
                .replace("_", " ")
                .lowercase(Locale.getDefault())
            // Capitalize first letter
            return (permissionName.substring(0, 1).uppercase(Locale.getDefault())
                    + permissionName.substring(1))
        }

    fun getStatus(): Status {
        return status!!
    }

    open fun setStatus(newStatus: Status) {
        status = newStatus
        ensureStatusValid()
    }

    open fun isGranted(): Boolean {
        return status == Status.GRANTED
    }

    /**
     * @return String representation of status
     * If the current permission is of type RUNTIME, the status will include eventual flags in the
     * following format: "STATUS|deniedOnce|grantedOnce|override"
     */
    open fun statusToString(): String {
        return status!!.name
    }

    open fun setStatusFromString(statusString: String?) {
        Objects.requireNonNull(statusString)
        val newStatus = Status.valueOf(statusString!!.uppercase(Locale.getDefault()))
        status = newStatus
        ensureStatusValid()
    }

    override fun toString(): String {
        return String.format(
            "%s{name='%s', status='%s'}", javaClass.getSimpleName(), name,
            statusToString()
        )
    }

    companion object {
        @JvmStatic
        protected val TAG: String = Permission::class.java.getSimpleName()
    }
}

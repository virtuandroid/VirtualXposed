package com.lody.virtual.server.permission

/**
 * @author Alberto Lazari
 * Install-time permissions are granted by default on application install, with the exception
 * of signature and system permissions and permissions that cannot be found on the system.
 * Their status can be either GRANTED or DENIED.
 */
class InstallPermission : Permission {
    constructor(name: String?, status: Status) : super(name, status)

    constructor(name: String?, permission: InstallPermission) : super(name, permission)

    internal constructor(name: String?, statusString: String?) : super(name, statusString)

    override fun isValidStatus(status: Status?): Boolean {
        return status == Status.GRANTED || status == Status.DENIED || status == Status.DECLARED
    }
}

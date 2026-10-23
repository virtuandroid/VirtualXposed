package com.lody.virtual.server.security

import android.content.pm.PackageManager
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.server.permission.VPermissionManager
import kotlin.jvm.Throws

object SecurityUtils {
    //https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/am/ActivityManagerService.java#3607
    @JvmStatic
    @Throws(SecurityException::class)
    fun enforceNotIsolatedCaller(caller: String) {
        if (VUserHandle.isIsolated(VBinder.getCallingUid())) {
            throw SecurityException("Isolated process not allowed to call $caller")
        }
    }

    @JvmStatic
    @Throws(SecurityException::class)
    fun assertPackageMatchesCallingUid(packageName: String?) {
        enforceCallingPackage(packageName, VBinder.getCallingUid())
    }

    // https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/am/ActivityManagerService.java#3658
    @JvmStatic
    @Throws(SecurityException::class)
    fun enforceCallingPackage(packageName: String?, callingUid: Int) {
        if (packageName == null) {
            throw IllegalArgumentException("callingPackage cannot be null")
        }
        // System is always allowed to perform all actions
        if (callingUid == VActivityManager.get().systemPid) {
            return
        }

        val userId = VUserHandle.getUserId(callingUid)
        val packageUid = VPackageManager.get().getPackageUid(packageName, userId)
        val isSameApp = VUserHandle.isSameApp(packageUid, callingUid)

        if (!isSameApp) {
            val e = SecurityException("$packageName does not belong to uid $callingUid")
            throw e
        }
    }

    // https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/am/ActivityManagerService.java#6986
    @JvmStatic
    @Throws(SecurityException::class)
    fun enforceCallingPermission(permission: String, func: String) {
        val uid = VBinder.getCallingUid()
        val permission = VPermissionManager.get().checkPermission(permission, uid)
        if (permission == PackageManager.PERMISSION_GRANTED) {
            return
        }

        val msg =
            "Permission Denial: $func from pid=${VBinder.getCallingPid()}, uid=${VBinder.getCallingUid()} requires $permission"
        throw SecurityException(msg)
    }


    @JvmStatic
    @Throws(SecurityException::class)
    fun enforceCallingUser(userId: Int) {
        if (isCrossUser(VBinder.getCallingUid(), userId)) {
            throw SecurityException("Forbidden cross-user interaction.")
        }
    }

    @JvmStatic
    @Throws(SecurityException::class)
    fun assertSystemCaller() {
        val callingUid = VBinder.getCallingUid()
        if (callingUid != VActivityManager.get().systemPid) {
            throw SecurityException("Only callable by the system.")
        }
    }

    // https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/accounts/AccountManagerService.java#1797
    private fun isCrossUser(callingUid: Int, userId: Int): Boolean {
        return (userId != VUserHandle.getCallingUserId()
                // Always allow the system to perform cross-user actions
                && callingUid != VActivityManager.get().systemPid)
    }
}
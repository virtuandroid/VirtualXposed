package com.lody.virtual.os;

import android.os.Binder;

import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;

/**
 * @author Lody
 */

public class VBinder {

    public static int getCallingUid() {
        return VActivityManager.get().getUidByPid(Binder.getCallingPid());
    }

    public static String getCallingPackageName() {
        return VPackageManager.get().getNameForUid(VBinder.getCallingUid());
    }

    public static int getBaseCallingUid() {
        return VUserHandle.getAppId(getCallingUid());
    }

    public static int getCallingPid() {
        return Binder.getCallingPid();
    }

    public static VUserHandle getCallingUserHandle() {
        return new VUserHandle(VUserHandle.getUserId(getCallingUid()));
    }
}

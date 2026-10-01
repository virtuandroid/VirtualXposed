package com.lody.virtual.client.hook.proxies.permission;

import android.annotation.TargetApi;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.server.permission.VPermissionManager;

import java.lang.reflect.Method;

import mirror.android.permission.IPermissionManager;

/**
 * @author LittleAngry
 * @see android.permission.PermissionManager
 */
public class PermissionManagerStub extends BinderInvocationProxy {

    public PermissionManagerStub() {
        super(IPermissionManager.Stub.asInterface, "permissionmgr");
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new MethodProxy() {
            @Override
            public String getMethodName() {
                return "addOnPermissionsChangeListener";
            }

            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                VLog.d("PermissionManager", "addOnPermissionsChangeListener ignored");
                return null;
            }
        });

        addMethodProxy(new MethodProxy() {
            @Override
            public String getMethodName() {
                return "shouldShowRequestPermissionRationale";
            }

            @Override
            public Object call(Object who, Method method, Object... args) throws Throwable {
                final String packageName = (String) args[0];
                final String permission = (String) args[1];
                final int userId = (int) args[2];
                int uid = VPackageManager.get().getPackageUid(packageName, userId);
                final int vuid = getVUid();
                if (uid != vuid && uid == getBaseVUid()) {
                    // android.os.UserHandle.getAppId(uid) returned the base app id,
                    // although here it's important to preserve userIds.
                    // Restore the VUid for the correct userId, otherwise it would return a
                    // SecurityException for accessing the baseVUid permissions
                    uid = vuid;
                }
                return VPermissionManager.get()
                    .shouldShowRequestPermissionRationale(permission, uid);
            }

            @Override
            public boolean isEnable() {
                return isAppProcess();
            }
        });
    }
}

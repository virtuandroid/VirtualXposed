package mirror.android.permission;

import mirror.RefClass;
import mirror.RefStaticMethod;

public class PermissionManager {

    public static Class<?> TYPE = RefClass.load(PermissionManager.class, "android.permission.PermissionManager");
    public static RefStaticMethod disablePermissionCache;
}

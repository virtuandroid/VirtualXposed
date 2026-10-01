package mirror.android.content.pm;

import mirror.RefClass;
import mirror.RefStaticObject;

public class PackageManager {

    public static Class<?> TYPE = RefClass.load(PackageManager.class, "android.content.pm.PackageManager");

    public static RefStaticObject<String> ACTION_REQUEST_PERMISSIONS;
    public static RefStaticObject<String> EXTRA_REQUEST_PERMISSIONS_NAMES;
    public static RefStaticObject<String> EXTRA_REQUEST_PERMISSIONS_RESULTS;
}

package mirror.android.content.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.SharedLibraryInfo;

import java.util.List;

import mirror.RefClass;
import mirror.RefObject;

public class ApplicationInfoN {
    public static Class<?> TYPE = RefClass.load(ApplicationInfoN.class, ApplicationInfo.class);
    public static RefObject<String> deviceProtectedDataDir;
    public static RefObject<String> deviceEncryptedDataDir;
    public static RefObject<String> credentialProtectedDataDir;
    public static RefObject<String> credentialEncryptedDataDir;
    // Android Q
    public static RefObject<List<SharedLibraryInfo>> sharedLibraryInfos;
}

package mirror.android.app;

import mirror.RefClass;
import mirror.RefObject;

public class LocaleManager {

    public static Class<?> TYPE = RefClass.load(LocaleManager.class, "android.app.LocaleManager");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(LocaleManager.Stub.class, "android.app.LocaleManager$Stub");
        public static RefObject<Object> mService;
    }
}

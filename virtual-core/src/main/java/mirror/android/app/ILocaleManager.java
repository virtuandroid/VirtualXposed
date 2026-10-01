package mirror.android.app;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

public class ILocaleManager {
    public static Class<?> TYPE = RefClass.load(ILocaleManager.class, "android.app.ILocaleManager");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(Stub.class, "android.app.ILocaleManager$Stub");

        @MethodParams({IBinder.class})
        public static RefStaticMethod<IInterface> asInterface;
    }
}
package com.lody.virtual.client.hook.proxies.libcore;

import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.base.Inject;
import com.lody.virtual.client.hook.base.MethodInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceUidMethodProxy;

import java.lang.reflect.Method;

import mirror.libcore.io.ForwardingOs;
import mirror.libcore.io.Libcore;

/**
 * @author Lody
 */
@Inject(MethodProxies.class)
public class LibCoreStub extends MethodInvocationProxy<MethodInvocationStub<Object>> {

    private static final String TAG = LibCoreStub.class.getSimpleName();

    public LibCoreStub() {
        super(new MethodInvocationStub<Object>(getOs()));
    }

    private static Object getOs() {
        Object os = Libcore.os.get();
        if (ForwardingOs.os != null) {
            Object posix = ForwardingOs.os.get(os);
            if (posix != null) {
                os = posix;
            }
        }
        return os;
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceUidMethodProxy("chown", 1));
        addMethodProxy(new ReplaceUidMethodProxy("fchown", 1));
        addMethodProxy(new ReplaceUidMethodProxy("getpwuid", 0));
        addMethodProxy(new ReplaceUidMethodProxy("lchown", 1));
        addMethodProxy(new ReplaceUidMethodProxy("setuid", 0));
        addMethodProxy(new Open());
    }

    @Override
    public void inject() throws Throwable {
        Libcore.os.set(getInvocationStub().getProxyInterface());
    }

    @Override
    public boolean isEnvBad() {
        return getOs() != getInvocationStub().getProxyInterface();
    }


    /**
     * MethodProxy
     * Proxies the libcore.io.Os.open method, and checks at runtime whether the
     * path to access is valid from the sandbox perspective.
     */
    public static class Open extends MethodProxy {
        @Override
        public String getMethodName() {
            return "open";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            String path = (String) args[0];

//            args[0] = ((String) args[0]).replace("/data/user_de/0/", "/data/user/0/io.va.exposed64/virtual/data/user/0/");
//
//            if (VirtualRuntime.getInitialPackageName() != null) {
//                PathChecker checker = PathChecker.get();
//                // TODO FIX EPIC LIB LOAD for XPOSED
//                // TODO FIX LOAD MODULES
//                if (checker.isPathValid(path) || path.endsWith("liblsplantbridge.so")) {
//                    VLog.d(TAG, "File access GRANTED to " + VirtualRuntime.getInitialPackageName() + ": " + path);
//                    return method.invoke(who, args);
//                }
//                VLog.d(TAG, "File access DENIED  to " + VirtualRuntime.getInitialPackageName() + ": " + path);
//                return null;
//            }

            return method.invoke(who, args);
        }
    }

}
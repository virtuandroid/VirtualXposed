package com.lody.virtual.client.hook.proxies.telephony;

import android.os.Build;
import android.telephony.PhoneStateListener;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.ReplaceSequencePkgMethodProxy;
import com.lody.virtual.helper.compat.BuildCompat;

import java.lang.reflect.Method;

import mirror.com.android.internal.telephony.ITelephonyRegistry;

public class TelephonyRegistryStub extends BinderInvocationProxy {

    public TelephonyRegistryStub() {
        super(ITelephonyRegistry.Stub.asInterface, "telephony.registry");
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceCallingPkgMethodProxy("listen"));
        addMethodProxy(new ReplaceSequencePkgMethodProxy("listenForSubscriber", 1) {
            @Override
            public boolean beforeCall(Object who, Method method, Object... args) {
                if (android.os.Build.VERSION.SDK_INT >= 17) {
                    if (isFakeLocationEnable()) {
                        for (int i = args.length - 1; i > 0; i--) {
                            if (args[i] instanceof Integer) {
                                int events = (Integer) args[i];
                                events ^= PhoneStateListener.LISTEN_CELL_INFO;
                                events ^= PhoneStateListener.LISTEN_CELL_LOCATION;
                                args[i] = events;
                                break;
                            }
                        }
                    }
                }
                return super.beforeCall(who, method, args);
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addMethodProxy(new MethodProxy() {
                @Override
                public Object call(Object who, Method method, Object... args) throws Throwable {
                    args[3] = VirtualCore.get().getHostPkg();
                    return super.call(who, method, args);
                }

                @Override
                public String getMethodName() {
                    return "listenWithEventList";
                }
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addMethodProxy(new MethodProxy() {
                @Override
                public Object call(Object who, Method method, Object... args) throws Throwable {
                    args[1] = VirtualCore.get().getHostPkg();
                    return super.call(who, method, args);
                }

                @Override
                public String getMethodName() {
                    return "listenWithEventList";
                }
            });
        }

        if (BuildCompat.isS()) {
            addMethodProxy(new ReplaceCallingPkgMethodProxy("listenWithEventList"));
        }
    }
}

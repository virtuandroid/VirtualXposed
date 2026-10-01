package com.lody.virtual.client.hook.proxies.nsd;

import static com.lody.virtual.client.hook.utils.MethodParameterUtils.replaceFirstAppPkg;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;

import java.lang.reflect.Method;
import java.util.Arrays;

import mirror.android.net.nsd.INsdManager;

public class NsdServiceStub extends BinderInvocationProxy {
    public NsdServiceStub() {
        super(INsdManager.Stub.asInterface, Context.NSD_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new Connect());
    }

    private static class Connect extends MethodProxy {
        private Connect() {
        }

        public Object call(Object who, Method method, Object... args) throws Throwable {
            replaceFirstAppPkg(args);
            System.out.println("REGISTER CALL TO " + Arrays.toString(args));
            return super.call(who, method, args);
        }

        public String getMethodName() {
            return "connect";
        }
    }
}

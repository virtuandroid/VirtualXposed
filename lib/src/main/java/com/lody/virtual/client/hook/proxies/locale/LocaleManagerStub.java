package com.lody.virtual.client.hook.proxies.locale;

import android.content.Context;
import android.os.IBinder;
import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

import mirror.android.app.ILocaleManager;

public class LocaleManagerStub extends BinderInvocationProxy {

    public LocaleManagerStub() {
        super(ILocaleManager.Stub.TYPE, Context.LOCALE_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        // Register method proxies for ILocaleManager calls
        addMethodProxy(new GetApplicationLocales());
        addMethodProxy(new SetApplicationLocales());
    }

    private static class GetApplicationLocales extends MethodProxy {
        @Override
        public String getMethodName() {
            return "getApplicationLocales";
        }

        @Override
        public Object call(Object provider, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(provider, args);
        }
    }

    private static class SetApplicationLocales extends MethodProxy {
        @Override
        public String getMethodName() {
            return "setApplicationLocales";
        }

        @Override
        public Object call(Object provider, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(provider, args);
        }
    }
}
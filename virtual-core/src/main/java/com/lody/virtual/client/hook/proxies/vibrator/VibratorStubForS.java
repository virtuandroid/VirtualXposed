package com.lody.virtual.client.hook.proxies.vibrator;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceUidMethodProxy;

import mirror.com.android.internal.os.IVibratorManagerService;

/**
 * @author LittleAngry
 */
public class VibratorStubForS extends BinderInvocationProxy {

    public VibratorStubForS() {
        super(IVibratorManagerService.Stub.asInterface, Context.VIBRATOR_MANAGER_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        addMethodProxy(new ReplaceUidMethodProxy("vibrate", 0));
        addMethodProxy(new ReplaceUidMethodProxy("setAlwaysOnEffect", 0));
    }
}

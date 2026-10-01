package com.lody.virtual.client.hook.proxies.am;

import android.app.ClientTransactionHandler;
import android.app.TransactionHandlerProxy;
import android.util.Log;

import com.lody.virtual.client.interfaces.IInjector;

import java.lang.reflect.Field;

import mirror.android.app.ActivityThread;
import timber.log.Timber;

/**
 * @author weishu
 * @date 2018/8/7.
 */
public class TransactionHandlerStub implements IInjector {
    @Override
    public void inject() throws Throwable {
        Timber.d("Inject transaction handler.");
        Object activityThread = ActivityThread.currentActivityThread.call();
        Object transactionExecutor = ActivityThread.mTransactionExecutor.get(activityThread);

        Field mTransactionHandlerField = transactionExecutor.getClass().getDeclaredField("mTransactionHandler");
        mTransactionHandlerField.setAccessible(true);
        ClientTransactionHandler original = (ClientTransactionHandler) mTransactionHandlerField.get(transactionExecutor);
        TransactionHandlerProxy proxy = new TransactionHandlerProxy(original);

        mTransactionHandlerField.set(transactionExecutor, proxy);
        Timber.d("Executor's handler: %s", mTransactionHandlerField.get(transactionExecutor));
    }

    @Override
    public boolean isEnvBad() {
        return false;
    }
}

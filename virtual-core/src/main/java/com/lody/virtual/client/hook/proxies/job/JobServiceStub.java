package com.lody.virtual.client.hook.proxies.job;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.ipc.VJobScheduler;
import com.lody.virtual.helper.utils.ComponentUtils;

import java.lang.reflect.Method;

import mirror.android.app.job.IJobScheduler;
import mirror.android.app.job.JobWorkItem;

/**
 * @author Lody
 * @see android.app.job.JobScheduler
 */
public class JobServiceStub extends BinderInvocationProxy {

    public JobServiceStub() {
        super(IJobScheduler.Stub.asInterface, Context.JOB_SCHEDULER_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new schedule());
        addMethodProxy(new getAllPendingJobs());
        addMethodProxy(new cancelAll());
        addMethodProxy(new cancel());

        if (Build.VERSION.SDK_INT >= 24) {
            addMethodProxy(new getPendingJob());
        }
        if (Build.VERSION.SDK_INT >= 26) {
            addMethodProxy(new enqueue());
        }
    }

    private static class getPendingJob extends MethodProxy {
        private getPendingJob() {
        }

        public Object call(Object who, Method method, Object... args) throws Throwable {
            return VJobScheduler.get().getPendingJob((Integer) args[0]);
        }

        public String getMethodName() {
            return "getPendingJob";
        }
    }

    private class enqueue extends MethodProxy {
        private enqueue() {
        }

        public Object call(Object who, Method method, Object... args) throws Throwable {

            // Namespace is the first argument in A14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return VJobScheduler.get().enqueue(
                        (JobInfo) args[1],
                        JobServiceStub.this.redirect(args[2], MethodProxy.getAppPkg())
                );
            } else {
                return VJobScheduler.get().enqueue(
                        (JobInfo) args[0],
                        JobServiceStub.this.redirect(args[1], MethodProxy.getAppPkg())
                );
            }
        }

        public String getMethodName() {
            return "enqueue";
        }
    }

    private Object redirect(Object item, String pkg) {
        if (item == null) {
            return null;
        }
        Intent redirectIntentSender = ComponentUtils.redirectIntentSender(4, pkg, (Intent) JobWorkItem.getIntent.call(item, new Object[0]), null);
        Object newInstance = JobWorkItem.ctor.newInstance(redirectIntentSender);
        JobWorkItem.mWorkId.set(newInstance, JobWorkItem.mWorkId.get(item));
        JobWorkItem.mGrants.set(newInstance, JobWorkItem.mGrants.get(item));
        JobWorkItem.mDeliveryCount.set(newInstance, JobWorkItem.mDeliveryCount.get(item));
        return newInstance;
    }

    private static class schedule extends MethodProxy {

        @Override
        public String getMethodName() {
            return "schedule";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            JobInfo jobInfo;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Namespace is the first argument in A14+
                jobInfo = (JobInfo) args[1];
            } else {
                jobInfo = (JobInfo) args[0];
            }

            return VJobScheduler.get().schedule(jobInfo);
        }
    }

    private static class getAllPendingJobs extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getAllPendingJobs";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return VJobScheduler.get().getAllPendingJobs();
        }
    }

    private static class cancelAll extends MethodProxy {

        @Override
        public String getMethodName() {
            return "cancelAll";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            VJobScheduler.get().cancelAll();
            return 0;
        }
    }

    private static class cancel extends MethodProxy {

        @Override
        public String getMethodName() {
            return "cancel";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            int jobId;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Namespace is the first argument in A14+
                jobId = (int) args[1];
            } else {
                jobId = (int) args[0];
            }

            VJobScheduler.get().cancel(jobId);
            return 0;
        }
    }
}

package com.lody.virtual.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.lody.virtual.IntentHelper.BUNDLE_BINDER
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.ServiceManagerNative
import com.lody.virtual.client.stub.DaemonService
import com.lody.virtual.helper.compat.BundleCompat
import com.lody.virtual.server.accounts.VAccountManagerService
import com.lody.virtual.server.am.BroadcastSystem.Companion.attach
import com.lody.virtual.server.am.VActivityManagerService
import com.lody.virtual.server.device.VDeviceManagerService
import com.lody.virtual.server.hook.VHookManagerService
import com.lody.virtual.server.interfaces.IServiceFetcher
import com.lody.virtual.server.job.VJobSchedulerService
import com.lody.virtual.server.location.VirtualLocationService
import com.lody.virtual.server.log.VLoggingManagerService
import com.lody.virtual.server.notification.VNotificationManagerService
import com.lody.virtual.server.pm.VAppManagerService
import com.lody.virtual.server.pm.VPackageManagerService
import com.lody.virtual.server.pm.VUserManagerService
import com.lody.virtual.server.vs.VirtualStorageService
import org.matrix.vector.service.VectorManagerService

/**
 * @author Lody
 */
class BinderProvider : ContentProvider() {
    private val mServiceFetcher: ServiceFetcher = ServiceFetcher()

    override fun onCreate(): Boolean {
        val context = context!!
        DaemonService.startup(context)
        if (!VirtualCore.get().isStartup) {
            return true
        }
        VPackageManagerService.systemReady()
        addService(ServiceManagerNative.PACKAGE, VPackageManagerService.get())
        VActivityManagerService.systemReady(context)
        addService(ServiceManagerNative.ACTIVITY, VActivityManagerService.get())
        addService(ServiceManagerNative.USER, VUserManagerService.get())
        VAppManagerService.systemReady()
        addService(ServiceManagerNative.APP, VAppManagerService.get())
        attach(VActivityManagerService.get(), VAppManagerService.get())
        addService(ServiceManagerNative.JOB, VJobSchedulerService.get())
        VNotificationManagerService.systemReady(context)
        addService(ServiceManagerNative.NOTIFICATION, VNotificationManagerService.get())
        VAppManagerService.get().scanApps()
        VAccountManagerService.systemReady()
        addService(ServiceManagerNative.ACCOUNT, VAccountManagerService.get())
        addService(ServiceManagerNative.VS, VirtualStorageService.get())
        addService(ServiceManagerNative.DEVICE, VDeviceManagerService.get())
        addService(ServiceManagerNative.VIRTUAL_LOC, VirtualLocationService.get())

        VLoggingManagerService.get().start()
        addService(ServiceManagerNative.VIRTUAL_LOG, VLoggingManagerService.get())

        VectorManagerService.get().start()
        addService(ServiceManagerNative.VIRTUAL_HOOK, VHookManagerService.get())
        return true
    }

    private fun addService(name: String?, service: IBinder?) {
        ServiceCache.addService(name, service)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // TODO Investigate this escape hatch
        if ("@" == method) {
            val bundle = Bundle()
            BundleCompat.putBinder(bundle, BUNDLE_BINDER, mServiceFetcher)
            return bundle
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String?>?,
        selection: String?,
        selectionArgs: Array<out String?>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        return 0
    }

    private class ServiceFetcher : IServiceFetcher.Stub() {
        @Throws(RemoteException::class)
        override fun getService(name: String?): IBinder? {
            if (name != null) {
                return ServiceCache.getService(name)
            }
            return null
        }

        @Throws(RemoteException::class)
        override fun addService(name: String?, service: IBinder?) {
            if (name != null && service != null) {
                ServiceCache.addService(name, service)
            }
        }

        @Throws(RemoteException::class)
        override fun removeService(name: String?) {
            if (name != null) {
                ServiceCache.removeService(name)
            }
        }
    }
}

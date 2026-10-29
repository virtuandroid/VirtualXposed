package com.lody.virtual.server.am

import android.app.ActivityManager
import android.app.IApplicationThread
import android.app.IServiceConnection
import android.app.IStopUserCallback
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.IIntentReceiver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import com.lody.virtual.IntentHelper.BROADCAST_CALLING_PACKAGE
import com.lody.virtual.IntentHelper.BROADCAST_COMPONENT
import com.lody.virtual.IntentHelper.BUNDLE_BINDER
import com.lody.virtual.IntentHelper.BUNDLE_CLIENT_BINDER
import com.lody.virtual.IntentHelper.BUNDLE_PID
import com.lody.virtual.IntentHelper.BUNDLE_VUID
import com.lody.virtual.IntentHelper.INTENT_EMBEDDED
import com.lody.virtual.IntentHelper.INTENT_USER_ID
import com.lody.virtual.client.IVClient
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.env.Constants
import com.lody.virtual.client.env.SpecialComponentList
import com.lody.virtual.client.ipc.ProviderCall
import com.lody.virtual.client.ipc.VNotificationManager
import com.lody.virtual.client.stub.StubCP.Companion.VA_INIT_PROCESS_METHOD
import com.lody.virtual.client.stub.VASettings
import com.lody.virtual.helper.compat.ActivityManagerCompat
import com.lody.virtual.helper.compat.ApplicationThreadCompat
import com.lody.virtual.helper.compat.BundleCompat
import com.lody.virtual.helper.compat.IApplicationThreadCompat
import com.lody.virtual.helper.utils.ComponentUtils
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.remote.AppTaskInfo
import com.lody.virtual.remote.BadgerInfo
import com.lody.virtual.remote.PendingIntentData
import com.lody.virtual.remote.PendingResultData
import com.lody.virtual.remote.VParceledListSlice
import com.lody.virtual.remote.logging.LogMessageHolder
import com.lody.virtual.server.IActivityManager
import com.lody.virtual.server.am.ServiceRecord.IntentBindRecord
import com.lody.virtual.server.interfaces.IProcessObserver
import com.lody.virtual.server.log.VLoggingManagerService
import com.lody.virtual.server.pm.PackageCacheManager
import com.lody.virtual.server.pm.PackageSetting
import com.lody.virtual.server.pm.VAppManagerService
import com.lody.virtual.server.pm.VPackageManagerService
import com.lody.virtual.server.secondary.BinderDelegateService
import com.lody.virtual.server.security.SecurityUtils.assertPackageMatchesCallingUid
import com.lody.virtual.server.security.SecurityUtils.enforceCallingUid
import com.lody.virtual.server.security.SecurityUtils.enforceCallingUser
import com.lody.virtual.server.security.SecurityUtils.enforceNotIsolatedCaller
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.LogMessage.AppDeath
import com.virtualxposed.log.client.LogMessage.BroadCastReceived
import mirror.android.app.IServiceConnectionO
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * @author Lody
 */
class VActivityManagerService : IActivityManager.Stub() {
    private data class ProcessIdentifier(val name: String?, val vuid: Int)

    /** Locks both mPidsSelfLocked and mProcessNames */
    private val pidLock = Any()

    /** Single source of truth. Maps PID to ProcessRecord */
    private val mPidsSelfLocked = ConcurrentHashMap<Int, ProcessRecord>()

    /** Maps ProcessIdentifier to PID */
    private val mProcessNames = ConcurrentHashMap<ProcessIdentifier, Int>()
    private val mHistory: MutableSet<ServiceRecord> = HashSet()
    private val mMainStack = ActivityStack(this)
    private val mPendingIntents = PendingIntents()
    private val am = VirtualCore.get().context
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val nm = VirtualCore.get().context
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun onCreate(context: Context) {
        AttributeCache.init(context)
        sService.set(this)
        val pm = context.packageManager
        val packageInfo = runCatching {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
            )
        }.onFailure {
            Timber.e(it)
        }.getOrNull()

        if (packageInfo == null) {
            throw RuntimeException("Unable to found PackageInfo : " + context.packageName)
        }
    }


    override fun startActivity(
        intent: Intent,
        info: ActivityInfo,
        resultTo: IBinder?,
        options: Bundle?,
        resultWho: String?,
        requestCode: Int,
        userId: Int
    ): Int {
        enforceNotIsolatedCaller("startActivity")
        enforceCallingUser(userId)
        val callingPackage = VBinder.getCallingPackageName()
        // TODO Permissions
        // TODO missing check for Intent component
        if (callingPackage != info.packageName && !info.exported) {
            throw SecurityException("Cannot start non-exported activity.")
        }

        synchronized(this) {
            return mMainStack.startActivityLocked(
                userId,
                intent,
                info,
                resultTo,
                options,
                resultWho,
                requestCode
            )
        }
    }

    override fun startActivities(
        intents: Array<Intent?>,
        resolvedTypes: Array<String?>?,
        token: IBinder?,
        options: Bundle?,
        userId: Int
    ): Int {
        enforceNotIsolatedCaller("startActivities")
        enforceCallingUser(userId)

        synchronized(this) {
            val infos = intents.map { intent ->
                VirtualCore.get().resolveActivityInfo(intent, userId)
                    ?: return ActivityManagerCompat.START_INTENT_NOT_RESOLVED
            }.toTypedArray()

            return mMainStack.startActivitiesLocked(
                userId,
                intents.filterNotNull().toTypedArray(),
                infos,
                resolvedTypes,
                token,
                options
            )
        }
    }

    override fun getPackageForIntentSender(binder: IBinder?): String? {
        val caller = VBinder.getCallingPackageName()
        val data = mPendingIntents.getPendingIntent(caller, binder)
        if (data != null) {
            return data.creator
        }
        return null
    }

    override fun getPendingIntent(binder: IBinder?): PendingIntentData? {
        val caller = VBinder.getCallingPackageName()
        return mPendingIntents.getPendingIntent(caller, binder)
    }

    override fun addPendingIntent(binder: IBinder, creator: String?) {
        val caller = VBinder.getCallingPackageName()
        mPendingIntents.addPendingIntent(caller, binder, creator)
    }

    override fun removePendingIntent(binder: IBinder?) {
        val caller = VBinder.getCallingPackageName()
        mPendingIntents.removePendingIntent(caller, binder)
    }

    override fun getSystemPid(): Int {
        return VirtualCore.get().myUid()
    }

    override fun onActivityCreated(
        component: ComponentName?,
        caller: ComponentName?,
        token: IBinder?,
        intent: Intent?,
        affinity: String?,
        taskId: Int,
        launchMode: Int,
        flags: Int
    ) {
        val pid = getCallingPid()
        val targetApp = findProcessLocked(pid) ?: return
        mMainStack.onActivityCreated(
            targetApp,
            component,
            caller,
            token,
            intent,
            affinity,
            taskId,
            launchMode,
            flags
        )
    }

    override fun onActivityResumed(userId: Int, token: IBinder?) {
        mMainStack.onActivityResumed(userId, token)
    }

    override fun onActivityDestroyed(userId: Int, token: IBinder?): Boolean {
        val r = mMainStack.onActivityDestroyed(userId, token)
        return r != null
    }

    override fun getTaskInfo(taskId: Int): AppTaskInfo? {
        return mMainStack.getTaskInfo(taskId)
    }

    override fun getPackageForToken(userId: Int, token: IBinder?): String? {
        return mMainStack.getPackageForToken(userId, token)
    }

    override fun getActivityClassForToken(userId: Int, token: IBinder?): ComponentName? {
        return mMainStack.getActivityClassForToken(userId, token)
    }


    private fun processDead(record: ProcessRecord) {
        synchronized(mHistory) {
            val iterator = mHistory.iterator()
            while (iterator.hasNext()) {
                val r = iterator.next()
                if (r.process != null && r.process.pid == record.pid) {
                    iterator.remove()
                }
            }
            mMainStack.processDied(record)
        }
    }


    override fun acquireProviderClient(userId: Int, info: ProviderInfo): IBinder? {
        enforceCallingUser(userId)
        val component = ComponentName(info.packageName, info.name)
        // We cannot trust any client input, therefore we retrieve our own provider
        // Notably this will also ensure that the provider is visible to the client (exported)
        val trustedProviderInfo =
            VPackageManagerService.get().getProviderInfo(component, 0, userId) ?: return null

        val processName = info.processName
        val r: ProcessRecord?
        synchronized(this) {
            r = startProcessIfNeedLocked(processName, userId, trustedProviderInfo.packageName)
        }
        if (r != null && r.client.asBinder().pingBinder()) {
            try {
                return r.client.acquireProviderClient(trustedProviderInfo)
            } catch (e: RemoteException) {
                Timber.e(e)
            }
        }
        return null
    }

    override fun getCallingActivity(userId: Int, token: IBinder?): ComponentName? {
        return mMainStack.getCallingActivity(userId, token)
    }

    override fun getCallingPackage(userId: Int, token: IBinder?): String? {
        return mMainStack.getCallingPackage(userId, token)
    }


    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } catch (e: Throwable) {
            Timber.e(e)
            throw e
        }
    }

    private fun addRecord(r: ServiceRecord) {
        synchronized(mHistory) {
            mHistory.add(r)
        }
    }

    private fun findRecordLocked(userId: Int, serviceInfo: ServiceInfo?): ServiceRecord? {
        synchronized(mHistory) {
            for (r in mHistory) {
                // If service is not created, and bindService with the flag that is
                // not BIND_AUTO_CREATE, r.process is null
                if ((r.process == null || r.process.userId == userId)
                    && ComponentUtils.isSameComponent(serviceInfo, r.serviceInfo)
                ) {
                    return r
                }
            }
            return null
        }
    }

    private fun findRecordLocked(connection: IServiceConnection?): ServiceRecord? {
        synchronized(mHistory) {
            for (r in mHistory) {
                if (r.containConnection(connection)) {
                    return r
                }
            }
            return null
        }
    }


    override fun startService(
        caller: IBinder?,
        service: Intent?,
        resolvedType: String?,
        userId: Int
    ): ComponentName? {
        enforceCallingUser(userId)
        synchronized(this) {
            return startServiceCommon(service, true, userId)
        }
    }

    private fun startServiceCommon(
        service: Intent?,
        scheduleServiceArgs: Boolean, userId: Int
    ): ComponentName? {
        // resolveServiceInfo gives null if permissions are not met
        val serviceInfo: ServiceInfo = resolveServiceInfo(service, userId) ?: return null
        val targetApp = startProcessIfNeedLocked(
            ComponentUtils.getProcessName(serviceInfo),
            userId,
            serviceInfo.packageName
        )

        if (targetApp == null) {
            VLog.e(
                TAG,
                "Unable to start new Process for : " + ComponentUtils.toComponentName(serviceInfo)
            )
            return null
        }

        val appThread = targetApp.appThread
        var r = findRecordLocked(userId, serviceInfo)
        if (r == null) {
            r = ServiceRecord()
            r.startId = 0
            r.activeSince = SystemClock.elapsedRealtime()
            r.process = targetApp
            r.serviceInfo = serviceInfo
            addRecord(r)
            try {
                IApplicationThreadCompat.scheduleCreateService(appThread, r, r.serviceInfo, 0)
            } catch (e: RemoteException) {
                Timber.e(e)
            }
        }
        r.lastActivityTime = SystemClock.uptimeMillis()
        if (scheduleServiceArgs) {
            r.startId++
            val taskRemoved = serviceInfo.applicationInfo != null
                    && serviceInfo.applicationInfo.targetSdkVersion < Build.VERSION_CODES.ECLAIR
            try {
                IApplicationThreadCompat.scheduleServiceArgs(
                    appThread,
                    r,
                    taskRemoved,
                    r.startId,
                    0,
                    service
                )
            } catch (e: RemoteException) {
                Timber.e(e)
            }
        }
        return ComponentUtils.toComponentName(serviceInfo)
    }

    override fun stopService(
        caller: IBinder?,
        service: Intent?,
        resolvedType: String?,
        userId: Int
    ): Int {
        enforceCallingUser(userId)
        synchronized(this) {
            // resolveServiceInfo gives null if permissions are not met
            val serviceInfo: ServiceInfo = resolveServiceInfo(service, userId) ?: return 0
            val r = findRecordLocked(userId, serviceInfo) ?: return 0
            this.stopServiceCommon(r, ComponentUtils.toComponentName(serviceInfo))
            return 1
        }
    }

    override fun stopServiceToken(
        className: ComponentName?,
        token: IBinder?,
        startId: Int,
        userId: Int
    ): Boolean {
        enforceCallingUser(userId)
        synchronized(this) {
            val r = token as? ServiceRecord ?: return false

            // Only the caller can stop services
            assertPackageMatchesCallingUid(r.serviceInfo.packageName)
            if (r.startId == startId || startId == -1) {
                stopServiceCommon(r, className)
                return true
            }
            return false
        }
    }

    private fun stopServiceCommon(r: ServiceRecord, className: ComponentName?) {
        for (bindRecord in r.bindings) {
            for (connection in bindRecord.connections) {
                // Report to all the connections that the service is no longer
                // available.
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        IServiceConnectionO.connected.call(connection, className, null, true)
                    } else {
                        connection.connected(className, null)
                    }
                } catch (e: RemoteException) {
                    Timber.e(e)
                }
            }
            try {
                IApplicationThreadCompat.scheduleUnbindService(
                    r.process.appThread,
                    r,
                    bindRecord.intent
                )
            } catch (e: RemoteException) {
                Timber.e(e)
            }
        }
        try {
            IApplicationThreadCompat.scheduleStopService(r.process.appThread, r)
        } catch (e: RemoteException) {
            Timber.e(e)
        }
        synchronized(mHistory) {
            mHistory.remove(r)
        }
    }

    override fun bindService(
        caller: IBinder?, token: IBinder?, service: Intent?, resolvedType: String?,
        connection: IServiceConnection, flags: Int, userId: Int
    ): Int {
        enforceCallingUser(userId)
        synchronized(this) {
            // resolveServiceInfo gives null if permissions are not met
            val serviceInfo: ServiceInfo = resolveServiceInfo(service, userId) ?: return 0

            val component = service!!.component
            val logMessage: LogMessage = LogMessage.BindService(
                serviceInfo.packageName,
                component?.className ?: "",
                serviceInfo.exported
            )
            val holder = LogMessageHolder.Builder(logMessage).build()
            VLoggingManagerService.get().log(holder)

            var r = findRecordLocked(userId, serviceInfo)
            val firstLaunch = r == null
            if (firstLaunch) {
                if ((flags and Context.BIND_AUTO_CREATE) != 0) {
                    startServiceCommon(service, false, userId)
                    r = findRecordLocked(userId, serviceInfo)
                }
            }
            if (r == null) {
                return 0
            }
            val boundRecord = r.peekBinding(service)
            if (boundRecord?.binder != null && boundRecord.binder.pingBinder()) {
                if (boundRecord.doRebind) {
                    try {
                        IApplicationThreadCompat.scheduleBindService(
                            r.process.appThread,
                            r,
                            service,
                            true,
                            0
                        )
                    } catch (e: RemoteException) {
                        Timber.e(e)
                    }
                }
                val componentName = ComponentName(r.serviceInfo.packageName, r.serviceInfo.name)
                connectService(connection, componentName, boundRecord, false)
            } else {
                try {
                    IApplicationThreadCompat.scheduleBindService(
                        r.process.appThread,
                        r,
                        service,
                        false,
                        0
                    )
                } catch (e: RemoteException) {
                    Timber.e(e)
                }
            }
            r.lastActivityTime = SystemClock.uptimeMillis()
            r.addToBoundIntent(service, connection)
            return 1
        }
    }


    override fun unbindService(connection: IServiceConnection?, userId: Int): Boolean {
        enforceCallingUser(userId)
        synchronized(this) {
            val r = findRecordLocked(connection) ?: return false

            for (bindRecord in r.bindings) {
                if (!bindRecord.containConnection(connection)) {
                    continue
                }
                bindRecord.removeConnection(connection)
                try {
                    IApplicationThreadCompat.scheduleUnbindService(
                        r.process.appThread,
                        r,
                        bindRecord.intent
                    )
                } catch (e: RemoteException) {
                    Timber.e(e)
                }
            }

            if (r.startId <= 0 && r.getConnectionCount() <= 0) {
                try {
                    IApplicationThreadCompat.scheduleStopService(r.process.appThread, r)
                } catch (e: RemoteException) {
                    Timber.e(e)
                }
            }
            return true
        }
    }

    override fun unbindFinished(token: IBinder?, service: Intent?, doRebind: Boolean, userId: Int) {
        enforceCallingUser(userId)
        synchronized(this) {
            val r = token as? ServiceRecord
            if (r != null) {
                assertPackageMatchesCallingUid(r.serviceInfo.packageName)
                val boundRecord = r.peekBinding(service)
                if (boundRecord != null) {
                    boundRecord.doRebind = doRebind
                }
            }
        }
    }


    override fun isVAServiceToken(token: IBinder?): Boolean {
        return token is ServiceRecord
    }


    override fun serviceDoneExecuting(
        token: IBinder?,
        type: Int,
        startId: Int,
        res: Int,
        userId: Int
    ) {
        enforceCallingUser(userId)
        synchronized(this) {
            val r = token as? ServiceRecord ?: return
            assertPackageMatchesCallingUid(r.serviceInfo.packageName)
            if (ActivityManagerCompat.SERVICE_DONE_EXECUTING_STOP == type) {
                synchronized(mHistory) {
                    mHistory.remove(r)
                }
            }
        }
    }

    override fun peekService(service: Intent?, resolvedType: String?, userId: Int): IBinder? {
        enforceCallingUser(userId)
        synchronized(this) {
            // resolveServiceInfo gives null if permissions are not met
            val serviceInfo: ServiceInfo = resolveServiceInfo(service, userId) ?: return null
            val r = findRecordLocked(userId, serviceInfo)
            if (r != null) {
                val boundRecord = r.peekBinding(service)
                if (boundRecord != null) {
                    return boundRecord.binder
                }
            }
            return null
        }
    }

    override fun publishService(token: IBinder?, intent: Intent?, service: IBinder?, userId: Int) {
        enforceCallingUser(userId)
        synchronized(this) {
            val r = token as? ServiceRecord
            if (r != null) {
                val boundRecord = r.peekBinding(intent)
                if (boundRecord != null) {
                    boundRecord.binder = service
                    for (conn in boundRecord.connections) {
                        val component = ComponentUtils.toComponentName(r.serviceInfo)
                        connectService(conn, component, boundRecord, false)
                    }
                }
            }
        }
    }

    private fun connectService(
        conn: IServiceConnection,
        component: ComponentName?,
        r: IntentBindRecord,
        dead: Boolean
    ) {
        try {
            val delegateService = BinderDelegateService(component, r.binder)
            if (Build.VERSION.SDK_INT >= 26) {
                IServiceConnectionO.connected.call(conn, component, delegateService, dead)
            } else {
                conn.connected(component, delegateService)
            }
        } catch (e: RemoteException) {
            Timber.e(e)
        }
    }

    override fun getServices(
        maxNum: Int,
        flags: Int,
        userId: Int
    ): VParceledListSlice<ActivityManager.RunningServiceInfo?> {
        enforceCallingUser(userId)
        synchronized(mHistory) {
            val services: MutableList<ActivityManager.RunningServiceInfo?> =
                ArrayList(mHistory.size)
            for (r in mHistory) {
                if (r.process.userId != userId) {
                    continue
                }
                val info = ActivityManager.RunningServiceInfo()
                info.uid = r.process.vuid
                info.pid = r.process.pid
                val processRecord = findProcessLocked(r.process.pid)
                if (processRecord != null) {
                    info.process = processRecord.processName
                    info.clientPackage = processRecord.info.packageName
                }
                info.activeSince = r.activeSince
                info.lastActivityTime = r.lastActivityTime
                info.clientCount = r.clientCount
                info.service = ComponentUtils.toComponentName(r.serviceInfo)
                info.started = r.startId > 0
                services.add(info)
            }
            return VParceledListSlice(services)
        }
    }

    override fun setServiceForeground(
        className: ComponentName?, token: IBinder?, id: Int, notification: Notification?,
        removeNotification: Boolean, userId: Int
    ) {
        enforceCallingUser(userId)
        val r = token as? ServiceRecord ?: return
        assertPackageMatchesCallingUid(r.serviceInfo.packageName)

        if (id != 0) {
            requireNotNull(notification) { "null notification" }
            if (r.foregroundId != id) {
                if (r.foregroundId != 0) {
                    cancelNotification(userId, r.foregroundId, r.serviceInfo.packageName)
                }
                r.foregroundId = id
            }
            r.foregroundNoti = notification
            postNotification(userId, id, r.serviceInfo.packageName, notification)
        } else {
            if (removeNotification) {
                cancelNotification(userId, r.foregroundId, r.serviceInfo.packageName)
                r.foregroundId = 0
                r.foregroundNoti = null
            }
        }
    }

    private fun cancelNotification(userId: Int, id: Int, pkg: String?) {
        val id = VNotificationManager.get().dealNotificationId(id, pkg, null, userId)
        val tag = VNotificationManager.get().dealNotificationTag(id, pkg, null, userId)
        nm.cancel(tag, id)
    }

    private fun postNotification(userId: Int, id: Int, pkg: String?, notification: Notification?) {
        val id = VNotificationManager.get().dealNotificationId(id, pkg, null, userId)
        val tag = VNotificationManager.get().dealNotificationTag(id, pkg, null, userId)
        //        VNotificationManager.get().dealNotification(id, notification, pkg);
        VNotificationManager.get().addNotification(id, tag, pkg, userId)
        try {
            nm.notify(tag, id, notification)
        } catch (e: Throwable) {
            Timber.e(e)
        }
    }

    override fun processRestarted(packageName: String?, processName: String?, userId: Int) {
        enforceCallingUser(userId)
        val callingPid = getCallingPid()
        val appId = VAppManagerService.get().getAppId(packageName)
        val uid = VUserHandle.getUid(userId, appId)
        synchronized(this) {
            val app = findProcessLocked(callingPid)
            if (app == null) {
                val appInfo =
                    VPackageManagerService.get().getApplicationInfo(packageName, 0, userId)
                        ?: return
                appInfo.flags = appInfo.flags or ApplicationInfo.FLAG_HAS_CODE
                val stubProcessName = getProcessName(callingPid)
                val vpid = parseVPid(stubProcessName)
                if (vpid != -1) {
                    performStartProcessLocked(uid, vpid, appInfo, processName)
                }
            }
        }
    }

    private fun parseVPid(stubProcessName: String?): Int {
        val prefix = VirtualCore.get().hostPkg + ":p"
        if (stubProcessName != null && stubProcessName.startsWith(prefix)) {
            try {
                return stubProcessName.substring(prefix.length).toInt()
            } catch (e: NumberFormatException) {
                // ignore
            }
        }
        return -1
    }


    private fun getProcessName(pid: Int): String? {
        am.runningAppProcesses?.forEach { info ->
            if (info.pid == pid) {
                return info.processName
            }
        }
        return null
    }


    private fun attachClient(pid: Int, clientBinder: IBinder?) {
        val client = IVClient.Stub.asInterface(clientBinder)
        if (client == null) {
            Process.killProcess(pid)
            return
        }
        var thread: IInterface? = null
        try {
            thread = ApplicationThreadCompat.asInterface(client.getAppThread())
        } catch (e: RemoteException) {
            // process has dead
        }
        if (thread == null) {
            Process.killProcess(pid)
            return
        }
        var app: ProcessRecord? = null
        try {
            val token = client.getToken()
            if (token is ProcessRecord) {
                app = token
            }
        } catch (e: RemoteException) {
            // process has dead
        }
        if (app == null) {
            Process.killProcess(pid)
            return
        }
        try {
            val record: ProcessRecord = app
            clientBinder!!.linkToDeath(object : IBinder.DeathRecipient {
                override fun binderDied() {
                    clientBinder.unlinkToDeath(this, 0)
                    onProcessDead(record)
                }
            }, 0)
        } catch (e: RemoteException) {
            Timber.e(e)
        }
        app.client = client
        app.appThread = thread
        app.pid = pid

        synchronized(pidLock) {
            mProcessNames[ProcessIdentifier(app.processName, app.vuid)] = pid
            mPidsSelfLocked[pid] = app
        }
    }

    private fun onProcessDead(record: ProcessRecord) {
        synchronized(pidLock) {
            mProcessNames.remove(ProcessIdentifier(record.processName, record.vuid), record.pid)
            mPidsSelfLocked.remove(record.pid, record)
        }

        processDead(record)
        record.lock.open()

        val message: LogMessage = AppDeath()
        val holder = LogMessageHolder.Builder(message)
            .setPackageName(record.processName)
            .setUserId(record.userId)
            .setPid(record.pid)
            .build()

        VLoggingManagerService.get().log(holder)
    }

    override fun getFreeStubCount(): Int {
        val currentProcesses = synchronized(pidLock) {
            mPidsSelfLocked.size
        }
        return VASettings.STUB_COUNT - currentProcesses
    }

    override fun initProcess(packageName: String?, processName: String?, userId: Int): Int {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(packageName)
        synchronized(this) {
            val r = startProcessIfNeedLocked(processName, userId, packageName)
            return r?.vpid ?: -1
        }
    }

    fun startProcessIfNeedLocked(
        processName: String?,
        userId: Int,
        packageName: String?
    ): ProcessRecord? {
        // TODO Implement a LRU and max process per guest app.
        // This can be abused for a DOS
        if (get().freeStubCount < 3) {
            // run GC
            killAllApps()
        }
        val ps = PackageCacheManager.getSetting(packageName)
        val info = VPackageManagerService.get().getApplicationInfo(packageName, 0, userId)
        if (ps == null || info == null) {
            return null
        }
        if (!ps.isLaunched(userId)) {
            sendFirstLaunchBroadcast(userId, ps)
            ps.setLaunched(userId, true)
            VAppManagerService.get().savePersistenceData()
        }
        val uid = VUserHandle.getUid(userId, ps.appId)
        var app = getProcessByName(processName, uid)
        if (app != null && app.client.asBinder().pingBinder()) {
            return app
        }
        val vpid = queryFreeStubProcessLocked()
        if (vpid == -1) {
            return null
        }
        app = performStartProcessLocked(uid, vpid, info, processName)
        app?.pkgList?.add(info.packageName)
        return app
    }

    private fun sendFirstLaunchBroadcast(userId: Int, ps: PackageSetting) {
        val intent = Intent(
            Intent.ACTION_PACKAGE_FIRST_LAUNCH,
            Uri.fromParts("package", ps.packageName, null)
        )
        intent.setPackage(ps.packageName)
        intent.putExtra(Intent.EXTRA_UID, VUserHandle.getUid(ps.appId, userId))
        intent.putExtra("android.intent.extra.user_handle", userId)
        sendBroadcastAsUser(intent, null)
    }

    // TODO prevent race conditions safety issues in process creation/death
    override fun getUidByPid(pid: Int): Int {
        if (pid == 0) {
            // TODO ensure invalid UIDs are handled properly
            return -1 // INVALID_UID
        }
        val myUid = Process.myUid()
        val r = findProcessLocked(pid)
        // TODO ensure that myUid does not collide with any vuid
        if (myUid == r?.vuid) {
            throw SecurityException("A guest app should never have the same VUID as the server process")
        }
        if (r != null) {
            return r.vuid
        }
        if (pid == mainPid || pid == Process.myPid()) {
            return myUid
        }
        return -1 // INVALID_UID
        // TODO check what happens to forked processes which may bypass mPidsSelfLocked
        // throw IllegalStateException("No UID found. This should not happen!")
    }

    private fun performStartProcessLocked(
        vuid: Int,
        vpid: Int,
        info: ApplicationInfo,
        processName: String?
    ): ProcessRecord? {
        val app = ProcessRecord(info, processName, vuid, vpid)
        val extras = Bundle()
        BundleCompat.putBinder(extras, BUNDLE_BINDER, app)
        extras.putInt(BUNDLE_VUID, vuid)
        val res = ProviderCall.call(
            VASettings.getStubAuthority(vpid),
            VA_INIT_PROCESS_METHOD,
            null,
            extras
        )
        if (res == null) {
            return null
        }
        val pid = res.getInt(BUNDLE_PID)
        val clientBinder = BundleCompat.getBinder(res, BUNDLE_CLIENT_BINDER)
        attachClient(pid, clientBinder)
        return app
    }

    private fun queryFreeStubProcessLocked(): Int {
        // Find first unused vpid in the range 0-49
        synchronized(pidLock) {
            repeat(VASettings.STUB_COUNT) { vpid ->
                val using = mPidsSelfLocked.values.any { r ->
                    r.vpid == vpid
                }

                if (!using) {
                    return vpid
                }
            }
        }

        return -1
    }

    override fun isAppProcess(processName: String?): Boolean {
        return parseVPid(processName) != -1
    }

    override fun isAppPid(pid: Int): Boolean {
        return findProcessLocked(pid) != null
    }

    override fun getAppProcessName(pid: Int): String? {
        synchronized(pidLock) {
            return mPidsSelfLocked[pid]?.processName
        }
    }

    fun getProcessByName(name: String?, vuid: Int): ProcessRecord? {
        synchronized(pidLock) {
            val pid = mProcessNames[ProcessIdentifier(name, vuid)] ?: return null
            return mPidsSelfLocked[pid]
        }
    }

    override fun getProcessPkgList(pid: Int): List<String?> {
        synchronized(pidLock) {
            return mPidsSelfLocked[pid]?.pkgList?.toList() ?: emptyList()
        }
    }

    override fun killAllApps() {
        val caller = VBinder.getCallingUid()
        // TODO Make a helper function to check system pid or engine pid
        val isSystem = VBinder.getCallingPid() == systemPid

        synchronized(pidLock) {
            mPidsSelfLocked.values.forEach {
                if (isSystem || it.vuid == caller) {
                    Process.killProcess(it.pid)
                }
            }
        }
    }

    override fun killAppByPkg(pkg: String?, userId: Int) {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(pkg)

        val uid = VBinder.getCallingUid()
        val isSystem = VBinder.getCallingPid() == systemPid

        synchronized(pidLock) {
            mProcessNames.entries.forEach { (process, pid) ->
                if (process.vuid == uid || isSystem) {
                    Process.killProcess(pid)
                }
            }
        }
    }

    override fun isAppRunning(packageName: String?, userId: Int): Boolean {
        enforceCallingUser(userId)
        assertPackageMatchesCallingUid(packageName)

        synchronized(pidLock) {
            return mPidsSelfLocked.values.any { r ->
                r.userId == userId && r.info.packageName == packageName
            }
        }
    }

    override fun killApplicationProcess(processName: String?, uid: Int) {
        enforceCallingUid(uid)
        synchronized(pidLock) {
            val pid = mProcessNames[ProcessIdentifier(processName, uid)]
            if (pid != null) {
                Process.killProcess(pid)
            }
        }
    }

    override fun dump() {
    }

    override fun registerProcessObserver(observer: IProcessObserver?) {
    }

    override fun unregisterProcessObserver(observer: IProcessObserver?) {
    }

    override fun getInitialPackage(pid: Int): String? {
        synchronized(pidLock) {
            return mPidsSelfLocked[pid]?.info?.packageName
        }
    }

    override fun handleApplicationCrash() {
        // Nothing
    }

    override fun appDoneExecuting() {
        val caller = VBinder.getCallingPid()
        synchronized(pidLock) {
            val r = mPidsSelfLocked[caller]
            if (r != null) {
                r.doneExecuting = true
                r.lock.open()
            }
        }
    }

    private fun findProcessLocked(pid: Int): ProcessRecord? {
        synchronized(pidLock) {
            return mPidsSelfLocked[pid]
        }
    }

    fun stopUser(userHandle: Int, stub: IStopUserCallback.Stub): Int {
        synchronized(pidLock) {
            mPidsSelfLocked.values.forEach { r ->
                if (r.userId == userHandle) {
                    Process.killProcess(r.pid)
                }
            }
        }

        try {
            stub.userStopped(userHandle)
        } catch (e: RemoteException) {
            Timber.e(e)
        }
        return 0
    }

    fun sendOrderedBroadcastAsUser(
        intent: Intent, user: VUserHandle?, receiverPermission: String?,
        resultReceiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int,
        initialData: String?, initialExtras: Bundle?
    ) {
        val context = VirtualCore.get().context
        if (user != null) {
            intent.putExtra(INTENT_USER_ID, user.identifier)
        }
        // TODO: checkPermission
        context.sendOrderedBroadcast(
            intent, null,  /* permission */resultReceiver, scheduler, initialCode, initialData,
            initialExtras
        )
    }

    fun sendBroadcastAsUser(intent: Intent, user: VUserHandle?) {
        SpecialComponentList.protectIntent(intent)
        val context = VirtualCore.get().context
        if (user != null) {
            intent.putExtra(INTENT_USER_ID, user.identifier)
        }
        context.sendBroadcast(intent)
    }

    fun sendBroadcastAsUser(intent: Intent, user: VUserHandle?, permission: String?) {
        SpecialComponentList.protectIntent(intent)
        val context = VirtualCore.get().context
        if (user != null) {
            intent.putExtra(INTENT_USER_ID, user.identifier)
        }
        // TODO: checkPermission
        context.sendBroadcast(intent)
    }

    fun handleStaticBroadcast(
        callingPackage: String,
        callingUserId: Int,
        appId: Int, info: ActivityInfo, intent: Intent,
        result: PendingResultData?
    ): Boolean {
        val realIntent = intent.getParcelableExtra<Intent?>(INTENT_EMBEDDED)
        val component = intent.getParcelableExtra<ComponentName?>(BROADCAST_COMPONENT)
        val userId = intent.getIntExtra(INTENT_USER_ID, VUserHandle.USER_NULL)
        if (realIntent == null) {
            return false
        }
        if (userId < 0) {
            VLog.w(TAG, "Sent a broadcast without userId $realIntent")
            return false
        }
        val vuid = VUserHandle.getUid(userId, appId)
        return handleUserBroadcast(
            callingPackage,
            callingUserId,
            vuid,
            info,
            component,
            realIntent,
            result
        )
    }

    private fun handleUserBroadcast(
        callingPackage: String,
        callingUserId: Int,
        vuid: Int,
        info: ActivityInfo,
        component: ComponentName?,
        realIntent: Intent,
        result: PendingResultData?
    ): Boolean {
        if (component != null && ComponentUtils.toComponentName(info) != component) {
            // Verify the component.
            return false
        }
        val originAction = SpecialComponentList.unprotectAction(realIntent.action)
        if (originAction != null) {
            // restore to origin action.
            realIntent.setAction(originAction)
        }

        val message = BroadCastReceived(realIntent.action ?: "", info.packageName)
        VLoggingManagerService.get().log(
            LogMessageHolder.Builder(message)
                .setPackageName(callingPackage)
                .setUserId(callingUserId)
                .build()
        )

        handleStaticBroadcastAsUser(vuid, info, realIntent, result)
        return true
    }

    private fun handleStaticBroadcastAsUser(
        vuid: Int, info: ActivityInfo, intent: Intent?,
        result: PendingResultData?
    ) {
        var r = getProcessByName(info.processName, vuid)
        if (isStartProcessForBroadcast(
                info.processName,
                info.packageName
            ) && r == null
        ) {
            r = startProcessIfNeedLocked(
                info.processName,
                VUserHandle.getUserId(vuid),
                info.packageName
            )
        }
        if (r?.appThread != null) {
            performScheduleReceiver(
                r.client, vuid, info, intent,
                result
            )
        }
    }

    private fun performScheduleReceiver(
        client: IVClient, vuid: Int, info: ActivityInfo, intent: Intent?,
        result: PendingResultData?
    ) {
        val componentName = ComponentUtils.toComponentName(info)
        BroadcastSystem.get().broadcastSent(vuid, info, result)
        try {
            client.scheduleReceiver(info.processName, componentName, intent, result)
        } catch (_: Throwable) {
            result?.finish()
        }
    }

    override fun broadcastFinish(res: PendingResultData) {
        BroadcastSystem.get().broadcastFinish(res)
    }

    @Throws(RemoteException::class)
    override fun notifyBadgerChange(info: BadgerInfo) {
        val intent = Intent(VASettings.ACTION_BADGER_CHANGE)
        intent.putExtra("userId", info.userId)
        intent.putExtra("packageName", info.packageName)
        intent.putExtra("badgerCount", info.badgerCount)
        VirtualCore.get().context.sendBroadcast(intent)
    }

    @Throws(RemoteException::class)
    override fun removeContentProvider(connection: IBinder?, stable: Boolean) {
    }

    @Throws(RemoteException::class)
    override fun broadcastIntentWithFeature(
        caller: IApplicationThread?,
        callingFeatureId: String?,
        intent: Intent,
        resolvedType: String?,
        resultTo: IIntentReceiver?,
        resultCode: Int,
        resultData: String?,
        map: Bundle?,
        requiredPermissions: Array<String?>?,
        excludePermissions: Array<String?>?,
        excludePackages: Array<String?>?,
        appOp: Int,
        options: Bundle?,
        serialized: Boolean,
        sticky: Boolean,
        userId: Int
    ): Int {
        // Android 11+ uses broadcastIntentWithFeature internally instead of sendBroadcast
        SpecialComponentList.protectIntent(intent)
        enforceCallingUser(userId)
        val caller = VBinder.getCallingPackageName()
        val context = VirtualCore.get().context
        // Prevent user ID spoofing in the intent
        intent.putExtra(INTENT_USER_ID, userId)
        intent.putExtra(BROADCAST_CALLING_PACKAGE, caller)
        // TODO use more sophisticated sendBroadcast to retain parameters
        context.sendBroadcast(intent)
        return 0
    }

    companion object {
        private const val BROADCAST_NOT_STARTED_PKG = false

        private var mainPid: Int? = null
        private val sService = AtomicReference<VActivityManagerService>()
        private val TAG: String = VActivityManagerService::class.java.getSimpleName()

        @JvmStatic
        fun get(): VActivityManagerService {
            return sService.get()
        }

        @JvmStatic
        fun systemReady(context: Context) {
            VActivityManagerService().onCreate(context)
        }

        @JvmStatic
        fun setMainPid(pid: Int) {
            mainPid = pid
        }

        private fun resolveServiceInfo(service: Intent?, userId: Int): ServiceInfo? {
            if (service != null) {
                val serviceInfo = VirtualCore.get().resolveServiceInfo(service, userId)
                if (serviceInfo != null) {
                    return serviceInfo
                }
            }
            return null
        }

        private fun isStartProcessForBroadcast(
            processName: String?,
            packageName: String?
        ): Boolean {
            return Constants.PRIVILEGE_APP.contains(packageName)
        }
    }
}
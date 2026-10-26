package com.lody.virtual.server.am

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Process
import androidx.core.content.ContextCompat
import com.lody.virtual.IntentHelper.BROADCAST_CALLING_PACKAGE
import com.lody.virtual.IntentHelper.BROADCAST_SPECIFIC_PACKAGE
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.env.SpecialComponentList
import com.lody.virtual.helper.collection.ArrayMap
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VBinder
import com.lody.virtual.remote.PendingResultData
import com.lody.virtual.server.pm.PackageSetting
import com.lody.virtual.server.pm.VAppManagerService
import com.lody.virtual.server.pm.parser.VPackage
import mirror.android.app.ContextImpl
import mirror.android.app.LoadedApkHuaWei
import mirror.android.rms.resource.ReceiverResourceLP
import mirror.android.rms.resource.ReceiverResourceM
import mirror.android.rms.resource.ReceiverResourceN
import timber.log.Timber
import java.util.Collections
import java.util.LinkedList

/**
 * @author Lody
 */
class BroadcastSystem private constructor(
    private val mContext: Context,
    private val mAMS: VActivityManagerService,
    private val mApp: VAppManagerService
) {
    private val mReceivers = ArrayMap<String, MutableList<BroadcastReceiver>>()
    private val mBroadcastRecords: MutableMap<IBinder?, BroadcastRecord?> =
        HashMap()
    private val mScheduler: StaticScheduler
    private val mTimeoutHandler: TimeoutHandler

    init {
        mScheduler = StaticScheduler()
        mTimeoutHandler = TimeoutHandler()
        fuckHuaWeiVerifier()
    }

    /**
     * FIX ISSUE #171:
     * java.lang.AssertionError: Register too many Broadcast Receivers
     * at android.app.LoadedApk.checkRecevierRegisteredLeakLocked(LoadedApk.java:772)
     * at android.app.LoadedApk.getReceiverDispatcher(LoadedApk.java:800)
     * at android.app.ContextImpl.registerReceiverInternal(ContextImpl.java:1329)
     * at android.app.ContextImpl.registerReceiver(ContextImpl.java:1309)
     * at com.lody.virtual.server.am.BroadcastSystem.startApp(BroadcastSystem.java:54)
     * at com.lody.virtual.server.pm.VAppManagerService.install(VAppManagerService.java:193)
     * at com.lody.virtual.server.pm.VAppManagerService.preloadAllApps(VAppManagerService.java:98)
     * at com.lody.virtual.server.pm.VAppManagerService.systemReady(VAppManagerService.java:70)
     * at com.lody.virtual.server.BinderProvider.onCreate(BinderProvider.java:42)
     */
    private fun fuckHuaWeiVerifier() {
        if (LoadedApkHuaWei.mReceiverResource != null) {
            val packageInfo = ContextImpl.mPackageInfo.get(mContext)
            if (packageInfo != null) {
                val receiverResource = LoadedApkHuaWei.mReceiverResource.get(packageInfo)
                if (receiverResource != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (ReceiverResourceN.mWhiteList != null) {
                            val whiteList = ReceiverResourceN.mWhiteList.get(receiverResource)
                            val newWhiteList: MutableList<String?> = ArrayList()
                            // Add our package name to the white list.
                            newWhiteList.add(mContext.packageName)
                            if (whiteList != null) {
                                newWhiteList.addAll(whiteList)
                            }
                            ReceiverResourceN.mWhiteList.set(receiverResource, newWhiteList)
                        }
                    } else {
                        if (ReceiverResourceM.mWhiteList != null) {
                            val whiteList = ReceiverResourceM.mWhiteList.get(receiverResource)
                            val newWhiteList: MutableList<String?> = LinkedList()
                            Collections.addAll<String?>(newWhiteList, *whiteList)
                            // Add our package name to the white list.
                            newWhiteList.add(mContext.packageName)
                            ReceiverResourceM.mWhiteList.set(
                                receiverResource,
                                newWhiteList.toTypedArray<String?>()
                            )
                        } else if (ReceiverResourceLP.mResourceConfig != null) {
                            // Just clear the ResourceConfig.
                            ReceiverResourceLP.mResourceConfig.set(receiverResource, null)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    fun startApp(p: VPackage) {
        val setting = p.mExtras as PackageSetting
        for (receiver in p.receivers) {
            val info = receiver.info
            var receivers = mReceivers[p.packageName]
            if (receivers == null) {
                receivers = ArrayList()
                mReceivers[p.packageName] = receivers
            }
            val componentAction = String.format("_VA_%s_%s", info.packageName, info.name)
            val componentFilter = IntentFilter(componentAction)
            val r: BroadcastReceiver = StaticBroadcastReceiver(setting.appId, info, componentFilter)
            // TODO Change permanent RECEIVER_EXPORTED to info.exported
            ContextCompat.registerReceiver(
                mContext,
                r,
                componentFilter,
                null,
                mScheduler,
                ContextCompat.RECEIVER_EXPORTED
            )
            receivers.add(r)
            for (ci in receiver.intents) {
                val cloneFilter = IntentFilter(ci.filter)
                SpecialComponentList.protectIntentFilter(cloneFilter)
                val r = StaticBroadcastReceiver(setting.appId, info, cloneFilter)
                ContextCompat.registerReceiver(
                    mContext,
                    r,
                    cloneFilter,
                    null,
                    mScheduler,
                    ContextCompat.RECEIVER_EXPORTED
                )
                receivers.add(r)
            }
        }
    }


    fun stopApp(packageName: String?) {
        synchronized(mBroadcastRecords) {
            val iterator = mBroadcastRecords.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val record = entry.value
                if (record?.receiverInfo?.packageName == packageName) {
                    record?.pendingResult?.finish()
                    iterator.remove()
                }
            }
        }
        synchronized(mReceivers) {
            val receivers = mReceivers[packageName]
            if (receivers != null) {
                for (r in receivers) {
                    mContext.unregisterReceiver(r)
                }
            }
            mReceivers.remove(packageName)
        }
    }

    fun broadcastFinish(res: PendingResultData) {
        synchronized(mBroadcastRecords) {
            val record = mBroadcastRecords.remove(res.mToken)
            if (record == null) {
                VLog.e(TAG, "Unable to find the BroadcastRecord by token: " + res.mToken)
            }
        }
        mTimeoutHandler.removeMessages(0, res.mToken)
        res.finish()
    }

    fun broadcastSent(vuid: Int, receiverInfo: ActivityInfo, res: PendingResultData?) {
        val record = BroadcastRecord(vuid, receiverInfo, res)
        synchronized(mBroadcastRecords) {
            mBroadcastRecords.put(res?.mToken, record)
        }
        val msg = Message()
        msg.obj = res?.mToken
        mTimeoutHandler.sendMessageDelayed(msg, BROADCAST_TIME_OUT.toLong())
    }

    private class StaticScheduler : Handler()

    private class BroadcastRecord(
        var vuid: Int,
        var receiverInfo: ActivityInfo,
        var pendingResult: PendingResultData?
    )

    private inner class TimeoutHandler : Handler() {
        override fun handleMessage(msg: Message) {
            val token = msg.obj as IBinder?
            val r = mBroadcastRecords.remove(token)
            if (r != null) {
                VLog.w(TAG, "Broadcast timeout, cancel to dispatch it.")
                r.pendingResult?.finish()
            }
        }
    }


    private inner class StaticBroadcastReceiver(
        private val appId: Int, private val info: ActivityInfo, private val filter: IntentFilter?
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            // Disallow apps from outside VirtualXposed from sending broadcasts
            if (VBinder.getCallingPid() != Process.myPid()) {
                return
            }
            // This cannot be spoofed since the caller is trusted.
            val callingPackage = intent.getStringExtra(BROADCAST_CALLING_PACKAGE)
            // Disallow private receivers
            if (callingPackage != info.packageName && !info.exported) {
                Timber.i("Blocked package $callingPackage from sending a broadcast to ${info.name}")
                // TODO add permission checks
                // TODO add stricter user ID tests
                return
            }
            if (mApp.isBooting) {
                return
            }
            if ((intent.flags and Intent.FLAG_RECEIVER_REGISTERED_ONLY) != 0 || isInitialStickyBroadcast) {
                return
            }
            val privilegePkg = intent.getStringExtra(BROADCAST_SPECIFIC_PACKAGE)
            if (privilegePkg != null && info.packageName != privilegePkg) {
                return
            }
            val result = goAsync()
            if (!mAMS.handleStaticBroadcast(appId, info, intent, PendingResultData(result))) {
                result.finish()
            }
        }
    }

    companion object {
        private val TAG: String = BroadcastSystem::class.java.getSimpleName()

        /**
         * MUST < 10000.
         */
        private const val BROADCAST_TIME_OUT = 8500

        @SuppressLint("StaticFieldLeak")
        private lateinit var gDefault: BroadcastSystem

        @JvmStatic
        fun attach(ams: VActivityManagerService, app: VAppManagerService) {
            gDefault = BroadcastSystem(VirtualCore.get().context.applicationContext, ams, app)
        }

        fun get(): BroadcastSystem {
            return gDefault
        }
    }
}

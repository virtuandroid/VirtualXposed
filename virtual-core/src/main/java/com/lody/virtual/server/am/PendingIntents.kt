package com.lody.virtual.server.am

import android.os.IBinder
import android.os.RemoteException
import com.lody.virtual.remote.PendingIntentData
import java.util.concurrent.ConcurrentHashMap

/**
 * @author Lody
 */
class PendingIntents {
    private val mLruHistory = ConcurrentHashMap<IBinder, PendingIntentData>()

    fun getPendingIntent(binder: IBinder?): PendingIntentData? {
        synchronized(mLruHistory) {
            return mLruHistory[binder]
        }
    }

    fun addPendingIntent(binder: IBinder, creator: String?) {
        synchronized(mLruHistory) {
            try {
                binder.linkToDeath(object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        binder.unlinkToDeath(this, 0)
                        mLruHistory.remove(binder)
                    }
                }, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }

            val pendingIntentData = mLruHistory[binder]
            if (pendingIntentData == null) {
                mLruHistory[binder] = PendingIntentData(creator, binder)
            } else {
                pendingIntentData.creator = creator
            }
        }
    }

    fun removePendingIntent(binder: IBinder?) {
        synchronized(mLruHistory) {
            mLruHistory.remove(binder)
        }
    }
}
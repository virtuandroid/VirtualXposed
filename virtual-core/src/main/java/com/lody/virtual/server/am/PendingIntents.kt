package com.lody.virtual.server.am

import android.os.IBinder
import android.os.RemoteException
import com.lody.virtual.remote.PendingIntentData

/**
 * @author Lody
 */
class PendingIntents {
    private val mLruHistory: MutableMap<String, MutableMap<IBinder, PendingIntentData>> = HashMap()

    fun getPendingIntent(packageName: String, binder: IBinder?): PendingIntentData? {
        synchronized(mLruHistory) {
            return mLruHistory[packageName]?.get(binder)
        }
    }

    fun addPendingIntent(packageName: String, binder: IBinder, creator: String?) {
        synchronized(mLruHistory) {
            try {
                binder.linkToDeath(object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        binder.unlinkToDeath(this, 0)
                        mLruHistory[packageName]?.remove(binder)
                    }
                }, 0)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }

            val pendingIntentData = mLruHistory[packageName]?.get(binder)
            if (pendingIntentData == null) {
                if (!mLruHistory.contains(packageName)) {
                    mLruHistory[packageName] = HashMap()
                }
                mLruHistory[packageName]?.put(binder, PendingIntentData(creator, binder))
            } else {
                pendingIntentData.creator = creator
            }
        }
    }

    fun removePendingIntent(packageName: String, binder: IBinder?) {
        synchronized(mLruHistory) {
            mLruHistory[packageName]?.remove(binder)
        }
    }
}
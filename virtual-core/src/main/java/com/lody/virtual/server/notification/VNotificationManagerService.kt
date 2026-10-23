package com.lody.virtual.server.notification

import android.app.NotificationManager
import android.content.Context
import android.text.TextUtils
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.server.INotificationManager
import java.util.concurrent.atomic.AtomicReference

class VNotificationManagerService private constructor(private val mContext: Context) :
    INotificationManager.Stub() {
    private val mNotificationManager: NotificationManager = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mDisables: MutableList<String?> = ArrayList()

    //VApp's Notifications
    private val mNotifications = HashMap<String?, MutableList<NotificationInfo>?>()

    /***
     * fake notification's id
     * 
     * @param id          notification's id
     * @param packageName notification's package
     * @param userId      user
     * @return
     */
    override fun dealNotificationId(id: Int, packageName: String?, tag: String?, userId: Int): Int {
        return id
    }

    /***
     * fake notification's tag
     * 
     * @param id          notification's id
     * @param packageName notification's package
     * @param tag         notification's tag
     * @param userId      user
     * @return
     */
    override fun dealNotificationTag(
        id: Int,
        packageName: String?,
        tag: String?,
        userId: Int
    ): String? {
        if (TextUtils.equals(mContext.packageName, packageName)) {
            return tag
        }
        if (tag == null) {
            return "$packageName@$userId"
        }
        return "$packageName:$tag@$userId"
    }

    override fun areNotificationsEnabledForPackage(packageName: String?, userId: Int): Boolean {
        return !mDisables.contains("$packageName:$userId")
    }

    override fun setNotificationsEnabledForPackage(
        packageName: String?,
        enable: Boolean,
        userId: Int
    ) {
        val key = "$packageName:$userId"
        if (enable) {
            if (mDisables.contains(key)) {
                mDisables.remove(key)
            }
        } else {
            if (!mDisables.contains(key)) {
                mDisables.add(key)
            }
        }
        //TODO: save mDisables ?
    }

    override fun addNotification(id: Int, tag: String?, packageName: String?, userId: Int) {
        val notificationInfo = NotificationInfo(id, tag, packageName, userId)
        synchronized(mNotifications) {
            var list = mNotifications[packageName]
            if (list == null) {
                list = mutableListOf()
                mNotifications[packageName] = list
            }
            if (!list.contains(notificationInfo)) {
                list.add(notificationInfo)
            }
        }
    }

    override fun cancelAllNotification(packageName: String?, userId: Int) {
        val infos: MutableList<NotificationInfo> = ArrayList<NotificationInfo>()
        synchronized(mNotifications) {
            val list = mNotifications[packageName]
            if (list != null) {
                val count = list.size
                for (i in count - 1 downTo 0) {
                    val info = list[i]
                    if (info.userId == userId) {
                        infos.add(info)
                        list.removeAt(i)
                    }
                }
            }
        }
        for ((id, tag) in infos) {
            VLog.d(TAG, "cancel $tag $id")
            mNotificationManager.cancel(tag, id)
        }
    }

    private data class NotificationInfo(
        var id: Int,
        var tag: String?,
        var packageName: String?,
        var userId: Int
    )

    companion object {
        private val gService = AtomicReference<VNotificationManagerService>()
        val TAG: String = NotificationCompat::class.java.getSimpleName()
        @JvmStatic
        fun systemReady(context: Context) {
            val instance = VNotificationManagerService(context)
            gService.set(instance)
        }

        @JvmStatic
        fun get(): VNotificationManagerService {
            return gService.get()
        }
    }
}

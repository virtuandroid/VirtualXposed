package com.lody.virtual.client.stub

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lody.virtual.IntentHelper.INTENT_EMBEDDED
import com.lody.virtual.IntentHelper.INTENT_USER_ID
import com.lody.virtual.client.ipc.VActivityManager

/**
 * @author Lody
 */
class StubPendingService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val realIntent = intent.getParcelableExtra<Intent?>(INTENT_EMBEDDED)
            val userId = intent.getIntExtra(INTENT_USER_ID, 0)
            if (realIntent != null) {
                VActivityManager.get().startService(null, realIntent, null, userId)
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }
}

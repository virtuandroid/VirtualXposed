package com.lody.virtual.client.stub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lody.virtual.IntentHelper.INTENT_EMBEDDED
import com.lody.virtual.IntentHelper.INTENT_USER_ID
import com.lody.virtual.helper.utils.ComponentUtils
import com.lody.virtual.os.VUserHandle

/**
 * @author Lody
 */
class StubPendingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val realIntent = intent.getParcelableExtra<Intent?>(INTENT_EMBEDDED)
        val userId = intent.getIntExtra(INTENT_USER_ID, VUserHandle.USER_ALL)
        if (realIntent != null) {
            val newIntent = ComponentUtils.redirectBroadcastIntent(realIntent, userId)
            context.sendBroadcast(newIntent)
        }
    }
}

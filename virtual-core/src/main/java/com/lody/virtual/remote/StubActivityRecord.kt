package com.lody.virtual.remote

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import com.lody.virtual.IntentHelper.INTENT_ACTIVITY_INFO
import com.lody.virtual.IntentHelper.INTENT_COMPONENT_CALLER
import com.lody.virtual.IntentHelper.INTENT_EMBEDDED
import com.lody.virtual.IntentHelper.INTENT_USER_ID

/**
 * @author Lody
 */
class StubActivityRecord {
    @JvmField
    var intent: Intent?
    @JvmField
    var info: ActivityInfo?
    @JvmField
    var caller: ComponentName?
    @JvmField
    var userId: Int

    constructor(intent: Intent?, info: ActivityInfo?, caller: ComponentName?, userId: Int) {
        this.intent = intent
        this.info = info
        this.caller = caller
        this.userId = userId
    }

    constructor(stub: Intent) {
        this.intent = stub.getParcelableExtra<Intent?>(INTENT_EMBEDDED)
        this.info = stub.getParcelableExtra<ActivityInfo?>(INTENT_ACTIVITY_INFO)
        this.caller = stub.getParcelableExtra<ComponentName?>(INTENT_COMPONENT_CALLER)
        this.userId = stub.getIntExtra(INTENT_USER_ID, 0)
    }

    fun saveToIntent(stub: Intent) {
        stub.putExtra(INTENT_EMBEDDED, intent)
        stub.putExtra(INTENT_ACTIVITY_INFO, info)
        stub.putExtra(INTENT_COMPONENT_CALLER, caller)
        stub.putExtra(INTENT_USER_ID, userId)
    }
}

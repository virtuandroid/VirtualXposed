package com.lody.virtual.server.am

import android.content.ComponentName
import android.os.IBinder

/**
 * @author Lody
 */
internal data class ActivityRecord(
    val task: TaskRecord,
    val component: ComponentName?,
    val caller: ComponentName?,
    val token: IBinder?,
    val userId: Int,
    val process: ProcessRecord,
    val launchMode: Int,
    val flags: Int,
    val affinity: String?
) {
    @Volatile
    var marked: Boolean = false
}

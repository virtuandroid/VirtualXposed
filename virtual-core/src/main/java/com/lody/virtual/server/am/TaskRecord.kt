package com.lody.virtual.server.am

import android.content.Intent
import android.os.SystemClock
import com.lody.virtual.remote.AppTaskInfo
import java.util.Collections

/**
 * @author Lody
 */
internal class TaskRecord(
    var taskId: Int,
    var userId: Int,
    var affinity: String?,
    var taskRoot: Intent?,
) {
    val activities: MutableList<ActivityRecord> =
        Collections.synchronizedList(ArrayList<ActivityRecord?>())
    val createdAt = SystemClock.elapsedRealtime()

    val appTaskInfo: AppTaskInfo?
        get() {
            val len = activities.size
            if (len == 0) {
                return null
            }
            val top = activities[len - 1].component
            return AppTaskInfo(taskId, taskRoot, taskRoot?.component, top)
        }

    val isFinishing: Boolean
        get() {
            var allFinish = true
            for (r in activities) {
                if (!r.marked) {
                    allFinish = false
                    break
                }
            }
            return allFinish
        }
}

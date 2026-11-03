package com.lody.virtual.server.am

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.env.VirtualRuntime
import com.lody.virtual.client.stub.VASettings
import com.lody.virtual.helper.utils.ArrayUtils
import com.lody.virtual.helper.utils.ClassUtils
import com.lody.virtual.helper.utils.ComponentUtils
import com.lody.virtual.remote.AppTaskInfo
import com.lody.virtual.remote.StubActivityRecord
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import mirror.android.app.ActivityManagerNative
import mirror.android.app.ActivityThread
import mirror.android.app.IActivityManager
import mirror.android.app.IApplicationThread
import mirror.com.android.internal.R_Hide
import timber.log.Timber

/**
 * @author Lody
 */
/* package */
internal class ActivityStack(private val mService: VActivityManagerService) {
    private val mAM: ActivityManager = VirtualCore.get().context
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * Key = TaskId, Value = TaskRecord
     */
    private val history = MutableStateFlow(persistentMapOf<Int, TaskRecord>())

    private fun deliverNewIntentLocked(
        sourceRecord: ActivityRecord?,
        targetRecord: ActivityRecord?,
        intent: Intent?
    ) {
        if (targetRecord == null) {
            return
        }
        val creator =
            if (sourceRecord != null) sourceRecord.component?.packageName else "android"
        try {
            targetRecord.process.client!!.scheduleNewIntent(creator, targetRecord.token, intent)
        } catch (e: RemoteException) {
            e.printStackTrace()
        } catch (npe: NullPointerException) {
            npe.printStackTrace()
        }
    }

    private fun findTaskByAffinityLocked(userId: Int, affinity: String): TaskRecord? {
        return this.history.value.values.firstOrNull { it.userId == userId && it.affinity == affinity }
    }

    private fun findTaskByIntentLocked(userId: Int, intent: Intent): TaskRecord? {
        return this.history.value.values.firstOrNull {
            it.userId == userId &&
                    it.taskRoot != null &&
                    intent.component == it.taskRoot!!.component
        }
    }

    private fun findActivityByToken(userId: Int, token: IBinder?): ActivityRecord? {
        val token = token ?: return null
        var target: ActivityRecord? = null
        this.history.value.values.forEach { task ->
            if (task.userId != userId) {
                return@forEach
            }

            synchronized(task.activities) {
                task.activities.forEach { r ->
                    if (r.token === token) {
                        target = r
                    }
                }
            }
        }

        return target
    }

    private fun markTaskByClearTarget(
        task: TaskRecord,
        clearTarget: ClearTarget,
        component: ComponentName?
    ): Boolean {
        var marked = false
        when (clearTarget) {
            ClearTarget.TASK -> {
                synchronized(task.activities) {
                    for (r in task.activities) {
                        r.marked = true
                        marked = true
                    }
                }
            }

            ClearTarget.SPEC_ACTIVITY -> {
                synchronized(task.activities) {
                    for (r in task.activities) {
                        if (r.component == component) {
                            r.marked = true
                            marked = true
                        }
                    }
                }
            }

            ClearTarget.TOP -> {
                synchronized(task.activities) {
                    var index = task.activities.size
                    while (index-- > 0) {
                        val r = task.activities[index]
                        if (r.component == component) {
                            marked = true
                            break
                        }
                    }
                    if (marked) {
                        while (index++ < task.activities.size - 1) {
                            task.activities[index].marked = true
                        }
                    }
                }
            }

            else -> {}
        }
        return marked
    }

    /**
     * App started in VA may be removed in OverView screen, then AMS.removeTask
     * will be invoked, all data struct about the task in AMS are released,
     * while the client's process is still alive. So remove related data in VA
     * as well. A new TaskRecord will be recreated in `onActivityCreated`
     */
    private fun optimizeTasks() {
        val snapshotAt = SystemClock.elapsedRealtime()

        val recent = try {
            mAM.appTasks.mapNotNull { it.taskInfo?.id }.toSet()
        } catch (e: Exception) {
            Timber.e(e)
            return
        }


        history.update { state ->
            state.filterNot { (_, task) ->
                // never prune tasks created after the snapshot was taken
                task.taskId !in recent && task.createdAt < snapshotAt - 2_000
            }.toPersistentMap()
        }
    }


    fun startActivitiesLocked(
        userId: Int,
        intents: Array<Intent>,
        infos: Array<ActivityInfo>,
        resolvedTypes: Array<String?>?,
        token: IBinder?,
        options: Bundle?
    ): Int {
        optimizeTasks()
        var reuseTarget = ReuseTarget.CURRENT
        val intent = intents.first()
        val info = infos.first()
        val resultTo = findActivityByToken(userId, token)
        if (resultTo != null && resultTo.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (containFlags(intent, Intent.FLAG_ACTIVITY_CLEAR_TOP)) {
            removeFlags(intent, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        if (containFlags(intent, Intent.FLAG_ACTIVITY_CLEAR_TASK) && !containFlags(
                intent,
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        ) {
            removeFlags(intent, Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        when (info.documentLaunchMode) {
            ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING -> reuseTarget = ReuseTarget.DOCUMENT
            ActivityInfo.DOCUMENT_LAUNCH_ALWAYS -> reuseTarget = ReuseTarget.MULTIPLE
        }
        if (containFlags(intent, Intent.FLAG_ACTIVITY_NEW_TASK)) {
            reuseTarget = if (containFlags(
                    intent,
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            ) ReuseTarget.MULTIPLE else ReuseTarget.AFFINITY
        } else if (info.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
            reuseTarget = if (containFlags(
                    intent,
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            ) ReuseTarget.MULTIPLE else ReuseTarget.AFFINITY
        }
        if (resultTo == null && reuseTarget == ReuseTarget.CURRENT) {
            reuseTarget = ReuseTarget.AFFINITY
        }
        val affinity = ComponentUtils.getTaskAffinity(info)
        var reuseTask: TaskRecord? = null
        when (reuseTarget) {
            ReuseTarget.AFFINITY -> {
                reuseTask = findTaskByAffinityLocked(userId, affinity)
            }

            ReuseTarget.CURRENT -> {
                reuseTask = resultTo!!.task
            }

            ReuseTarget.DOCUMENT -> {
                reuseTask = findTaskByIntentLocked(userId, intent)
            }

            else -> {}
        }
        val destIntents = startActivitiesProcess(userId, intents, infos, resultTo)
        if (reuseTask == null) {
            realStartActivitiesLocked(null, destIntents, resolvedTypes, options)
        } else {
            val top: ActivityRecord? = topActivityInTask(reuseTask)
            if (top != null) {
                realStartActivitiesLocked(top.token, destIntents, resolvedTypes, options)
            }
        }
        return 0
    }

    private fun startActivitiesProcess(
        userId: Int,
        intents: Array<Intent>,
        infos: Array<ActivityInfo>,
        resultTo: ActivityRecord?
    ): Array<Intent?> {
        val destIntents = arrayOfNulls<Intent>(intents.size)
        for (i in intents.indices) {
            destIntents[i] = startActivityProcess(userId, resultTo, intents[i], infos[i])
        }
        return destIntents
    }


    fun startActivityLocked(
        userId: Int, intent: Intent, info: ActivityInfo, resultTo: IBinder?, options: Bundle?,
        resultWho: String?, requestCode: Int
    ): Int {
        optimizeTasks()

        val destIntent: Intent?
        val sourceRecord = findActivityByToken(userId, resultTo)
        val sourceTask = sourceRecord?.task

        var reuseTarget = ReuseTarget.CURRENT
        var clearTarget = ClearTarget.NOTHING
        var clearTop: Boolean = containFlags(intent, Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val clearTask: Boolean = containFlags(intent, Intent.FLAG_ACTIVITY_CLEAR_TASK)

        if (intent.component == null) {
            intent.setComponent(ComponentName(info.packageName, info.name))
        }
        if (sourceRecord != null && sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (clearTop) {
            removeFlags(intent, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            clearTarget = ClearTarget.TOP
        }
        if (clearTask) {
            if (containFlags(intent, Intent.FLAG_ACTIVITY_NEW_TASK)) {
                clearTarget = ClearTarget.TASK
            } else {
                removeFlags(intent, Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
        when (info.documentLaunchMode) {
            ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING -> {
                clearTarget = ClearTarget.TASK
                reuseTarget = ReuseTarget.DOCUMENT
            }

            ActivityInfo.DOCUMENT_LAUNCH_ALWAYS -> reuseTarget = ReuseTarget.MULTIPLE
        }
        var singleTop = false

        when (info.launchMode) {
            ActivityInfo.LAUNCH_SINGLE_TOP -> {
                singleTop = true
                if (containFlags(intent, Intent.FLAG_ACTIVITY_NEW_TASK)) {
                    reuseTarget = if (containFlags(intent, Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                        ReuseTarget.MULTIPLE
                    else
                        ReuseTarget.AFFINITY
                }
            }

            ActivityInfo.LAUNCH_SINGLE_TASK -> {
                clearTop = false
                clearTarget = ClearTarget.TOP
                reuseTarget = if (containFlags(intent, Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                    ReuseTarget.MULTIPLE
                else
                    ReuseTarget.AFFINITY
            }

            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> {
                clearTop = false
                clearTarget = ClearTarget.TOP
                reuseTarget = ReuseTarget.AFFINITY
            }

            else -> {
                if (containFlags(intent, Intent.FLAG_ACTIVITY_SINGLE_TOP)) {
                    singleTop = true
                }
            }
        }
        if (clearTarget == ClearTarget.NOTHING) {
            if (containFlags(intent, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) {
                clearTarget = ClearTarget.SPEC_ACTIVITY
            }
        }
        if (sourceTask == null && reuseTarget == ReuseTarget.CURRENT) {
            reuseTarget = ReuseTarget.AFFINITY
        }

        val affinity = ComponentUtils.getTaskAffinity(info)
        var reuseTask: TaskRecord? = null
        when (reuseTarget) {
            ReuseTarget.AFFINITY -> reuseTask = findTaskByAffinityLocked(userId, affinity)
            ReuseTarget.DOCUMENT -> reuseTask = findTaskByIntentLocked(userId, intent)
            ReuseTarget.CURRENT -> reuseTask = sourceTask
            else -> {}
        }

        var taskMarked = false
        if (reuseTask == null) {
            startActivityInNewTaskLocked(userId, intent, info, options)
        } else {
            var delivered = false
            mAM.moveTaskToFront(reuseTask.taskId, 0)
            val startTaskToFront =
                !clearTask && !clearTop && ComponentUtils.isSameIntent(intent, reuseTask.taskRoot)

            if (clearTarget.deliverIntent || singleTop) {
                taskMarked = markTaskByClearTarget(reuseTask, clearTarget, intent.component)
                val topRecord: ActivityRecord? = topActivityInTask(reuseTask)
                if (clearTop && !singleTop && topRecord != null && taskMarked) {
                    topRecord.marked = true
                }
                // Target activity is on top
                if (topRecord != null && !topRecord.marked && topRecord.component == intent.component) {
                    deliverNewIntentLocked(sourceRecord, topRecord, intent)
                    delivered = true
                }
            }
            if (taskMarked) {
                scheduleFinishMarkedActivityLocked()
            }
            if (!startTaskToFront) {
                if (!delivered) {
                    destIntent = startActivityProcess(userId, sourceRecord, intent, info)
                    if (destIntent != null) {
                        startActivityFromSourceTask(
                            reuseTask,
                            destIntent,
                            info,
                            resultWho,
                            requestCode,
                            options
                        )
                    }
                }
            }
        }
        return 0
    }

    private fun startActivityInNewTaskLocked(
        userId: Int,
        intent: Intent?,
        info: ActivityInfo,
        options: Bundle?
    ) {
        val destIntent = startActivityProcess(userId, null, intent, info)
        if (destIntent != null) {
            destIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            destIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            destIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            destIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

            VirtualCore.get().context.startActivity(destIntent, options)
        }
    }

    private fun scheduleFinishMarkedActivityLocked() {
        this.history.value.values.forEach { task ->
            synchronized(task.activities) {
                task.activities.forEach { r ->
                    if (r.marked) {
                        VirtualRuntime.getUIHandler().post {
                            try {
                                r.process.client!!.finishActivity(r.token)
                            } catch (e: RemoteException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startActivityFromSourceTask(
        task: TaskRecord, intent: Intent, info: ActivityInfo, resultWho: String?,
        requestCode: Int, options: Bundle?
    ) {
        val top = task.activities.lastOrNull()
        if (top != null) {
            if (startActivityProcess(task.userId, top, intent, info) != null) {
                realStartActivityLocked(top.token, intent, resultWho, requestCode, options)
            }
        }
    }


    private fun realStartActivitiesLocked(
        resultTo: IBinder?,
        intents: Array<Intent?>?,
        resolvedTypes: Array<String?>?,
        options: Bundle?
    ) {
        val types = IActivityManager.startActivities.paramList()
        val args = arrayOfNulls<Any>(types.size)
        if (types[0] == IApplicationThread.TYPE) {
            args[0] = ActivityThread.getApplicationThread.call(VirtualCore.mainThread())
        }
        val pkgIndex = ArrayUtils.protoIndexOf(types, String::class.java)
        val intentsIndex = ArrayUtils.protoIndexOf(types, Array<Intent>::class.java)
        val resultToIndex = ArrayUtils.protoIndexOf(types, IBinder::class.java, 2)
        val optionsIndex = ArrayUtils.protoIndexOf(types, Bundle::class.java)
        val resolvedTypesIndex = intentsIndex + 1
        if (pkgIndex != -1) {
            args[pkgIndex] = VirtualCore.get().hostPkg
        }
        args[intentsIndex] = intents
        args[resultToIndex] = resultTo
        args[resolvedTypesIndex] = resolvedTypes
        args[optionsIndex] = options
        ClassUtils.fixArgs(types, args)
        IActivityManager.startActivities.call(
            ActivityManagerNative.getDefault.call(),
            *args
        )
    }

    private fun realStartActivityLocked(
        resultTo: IBinder?, intent: Intent, resultWho: String?, requestCode: Int,
        options: Bundle?
    ) {
        val types = IActivityManager.startActivity.paramList()
        val args = arrayOfNulls<Any>(types.size)
        if (types[0] == IApplicationThread.TYPE) {
            args[0] = ActivityThread.getApplicationThread.call(VirtualCore.mainThread())
        }
        val intentIndex = ArrayUtils.protoIndexOf(types, Intent::class.java)
        val resultToIndex = ArrayUtils.protoIndexOf(types, IBinder::class.java, 2)
        val optionsIndex = ArrayUtils.protoIndexOf(types, Bundle::class.java)
        val resolvedTypeIndex = intentIndex + 1
        val resultWhoIndex = resultToIndex + 1
        val requestCodeIndex = resultToIndex + 2

        args[intentIndex] = intent
        args[resultToIndex] = resultTo
        args[resultWhoIndex] = resultWho
        args[requestCodeIndex] = requestCode
        if (optionsIndex != -1) {
            args[optionsIndex] = options
        }
        args[resolvedTypeIndex] = intent.type
        args[intentIndex - 1] = VirtualCore.get().hostPkg
        ClassUtils.fixArgs(types, args)

        IActivityManager.startActivity.call(
            ActivityManagerNative.getDefault.call(),
            *args
        )
    }

    @Suppress("LocalVariableName")
    private fun fetchStubActivity(vpid: Int, targetInfo: ActivityInfo): String {
        var isFloating = false
        var isTranslucent = false
        var showWallpaper = false
        try {
            val R_Styleable_Window = R_Hide.styleable.Window.get()
            val R_Styleable_Window_windowIsTranslucent =
                R_Hide.styleable.Window_windowIsTranslucent.get()
            val R_Styleable_Window_windowIsFloating = R_Hide.styleable.Window_windowIsFloating.get()
            val R_Styleable_Window_windowShowWallpaper =
                R_Hide.styleable.Window_windowShowWallpaper.get()

            val ent = AttributeCache.instance().get(
                targetInfo.packageName, targetInfo.theme,
                R_Styleable_Window
            )
            if (ent?.array != null) {
                showWallpaper = ent.array.getBoolean(R_Styleable_Window_windowShowWallpaper, false)
                isTranslucent = ent.array.getBoolean(R_Styleable_Window_windowIsTranslucent, false)
                isFloating = ent.array.getBoolean(R_Styleable_Window_windowIsFloating, false)
            } else {
                val resources = VirtualCore.get().getResources(targetInfo.packageName)
                if (resources != null) {
                    val typedArray = resources.newTheme()
                        .obtainStyledAttributes(targetInfo.theme, R_Styleable_Window)
                    showWallpaper =
                        typedArray.getBoolean(R_Styleable_Window_windowShowWallpaper, false)
                    isTranslucent =
                        typedArray.getBoolean(R_Styleable_Window_windowIsTranslucent, false)
                    isFloating =
                        typedArray.getBoolean(R_Styleable_Window_windowIsFloating, false)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        // deal with manifest Activity style android:excludeFromRecents="true", becasue Mobile Legends has two recent task
        val isExcludeFromRecents =
            ((targetInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0)
        if (isExcludeFromRecents) {
            return VASettings.getStubExcludeFromRecentActivityName(vpid)
        }

        val isDialogStyle = isFloating || isTranslucent || showWallpaper
        return if (isDialogStyle) {
            VASettings.getStubDialogName(vpid)
        } else {
            VASettings.getStubActivityName(vpid)
        }
    }

    private fun startActivityProcess(
        userId: Int,
        sourceRecord: ActivityRecord?,
        intent: Intent?,
        info: ActivityInfo
    ): Intent? {
        val intentCopy = Intent(intent)
        val targetApp =
            mService.startProcessIfNeed(info.processName, userId, info.packageName) ?: return null
        val targetIntent = Intent()
        targetIntent.setClassName(
            VirtualCore.get().hostPkg,
            fetchStubActivity(targetApp.vpid, info)
        )
        val component = intentCopy.component ?: ComponentUtils.toComponentName(info)
        targetIntent.setType(component.flattenToString())
        val saveInstance = StubActivityRecord(
            intentCopy, info,
            sourceRecord?.component, userId
        )
        saveInstance.saveToIntent(targetIntent)
        return targetIntent
    }

    fun onActivityCreated(
        targetApp: ProcessRecord,
        component: ComponentName?,
        caller: ComponentName?,
        token: IBinder?,
        taskRoot: Intent,
        affinity: String?,
        taskId: Int,
        launchMode: Int,
        flags: Int
    ) {
        optimizeTasks()

        val task = history.updateAndGet { state ->
            if (state.contains(taskId)) {
                state
            } else {
                state.putting(taskId, TaskRecord(taskId, targetApp.userId, affinity, taskRoot))
            }
        }.getValue(taskId)

        val record = ActivityRecord(
            task, component, caller, token, targetApp.userId, targetApp,
            launchMode, flags, affinity
        )
        task.activities.add(record)
    }

    fun onActivityResumed(userId: Int, token: IBinder?) {
        optimizeTasks()
        val r = findActivityByToken(userId, token)
        if (r != null) {
            r.task.activities.remove(r)
            r.task.activities.add(r)
        }
    }

    fun onActivityDestroyed(userId: Int, token: IBinder?): ActivityRecord? {
        optimizeTasks()
        val r = findActivityByToken(userId, token)
        r?.task?.activities?.remove(r)
        return r
    }

    fun processDied(record: ProcessRecord) {
        optimizeTasks()

        this.history.value.values.forEach { task ->
            synchronized(task.activities) {
                val iterator = task.activities.iterator()
                while (iterator.hasNext()) {
                    val r = iterator.next()
                    if (r.process.pid == record.pid) {
                        iterator.remove()
                        if (task.activities.isEmpty()) {
                            history.update {
                                it.removing(task.taskId)
                            }
                        }
                    }
                }
            }
        }
    }

    fun getPackageForToken(userId: Int, token: IBinder?): String? {
        return findActivityByToken(userId, token)?.component?.packageName
    }

    fun getCallingActivity(userId: Int, token: IBinder?): ComponentName? {
        val r = findActivityByToken(userId, token)
        if (r != null) {
            return r.caller ?: r.component
        }
        return null
    }

    fun getCallingPackage(userId: Int, token: IBinder?): String {
        val r = findActivityByToken(userId, token)
        if (r != null) {
            return if (r.caller != null) r.caller.packageName else "android"
        }
        return "android"
    }

    fun getTaskInfo(taskId: Int): AppTaskInfo? {
        return history.value[taskId]?.appTaskInfo
    }

    fun getActivityClassForToken(userId: Int, token: IBinder?): ComponentName? {
        return findActivityByToken(userId, token)?.component
    }

    private enum class ClearTarget(val deliverIntent: Boolean = false) {
        NOTHING,
        SPEC_ACTIVITY,
        TASK(true),
        TOP(true)
    }

    private enum class ReuseTarget {
        CURRENT, AFFINITY, DOCUMENT, MULTIPLE
    }

    companion object {
        private fun removeFlags(intent: Intent, flags: Int) {
            intent.setFlags(intent.flags and flags.inv())
        }

        private fun containFlags(intent: Intent, flags: Int): Boolean {
            return (intent.flags and flags) != 0
        }

        private fun topActivityInTask(task: TaskRecord): ActivityRecord? {
            synchronized(task.activities) {
                for (size in task.activities.indices.reversed()) {
                    val r = task.activities[size]
                    if (!r.marked) {
                        return r
                    }
                }
                return null
            }
        }
    }
}
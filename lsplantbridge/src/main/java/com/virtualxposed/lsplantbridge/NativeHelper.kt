package com.virtualxposed.lsplantbridge

import android.app.Application
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.VLoggingClient

object NativeHelper {
    // Dirty context helper, should be replaced
    private fun getGlobalContext(): Application? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            currentApplicationMethod.invoke(null) as? Application
        }.getOrNull()
    }

    @JvmStatic
    fun logNativeCodeLoad(path: String) {
        VLoggingClient.get().log(LogMessage.CodeLoad(path, LogMessage.CodeLoadMethod.Native))
    }

    @JvmStatic
    fun logNativeExec(path: String) {
        VLoggingClient.get().log(LogMessage.Exec(path))
    }

    @JvmStatic
    fun logNativeKill(pid: Int) {
        val context = getGlobalContext()
        val activityManager =
            context?.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val processName =
            activityManager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName

        VLoggingClient.get().log(LogMessage.AppKill(pid, processName))
    }
}
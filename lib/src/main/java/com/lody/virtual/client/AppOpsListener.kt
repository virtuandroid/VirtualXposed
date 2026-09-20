package com.lody.virtual.client

import android.app.AppOpsManager
import android.app.AsyncNotedAppOp
import android.app.SyncNotedAppOp
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.VLoggingClient

class AppOpsListener(val context: Context) {
    @RequiresApi(Build.VERSION_CODES.R)
    fun start() {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

        val callback = object : AppOpsManager.OnOpNotedCallback() {
            override fun onAsyncNoted(asyncNotedAppOp: AsyncNotedAppOp) {
                val permission = opToPermission(asyncNotedAppOp.op) ?: return
                VLoggingClient.log(LogMessage.UsePermission(permission))
            }

            override fun onNoted(op: SyncNotedAppOp) {
                val permission = opToPermission(op.op) ?: return
                   VLoggingClient.log(LogMessage.UsePermission(permission))
            }

            override fun onSelfNoted(op: SyncNotedAppOp) {
                val permission = opToPermission(op.op) ?: return
                VLoggingClient.log(LogMessage.UsePermission(permission))
            }
        }

        appOpsManager?.setOnOpNotedCallback(context.mainExecutor, callback)
    }

    private fun opToPermission(op: String?): String? {
        if (op == null) return null
        return runCatching {
            val method = AppOpsManager::class.java.getMethod("opToPermission", String::class.java)
            method.invoke(null, op) as? String
        }.getOrNull()
    }
}
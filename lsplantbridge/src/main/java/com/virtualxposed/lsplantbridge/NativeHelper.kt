package com.virtualxposed.lsplantbridge

import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.VLoggingClient

object NativeHelper {
    @JvmStatic
    fun logNativeCodeLoad(path: String) {
        VLoggingClient.get().log(LogMessage.CodeLoad(path, LogMessage.CodeLoadMethod.Native))
    }
}
package com.virtualxposed.log.client

import com.virtualxposed.log.server.IVLoggingService

interface VLoggingAttacher {
    fun getInterface(): IVLoggingService?
}
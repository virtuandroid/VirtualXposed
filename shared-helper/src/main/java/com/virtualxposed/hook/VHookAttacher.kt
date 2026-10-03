package com.virtualxposed.hook

import com.virtualxposed.hook.server.IVHookService


interface VHookAttacher {
    fun getInterface(): IVHookService?

}
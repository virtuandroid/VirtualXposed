package com.virtualxposed.lsplantbridge

import java.lang.reflect.Member
import java.lang.reflect.Method


class HookManager {
    private val hooks: MutableMap<Member, MethodHooker> = mutableMapOf()

    fun getOriginalMethod(target: Member): Method? {
        return hooks[target]?.backup
    }

    fun hook(
        target: Member,
        callback: (originalMethod: Method, args: Array<Any?>) -> Any
    ): MethodHooker {
        val methodHook = MethodHooker(target, callback)
        methodHook.hook()
        hooks[target] = methodHook
        return methodHook
    }
}
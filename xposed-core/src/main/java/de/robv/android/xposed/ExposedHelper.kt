package de.robv.android.xposed

import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import java.lang.reflect.Member
import androidx.tracing.trace
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.VLoggingClient
import com.virtualxposed.lsplantbridge.HookManager
import com.virtualxposed.xposed.BuildConfig
import timber.log.Timber
import java.lang.reflect.Modifier

/**
 * Created by weishu on 17/11/30.
 */
object ExposedHelper {
    private val hookManager = HookManager()

    @JvmStatic
    fun initSeLinux(processName: String?) {
        SELinuxHelper.initOnce()
        SELinuxHelper.initForProcess(processName)
    }

    @JvmStatic
    fun isIXposedMod(moduleClass: Class<*>): Boolean {
        Timber.d(
            "Module's classLoader: %s module super class: %s",
            moduleClass.getClassLoader(),
            moduleClass.getSuperclass()
        )
        Timber.d("IXposedMod's classLoader: %s", IXposedMod::class.java.getClassLoader())

        return IXposedMod::class.java.isAssignableFrom(moduleClass)
    }


    @JvmStatic
    fun newUnHook(methodHook: XC_MethodHook?, member: Member?): XC_MethodHook.Unhook? {
        return methodHook?.Unhook(member)
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun callInitZygote(modulePath: String?, moduleInstance: Any) {
        val param = IXposedHookZygoteInit.StartupParam()
        param.modulePath = modulePath
        param.startsSystemServer = false
        (moduleInstance as IXposedHookZygoteInit).initZygote(param)
    }

    @Throws(Throwable::class)
    fun beforeHookedMethod(methodHook: XC_MethodHook, param: MethodHookParam?) {
        methodHook.beforeHookedMethod(param)
    }

    @Throws(Throwable::class)
    fun afterHookedMethod(methodHook: XC_MethodHook, param: MethodHookParam?) {
        methodHook.afterHookedMethod(param)
    }

    @JvmStatic
    fun createHook(target: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        if (!HookVerifier.isHookAllowed(target)) {
            return callback.Unhook(target)
        }

        Timber.i("Hooking method with LSPosed. Target is: $target")

        VLoggingClient.get().log(
            LogMessage.HookAttach(
                target.name,
                target.javaClass.simpleName,
                target.declaringClass.name
            )
        )

        return trace("Method hook") {
            hookMember(target, callback)
        }
    }

    @JvmStatic
    fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>): Any? {
        return hookManager.getOriginalMethod(method)?.invoke(thisObject, *args)
    }







    @Suppress("NOTHING_TO_INLINE")
    // We want this inlined to prevent security bypasses with reflection.
    private inline fun hookMember(target: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val hooker = hookManager.hook(target) { oldMethod, args ->
            val params = MethodHookParam()
            Timber.d("Executing hooked method: ${oldMethod.name}")

            // TODO Checks for:
            // - Abstract classes
            // - Hooking this code
            // - Recursive hooks (Method.invoke, Constructor.newInstance, getClass)
            val isStatic = Modifier.isStatic(target.modifiers)
            val thisObject = if (isStatic) null else args[0]
            val actualArgs = if (isStatic) args else args.sliceArray(1 until args.size)

            params.args = actualArgs
            params.method = oldMethod
            params.thisObject = thisObject

            runCatching {
                callback.beforeHookedMethod(params)
            }.onFailure { throwable ->
                Timber.e(throwable)
                params.throwable = throwable
            }

            if (params.returnEarly) {
                return@hook params.result
                // TODO Log early result!
            }

            try {
                val realResult = oldMethod.invoke(thisObject, *actualArgs)
                params.result = if (oldMethod.returnType == Void.TYPE) {
                    Unit
                } else {
                    realResult
                }
            } catch (t: Throwable) {
                Timber.e(t)
                params.throwable = t
            }

            runCatching {
                callback.afterHookedMethod(params)
            }.onFailure { throwable ->
                if (BuildConfig.DEBUG) {
                    throwable.printStackTrace()
                }
            }

            val finalResult = if (params.throwable != null) {
                throw params.throwable
            } else {
                params.result
            }

            finalResult ?: Unit
        }

        return callback.Unhook(hooker.backup)
    }
}

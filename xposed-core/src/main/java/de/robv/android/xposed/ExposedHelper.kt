package de.robv.android.xposed;

import java.lang.reflect.Member;

import timber.log.Timber;

/**
 * Created by weishu on 17/11/30.
 */
public class ExposedHelper {

    private static final String TAG = "ExposedHelper";

    public static void initSeLinux(String processName) {
        SELinuxHelper.initOnce();
        SELinuxHelper.initForProcess(processName);
    }

    public static boolean isIXposedMod(Class<?> moduleClass) {
        Timber.d("Module's classLoader: %s module super class: %s", moduleClass.getClassLoader(), moduleClass.getSuperclass());
        Timber.d("IXposedMod's classLoader: %s", IXposedMod.class.getClassLoader());

        return IXposedMod.class.isAssignableFrom(moduleClass);
    }


    public static XC_MethodHook.Unhook newUnHook(XC_MethodHook methodHook, Member member) {
        return methodHook.new Unhook(member);
    }

    public static void callInitZygote(String modulePath, Object moduleInstance) throws Throwable {
        IXposedHookZygoteInit.StartupParam param = new IXposedHookZygoteInit.StartupParam();
        param.modulePath = modulePath;
        param.startsSystemServer = false;
        ((IXposedHookZygoteInit) moduleInstance).initZygote(param);
    }

    public static void beforeHookedMethod(XC_MethodHook methodHook, XC_MethodHook.MethodHookParam param) throws Throwable{
        methodHook.beforeHookedMethod(param);
    }

    public static void afterHookedMethod(XC_MethodHook methodHook, XC_MethodHook.MethodHookParam param) throws Throwable{
        methodHook.afterHookedMethod(param);
    }
}

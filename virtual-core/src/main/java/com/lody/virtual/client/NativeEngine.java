package com.lody.virtual.client;

import android.os.Binder;
import android.os.Build;
import android.os.Process;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.natives.NativeMethods;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.DeviceUtil;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.InstalledAppInfo;
import com.virtualxposed.log.client.LogMessage;
import com.virtualxposed.log.client.VLoggingClient;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/**
 * VirtualApp Native Project
 */
public class NativeEngine {
    private static final String TAG = NativeEngine.class.getSimpleName();

    // TODO REMOVE ESCAPE PATH!
    private static final String VESCAPE = "/6decacfa7aad11e8a718985aebe4663a";

    private static Map<String, InstalledAppInfo> sDexOverrideMap;

    private static boolean sFlag = false;

    private static final String LIB_NAME = "va++";

    static {
        try {
            System.loadLibrary(LIB_NAME);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    static {
        NativeMethods.init();
    }


    public static void startDexOverride() {
        List<InstalledAppInfo> installedAppInfos = VirtualCore.get().getInstalledApps(0);
        sDexOverrideMap = new HashMap<>(installedAppInfos.size());
        for (InstalledAppInfo info : installedAppInfos) {
            sDexOverrideMap.put(new File(info.apkPath).getAbsolutePath(), info);
        }
    }

    public static String getRedirectedPath(String redirectPath) {
        try {
            return nativeGetRedirectedPath(redirectPath);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
        return redirectPath;
    }

    public static String resverseRedirectedPath(String origPath) {
        try {
            return nativeReverseRedirectedPath(origPath);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
        return origPath;
    }

    public static void redirectDirectory(String origPath, String newPath) {
        Timber.d("Redirect path: %s -> %s", origPath, newPath);

        if (!origPath.endsWith("/")) {
            origPath = origPath + "/";
        }
        if (!newPath.endsWith("/")) {
            newPath = newPath + "/";
        }
        try {
            nativeIORedirect(origPath, newPath);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static String getEscapePath(String path) {
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (file.exists()) {
            return file.getAbsolutePath();
        }
        return new File(VESCAPE, path).getAbsolutePath();
    }

    public static void redirectFile(String origPath, String newPath) {
        if (origPath.endsWith("/")) {
            origPath = origPath.substring(0, origPath.length() - 1);
        }
        if (newPath.endsWith("/")) {
            newPath = newPath.substring(0, newPath.length() - 1);
        }

        try {
            nativeIORedirect(origPath, newPath);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void whitelist(String path, boolean directory) {
        Timber.d("Whitelisted path: %s", path);

        if (directory && !path.endsWith("/")) {
            path = path + "/";
        } else if (!directory && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            nativeIOWhitelist(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    /** Log all file access to this path, except for whitelisted paths, to prevent log clutter. */
    public static void log(String path) {
        Timber.d("Notify path: %s", path);

        if (!path.endsWith("/")) {
            path = path + "/";
        }
        try {
            nativeIOLog(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void forbid(String path) {
        Timber.d("Forbid path: %s", path);

        if (!path.endsWith("/")) {
            path = path + "/";
        }
        try {
            nativeIOForbid(path);
        } catch (Throwable e) {
            VLog.e(TAG, VLog.getStackTraceString(e));
        }
    }

    public static void enableIORedirect() {
        try {
            String sourceDir = VirtualCore.get().getContext().getApplicationInfo().nativeLibraryDir;
            String libPath = File.separator + "lib" + LIB_NAME + ".so";
            String soPath = sourceDir + libPath;
            if (!new File(soPath).exists()) {
                throw new RuntimeException("IO redirect failed, missing so library: " + soPath);
            }
            redirectDirectory(VESCAPE, "/");
            nativeEnableIORedirect(soPath, Build.VERSION.SDK_INT, BuildCompat.getPreviewSDKInt());
        } catch (Throwable e) {
            Timber.e(e);
        }
    }

    static void launchEngine() {
        if (sFlag) {
            return;
        }
        Method[] methods = {NativeMethods.gOpenDexFileNative, NativeMethods.gCameraNativeSetup, NativeMethods.gAudioRecordNativeCheckPermission};
        try {
            nativeLaunchEngine(methods, VirtualCore.get().getHostPkg(), VirtualRuntime.isArt(), Build.VERSION.SDK_INT, NativeMethods.gCameraMethodType);
        } catch (Throwable e) {
            Timber.e(e);
        }
        sFlag = true;
    }

    public static void onKillProcess(int pid, int signal) {
        VLog.e(TAG, "killProcess: pid = %d, signal = %d.", pid, signal);
        if (pid == android.os.Process.myPid()) {
            VLog.e(TAG, VLog.getStackTraceString(new Throwable()));
        }
    }

    public static int onGetCallingUid(int originUid) {
        int callingPid = Binder.getCallingPid();

        // Sidestep ApplicationThread.onTransact security issues
        if (Build.VERSION.SDK_INT >= 37) {
            if (callingPid == Process.ROOT_UID) {
                return Process.SHELL_UID;
            }
        }

        if (callingPid == Process.myPid()) {
            return VClientImpl.get().getVUid();
        }
        if (callingPid == VirtualCore.get().getSystemPid()) {
            return Process.SYSTEM_UID;
        }
        int vuid = VActivityManager.get().getUidByPid(callingPid);
        if (vuid != -1) {
            return VUserHandle.getAppId(vuid);
        }
        VLog.w(TAG, String.format("Unknown uid: %s", callingPid));
        return VClientImpl.get().getVUid();
    }

    public static void onOpenDexFileNative(String[] params) {
        String dexOrJarPath = params[0];
        VLoggingClient.get().log(new LogMessage.CodeLoad(dexOrJarPath, LogMessage.CodeLoadMethod.APK));
        String outputPath = params[1];
        VLog.d(TAG, "DexOrJarPath = %s, OutputPath = %s.", dexOrJarPath, outputPath);
        String canonical = new File(dexOrJarPath).getAbsolutePath();
        InstalledAppInfo info = sDexOverrideMap.get(canonical);
        if (info != null && !info.dependSystem || info != null && DeviceUtil.isMeizuBelowN() && params[1] == null) {
            outputPath = info.getOdexFile().getPath();
            params[1] = outputPath;
        }
    }

    // TODO Migrate these to dobby/lsplant for more arch support + proper dlopen restrictions

    private static native void nativeLaunchEngine(Object[] method, String hostPackageName, boolean isArt, int apiLevel, int cameraMethodType);

    private static native void nativeMark();

    private static native String nativeReverseRedirectedPath(String redirectedPath);

    private static native String nativeGetRedirectedPath(String orgPath);

    private static native void nativeIORedirect(String origPath, String newPath);

    private static native void nativeIOWhitelist(String path);

    private static native void nativeIOForbid(String path);

    private static native void nativeIOLog(String path);

    private static native void nativeEnableIORedirect(String selfSoPath, int apiLevel, int previewApiLevel);

    public static native void disableJit(int apiLevel);

    public static native void hookDlOpen();

    public static int onGetUid(int uid) {
        return VClientImpl.get().getBaseVUid();
    }
}

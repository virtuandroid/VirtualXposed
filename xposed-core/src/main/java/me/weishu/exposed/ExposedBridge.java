package me.weishu.exposed;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.view.AbsSavedState;
import android.view.View;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.ExposedHelper;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.LSPosedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import timber.log.Timber;

import static de.robv.android.xposed.XposedBridge.log;

import com.virtualxposed.log.client.LogMessage;
import com.virtualxposed.log.client.VLoggingClient;


public class ExposedBridge {
    private static final Map<ClassLoader, ClassLoader> exposedClassLoaderMap = new HashMap<>();
    private static ClassLoader xposedClassLoader;
    private static Context appContext;
    private static String currentPackage;
    private static boolean SYSTEM_CLASSLOADER_INJECT = false;
    /**
     * Module load result
     */
    enum ModuleLoadResult {
        DISABLED,
        NOT_EXIST,
        INVALID,
        SUCCESS,
        FAILED,
        IGNORED
    }

    public static void initOnce(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        // SYSTEM_CLASSLOADER_INJECT = patchSystemClassLoader();
        appContext = context;
        initForPackage(context, applicationInfo);
        System.loadLibrary("lsplantbridge");
        ExposedHelper.initSeLinux(applicationInfo.processName);
        XSharedPreferences.setPackageBaseDirectory(new File(applicationInfo.dataDir).getParentFile());
    }

    private static void initForPackage(Context context, ApplicationInfo applicationInfo) {
        currentPackage = applicationInfo.packageName;

        if (currentPackage == null) {
            currentPackage = context.getPackageName();
        }

        System.setProperty("vxp", "1");
        System.setProperty("vxp_user_dir", new File(applicationInfo.dataDir).getParent());
    }

    private static boolean patchSystemClassLoader() {
        // 1. first create XposedClassLoader -> BootstrapClassLoader
        ClassLoader xposedClassLoader = new XposedClassLoader(ExposedBridge.class.getClassLoader());

        // 2. replace the systemclassloader's parent.
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

        try {
            Field parent = ClassLoader.class.getDeclaredField("parent");
            parent.setAccessible(true);
            parent.set(systemClassLoader, xposedClassLoader);

            log("XposedBridge's BootClassLoader: " + XposedBridge.BOOTCLASSLOADER + ", parent: " + XposedBridge.BOOTCLASSLOADER.getParent());
            // SystemClassLoader -> XposedClassLoader -> BootstrapClassLoader
            return systemClassLoader.getParent() == xposedClassLoader;
        } catch (NoSuchFieldException e) {
            // todo no such field ? use unsafe.
            log(e);
            return false;
        } catch (IllegalAccessException e) {
            log(e);
            return false;
        }
    }

    private static synchronized ClassLoader getAppClassLoaderWithXposed(ClassLoader appClassLoader) {
        if (exposedClassLoaderMap.containsKey(appClassLoader)) {
            return exposedClassLoaderMap.get(appClassLoader);
        } else {
            ClassLoader hostClassLoader = ExposedBridge.class.getClassLoader();
            ClassLoader xposedClassLoader = getXposedClassLoader(hostClassLoader);
            ClassLoader exposedClassLoader = new ComposeClassLoader(xposedClassLoader, appClassLoader);
            exposedClassLoaderMap.put(appClassLoader, exposedClassLoader);
            return exposedClassLoader;
        }
    }

    public static synchronized ClassLoader getXposedClassLoader(ClassLoader hostClassLoader) {
        if (xposedClassLoader == null) {
            xposedClassLoader = new XposedClassLoader(hostClassLoader);
        }
        return xposedClassLoader;
    }

    public static ModuleLoadResult loadModule(final String moduleApkPath, String moduleOdexDir, String moduleLibPath,
                                              final ApplicationInfo currentApplicationInfo, ClassLoader appClassLoader) {
        if (filterApplication(currentApplicationInfo)) {
            return ModuleLoadResult.IGNORED;
        }

        Timber.i("Loading modules from: %s for process: %s",  moduleApkPath,  currentApplicationInfo.processName );

        if (!new File(moduleApkPath).exists()) {
            Timber.e("Module %s does not exist", moduleApkPath);
            return ModuleLoadResult.NOT_EXIST;
        }

        VLoggingClient.get().log(new LogMessage.ModuleLoad(moduleApkPath));

        ClassLoader appClassLoaderWithXposed;
        ClassLoader mcl;
        if (SYSTEM_CLASSLOADER_INJECT) {
            // we replace the systemclassloader's parent success, go with xposed's way
            appClassLoaderWithXposed = appClassLoader;
            mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, XposedBridge.BOOTCLASSLOADER);
        } else {
            // replace failed, just wrap.
            ClassLoader hostClassLoader = ExposedBridge.class.getClassLoader();
            appClassLoaderWithXposed = getAppClassLoaderWithXposed(appClassLoader);
            mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, getXposedClassLoader(hostClassLoader));
            // ClassLoader mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, hostClassLoader);
        }

        InputStream is = mcl.getResourceAsStream("assets/xposed_init");
        if (is == null) {
            Timber.e("Initialization file assets/xposed_init not found in the module APK");
            return ModuleLoadResult.INVALID;
        }

        BufferedReader moduleClassesReader = new BufferedReader(new InputStreamReader(is));
        try {
            String moduleClassName;
            while ((moduleClassName = moduleClassesReader.readLine()) != null) {
                moduleClassName = moduleClassName.trim();
                if (moduleClassName.isEmpty() || moduleClassName.startsWith("#"))
                    continue;

                try {
                    Timber.i("Loading class %s", moduleClassName);
                    Class<?> moduleClass = mcl.loadClass(moduleClassName);

                    if (!ExposedHelper.isIXposedMod(moduleClass)) {
                        log("    This class doesn't implement any sub-interface of IXposedMod, skipping it");
                        continue;
                    } else if (IXposedHookInitPackageResources.class.isAssignableFrom(moduleClass)) {
                        log("    This class requires resource-related hooks (which are disabled), skipping it.");
                        continue;
                    }

                    final Object moduleInstance = moduleClass.getDeclaredConstructor()
                            .newInstance();
                    if (moduleInstance instanceof IXposedHookZygoteInit) {
                        ExposedHelper.callInitZygote(moduleApkPath, moduleInstance);
                    }

                    if (moduleInstance instanceof IXposedHookLoadPackage) {
                        // hookLoadPackage(new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) moduleInstance));
                        IXposedHookLoadPackage.Wrapper wrapper = new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) moduleInstance);
                        XposedBridge.CopyOnWriteSortedSet<XC_LoadPackage> xc_loadPackageCopyOnWriteSortedSet = new XposedBridge.CopyOnWriteSortedSet<>();
                        xc_loadPackageCopyOnWriteSortedSet.add(wrapper);
                        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(xc_loadPackageCopyOnWriteSortedSet);
                        lpparam.packageName = currentApplicationInfo.packageName;
                        lpparam.processName = currentApplicationInfo.processName;
                        lpparam.classLoader = appClassLoaderWithXposed;
                        lpparam.appInfo = currentApplicationInfo;
                        lpparam.isFirstApplication = true;
                        XC_LoadPackage.callAll(lpparam);
                    }

                    if (moduleInstance instanceof IXposedHookInitPackageResources) {
                        // hookInitPackageResources(new IXposedHookInitPackageResources.Wrapper((IXposedHookInitPackageResources) moduleInstance));
                        // TODO: 17/12/1 Support Resource hook
                    }
                } catch (Throwable t) {
                    log(t);
                }
                return ModuleLoadResult.SUCCESS;
            }
        } catch (IOException e) {
            log(e);
        } finally {
            closeSliently(is);
        }
        return ModuleLoadResult.FAILED;
    }

    private static void presetMethod(Member method) {
        if (method == null) {
            return;
        }
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        presetMethod(method);

        final XC_MethodHook.Unhook unhook = LSPosedBridge.INSTANCE.createHook(method, callback);
        return ExposedHelper.newUnHook(callback, unhook.getHookedMethod());
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {

        return LSPosedBridge.INSTANCE.invokeOriginalMethod(method, thisObject, args);
    }

    private static boolean filterApplication(ApplicationInfo applicationInfo) {
        return applicationInfo == null;
    }

    public static boolean deleteDir(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (String file : children) {
                boolean success = deleteDir(new File(dir, file));
                if (!success) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    /**
     * avoid from being searched by google.
     *
     * @param base64
     * @return
     */
    private static String decodeFromBase64(String base64) {
        return new String(Base64.decode(base64, 0));
    }

    private static void closeSliently(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable e) {
            // IGNORE
        }
    }
}


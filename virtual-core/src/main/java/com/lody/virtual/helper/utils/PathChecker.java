package com.lody.virtual.helper.utils;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Vector;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;

/**
 * Allows for check operations on the path during a file access. Based on an "allow all" mode,
 * with blacklist and exceptions.
 *
 * @author Luca Boscolo Meneguolo @calugj
 */
public final class PathChecker {
    private static final PathChecker instance = new PathChecker();
    public static PathChecker get() { return instance; }

    @SuppressLint("SdCardPath")
    private static final String[] exceptions = {    // Whitelisted directories
            "/data/user/^USER^/^HOST_PACKAGE^/virtual/data/user/^VIRTUAL_USER^/^VIRTUAL_PACKAGE^/*",    // Virtual app sandbox folder
            "/data/user/^USER^/^HOST_PACKAGE^/virtual/data/app/^VIRTUAL_PACKAGE^/*",                    // Virtual app installation folder
            "/data/user/^USER^/^HOST_PACKAGE^/virtual/data/user/^VIRTUAL_USER^/wifiMacAddress",
            "/storage/emulated/^USER^/Android/data/^HOST_PACKAGE^/virtual/^VIRTUAL_USER^/^VIRTUAL_PACKAGE^/*",      // External storage
            "/data/user/^USER^/^HOST_PACKAGE^/virtual/data/user/^VIRTUAL_USER^/de.robv.android.xposed.installer/conf/modules.list" // Xposed modules
    };
    // /data/user_de/0/de.robv.android.xposed.installer/conf/modules.list

    @SuppressLint("SdCardPath")
    private static final String[] blacklist =  {
            "/data/user/^USER^/^HOST_PACKAGE^/*",   // Host app sandbox folder
            "^INSTALL_DIR^/*",                      // Host app installation folder
            "/storage/emulated/^USER^/Android/data/^HOST_PACKAGE^/*",    // External storage
    };

    private final Vector<String[]> exceptionsPaths;
    private final Vector<String[]> blacklistPaths;
    private final HashMap<String, Boolean> cache;
    private boolean isInit = false;

    public PathChecker() {
        blacklistPaths = new Vector<>();
        exceptionsPaths = new Vector<>();
        cache = new HashMap<>();
    }

    private void init() {
        // Init exceptions
        for(String path : exceptions) {
            path = replace(path);
            exceptionsPaths.add(PathTokenizer.getTokens(path));
        }

        // Init blacklist
        for(String path : blacklist) {
            path = replace(path);
            blacklistPaths.add(PathTokenizer.getTokens(path));
        }

        isInit = true;
    }

    /**
     * Checks whether the specified path is valid for access.
     * @param path the path to check.
     * @return a boolean indicating whether the path is valid for read/write access.
     */
    public boolean isPathValid(String path) {
        if(!isInit) init();

        if(cache.containsKey(path)) return Boolean.TRUE.equals(cache.get(path));

        final String[] tokens = PathTokenizer.getTokens(path);

        // check if accessed path is among the whitelist exceptions
        for(String[] list : exceptionsPaths) {
            int i = 0;
            while(i < list.length && i < tokens.length) {
                if(list[i].equals("*") && tokens.length >= list.length) return cacheAndReturn(path, true);

                if(!tokens[i].equals(list[i])) break;

                if(i == list.length - 1 && i == tokens.length - 1 && tokens[i].equals(list[i]))
                    return cacheAndReturn(path, true);

                i++;
            }
        }

        // if not, check if it is blacklisted
        for(String[] list : blacklistPaths) {
            int i = 0;
            while(i < list.length && i < tokens.length) {
                if(list[i].equals("*") && tokens.length >= list.length) return cacheAndReturn(path, false);

                if(!tokens[i].equals(list[i])) break;

                if(i == list.length - 1 && i == tokens.length - 1 && tokens[i].equals(list[i]))
                    return cacheAndReturn(path, false);

                i++;
            }
        }

        return cacheAndReturn(path, true);    // finally, accept all other paths
    }

    private boolean cacheAndReturn(String path, Boolean retVal) {
        cache.put(path, retVal);
        return retVal;
    }


    /**
     * Replaces the placeholders of parametrized paths with runtime values
     */
    private String replace(String original) {
        String systemUser;
        try {
            Method getCurrentUser = ActivityManager.class.getMethod("getCurrentUser");
            getCurrentUser.setAccessible(true);
            systemUser = getCurrentUser.invoke(null).toString();
        } catch(Exception e) { systemUser = "0"; }
        int uid = VBinder.getCallingUid();
        String hostPackage = VirtualCore.get().getHostPkg();
        int virtualUser = VUserHandle.getCallingUserId();
        String virtualPackage = VPackageManager.get().getNameForUid(uid);
        String apkPath = VirtualCore.get().getContext().getPackageCodePath();
        File apkFile = new File(apkPath);
        File installDir = apkFile.getParentFile();

        if(installDir != null) original = original.replace("^INSTALL_DIR^", installDir.getAbsolutePath());
        original = original.replace("^USER^", systemUser);
        original = original.replace("^HOST_PACKAGE^", hostPackage);
        original = original.replace("^VIRTUAL_USER^", String.valueOf(virtualUser));
        original = original.replace("^VIRTUAL_PACKAGE^", virtualPackage);

        return original;
    }


    /**
     * Helper class that allows to create a String array containing tokens from a
     * path String and vice versa.
     *
     * @author Luca Boscolo Meneguolo @calugj
     */
    private static final class PathTokenizer {
        /**
         * Create a String array containing al tokens from a path.
         * @param path the path to create tokens from.
         * @return an array of Strings containing all tokens.
         */
        public static String[] getTokens(String path) {
            return path.substring(1).split("/");
        }

        /**
         * Build a String from an array of tokens.
         * @param tokens the array of tokens.
         * @return a String representation of the path.
         */
        public static String getPath(final String[] tokens) {
            StringBuilder result = new StringBuilder();
            for(String token : tokens) {
                result.append("/").append(token);
            }
            return result.toString();
        }
    }
}

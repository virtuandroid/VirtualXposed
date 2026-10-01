package com.lody.virtual.helper.utils;

import android.os.Build;

/**
 * author: weishu on 18/1/17.
 */

public class DeviceUtil {
    /**
     * is Meizu Android L/M device
     * @return
     */
    public static boolean isMeizuBelowN() {
        if (Build.VERSION.SDK_INT > 23) {
            return false;
        }
        String display = Build.DISPLAY;
        return display.toLowerCase().contains("flyme");
    }

    public static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.BRAND) || "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    public static boolean isX86_64() {
        String[] abis = Build.SUPPORTED_ABIS;

        for (String cpuAbi : abis) {
            if (cpuAbi.equals("x86_64"))
                return true;
        }

        return false;
    }

}

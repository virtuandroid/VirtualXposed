package com.lody.virtual.client.stub;

import com.lody.virtual.client.core.VirtualCore;

/**
 * @author LittleAngry
 */
public class StorageRedirect {

    public static String redirect(String sourcePath) {

        if (sourcePath.startsWith("file:///"))
            return "file:///storage/emulated/0/Android/data/" + VirtualCore.get().getHostPkg() + "/" + sourcePath.replace("file:///", "");

        return null;
    }

}

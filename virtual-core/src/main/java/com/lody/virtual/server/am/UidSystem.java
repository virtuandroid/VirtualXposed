package com.lody.virtual.server.am;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.FileUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.server.pm.parser.VPackage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;

import static android.os.Process.FIRST_APPLICATION_UID;

/**
 * @author Lody
 */

public class UidSystem {

    private static final String TAG = UidSystem.class.getSimpleName();

    private final HashMap<String, Integer> mPackageUidMap = new HashMap<>();
    private int mFreeUid = FIRST_APPLICATION_UID;


    public void initUidList() {
        mPackageUidMap.clear();
        File uidFile = VEnvironment.getUidListFile();
        if (!loadUidList(uidFile)) {
            File bakUidFile = VEnvironment.getBakUidListFile();
            loadUidList(bakUidFile);
        }
    }

    private boolean loadUidList(File uidFile) {
        if (!uidFile.exists()) {
            return false;
        }
        try {
            ObjectInputStream is = new ObjectInputStream(new FileInputStream(uidFile));
            mFreeUid = is.readInt();
            //noinspection unchecked
            HashMap<String, Integer> map = (HashMap<String, Integer>) is.readObject();
            mPackageUidMap.putAll(map);
            is.close();
        } catch (Throwable e) {
            return false;
        }
        return true;
    }

    private void save() {
        File uidFile = VEnvironment.getUidListFile();
        File bakUidFile = VEnvironment.getBakUidListFile();
        if (uidFile.exists()) {
            if (bakUidFile.exists() && !bakUidFile.delete()) {
                VLog.w(TAG, "Warning: Unable to delete the expired file --\n " + bakUidFile.getPath());
            }
            try {
                FileUtils.copyFile(uidFile, bakUidFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream(uidFile));
            os.writeInt(mFreeUid);
            os.writeObject(mPackageUidMap);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getOrCreateUid(int userId, VPackage pkg) {
        String packageName = pkg.mSharedUserId;
        if (packageName == null) {
            packageName = pkg.packageName;
        }
        // Get existing application UID for package name
        Integer appId = mPackageUidMap.get(packageName);
        if (appId == null) {
            // Create new application UID
            appId = ++mFreeUid;
            mPackageUidMap.put(packageName, appId);
            save();
        }
        // Return actual UID for current user
        return VUserHandle.getUid(userId, appId);
    }
}

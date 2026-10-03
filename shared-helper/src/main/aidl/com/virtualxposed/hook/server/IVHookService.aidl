package com.virtualxposed.hook.server;

import rikka.parcelablelist.ParcelableListSlice;

/**
* API to be used by guest apps to determine hooking security rules.
* This is akin to a minimal and untrusted version of IManagerService.aidl
*
* Security logic, such as determining if the hook should be allowed is deliberately
* offloaded to the server process.
*/
interface IVHookService {
    boolean isHookAllowed(String method, String modulePackage);

    const int ALLOWED = 1;
    const int DENIED = 0;
}
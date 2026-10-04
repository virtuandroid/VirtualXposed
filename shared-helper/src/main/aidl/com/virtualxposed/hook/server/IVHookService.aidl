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
    int isHookAllowed(String method, String modulePackage);
    const int ALLOWED = 1;
    const int DENIED = 0;

    /**
    * Get the Vector manager service binder. Since the vector manager allows the client to
    * call privleged methods this will only return a binder if the caller process package
    * name is the expected package name of Vector Manager.
    */
    @nullable IBinder getVectorManager();
}
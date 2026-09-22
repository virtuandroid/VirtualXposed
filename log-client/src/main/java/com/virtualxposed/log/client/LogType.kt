package com.virtualxposed.log.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
enum class LogType : Parcelable {
    HookAttach,
    HookExecution,
    AppLoad,
    AppKill,
    AppDeath,
    ModuleLoad,
    BroadcastReceived,
    CodeLoad,
    BindService,
    GetService,
    GetProvider,
    Exec,
    SocketConnect,
    AppInstall,
    AppUninstall,
    UsePermission,
    FingerprintChange,
    FileAccess;
}
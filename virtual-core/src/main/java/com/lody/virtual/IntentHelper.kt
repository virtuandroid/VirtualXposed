package com.lody.virtual
import com.lody.virtual.os.VBinder
import com.lody.virtual.server.security.SecurityUtils

object IntentHelper {
    /** Send a broadcast to a specific package only. */
    const val BROADCAST_SPECIFIC_PACKAGE = "_VA_|_privilege_pkg_"

    /** Specify the broadcast sender by package name.
     * This is used for receiver authentication and should therefore
     * never be supplied by the client. Use [VBinder.getCallingPackageName]
     * to get the calling package safely. */
    const val BROADCAST_CALLING_PACKAGE = "_VA_|_pkg_name_safe_"

    /** Specify the broadcast component */
    const val BROADCAST_COMPONENT = "_VA_|_component_"
    const val BROADCAST_UID = "_VA_|_uid_"
    const val INTENT_COMPONENT_CALLER = "_VA_|_caller_"

    /** Specify the affected user ID. Use with care to prevent cross-user interaction.
     * @see [VBinder.getCallingUserHandle]
     * @see [SecurityUtils.enforceCallingUser]
     */
    const val INTENT_USER_ID = "_VA_|_user_id_"

    /** Used to pack another Intent into the Intent. */
    const val INTENT_EMBEDDED = "_VA_|_intent_"
    const val INTENT_URI = "_VA_|_uri_"
    const val INTENT_ACTIVITY_INFO = "_VA_|_info_"
    const val INTENT_ACTIVITY_SPLASH = "_VA_|_splash_"
    const val INTENT_SENDER = "_VA_|_sender_"
    const val INTENT_UI_CALLBACK = "_VA_|_ui_callback_"
    const val INTENT_CREATOR = "_VA_|_creator_"
    const val INTENT_FROM_INNER = "_VA_|_from_inner_"
    const val BUNDLE_BINDER = "_VA_|_binder_"
    const val BUNDLE_PID = "_VA_|_pid_"
    const val BUNDLE_VUID = "_VA_|_vuid_"
    const val BUNDLE_CLIENT_BINDER = "_VA_|_client_"
}
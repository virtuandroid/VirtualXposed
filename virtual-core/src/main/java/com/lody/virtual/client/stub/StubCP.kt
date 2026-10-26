package com.lody.virtual.client.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.lody.virtual.IntentHelper.BUNDLE_BINDER
import com.lody.virtual.IntentHelper.BUNDLE_CLIENT_BINDER
import com.lody.virtual.IntentHelper.BUNDLE_PID
import com.lody.virtual.IntentHelper.BUNDLE_VUID
import com.lody.virtual.client.VClientImpl
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.compat.BundleCompat

/**
 * @author Lody
 */
open class StubCP : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String?>?,
        selection: String?,
        selectionArgs: Array<out String?>?,
        sortOrder: String?
    ): Cursor? {
         return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == VA_INIT_PROCESS_METHOD) {
            return initProcess(extras)
        }
        return null
    }

    private fun initProcess(extras: Bundle?): Bundle? {
        val lock = VirtualCore.get().initLock
        if (lock != null) {
            lock.block()
        }
        val token = BundleCompat.getBinder(extras, BUNDLE_BINDER)
        val vuid = extras?.getInt(BUNDLE_VUID) ?: return null
        val client = VClientImpl.get()
        client.initProcess(token, vuid)
        val res = Bundle()
        BundleCompat.putBinder(res, BUNDLE_CLIENT_BINDER, client.asBinder())
        res.putInt(BUNDLE_PID, Process.myPid())
        return res
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        return 0
    }

    class C0 : StubCP()

    class C1 : StubCP()

    class C2 : StubCP()

    class C3 : StubCP()

    class C4 : StubCP()

    class C5 : StubCP()

    class C6 : StubCP()

    class C7 : StubCP()

    class C8 : StubCP()

    class C9 : StubCP()

    class C10 : StubCP()

    class C11 : StubCP()

    class C12 : StubCP()

    class C13 : StubCP()

    class C14 : StubCP()

    class C15 : StubCP()

    class C16 : StubCP()

    class C17 : StubCP()

    class C18 : StubCP()

    class C19 : StubCP()

    class C20 : StubCP()

    class C21 : StubCP()

    class C22 : StubCP()

    class C23 : StubCP()

    class C24 : StubCP()

    class C25 : StubCP()

    class C26 : StubCP()

    class C27 : StubCP()

    class C28 : StubCP()

    class C29 : StubCP()

    class C30 : StubCP()

    class C31 : StubCP()

    class C32 : StubCP()

    class C33 : StubCP()

    class C34 : StubCP()

    class C35 : StubCP()

    class C36 : StubCP()

    class C37 : StubCP()

    class C38 : StubCP()

    class C39 : StubCP()

    class C40 : StubCP()

    class C41 : StubCP()

    class C42 : StubCP()

    class C43 : StubCP()

    class C44 : StubCP()

    class C45 : StubCP()

    class C46 : StubCP()

    class C47 : StubCP()

    class C48 : StubCP()

    class C49 : StubCP()

    class C50 : StubCP()

    class C51 : StubCP()

    class C52 : StubCP()

    class C53 : StubCP()

    class C54 : StubCP()

    class C55 : StubCP()

    class C56 : StubCP()

    class C57 : StubCP()

    class C58 : StubCP()

    class C59 : StubCP()

    class C60 : StubCP()

    class C61 : StubCP()

    class C62 : StubCP()

    class C63 : StubCP()

    class C64 : StubCP()

    class C65 : StubCP()

    class C66 : StubCP()

    class C67 : StubCP()

    class C68 : StubCP()

    class C69 : StubCP()

    class C70 : StubCP()

    class C71 : StubCP()

    class C72 : StubCP()

    class C73 : StubCP()

    class C74 : StubCP()

    class C75 : StubCP()

    class C76 : StubCP()

    class C77 : StubCP()

    class C78 : StubCP()

    class C79 : StubCP()

    class C80 : StubCP()

    class C81 : StubCP()

    class C82 : StubCP()

    class C83 : StubCP()

    class C84 : StubCP()

    class C85 : StubCP()

    class C86 : StubCP()

    class C87 : StubCP()

    class C88 : StubCP()

    class C89 : StubCP()

    class C90 : StubCP()

    class C91 : StubCP()

    class C92 : StubCP()

    class C93 : StubCP()

    class C94 : StubCP()

    class C95 : StubCP()

    class C96 : StubCP()

    class C97 : StubCP()

    class C98 : StubCP()

    class C99 : StubCP()


    companion object {
        const val VA_INIT_PROCESS_METHOD: String = "_VA_|_init_process_"
    }
}

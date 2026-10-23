package com.lody.virtual.server.pm

import android.app.Activity
import android.app.IStopUserCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.SparseArray
import android.util.Xml
import com.lody.virtual.R
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.env.Constants
import com.lody.virtual.helper.compat.ActivityManagerCompat
import com.lody.virtual.helper.utils.ArrayUtils
import com.lody.virtual.helper.utils.AtomicFile
import com.lody.virtual.helper.utils.FastXmlSerializer
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.os.VUserInfo
import com.lody.virtual.os.VUserManager
import com.lody.virtual.server.IUserManager
import com.lody.virtual.server.am.VActivityManagerService
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlSerializer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.util.size
import timber.log.Timber

/**
 * @author Lody
 */
class VUserManagerService private constructor(
    private val mContext: Context, private val mPm: VPackageManagerService,
    private val mInstallLock: Any, private val mPackagesLock: Any,
    dataDir: File?, baseUserPath: File?
) : IUserManager.Stub() {
    private val mUsersDir: File
    private val mUserListFile: File
    private val mBaseUserPath: File?
    private val mUsers = SparseArray<VUserInfo>()
    private val mRemovingUserIds = HashSet<Int?>()
    lateinit var userIdsLPr: IntArray
        private set
    private var mGuestEnabled = false
    private var mNextSerialNumber = 0

    // This resets on a reboot. Otherwise, it keeps incrementing so that user ids are
    // not reused in quick succession
    private var mNextUserId: Int = MIN_USER_ID
    private var mUserVersion = 0

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    internal constructor(
        context: Context, pm: VPackageManagerService,
        installLock: Any, packagesLock: Any
    ) : this(
        context, pm, installLock, packagesLock,
        VEnvironment.getDataDirectory(),
        File(VEnvironment.getDataDirectory(), "user")
    )

    init {
        synchronized(mInstallLock) {
            synchronized(mPackagesLock) {
                mUsersDir = File(dataDir, USER_INFO_DIR)
                mUsersDir.mkdirs()
                // Make zeroth user directory, for services to migrate their files to that location
                val userZeroDir = File(mUsersDir, "0")
                userZeroDir.mkdirs()
                mBaseUserPath = baseUserPath
                //                FileUtils.setPermissions(mUsersDir.toString(),
//                        FileUtils.S_IRWXU|FileUtils.S_IRWXG
//                        |FileUtils.S_IROTH|FileUtils.S_IXOTH,
//                        -1, -1);
                mUserListFile = File(mUsersDir, USER_LIST_FILENAME)
                readUserListLocked()
                // Prune out any partially created/partially removed users.
                val partials = ArrayList<VUserInfo>()
                for (i in 0..<mUsers.size) {
                    val ui = mUsers.valueAt(i)
                    if (ui.partial && i != 0) {
                        partials.add(ui)
                    }
                }
                for (i in partials.indices) {
                    val ui = partials[i]
                    Timber.w(
                        ("Removing partially created user #" + i
                                + " (name=" + ui.name + ")")
                    )
                    removeUserStateLocked(ui.id)
                }
                sInstance = this
            }
        }
    }

    override fun getUsers(excludeDying: Boolean): MutableList<VUserInfo?> {
        //checkManageUsersPermission("query users");
        synchronized(mPackagesLock) {
            val users = ArrayList<VUserInfo?>(mUsers.size)
            for (i in 0..<mUsers.size) {
                val ui = mUsers.valueAt(i)
                if (ui.partial) {
                    continue
                }
                if (!excludeDying || !mRemovingUserIds.contains(ui.id)) {
                    users.add(ui)
                }
            }
            return users
        }
    }

    override fun getUserInfo(userId: Int): VUserInfo? {
        //checkManageUsersPermission("query user");
        synchronized(mPackagesLock) {
            return getUserInfoLocked(userId)
        }
    }

    /*
     * Should be locked on mUsers before calling this.
     */
    private fun getUserInfoLocked(userId: Int): VUserInfo? {
        val ui = mUsers.get(userId)
        // If it is partial and not in the process of being removed, return as unknown user.
        if (ui != null && ui.partial && !mRemovingUserIds.contains(userId)) {
            Timber.w("getUserInfo: unknown user #$userId")
            return null
        }
        return ui
    }

    fun exists(userId: Int): Boolean {
        synchronized(mPackagesLock) {
            return ArrayUtils.contains(
                this.userIdsLPr, userId
            )
        }
    }

    override fun setUserName(userId: Int, name: String?) {
        checkManageUsersPermission("rename users")
        var changed = false
        synchronized(mPackagesLock) {
            val info = mUsers.get(userId)
            if (info == null || info.partial) {
                Timber.w("setUserName: unknown user #$userId")
                return
            }
            if (name != null && name != info.name) {
                info.name = name
                writeUserLocked(info)
                changed = true
            }
        }
        if (changed) {
            sendUserInfoChangedBroadcast(userId)
        }
    }

    override fun setUserIcon(userId: Int, bitmap: Bitmap) {
        checkManageUsersPermission("update users")
        synchronized(mPackagesLock) {
            val info = mUsers.get(userId)
            if (info == null || info.partial) {
                Timber.w( "setUserIcon: unknown user #$userId")
                return
            }
            writeBitmapLocked(info, bitmap)
            writeUserLocked(info)
        }
        sendUserInfoChangedBroadcast(userId)
    }

    private fun sendUserInfoChangedBroadcast(userId: Int) {
        val changedIntent = Intent(Constants.ACTION_USER_INFO_CHANGED)
        changedIntent.putExtra(Constants.EXTRA_USER_HANDLE, userId)
        changedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        VActivityManagerService.get().sendBroadcastAsUser(changedIntent, VUserHandle(userId))
    }

    override fun getUserIcon(userId: Int): Bitmap? {
        //checkManageUsersPermission("read users");
        synchronized(mPackagesLock) {
            val info = mUsers.get(userId)
            if (info == null || info.partial) {
                Timber.w("getUserIcon: unknown user #$userId")
                return null
            }
            if (info.iconPath == null) {
                return null
            }
            return BitmapFactory.decodeFile(info.iconPath)
        }
    }

    override fun isGuestEnabled(): Boolean {
        synchronized(mPackagesLock) {
            return mGuestEnabled
        }
    }

    override fun setGuestEnabled(enable: Boolean) {
        checkManageUsersPermission("enable guest users")
        synchronized(mPackagesLock) {
            if (mGuestEnabled != enable) {
                mGuestEnabled = enable
                // Erase any guest user that currently exists
                for (i in 0..<mUsers.size) {
                    val user = mUsers.valueAt(i)
                    if (!user.partial && user.isGuest) {
                        if (!enable) {
                            removeUser(user.id)
                        }
                        return
                    }
                }
                // No guest was found
                if (enable) {
                    createUser("Guest", VUserInfo.FLAG_GUEST)
                }
            }
        }
    }

    override fun wipeUser(userHandle: Int) {
        checkManageUsersPermission("wipe user")
        // TODO:
    }

    fun makeInitialized(userId: Int) {
        checkManageUsersPermission("makeInitialized")
        synchronized(mPackagesLock) {
            val info = mUsers.get(userId)
            if (info == null || info.partial) {
                Timber.w("makeInitialized: unknown user #$userId")
            }
            if ((info!!.flags and VUserInfo.FLAG_INITIALIZED) == 0) {
                info.flags = info.flags or VUserInfo.FLAG_INITIALIZED
                writeUserLocked(info)
            }
        }
    }

    private val isUserLimitReachedLocked: Boolean
        /**
         * Check if we've hit the limit of how many users can be created.
         */
        get() {
            val nUsers = mUsers.size
            return nUsers >= VUserManager.getMaxSupportedUsers()
        }

    private fun writeBitmapLocked(info: VUserInfo, bitmap: Bitmap) {
        try {
            val dir = File(mUsersDir, info.id.toString())
            val file = File(dir, USER_PHOTO_FILENAME)
            if (!dir.exists()) {
                dir.mkdir()
                //                FileUtils.setPermissions(
//                        dir.getPath(),
//                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
//                        -1, -1);
            }
            val os: FileOutputStream?
            if (bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    FileOutputStream(file).also { os = it })
            ) {
                info.iconPath = file.absolutePath
            }
            try {
                os!!.close()
            } catch (_: IOException) {
                // What the ... !
            }
        } catch (e: FileNotFoundException) {
            Timber.w(e, "Error setting photo for user ")
        }
    }

    val userIds: IntArray
        /**
         * Returns an array of user ids. This array is cached here for quick access, so do not modify or
         * cache it elsewhere.
         * @return the array of user ids.
         */
        get() {
            synchronized(mPackagesLock) {
                return this.userIdsLPr
            }
        }

    private fun readUserList() {
        synchronized(mPackagesLock) {
            readUserListLocked()
        }
    }

    private fun readUserListLocked() {
        mGuestEnabled = false
        if (!mUserListFile.exists()) {
            fallbackToSingleUserLocked()
            return
        }
        var fis: FileInputStream? = null
        val userListFile = AtomicFile(mUserListFile)
        try {
            fis = userListFile.openRead()
            val parser = Xml.newPullParser()
            parser.setInput(fis, null)
            var type: Int
            while ((parser.next().also { type = it }) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT
            ) {
            }

            if (type != XmlPullParser.START_TAG) {
                Timber.e( "Unable to read user list")
                fallbackToSingleUserLocked()
                return
            }

            mNextSerialNumber = -1
            if (parser.name == TAG_USERS) {
                val lastSerialNumber = parser.getAttributeValue(null, ATTR_NEXT_SERIAL_NO)
                if (lastSerialNumber != null) {
                    mNextSerialNumber = lastSerialNumber.toInt()
                }
                val versionNumber = parser.getAttributeValue(null, ATTR_USER_VERSION)
                if (versionNumber != null) {
                    mUserVersion = versionNumber.toInt()
                }
            }

            while ((parser.next().also { type = it }) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.name == TAG_USER) {
                    val id = parser.getAttributeValue(null, ATTR_ID)
                    val user = readUser(id.toInt())

                    if (user != null) {
                        mUsers.put(user.id, user)
                        if (user.isGuest) {
                            mGuestEnabled = true
                        }
                        if (mNextSerialNumber < 0 || mNextSerialNumber <= user.id) {
                            mNextSerialNumber = user.id + 1
                        }
                    }
                }
            }
            updateUserIdsLocked()
            upgradeIfNecessary()
        } catch (_: IOException) {
            fallbackToSingleUserLocked()
        } catch (_: XmlPullParserException) {
            fallbackToSingleUserLocked()
        } finally {
            if (fis != null) {
                try {
                    fis.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * This fixes an incorrect initialization of user name for the owner.
     * TODO: Remove in the next release.
     */
    private fun upgradeIfNecessary() {
        var userVersion = mUserVersion
        if (userVersion < 1) {
            // Assign a proper name for the owner, if not initialized correctly before
            val user = mUsers.get(VUserHandle.USER_OWNER)
            if ("Primary" == user.name) {
                user.name = "Admin"
                writeUserLocked(user)
            }
            userVersion = 1
        }

        if (userVersion < USER_VERSION) {
            Timber.w(
                ("User version " + mUserVersion + " didn't upgrade as expected to "
                        + USER_VERSION)
            )
        } else {
            mUserVersion = userVersion
            writeUserListLocked()
        }
    }

    private fun fallbackToSingleUserLocked() {
        // Create the primary user
        val primary = VUserInfo(
            0,
            mContext.resources.getString(R.string.owner_name), null,
            VUserInfo.FLAG_ADMIN or VUserInfo.FLAG_PRIMARY or VUserInfo.FLAG_INITIALIZED
        )
        mUsers.put(0, primary)
        mNextSerialNumber = MIN_USER_ID
        updateUserIdsLocked()

        writeUserListLocked()
        writeUserLocked(primary)
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    private fun writeUserLocked(userInfo: VUserInfo) {
        var fos: FileOutputStream? = null
        val userFile = AtomicFile(File(mUsersDir, userInfo.id.toString() + ".xml"))
        try {
            fos = userFile.startWrite()
            val bos = BufferedOutputStream(fos)

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            val serializer: XmlSerializer = FastXmlSerializer()
            serializer.setOutput(bos, "utf-8")
            serializer.startDocument(null, true)
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

            serializer.startTag(null, TAG_USER)
            serializer.attribute(null, ATTR_ID, userInfo.id.toString())
            serializer.attribute(null, ATTR_SERIAL_NO, userInfo.serialNumber.toString())
            serializer.attribute(null, ATTR_FLAGS, userInfo.flags.toString())
            serializer.attribute(null, ATTR_CREATION_TIME, userInfo.creationTime.toString())
            serializer.attribute(
                null, ATTR_LAST_LOGGED_IN_TIME,
                userInfo.lastLoggedInTime.toString()
            )
            if (userInfo.iconPath != null) {
                serializer.attribute(null, ATTR_ICON_PATH, userInfo.iconPath)
            }
            if (userInfo.partial) {
                serializer.attribute(null, ATTR_PARTIAL, "true")
            }

            serializer.startTag(null, TAG_NAME)
            serializer.text(userInfo.name)
            serializer.endTag(null, TAG_NAME)

            serializer.endTag(null, TAG_USER)

            serializer.endDocument()
            userFile.finishWrite(fos)
        } catch (ioe: Exception) {
            Timber.e(ioe,"Error writing user info ${userInfo.id}")
            userFile.failWrite(fos)
        }
    }

    /*
     * Writes the user list file in this format:
     *
     * <users nextSerialNumber="3">
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
    private fun writeUserListLocked() {
        var fos: FileOutputStream? = null
        val userListFile = AtomicFile(mUserListFile)
        try {
            fos = userListFile.startWrite()
            val bos = BufferedOutputStream(fos)

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            val serializer: XmlSerializer = FastXmlSerializer()
            serializer.setOutput(bos, "utf-8")
            serializer.startDocument(null, true)
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

            serializer.startTag(null, TAG_USERS)
            serializer.attribute(null, ATTR_NEXT_SERIAL_NO, mNextSerialNumber.toString())
            serializer.attribute(null, ATTR_USER_VERSION, mUserVersion.toString())

            for (i in 0..<mUsers.size) {
                val user = mUsers.valueAt(i)
                serializer.startTag(null, TAG_USER)
                serializer.attribute(null, ATTR_ID, user.id.toString())
                serializer.endTag(null, TAG_USER)
            }

            serializer.endTag(null, TAG_USERS)

            serializer.endDocument()
            userListFile.finishWrite(fos)
        } catch (_: Exception) {
            userListFile.failWrite(fos)
            Timber.e("Error writing user list")
        }
    }

    private fun readUser(id: Int): VUserInfo? {
        var flags = 0
        var serialNumber = id
        var name: String? = null
        var iconPath: String? = null
        var creationTime = 0L
        var lastLoggedInTime = 0L
        var partial = false

        var fis: FileInputStream? = null
        try {
            val userFile =
                AtomicFile(File(mUsersDir, "$id.xml"))
            fis = userFile.openRead()
            val parser = Xml.newPullParser()
            parser.setInput(fis, null)
            var type: Int
            while ((parser.next().also { type = it }) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT
            ) {
            }

            if (type != XmlPullParser.START_TAG) {
                Timber.e( "Unable to read user $id")
                return null
            }

            if (parser.name == TAG_USER) {
                val storedId = readIntAttribute(parser, ATTR_ID, -1)
                if (storedId != id) {
                    Timber.e("User id does not match the file name")
                    return null
                }
                serialNumber = readIntAttribute(parser, ATTR_SERIAL_NO, id)
                flags = readIntAttribute(parser, ATTR_FLAGS, 0)
                iconPath = parser.getAttributeValue(null, ATTR_ICON_PATH)
                creationTime = readLongAttribute(parser, ATTR_CREATION_TIME, 0)
                lastLoggedInTime = readLongAttribute(parser, ATTR_LAST_LOGGED_IN_TIME, 0)
                val valueString = parser.getAttributeValue(null, ATTR_PARTIAL)
                if ("true" == valueString) {
                    partial = true
                }

                while ((parser.next().also { type = it }) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT
                ) {
                }
                if (type == XmlPullParser.START_TAG && parser.name == TAG_NAME) {
                    type = parser.next()
                    if (type == XmlPullParser.TEXT) {
                        name = parser.text
                    }
                }
            }

            val userInfo = VUserInfo(id, name, iconPath, flags)
            userInfo.serialNumber = serialNumber
            userInfo.creationTime = creationTime
            userInfo.lastLoggedInTime = lastLoggedInTime
            userInfo.partial = partial
            return userInfo
        } catch (_: IOException) {
        } catch (_: XmlPullParserException) {
        } finally {
            if (fis != null) {
                try {
                    fis.close()
                } catch (_: IOException) {
                }
            }
        }
        return null
    }

    private fun readIntAttribute(parser: XmlPullParser, attr: String?, defaultValue: Int): Int {
        val valueString = parser.getAttributeValue(null, attr) ?: return defaultValue
        return try {
            valueString.toInt()
        } catch (_: NumberFormatException) {
            defaultValue
        }
    }

    private fun readLongAttribute(parser: XmlPullParser, attr: String?, defaultValue: Long): Long {
        val valueString = parser.getAttributeValue(null, attr) ?: return defaultValue
        return try {
            valueString.toLong()
        } catch (_: NumberFormatException) {
            defaultValue
        }
    }

    override fun createUser(name: String?, flags: Int): VUserInfo? {
        checkManageUsersPermission("Only the system can create users")

        val ident = clearCallingIdentity()
        val userInfo: VUserInfo
        try {
            synchronized(mInstallLock) {
                synchronized(mPackagesLock) {
                    if (this.isUserLimitReachedLocked) return null
                    val userId = this.nextAvailableIdLocked
                    userInfo = VUserInfo(userId, name, null, flags)
                    val userPath = File(mBaseUserPath, userId.toString())
                    userInfo.serialNumber = mNextSerialNumber++
                    val now = System.currentTimeMillis()
                    userInfo.creationTime = if (now > EPOCH_PLUS_30_YEARS) now else 0
                    userInfo.partial = true
                    VEnvironment.getUserSystemDirectory(userInfo.id).mkdirs()
                    mUsers.put(userId, userInfo)
                    writeUserListLocked()
                    writeUserLocked(userInfo)
                    mPm.createNewUser(userId, userPath)
                    userInfo.partial = false
                    writeUserLocked(userInfo)
                    updateUserIdsLocked()
                }
            }
            val addedIntent = Intent(Constants.ACTION_USER_ADDED)
            addedIntent.putExtra(Constants.EXTRA_USER_HANDLE, userInfo.id)
            VActivityManagerService.get().sendBroadcastAsUser(
                addedIntent, VUserHandle.ALL,
                null
            )
        } finally {
            restoreCallingIdentity(ident)
        }
        return userInfo
    }

    /**
     * Removes a user and all data directories created for that user. This method should be called
     * after the user's processes have been terminated.
     * @param userHandle the user's id
     */
    override fun removeUser(userHandle: Int): Boolean {
        checkManageUsersPermission("Only the system can remove users")
        val user: VUserInfo?
        synchronized(mPackagesLock) {
            user = mUsers.get(userHandle)
            if (userHandle == 0 || user == null) {
                return false
            }
            mRemovingUserIds.add(userHandle)
            // Set this to a partially created user, so that the user will be purged
            // on next startup, in case the runtime stops now before stopping and
            // removing the user completely.
            user.partial = true
            writeUserLocked(user)
        }
        if (DBG) Timber.i( "Stopping user $userHandle")
        val res = VActivityManagerService.get().stopUser(
            userHandle,
            object : IStopUserCallback.Stub() {
                override fun userStopped(userId: Int) {
                    finishRemoveUser(userId)
                }

                override fun userStopAborted(userId: Int) {
                }
            })
        return res == ActivityManagerCompat.USER_OP_SUCCESS
    }

    fun finishRemoveUser(userHandle: Int) {
        if (DBG) Timber.i( "finishRemoveUser $userHandle")
        // Let other services shutdown any activity and clean up their state before completely
        // wiping the user's system directory and removing from the user list
        val identity = clearCallingIdentity()
        try {
            val addedIntent = Intent(Constants.ACTION_USER_REMOVED)
            addedIntent.putExtra(Constants.EXTRA_USER_HANDLE, userHandle)
            VActivityManagerService.get().sendOrderedBroadcastAsUser(
                addedIntent, VUserHandle.ALL,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (DBG) {
                            Timber.i(
                                "USER_REMOVED broadcast sent, cleaning up user data %s", userHandle
                            )
                        }
                        object : Thread() {
                            override fun run() {
                                synchronized(mInstallLock) {
                                    synchronized(mPackagesLock) {
                                        removeUserStateLocked(userHandle)
                                    }
                                }
                            }
                        }.start()
                    }
                },
                null, Activity.RESULT_OK, null, null
            )
        } finally {
            restoreCallingIdentity(identity)
        }
    }

    private fun removeUserStateLocked(userHandle: Int) {
        // Cleanup package manager settings
        mPm.cleanUpUser(userHandle)

        // Remove this user from the list
        mUsers.remove(userHandle)
        mRemovingUserIds.remove(userHandle)
        // Remove user file
        val userFile = AtomicFile(File(mUsersDir, "$userHandle.xml"))
        userFile.delete()
        // Update the user list
        writeUserListLocked()
        updateUserIdsLocked()
        removeDirectoryRecursive(VEnvironment.getUserSystemDirectory(userHandle))
    }

    private fun removeDirectoryRecursive(parent: File) {
        if (parent.isDirectory()) {
            val files = parent.list()
            for (filename in files!!) {
                val child = File(parent, filename)
                removeDirectoryRecursive(child)
            }
        }
        parent.delete()
    }

    override fun getUserSerialNumber(userHandle: Int): Int {
        synchronized(mPackagesLock) {
            if (!exists(userHandle)) return -1
            return getUserInfoLocked(userHandle)!!.serialNumber
        }
    }

    override fun getUserHandle(userSerialNumber: Int): Int {
        synchronized(mPackagesLock) {
            for (userId in this.userIdsLPr) {
                if (getUserInfoLocked(userId)!!.serialNumber == userSerialNumber) return userId
            }
            // Not found
            return -1
        }
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private fun updateUserIdsLocked() {
        var num = 0
        for (i in 0..<mUsers.size) {
            if (!mUsers.valueAt(i).partial) {
                num++
            }
        }
        val newUsers = IntArray(num)
        var n = 0
        for (i in 0..<mUsers.size) {
            if (!mUsers.valueAt(i).partial) {
                newUsers[n++] = mUsers.keyAt(i)
            }
        }
        this.userIdsLPr = newUsers
    }

    /**
     * Make a note of the last started time of a user.
     * @param userId the user that was just foregrounded
     */
    fun userForeground(userId: Int) {
        synchronized(mPackagesLock) {
            val user = mUsers.get(userId)
            val now = System.currentTimeMillis()
            if (user == null || user.partial) {
                Timber.w("userForeground: unknown user #$userId")
                return
            }
            if (now > EPOCH_PLUS_30_YEARS) {
                user.lastLoggedInTime = now
                writeUserLocked(user)
            }
        }
    }

    private val nextAvailableIdLocked: Int
        /**
         * Returns the next available user id, filling in any holes in the ids.
         * TODO: May not be a good idea to recycle ids, in case it results in confusion
         * for data and battery stats collection, or unexpected cross-talk.
         * @return
         */
        get() {
            synchronized(mPackagesLock) {
                var i = mNextUserId
                while (i < Int.MAX_VALUE) {
                    if (mUsers.indexOfKey(i) < 0 && !mRemovingUserIds.contains(i)) {
                        break
                    }
                    i++
                }
                mNextUserId = i + 1
                return i
            }
        }

    override fun dump(fd: FileDescriptor, args: Array<String>?) {
    }

    companion object {
        private const val DBG = false

        private const val TAG_NAME = "name"
        private const val ATTR_FLAGS = "flags"
        private const val ATTR_ICON_PATH = "icon"
        private const val ATTR_ID = "id"
        private const val ATTR_CREATION_TIME = "created"
        private const val ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn"
        private const val ATTR_SERIAL_NO = "serialNumber"
        private const val ATTR_NEXT_SERIAL_NO = "nextSerialNumber"
        private const val ATTR_PARTIAL = "partial"
        private const val ATTR_USER_VERSION = "version"
        private const val TAG_USERS = "users"
        private const val TAG_USER = "user"

        private val USER_INFO_DIR = "system" + File.separator + "users"
        private const val USER_LIST_FILENAME = "userlist.xml"
        private const val USER_PHOTO_FILENAME = "photo.png"

        private const val MIN_USER_ID = 1

        private const val USER_VERSION = 1

        private const val EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L // ms
        private lateinit var sInstance: VUserManagerService
        @JvmStatic
        fun get(): VUserManagerService {
            synchronized(VUserManagerService::class.java) {
                return sInstance
            }
        }

        /**
         * Enforces that only the system UID or root's UID or apps that have the
         * {android.Manifest.permission.MANAGE_USERS MANAGE_USERS}
         * permission can make certain calls to the VUserManager.
         * 
         * @param message used as message if SecurityException is thrown
         * @throws SecurityException if the caller is not system or root
         */
        private fun checkManageUsersPermission(message: String?) {
            val uid = VBinder.getCallingUid()
            if (uid != VirtualCore.get().myUid()) {
                throw SecurityException("You need MANAGE_USERS permission to: $message")
            }
        }
    }
}

package com.lody.virtual.server.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.accounts.IAccountAuthenticator
import android.accounts.IAccountAuthenticatorResponse
import android.accounts.IAccountManagerResponse
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.SparseArray
import android.util.Xml
import androidx.core.util.size
import com.lody.virtual.IntentHelper.INTENT_USER_ID
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.helper.compat.AccountManagerCompat
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.server.IAccountManager
import com.lody.virtual.server.am.VActivityManagerService
import com.lody.virtual.server.pm.VPackageManagerService
import com.lody.virtual.server.security.SecurityUtils.enforceCallingUser
import mirror.com.android.internal.R_Hide
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * @author Lody
 */
class VAccountManagerService : IAccountManager.Stub() {
    // TODO refactor to accounts by UID
    // TODO remove accounts on app uninstall
    private val accountsByUserId = SparseArray<MutableList<VAccount>?>()
    private val authTokenRecords = LinkedList<AuthTokenRecord>()
    private val mSessions = LinkedHashMap<String?, Session?>()
    private val cache = AuthenticatorCache()
    private val mContext: Context = VirtualCore.get().context
    private var lastAccountChangeTime: Long = 0


    override fun getAuthenticatorTypes(userId: Int): Array<AuthenticatorDescription?> {
        enforceCallingUser(userId)
        synchronized(cache) {
            val descArray = arrayOfNulls<AuthenticatorDescription>(cache.authenticators.size)
            for ((i, info) in cache.authenticators.values.withIndex()) {
                descArray[i] = info.desc
            }
            return descArray
        }
    }

    override fun getAccountsByFeatures(
        userId: Int,
        response: IAccountManagerResponse,
        type: String,
        features: Array<String?>?
    ) {
        enforceCallingUser(userId)
        val info = getAuthenticatorInfo(type)
        if (info == null) {
            val bundle = Bundle()
            bundle.putParcelableArray(AccountManager.KEY_ACCOUNTS, arrayOfNulls<Account>(0))
            try {
                response.onResult(bundle)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }

        if (features.isNullOrEmpty()) {
            val bundle = Bundle()
            bundle.putParcelableArray(AccountManager.KEY_ACCOUNTS, getAccounts(userId, type))
            try {
                response.onResult(bundle)
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        } else {
            GetAccountsByTypeAndFeatureSession(response, userId, info, features).bind()
        }
    }

    override fun getPreviousName(userId: Int, account: Account): String? {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            var previousName: String? = null
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                previousName = vAccount.previousName
            }
            return previousName
        }
    }


    override fun getAccounts(userId: Int, type: String?): Array<Account?> {
        enforceCallingUser(userId)
        val accountList = getAccountList(userId, type)
        return accountList.toTypedArray<Account?>()
    }


    private fun getAccountList(userId: Int, type: String?): List<Account?> {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val accounts: MutableList<Account?> = ArrayList()
            val vAccounts = accountsByUserId.get(userId)
            if (vAccounts != null) {
                for (vAccount in vAccounts) {
                    if (type == null || vAccount.type == type) {
                        accounts.add(Account(vAccount.name, vAccount.type))
                    }
                }
            }
            return accounts
        }
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    override fun getAuthToken(
        userId: Int,
        response: IAccountManagerResponse,
        account: Account?,
        authTokenType: String?,
        notifyOnAuthFailure: Boolean,
        expectActivityLaunch: Boolean,
        loginOptions: Bundle
    ) {
        enforceCallingUser(userId)
        try {
            if (account == null) {
                Timber.w( "getAuthToken called with null account")
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "account is null")
                return
            }
            if (authTokenType == null) {
                Timber.w("getAuthToken called with null authTokenType")
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "authTokenType is null")
                return
            }
        } catch (e: RemoteException) {
            e.printStackTrace()
            return
        }
        val info = getAuthenticatorInfo(account.type)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        // Get the calling package. We will use it for the purpose of caching.
        val callerPkg = loginOptions.getString(AccountManagerCompat.KEY_ANDROID_PACKAGE_NAME)
        val customTokens = info.desc.customTokens

        loginOptions.putInt(AccountManager.KEY_CALLER_UID, VBinder.getCallingUid())
        loginOptions.putInt(AccountManager.KEY_CALLER_PID, getCallingPid())
        if (notifyOnAuthFailure) {
            loginOptions.putBoolean(AccountManagerCompat.KEY_NOTIFY_ON_FAILURE, true)
        }
        if (!customTokens) {
            val vAccount: VAccount?
            synchronized(accountsByUserId) {
                vAccount = getAccount(userId, account)
                val authToken = vAccount?.authTokens?.get(authTokenType)
                if (authToken != null) {
                    val result = Bundle()
                    result.putString(AccountManager.KEY_AUTHTOKEN, authToken)
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                    onResult(response, result)
                    return
                }
            }
        }
        if (customTokens) {
            val authToken = getCustomAuthToken(userId, account, authTokenType, callerPkg!!)
            if (authToken != null) {
                val result = Bundle()
                result.putString(AccountManager.KEY_AUTHTOKEN, authToken)
                result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                onResult(response, result)
                return
            }
        }
        object : Session(response, userId, info, expectActivityLaunch, false, account.name) {
            override fun toDebugString(now: Long): String {
                return (super.toDebugString(now) + ", getAuthToken"
                        + ", " + account
                        + ", authTokenType " + authTokenType
                        + ", loginOptions " + loginOptions
                        + ", notifyOnAuthFailure " + notifyOnAuthFailure)
            }

            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.getAuthToken(this, account, authTokenType, loginOptions)
            }

            @Throws(RemoteException::class)
            override fun onResult(result: Bundle?) {
                if (result != null) {
                    val authToken = result.getString(AccountManager.KEY_AUTHTOKEN)
                    if (authToken != null) {
                        val name = result.getString(AccountManager.KEY_ACCOUNT_NAME)
                        val type = result.getString(AccountManager.KEY_ACCOUNT_TYPE)
                        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
                            onError(
                                AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "the type and name should not be empty"
                            )
                            return
                        }
                        if (!customTokens) {
                            synchronized(accountsByUserId) {
                                var account = getAccount(userId, name, type)
                                if (account == null) {
                                    var accounts = accountsByUserId.get(userId)
                                    if (accounts == null) {
                                        accounts = mutableListOf()
                                        accountsByUserId.put(userId, accounts)
                                    }
                                    account = VAccount(userId, Account(name!!, type!!))
                                    accounts.add(account)
                                    saveAllAccounts()
                                }
                            }
                        }
                        val expiryMillis = result.getLong(
                            AccountManagerCompat.KEY_CUSTOM_TOKEN_EXPIRY, 0L
                        )
                        if (customTokens
                            && expiryMillis > System.currentTimeMillis()
                        ) {
                            val record = AuthTokenRecord(
                                userId,
                                account,
                                authTokenType,
                                callerPkg!!,
                                authToken,
                                expiryMillis
                            )
                            synchronized(authTokenRecords) {
                                authTokenRecords.remove(record)
                                authTokenRecords.add(record)
                            }
                        }
                    }
                    val intent = result.getParcelable<Intent?>(AccountManager.KEY_INTENT)
                    if (intent != null && notifyOnAuthFailure && !customTokens) {
                        // TODO: send Signin error Notification
                    }
                }
                super.onResult(result)
            }
        }.bind()
    }


    override fun setPassword(userId: Int, account: Account, password: String?) {
        enforceCallingUser(userId)
        setPasswordInternal(userId, account, password)
    }

    private fun setPasswordInternal(userId: Int, account: Account, password: String?) {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                vAccount.password = password
                vAccount.authTokens.clear()
                saveAllAccounts()
                synchronized(authTokenRecords) {
                    val iterator = authTokenRecords.iterator()
                    while (iterator.hasNext()) {
                        val record = iterator.next()
                        if (record.userId == userId && record.account == account) {
                            iterator.remove()
                        }
                    }
                }
                sendAccountsChangedBroadcast(userId)
            }
        }
    }

    override fun setAuthToken(
        userId: Int,
        account: Account,
        authTokenType: String,
        authToken: String?
    ) {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                // FIXME: cancelNotification
                vAccount.authTokens[authTokenType] = authToken
                this.saveAllAccounts()
            }
        }
    }


    override fun setUserData(userId: Int, account: Account, key: String, value: String?) {
        enforceCallingUser(userId)
        val vAccount = getAccount(userId, account)
        if (vAccount != null) {
            synchronized(accountsByUserId) {
                vAccount.userDatas[key] = value
                saveAllAccounts()
            }
        }
    }


    override fun hasFeatures(
        userId: Int, response: IAccountManagerResponse,
        account: Account, features: Array<String?>
    ) {
        enforceCallingUser(userId)
        val info = this.getAuthenticatorInfo(account.type)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        object : Session(response, userId, info, false, true, account.name) {
            @Throws(RemoteException::class)
            override fun run() {
                try {
                    mAuthenticator!!.hasFeatures(this, account, features)
                } catch (_: RemoteException) {
                    onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception")
                }
            }

            @Throws(RemoteException::class)
            override fun onResult(result: Bundle?) {
                val response = this.responseAndClose
                if (response != null) {
                    try {
                        if (result == null) {
                            response.onError(
                                AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle"
                            )
                            return
                        }
                        Timber.v(
                            (javaClass.getSimpleName() + " calling onResult() on response "
                                    + response)
                        )
                        val newResult = Bundle()
                        newResult.putBoolean(
                            AccountManager.KEY_BOOLEAN_RESULT,
                            result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
                        )
                        response.onResult(newResult)
                    } catch (e: RemoteException) {
                        // if the caller is dead then there is no one to care about remote exceptions
                        Timber.v(e, "failure while notifying response")
                    }
                }
            }
        }.bind()
    }


    override fun updateCredentials(
        userId: Int, response: IAccountManagerResponse, account: Account,
        authTokenType: String, expectActivityLaunch: Boolean,
        loginOptions: Bundle?
    ) {
        enforceCallingUser(userId)
        val info = this.getAuthenticatorInfo(account.type)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        object : Session(response, userId, info, expectActivityLaunch, false, account.name) {
            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.updateCredentials(this, account, authTokenType, loginOptions)
            }

            override fun toDebugString(now: Long): String {
                loginOptions?.keySet()
                return (super.toDebugString(now) + ", updateCredentials"
                        + ", " + account
                        + ", authTokenType " + authTokenType
                        + ", loginOptions " + loginOptions)
            }
        }.bind()
    }

    override fun getPassword(userId: Int, account: Account): String? {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                return vAccount.password
            }
            return null
        }
    }

    override fun getUserData(userId: Int, account: Account, key: String): String? {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                return vAccount.userDatas[key]
            }
            return null
        }
    }

    override fun editProperties(
        userId: Int, response: IAccountManagerResponse, accountType: String,
        expectActivityLaunch: Boolean
    ) {
        enforceCallingUser(userId)
        val info = this.getAuthenticatorInfo(accountType)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        object : Session(response, userId, info, expectActivityLaunch, true, null) {
            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.editProperties(this, mAuthenticatorInfo.desc.type)
            }

            override fun toDebugString(now: Long): String {
                return (super.toDebugString(now) + ", editProperties"
                        + ", accountType " + accountType)
            }
        }.bind()
    }


    override fun getAuthTokenLabel(
        userId: Int, response: IAccountManagerResponse, accountType: String,
        authTokenType: String
    ) {
        enforceCallingUser(userId)
        val info = getAuthenticatorInfo(accountType)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        object : Session(response, userId, info, false, false, null) {
            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.getAuthTokenLabel(this, authTokenType)
            }

            @Throws(RemoteException::class)
            override fun onResult(result: Bundle?) {
                if (result != null) {
                    val label = result.getString(AccountManager.KEY_AUTH_TOKEN_LABEL)
                    val bundle = Bundle()
                    bundle.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, label)
                    super.onResult(bundle)
                } else {
                    super.onResult(null)
                }
            }
        }.bind()
    }

    override fun confirmCredentials(
        userId: Int,
        response: IAccountManagerResponse,
        account: Account,
        options: Bundle?,
        expectActivityLaunch: Boolean
    ) {
        enforceCallingUser(userId)
        val info = getAuthenticatorInfo(account.type)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        object :
            Session(response, userId, info, expectActivityLaunch, true, account.name, true, true) {
            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.confirmCredentials(this, account, options)
            }
        }.bind()
    }

    override fun addAccount(
        userId: Int, response: IAccountManagerResponse, accountType: String,
        authTokenType: String?, requiredFeatures: Array<String?>?,
        expectActivityLaunch: Boolean, optionsIn: Bundle?
    ) {
        enforceCallingUser(userId)
        val info = getAuthenticatorInfo(accountType)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }
        object : Session(response, userId, info, expectActivityLaunch, true, null, false, true) {
            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.addAccount(
                    this, mAuthenticatorInfo.desc.type, authTokenType, requiredFeatures,
                    optionsIn
                )
            }

            override fun toDebugString(now: Long): String {
                return (super.toDebugString(now) + ", addAccount"
                        + ", accountType " + accountType
                        + ", requiredFeatures "
                        + (if (requiredFeatures != null)
                    TextUtils.join(",", requiredFeatures)
                else
                    null))
            }
        }.bind()
    }

    override fun addAccountExplicitly(
        userId: Int,
        account: Account,
        password: String?,
        extras: Bundle?
    ): Boolean {
        enforceCallingUser(userId)
        return insertAccountIntoDatabase(userId, account, password, extras)
    }

    override fun removeAccountExplicitly(userId: Int, account: Account?): Boolean {
        enforceCallingUser(userId)
        return account != null && removeAccountInternal(userId, account)
    }

    override fun renameAccount(
        userId: Int,
        response: IAccountManagerResponse,
        accountToRename: Account,
        newName: String?
    ) {
        enforceCallingUser(userId)
        val resultingAccount = renameAccountInternal(userId, accountToRename, newName)
        val result = Bundle()
        result.putString(AccountManager.KEY_ACCOUNT_NAME, resultingAccount.name)
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, resultingAccount.type)
        try {
            response.onResult(result)
        } catch (e: RemoteException) {
            Timber.w(e)
        }
    }

    override fun removeAccount(
        userId: Int, response: IAccountManagerResponse, account: Account,
        expectActivityLaunch: Boolean
    ) {
        enforceCallingUser(userId)
        val info = this.getAuthenticatorInfo(account.type)
        if (info == null) {
            try {
                response.onError(
                    AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    "account.type does not exist"
                )
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
            return
        }

        // FIXME: Cancel Notification
        object : Session(response, userId, info, expectActivityLaunch, true, account.name) {
            override fun toDebugString(now: Long): String {
                return (super.toDebugString(now) + ", removeAccount"
                        + ", account " + account)
            }

            @Throws(RemoteException::class)
            override fun run() {
                mAuthenticator!!.getAccountRemovalAllowed(this, account)
            }

            @Throws(RemoteException::class)
            override fun onResult(result: Bundle?) {
                if (result != null && result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)
                    && !result.containsKey(AccountManager.KEY_INTENT)
                ) {
                    val removalAllowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)
                    if (removalAllowed) {
                        removeAccountInternal(userId, account)
                    }
                    val response = this.responseAndClose
                    if (response != null) {
                        Timber.v(
                            (javaClass.getSimpleName() + " calling onResult() on response "
                                    + response)
                        )
                        val result2 = Bundle()
                        result2.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, removalAllowed)
                        try {
                            response.onResult(result2)
                        } catch (e: RemoteException) {
                            e.printStackTrace()
                        }
                    }
                }
                super.onResult(result)
            }
        }.bind()
    }

    override fun clearPassword(userId: Int, account: Account) {
        enforceCallingUser(userId)
        setPasswordInternal(userId, account, null)
    }

    private fun removeAccountInternal(userId: Int, account: Account): Boolean {
        val accounts = accountsByUserId.get(userId)
        if (accounts != null) {
            val iterator: MutableIterator<VAccount> = accounts.iterator()
            while (iterator.hasNext()) {
                val vAccount = iterator.next()
                if (userId == vAccount.userId && TextUtils.equals(vAccount.name, account.name)
                    && TextUtils.equals(account.type, vAccount.type)
                ) {
                    iterator.remove()
                    saveAllAccounts()
                    sendAccountsChangedBroadcast(userId)
                    return true
                }
            }
        }
        return false
    }


    override fun accountAuthenticated(userId: Int, account: Account): Boolean {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                vAccount.lastAuthenticatedTime = System.currentTimeMillis()
                saveAllAccounts()
                return true
            }
            return false
        }
    }

    override fun invalidateAuthToken(userId: Int, accountType: String, authToken: String) {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val accounts = accountsByUserId.get(userId)
            if (accounts != null) {
                var changed = false
                for (account in accounts) {
                    if (account.type == accountType) {
                        account.authTokens.values.remove(authToken)
                        changed = true
                    }
                }
                if (changed) {
                    saveAllAccounts()
                }
            }
            synchronized(authTokenRecords) {
                val iterator = authTokenRecords.iterator()
                while (iterator.hasNext()) {
                    val record = iterator.next()
                    if (record.userId == userId && record.authTokenType == accountType
                        && record.authToken == authToken
                    ) {
                        iterator.remove()
                    }
                }
            }
        }
    }


    private fun renameAccountInternal(
        userId: Int,
        accountToRename: Account,
        newName: String?
    ): Account {
        // TODO: Cancel Notification
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, accountToRename)
            if (vAccount != null) {
                vAccount.previousName = vAccount.name
                vAccount.name = newName
                saveAllAccounts()
                val newAccount = Account(vAccount.name, vAccount.type)
                synchronized(authTokenRecords) {
                    for (record in authTokenRecords) {
                        if (record.userId == userId && record.account == accountToRename) {
                            record.account = newAccount
                        }
                    }
                }
                sendAccountsChangedBroadcast(userId)
                return newAccount
            }
        }
        return accountToRename
    }

    override fun peekAuthToken(userId: Int, account: Account, authTokenType: String): String? {
        enforceCallingUser(userId)
        synchronized(accountsByUserId) {
            val vAccount = getAccount(userId, account)
            if (vAccount != null) {
                return vAccount.authTokens[authTokenType]
            }
            return null
        }
    }


    private fun getCustomAuthToken(
        userId: Int,
        account: Account,
        authTokenType: String,
        packageName: String
    ): String? {
        val record = AuthTokenRecord(userId, account, authTokenType, packageName)
        var authToken: String? = null
        val now = System.currentTimeMillis()
        synchronized(authTokenRecords) {
            val iterator = authTokenRecords.iterator()
            while (iterator.hasNext()) {
                val one = iterator.next()
                if (one.expiryEpochMillis in 1..<now) {
                    iterator.remove()
                } else if (record == one) {
                    authToken = record.authToken
                }
            }
        }
        return authToken
    }

    private fun onResult(response: IAccountManagerResponse, result: Bundle?) {
        try {
            response.onResult(result)
        } catch (e: RemoteException) {
            // if the caller is dead then there is no one to care about remote
            // exceptions
            e.printStackTrace()
        }
    }

    private fun getAuthenticatorInfo(type: String?): AuthenticatorInfo? {
        synchronized(cache) {
            return if (type == null) null else cache.authenticators[type]
        }
    }


    private fun getAccount(userId: Int, account: Account): VAccount? {
        return this.getAccount(userId, account.name, account.type)
    }

    private fun insertAccountIntoDatabase(
        userId: Int,
        account: Account?,
        password: String?,
        extras: Bundle?
    ): Boolean {
        if (account == null) {
            return false
        }
        synchronized(accountsByUserId) {
            val vAccount = VAccount(userId, account)
            vAccount.password = password
            // convert the [Bundle] to [Map<String, String>]
            if (extras != null) {
                for (key in extras.keySet()) {
                    val value = extras.get(key)
                    if (value is String) {
                        vAccount.userDatas[key] = value
                    }
                }
            }
            var accounts = accountsByUserId.get(userId)
            if (accounts == null) {
                accounts = mutableListOf()
                accountsByUserId.put(userId, accounts)
            }
            accounts.add(vAccount)
            saveAllAccounts()
            sendAccountsChangedBroadcast(vAccount.userId)
            return true
        }
    }

    private fun sendAccountsChangedBroadcast(userId: Int) {
        val intent = Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION)
        VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle(userId))
        broadcastCheckInNowIfNeed(userId)
    }

    private fun broadcastCheckInNowIfNeed(userId: Int) {
        val time = System.currentTimeMillis()
        if (abs(time - lastAccountChangeTime) > CHECK_IN_TIME) {
            lastAccountChangeTime = time
            saveAllAccounts()
            val intent = Intent("android.server.checkin.CHECKIN_NOW")
            VActivityManagerService.get().sendBroadcastAsUser(intent, VUserHandle(userId))
        }
    }

    /**
     * Serializing all accounts
     */
    private fun saveAllAccounts() {
        val accountFile = VEnvironment.getAccountConfigFile()
        val dest = Parcel.obtain()
        try {
            dest.writeInt(1)
            val accounts: MutableList<VAccount> = ArrayList()
            for (i in 0..<this.accountsByUserId.size) {
                val list = this.accountsByUserId.valueAt(i)
                if (list != null) {
                    accounts.addAll(list)
                }
            }
            dest.writeInt(accounts.size)
            for (account in accounts) {
                account.writeToParcel(dest, 0)
            }
            dest.writeLong(lastAccountChangeTime)
            val fileOutputStream = FileOutputStream(accountFile)
            fileOutputStream.write(dest.marshall())
            fileOutputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dest.recycle()
    }

    /**
     * Read all accounts from file.
     */
    private fun readAllAccounts() {
        val accountFile = VEnvironment.getAccountConfigFile()
        refreshAuthenticatorCache(null)
        if (accountFile.exists()) {
            accountsByUserId.clear()
            val dest = Parcel.obtain()
            try {
                val `is` = FileInputStream(accountFile)
                val bytes = ByteArray(accountFile.length().toInt())
                val readLength = `is`.read(bytes)
                `is`.close()
                if (readLength != bytes.size) {
                    throw IOException(
                        String.format(
                            Locale.ENGLISH,
                            "Expect length %d, but got %d.",
                            bytes.size,
                            readLength
                        )
                    )
                }
                dest.unmarshall(bytes, 0, bytes.size)
                dest.setDataPosition(0)
                dest.readInt() // skip the magic
                var size = dest.readInt() // the VAccount's size we need to read
                var invalid = false
                while (size-- > 0) {
                    val account = VAccount(dest)
                    Timber.d( "Reading account : %s", account.type)
                    val info = cache.authenticators[account.type]
                    if (info != null) {
                        var accounts = accountsByUserId.get(account.userId)
                        if (accounts == null) {
                            accounts = mutableListOf()
                            accountsByUserId.put(account.userId, accounts)
                        }
                        accounts.add(account)
                    } else {
                        invalid = true
                    }
                }
                lastAccountChangeTime = dest.readLong()
                if (invalid) {
                    saveAllAccounts()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                dest.recycle()
            }
        }
    }


    private fun getAccount(userId: Int, accountName: String?, accountType: String?): VAccount? {
        val accounts = accountsByUserId.get(userId)
        if (accounts != null) {
            for (account in accounts) {
                if (TextUtils.equals(account.name, accountName) && TextUtils.equals(
                        account.type,
                        accountType
                    )
                ) {
                    return account
                }
            }
        }
        return null
    }


    fun refreshAuthenticatorCache(packageName: String?) {
        cache.authenticators.clear()
        val intent = Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT)
        if (packageName != null) {
            intent.setPackage(packageName)
        }
        generateServicesMap(
            VPackageManagerService.get()
                .queryIntentServices(intent, null, PackageManager.GET_META_DATA, 0) ?: emptyList(),
            cache.authenticators, RegisteredServicesParser()
        )
    }

    private fun generateServicesMap(
        services: List<ResolveInfo>, map: MutableMap<String?, AuthenticatorInfo>,
        accountParser: RegisteredServicesParser
    ) {
        for (info in services) {
            val parser = accountParser.getParser(
                mContext, info.serviceInfo,
                AccountManager.AUTHENTICATOR_META_DATA_NAME
            )
            if (parser != null) {
                try {
                    val attributeSet = Xml.asAttributeSet(parser)
                    var type: Int
                    while ((parser.next().also {
                            type = it
                        }) != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                        // Nothing to do
                    }
                    if (AccountManager.AUTHENTICATOR_ATTRIBUTES_NAME == parser.name) {
                        val desc: AuthenticatorDescription? = parseAuthenticatorDescription(
                            accountParser.getResources(mContext, info.serviceInfo.applicationInfo),
                            info.serviceInfo.packageName, attributeSet
                        )
                        if (desc != null) {
                            map[desc.type] = AuthenticatorInfo(desc, info.serviceInfo)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    internal class AuthTokenRecord {
        var userId: Int
        var account: Account
        var expiryEpochMillis: Long = 0
        var authToken: String? = null
        val authTokenType: String
        private val packageName: String

        constructor(
            userId: Int,
            account: Account,
            authTokenType: String,
            packageName: String,
            authToken: String,
            expiryEpochMillis: Long
        ) {
            this.userId = userId
            this.account = account
            this.authTokenType = authTokenType
            this.packageName = packageName
            this.authToken = authToken
            this.expiryEpochMillis = expiryEpochMillis
        }

        constructor(userId: Int, account: Account, authTokenType: String, packageName: String) {
            this.userId = userId
            this.account = account
            this.authTokenType = authTokenType
            this.packageName = packageName
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as AuthTokenRecord
            return userId == that.userId && account == that.account
                    && authTokenType == that.authTokenType
                    && packageName == that.packageName
        }

        override fun hashCode(): Int {
            return (((this.userId * 31 + this.account.hashCode()) * 31
                    + this.authTokenType.hashCode()) * 31
                    + this.packageName.hashCode())
        }
    }

    private class AuthenticatorInfo(
        val desc: AuthenticatorDescription,
        val serviceInfo: ServiceInfo
    )

    private class AuthenticatorCache {
        val authenticators: MutableMap<String?, AuthenticatorInfo> =
            HashMap()
    }

    private abstract inner class Session @JvmOverloads constructor(
        response: IAccountManagerResponse?,
        userId: Int,
        info: AuthenticatorInfo,
        expectActivityLaunch: Boolean,
        stripAuthTokenFromResult: Boolean,
        accountName: String?,
        authDetailsRequired: Boolean = false,
        updateLastAuthenticatedTime: Boolean = false
    ) : IAccountAuthenticatorResponse.Stub(), IBinder.DeathRecipient, ServiceConnection {
        val mUserId: Int
        val mAuthenticatorInfo: AuthenticatorInfo
        private val mStripAuthTokenFromResult: Boolean = stripAuthTokenFromResult
        var mNumResults: Int = 0
        var mAuthenticator: IAccountAuthenticator? = null
        private var mResponse: IAccountManagerResponse?
        private val mExpectActivityLaunch: Boolean
        private val mCreationTime: Long
        private val mAccountName: String?
        private val mAuthDetailsRequired: Boolean
        private val mUpdateLastAuthenticatedTime: Boolean
        private var mNumRequestContinued = 0
        private var mNumErrors = 0


        init {
            this.mResponse = response
            this.mUserId = userId
            this.mAuthenticatorInfo = info
            this.mExpectActivityLaunch = expectActivityLaunch
            this.mCreationTime = SystemClock.elapsedRealtime()
            this.mAccountName = accountName
            this.mAuthDetailsRequired = authDetailsRequired
            this.mUpdateLastAuthenticatedTime = updateLastAuthenticatedTime
            synchronized(mSessions) {
                mSessions.put(toString(), this)
            }
            if (response != null) {
                try {
                    response.asBinder().linkToDeath(this, 0 /* flags */)
                } catch (_: RemoteException) {
                    mResponse = null
                    binderDied()
                }
            }
        }

        val responseAndClose: IAccountManagerResponse?
            get() {
                if (mResponse == null) {
                    // this session has already been closed
                    return null
                }
                val response = mResponse
                close() // this clears mResponse so we need to save the response before this call
                return response
            }

        fun close() {
            synchronized(mSessions) {
                if (mSessions.remove(toString()) == null) {
                    // the session was already closed, so bail out now
                    return
                }
            }
            if (mResponse != null) {
                // stop listening for response deaths
                mResponse!!.asBinder().unlinkToDeath(this, 0 /* flags */)

                // clear this so that we don't accidentally send any further results
                mResponse = null
            }
            unbind()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service)
            try {
                run()
            } catch (_: RemoteException) {
                onError(
                    AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                    "remote exception"
                )
            }
        }

        override fun onRequestContinued() {
            mNumRequestContinued++
        }

        override fun onError(errorCode: Int, errorMessage: String?) {
            mNumErrors++
            val response = this.responseAndClose
            if (response != null) {
                Timber.v(
                    (javaClass.getSimpleName()
                            + " calling onError() on response " + response)
                )
                try {
                    response.onError(errorCode, errorMessage)
                } catch (e: RemoteException) {
                    Timber.v(e, "Session.onError: caught RemoteException while responding")
                }
            } else {
                Timber.v("Session.onError: already closed")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mAuthenticator = null
            val response = this.responseAndClose
            if (response != null) {
                try {
                    response.onError(
                        AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "disconnected"
                    )
                } catch (e: RemoteException) {
                    Timber.v(
                        e, "Session.onServiceDisconnected: "
                                + "caught RemoteException while responding"
                    )
                }
            }
        }

        @Throws(RemoteException::class)
        override fun onResult(result: Bundle?) {
            mNumResults++
            if (result != null) {
                val isSuccessfulConfirmCreds = result.getBoolean(
                    AccountManager.KEY_BOOLEAN_RESULT, false
                )
                val isSuccessfulUpdateCredsOrAddAccount =
                    result.containsKey(AccountManager.KEY_ACCOUNT_NAME)
                            && result.containsKey(AccountManager.KEY_ACCOUNT_TYPE)
                // We should only update lastAuthenticated time, if
                // mUpdateLastAuthenticatedTime is true and the confirmRequest
                // or updateRequest was successful
                val needUpdate = mUpdateLastAuthenticatedTime
                        && (isSuccessfulConfirmCreds || isSuccessfulUpdateCredsOrAddAccount)
                if (needUpdate || mAuthDetailsRequired) {
                    synchronized(accountsByUserId) {
                        val account =
                            getAccount(mUserId, mAccountName, mAuthenticatorInfo.desc.type)
                        if (needUpdate && account != null) {
                            account.lastAuthenticatedTime = System.currentTimeMillis()
                            saveAllAccounts()
                        }
                        if (mAuthDetailsRequired) {
                            var lastAuthenticatedTime: Long = -1
                            if (account != null) {
                                lastAuthenticatedTime = account.lastAuthenticatedTime
                            }
                            result.putLong(
                                AccountManagerCompat.KEY_LAST_AUTHENTICATED_TIME,
                                lastAuthenticatedTime
                            )
                        }
                    }
                }
            }
            if (result != null
                && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))
            ) {
//				String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
//				String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
//				if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
//					Account account = new Account(accountName, accountType);
//					FIXME: Cancel Notification
//				}
            }
            var intent: Intent? = null
            if (result != null) {
                intent = result.getParcelable<Intent?>(AccountManager.KEY_INTENT)
            }
            val response = if (mExpectActivityLaunch && result != null && result.containsKey(AccountManager.KEY_INTENT)) {
                mResponse
            } else {
                this.responseAndClose
            }
            if (response != null) {
                try {
                    if (result == null) {
                        Timber.v(
                            (javaClass.getSimpleName()
                                    + " calling onError() on response " + response)
                        )
                        response.onError(
                            AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "null bundle returned"
                        )
                    } else {
                        if (mStripAuthTokenFromResult) {
                            result.remove(AccountManager.KEY_AUTHTOKEN)
                        }
                        Timber.v(
                            (javaClass.getSimpleName()
                                    + " calling onResult() on response " + response)
                        )
                        if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0) &&
                            (intent == null)
                        ) {
                            // All AccountManager error codes are greater than 0
                            response.onError(
                                result.getInt(AccountManager.KEY_ERROR_CODE),
                                result.getString(AccountManager.KEY_ERROR_MESSAGE)
                            )
                        } else {
                            response.onResult(result)
                        }
                    }
                } catch (e: RemoteException) {
                    // if the caller is dead then there is no one to care about remote exceptions
                    Timber.v(e, "failure while notifying response")
                }
            }
        }

        @Throws(RemoteException::class)
        abstract fun run()

        fun bind() {
            Timber.v("initiating bind to authenticator type %s", mAuthenticatorInfo.desc.type)
            val intent = Intent()
            intent.setAction(AccountManager.ACTION_AUTHENTICATOR_INTENT)
            intent.setClassName(
                mAuthenticatorInfo.serviceInfo.packageName,
                mAuthenticatorInfo.serviceInfo.name
            )
            intent.putExtra(INTENT_USER_ID, mUserId)

            if (!mContext.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                Timber.d("bind attempt failed for %s", toDebugString())
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "bind failure")
            }
        }

        protected fun toDebugString(): String {
            return toDebugString(SystemClock.elapsedRealtime())
        }

        protected open fun toDebugString(now: Long): String {
            return ("Session: expectLaunch " + mExpectActivityLaunch
                    + ", connected " + (mAuthenticator != null)
                    + ", stats (" + mNumResults + "/" + mNumRequestContinued
                    + "/" + mNumErrors + ")"
                    + ", lifetime " + ((now - mCreationTime) / 1000.0))
        }

        fun unbind() {
            if (mAuthenticator != null) {
                mAuthenticator = null
                mContext.unbindService(this)
            }
        }

        override fun binderDied() {
            mResponse = null
            close()
        }
    }

    private inner class GetAccountsByTypeAndFeatureSession(
        response: IAccountManagerResponse?,
        userId: Int,
        info: AuthenticatorInfo,
        private val mFeatures: Array<String?>?
    ) : Session(
        response, userId, info, false,  /* expectActivityLaunch */
        true,  /* stripAuthTokenFromResult */null /* accountName */
    ) {
        @Volatile
        private var mAccountsOfType: Array<Account?>? = null

        @Volatile
        private var mAccountsWithFeatures: ArrayList<Account?>? = null

        @Volatile
        private var mCurrentAccount = 0

        @Throws(RemoteException::class)
        override fun run() {
            mAccountsOfType = getAccounts(mUserId, mAuthenticatorInfo.desc.type)
            // check whether each account matches the requested features
            mAccountsWithFeatures = ArrayList(mAccountsOfType!!.size)
            mCurrentAccount = 0

            checkAccount()
        }

        fun checkAccount() {
            if (mCurrentAccount >= mAccountsOfType!!.size) {
                sendResult()
                return
            }

            val accountAuthenticator = mAuthenticator
            if (accountAuthenticator == null) {
                // It is possible that the authenticator has died, which is indicated by
                // mAuthenticator being set to null. If this happens then just abort.
                // There is no need to send back a result or error in this case since
                // that already happened when mAuthenticator was cleared.
                Timber.v(
                    ("checkAccount: aborting session since we are no longer"
                            + " connected to the authenticator, " + toDebugString())
                )
                return
            }
            try {
                accountAuthenticator.hasFeatures(
                    this,
                    mAccountsOfType!![mCurrentAccount],
                    mFeatures
                )
            } catch (_: RemoteException) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception")
            }
        }

        override fun onResult(result: Bundle?) {
            mNumResults++
            if (result == null) {
                onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle")
                return
            }
            if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                mAccountsWithFeatures!!.add(mAccountsOfType!![mCurrentAccount])
            }
            mCurrentAccount++
            checkAccount()
        }

        fun sendResult() {
            val response = this.responseAndClose
            if (response != null) {
                try {
                    val accounts = arrayOfNulls<Account>(mAccountsWithFeatures!!.size)
                    for (i in accounts.indices) {
                        accounts[i] = mAccountsWithFeatures!![i]
                    }
                        Timber.v(
                            (javaClass.getSimpleName() + " calling onResult() on response "
                                    + response)
                        )
                    val result = Bundle()
                    result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts)
                    response.onResult(result)
                } catch (e: RemoteException) {
                    // if the caller is dead then there is no one to care about remote exceptions
                    Timber.v(e, "failure while notifying response")
                }
            }
        }


        override fun toDebugString(now: Long): String {
            return (super.toDebugString(now) + ", getAccountsByTypeAndFeatures"
                    + ", " + (if (mFeatures != null) TextUtils.join(",", mFeatures) else null))
        }
    }

    companion object {
        private val sInstance = AtomicReference<VAccountManagerService>()
        private const val CHECK_IN_TIME = 30 * 24 * 60 * 1000L

        @JvmStatic
        fun get(): VAccountManagerService {
            return sInstance.get()
        }

        @JvmStatic
        fun systemReady() {
            val service = VAccountManagerService()
            service.readAllAccounts()
            sInstance.set(service)
        }


        private fun parseAuthenticatorDescription(
            resources: Resources, packageName: String?,
            attributeSet: AttributeSet?
        ): AuthenticatorDescription? {
            val array = resources.obtainAttributes(
                attributeSet,
                R_Hide.styleable.AccountAuthenticator.get()
            )
            try {
                val accountType =
                    array.getString(R_Hide.styleable.AccountAuthenticator_accountType.get())
                val label =
                    array.getResourceId(R_Hide.styleable.AccountAuthenticator_label.get(), 0)
                val icon = array.getResourceId(R_Hide.styleable.AccountAuthenticator_icon.get(), 0)
                val smallIcon =
                    array.getResourceId(R_Hide.styleable.AccountAuthenticator_smallIcon.get(), 0)
                val accountPreferences = array.getResourceId(
                    R_Hide.styleable.AccountAuthenticator_accountPreferences.get(),
                    0
                )
                val customTokens = array.getBoolean(
                    R_Hide.styleable.AccountAuthenticator_customTokens.get(),
                    false
                )
                if (TextUtils.isEmpty(accountType)) {
                    return null
                }
                return AuthenticatorDescription(
                    accountType, packageName, label, icon, smallIcon, accountPreferences,
                    customTokens
                )
            } finally {
                array.recycle()
            }
        }
    }
}

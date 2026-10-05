package com.lody.virtual.server.log

import android.widget.Toast
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VBinder
import com.lody.virtual.remote.logging.LogMessageHolder
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.server.IVLoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toLocalDateTime
import timber.log.Timber
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

object VLoggingManagerService : IVLoggingService.Stub() {
    fun start() {
        val store = PersistentLogSettingStore.getStore(backingFile) ?: return
        fingerprintAction.update { store.fingerprintAction }
        initLogChannel()
        setupStoreJob()
    }

    // Keep writing asynchronously all the time, no waiting on IO
    private val logChannel = Channel<LogMessageHolder>(capacity = Channel.BUFFERED)
    private val fingerprintAction = MutableStateFlow(LOG_ONLY)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @OptIn(FlowPreview::class)
    private fun setupStoreJob() {
        fingerprintAction
            .drop(1) // Drop initial state
            .debounce(500.milliseconds)
            .onEach { action ->
                // Currently overengineered, but it is fine since it will need to be expanded in the future
                val settings = LogSettings(action)
                PersistentLogSettingStore.writeStore(backingFile, settings)
            }
            .launchIn(scope)
    }


    val backingFile by lazy {
        File(VirtualCore.get().context.applicationContext.filesDir, "settings.json")
    }

    private fun initLogChannel() {
        @OptIn(ExperimentalCoroutinesApi::class) val writeDispatcher =
            Dispatchers.IO.limitedParallelism(1)
        CoroutineScope(writeDispatcher).launch {
            for (logItem in logChannel) {
                runCatching {
                    logWriter.write(logItem.toPrettyJson())
                    logWriter.newLine()
                    logWriter.flush()
                }.onFailure { e ->
                    Timber.e(e, "Failed to write log line!")
                }
            }
        }
    }

    val logDir: String by lazy {
        val context = VirtualCore.get().context
        "${context.cacheDir.absolutePath}/logs"
    }

    private val logFile: File by lazy {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

        val customFormat = LocalDateTime.Format {
            @OptIn(FormatStringsInDatetimeFormats::class) byUnicodePattern("yyyy-MM-dd_HH_mm_ss")
        }

        val logTime = now.format(customFormat)
        // Cache dir makes it easy to clean the log files
        val file = File(logDir, "$logTime.jsonl")
        file.parentFile?.mkdirs()
        file.createNewFile()
        file
    }

    private val logWriter by lazy {
        logFile.bufferedWriter()
    }


    @JvmStatic
    fun get() = this

    fun log(logMessageHolder: LogMessageHolder) {
        if (!logMessageHolder.shouldBeDisplayed()) {
            return
        }

        logChannel.trySend(logMessageHolder).onFailure {
            Timber.e(it, "Failed to send long message to channel!")
        }

        val logMessage = logMessageHolder.logMessage
        if (logMessageHolder.dangerous && logMessage is LogMessage.FingerprintChange) {
            runCatching {
                val settings = fingerprintLogAction
                when (settings) {
                    TOAST -> {
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                VirtualCore.get().context,
                                "Suspicious fingerprint change detected!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    KILL_APP -> {
                        val pkg = logMessageHolder.packageName
                        val userId = logMessageHolder.userId

                        VActivityManager.get().killAppByPkg(pkg, userId)
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                VirtualCore.get().context,
                                "App killed due to suspicious fingerprint change!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }.onFailure {
                Timber.e(it)
            }
        }

        if (logMessageHolder.dangerous) {
            when (logMessage) {
                is LogMessage.HookArgumentsRewrite, is LogMessage.HookReturnRewrite, is LogMessage.HookExceptionMismatch -> {
                    val pkg = logMessageHolder.packageName
                    val userId = logMessageHolder.userId

                    VActivityManager.get().killAppByPkg(pkg, userId)
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            VirtualCore.get().context,
                            "App killed due to disallowed return value rewriting!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                else -> {}
            }
        }

        runCatching {
            Timber.i("Received log message: ${logMessageHolder.toPrettyJson()}")
        }.onFailure {
            Timber.e(it, "Failed to log message")
        }
    }

    override fun log(message: LogMessage?) {
        if (message == null) {
            Timber.w("Received null message")
            return
        }

        val logMessageHolder = LogMessageHolder.Builder(message).build()
        log(logMessageHolder)
    }

    override fun getFingerprintLogAction(): Int {
        return fingerprintAction.value
    }

    override fun setFingerprintLogAction(action: Int): Boolean {
        return if (VActivityManager.get().systemPid == VBinder.getCallingUid()) {
            fingerprintAction.update {
                action
            }
            true
        } else {
            false
        }
    }
}
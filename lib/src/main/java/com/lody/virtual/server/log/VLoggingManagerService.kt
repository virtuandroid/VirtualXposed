package com.lody.virtual.server.log

import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.remote.logging.LogMessageHolder
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.server.IVLoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
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

object VLoggingManagerService : IVLoggingService.Stub() {
    init {
        initLogChannel()
    }

    // Keep writing asynchronously all the time, no waiting on IO
    private val logChannel = Channel<LogMessageHolder>(capacity = Channel.BUFFERED)

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

    private val logFile: File by lazy {
        val context = VirtualCore.get().context
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

        val customFormat = LocalDateTime.Format {
            @OptIn(FormatStringsInDatetimeFormats::class) byUnicodePattern("yyyy-MM-dd_HH_mm_ss")
        }

        val logTime = now.format(customFormat)
        // Cache dir makes it easy to clean the log files
        val file = File("${context.cacheDir.absolutePath}/logs", "$logTime.jsonl")
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
}
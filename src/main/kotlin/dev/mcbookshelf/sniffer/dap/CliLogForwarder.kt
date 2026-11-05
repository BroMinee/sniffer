package dev.mcbookshelf.sniffer.dap

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mirrors the game log to all connected CLI WebSocket sessions.
 *
 * Same design as [LogForwarder] (queue + sender thread), but writes to the CLI sessions
 * instead of the DAP output channel.
 *
 * @author theogiraudet
 */
object CliLogForwarder {

    private val LOGGER = LoggerFactory.getLogger("sniffer")
    private val gson = Gson()

    private const val APPENDER_NAME = "sniffer-cli"
    private const val THREAD_NAME = "sniffer-cli-log-forwarder"
    private const val PATTERN = "[%d{HH:mm:ss}] [%t/%level] (%logger{1}) %msg%n%throwable"
    private const val QUEUE_CAPACITY = 4096
    private const val POLL_MS = 100L

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private var appender: Appender? = null

    @Volatile
    private var sender: Thread? = null

    @Synchronized
    fun ensureRunning() {
        if (appender != null) return

        val context = runCatching { LogManager.getContext(false) as LoggerContext }.getOrElse {
            LOGGER.warn("Cannot mirror the game log to CLI: log4j is not the logging backend", it)
            return
        }

        queue.clear()
        val thread = Thread({ sendLoop() }, THREAD_NAME).apply { isDaemon = true }
        sender = thread
        thread.start()

        val created = CliAppender().apply { start() }
        appender = created
        context.configuration.addAppender(created)
        context.configuration.rootLogger.addAppender(created, null, null)
        context.updateLoggers()
    }

    @Synchronized
    fun stopIfEmpty() {
        if (CliEndpoint.sessions.isNotEmpty()) return
        stop()
    }

    @Synchronized
    private fun stop() {
        val current = appender ?: return
        appender = null

        runCatching {
            val context = LogManager.getContext(false) as LoggerContext
            context.configuration.rootLogger.removeAppender(APPENDER_NAME)
            context.updateLoggers()
        }
        current.stop()

        val thread = sender
        sender = null
        thread?.interrupt()
        queue.clear()
    }

    private fun sendLoop() {
        try {
            while (Thread.currentThread() === sender) {
                val line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                val json = JsonObject().apply {
                    addProperty("type", "log")
                    addProperty("message", line)
                }
                val text = gson.toJson(json)
                for (session in CliEndpoint.sessions) {
                    runCatching { sendText(session, text) }
                }
            }
        } catch (_: InterruptedException) {
            // Stopping.
        }
    }

    private class CliAppender : AbstractAppender(
        APPENDER_NAME,
        null,
        PatternLayout.newBuilder().setPattern(PATTERN).build(),
        true,
        Property.EMPTY_ARRAY,
    ) {
        override fun append(event: LogEvent) {
            if (Thread.currentThread() === sender) return
            val isError = event.level.isMoreSpecificThan(Level.ERROR)
                    && event.threadName == "Server thread"
            val isChat = event.loggerName.contains("ChatComponent", ignoreCase = true)
                    || event.loggerName.contains("SystemChat", ignoreCase = true)
                    || event.loggerName.contains("net.minecraft.server.network", ignoreCase = true)
            if (!isError && !isChat) return
            queue.offer(layout.toSerializable(event) as String)
        }
    }
}

package dev.mcbookshelf.sniffer.dap

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import dev.mcbookshelf.sniffer.util.IsolatedExecution
import jakarta.websocket.*
import net.minecraft.commands.CommandSource
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Lightweight WebSocket endpoint for external tools (Claude, scripts, etc.) to run Minecraft commands
 * and receive game logs in real time.
 *
 * Protocol (JSON over text frames):
 *
 * **Client → Server:**
 * ```json
 * {"type":"command","command":"/reload"}
 * ```
 *
 * **Server → Client (command response):**
 * ```json
 * {"type":"response","success":true,"result":0,"feedback":["Reloading!"]}
 * ```
 *
 * **Server → Client (log stream):**
 * ```json
 * {"type":"log","message":"[12:34:56] [Server thread/INFO] (minecraft) Reloading!"}
 * ```
 *
 * Multiple CLI sessions are allowed simultaneously, and they are independent of the DAP session.
 *
 * @author theogiraudet
 */
class CliEndpoint : Endpoint() {

    companion object {
        private val logger = LoggerFactory.getLogger("sniffer")
        private val gson = Gson()

        /** All connected CLI sessions, each receiving log lines. */
        internal val sessions = CopyOnWriteArraySet<Session>()
    }

    override fun onOpen(session: Session, config: EndpointConfig) {
        logger.info("CLI WebSocket connected: {}", session.requestURI)
        session.maxIdleTimeout = 0

        sessions.add(session)
        CliLogForwarder.ensureRunning()

        session.addMessageHandler(object : MessageHandler.Whole<String> {
            override fun onMessage(message: String) {
                handleMessage(session, message)
            }
        })
    }

    override fun onClose(session: Session, closeReason: CloseReason) {
        logger.info("CLI WebSocket closed: {}", closeReason)
        sessions.remove(session)
        CliLogForwarder.stopIfEmpty()
    }

    override fun onError(session: Session, throwable: Throwable) {
        logger.error("CLI WebSocket error", throwable)
        sessions.remove(session)
        CliLogForwarder.stopIfEmpty()
    }

    private fun handleMessage(session: Session, raw: String) {
        val json = try {
            gson.fromJson(raw, JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            sendError(session, "invalid JSON")
            return
        }

        when (json.get("type")?.asString) {
            "command" -> {
                val command = json.get("command")?.asString
                if (command.isNullOrBlank()) {
                    sendError(session, "missing 'command' field")
                    return
                }
                executeCommand(session, command)
            }
            else -> sendError(session, "unknown message type, expected 'command'")
        }
    }

    private fun executeCommand(session: Session, command: String) {
        val server = runCatching { ServerReference.get() }.getOrNull()
        if (server == null) {
            sendError(session, "Minecraft server not available")
            return
        }

        // Run on the server thread to be safe with MC internals.
        CompletableFuture.supplyAsync({
            val trimmed = Commands.trimOptionalPrefix(command.trim())
            val feedback = mutableListOf<String>()
            var success = false
            var result = 0

            val source = server.createCommandSourceStack()
                .withSource(collectingInto(feedback))
                .withCallback { ok, value -> success = ok; result = value }

            val parse = server.commands.dispatcher.parse(trimmed, source)
            IsolatedExecution.outsideCurrentContext { server.commands.performCommand(parse, trimmed) }

            val response = JsonObject().apply {
                addProperty("type", "response")
                addProperty("success", success)
                addProperty("result", result)
                add("feedback", gson.toJsonTree(feedback))
            }
            sendText(session, gson.toJson(response))
        }, server::execute).exceptionally { ex ->
            logger.error("CLI command execution failed", ex)
            sendError(session, "execution failed: ${ex.message}")
            null
        }
    }

    private fun collectingInto(feedback: MutableList<String>): CommandSource = object : CommandSource {
        override fun sendSystemMessage(message: Component) {
            feedback += message.string
        }
        override fun acceptsSuccess() = true
        override fun acceptsFailure() = true
        override fun shouldInformAdmins() = false
    }

    private fun sendError(session: Session, message: String) {
        val response = JsonObject().apply {
            addProperty("type", "error")
            addProperty("message", message)
        }
        sendText(session, gson.toJson(response))
    }
}

/** Sends a text frame, swallowing errors from a session that closed between the check and the send. */
internal fun sendText(session: Session, text: String) {
    try {
        if (session.isOpen) {
            session.basicRemote.sendText(text)
        }
    } catch (_: Exception) {
        // Session closed concurrently.
    }
}

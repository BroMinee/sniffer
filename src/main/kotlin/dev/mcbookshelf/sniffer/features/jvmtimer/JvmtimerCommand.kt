package dev.mcbookshelf.sniffer.features.jvmtimer

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.context.CommandContext
import dev.mcbookshelf.sniffer.util.Extension.appendLine
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.CommonColors
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.mcbookshelf.sniffer.command.SnifferCommand
import dev.mcbookshelf.sniffer.chat.SnifferChat
import dev.mcbookshelf.sniffer.mixin.ServerFunctionLibraryAccessors
import dev.mcbookshelf.sniffer.mixin.ServerFunctionManagerAccessors

/**
 * The `/jvmtimer` command, which times datapack functions from the JVM side.
 * Its subcommands all work on named timers.
 *
 * @author Alumopper
 */
object JvmtimerCommand : SnifferCommand {

    val timers = HashMap<String, JvmTimer>()
    private var fullMode = false

    override fun build(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> =
        literal<CommandSourceStack>("jvmtimer")
            .then(literal<CommandSourceStack>("start")
                .then(argument("id", StringArgumentType.greedyString())
                    .suggests(JvmtimerSuggestionProvider)
                    .executes {
                        val id = StringArgumentType.getString(it, "id")
                        if (getTimer(id).start(it.source))
                            SnifferChat.reply(it.source, "sniffer.commands.jvmtimer.started", id)
                        1
                    }
                )
            ).then(literal<CommandSourceStack>("end")
                .then(argument("id", StringArgumentType.greedyString())
                    .suggests(JvmtimerSuggestionProvider)
                    .executes {
                        val id = StringArgumentType.getString(it ,"id")
                        if (getTimer(id).end(it.source))
                            SnifferChat.reply(it.source, "sniffer.commands.jvmtimer.stopped", id)
                        1
                    }
                )
            ).then(literal<CommandSourceStack>("get")
                .then(argument("id", StringArgumentType.greedyString())
                    .suggests(JvmtimerSuggestionProvider)
                    .executes {
                        val id = StringArgumentType.getString(it ,"id")
                        getTimer(id).get(it)
                        1
                    }
                )
            ).then(literal<CommandSourceStack>("reset")
                .then(argument("id", StringArgumentType.greedyString())
                    .suggests(JvmtimerSuggestionProvider)
                    .executes {
                        val id = StringArgumentType.getString(it ,"id")
                        getTimer(id).reset()
                        SnifferChat.reply(it.source, "sniffer.commands.jvmtimer.reset", id)
                        1
                    }
                )
            ).then(literal<CommandSourceStack>("profile")
                .then(literal<CommandSourceStack>("start")
                    .then(argument("namespace", StringArgumentType.greedyString())
                        .suggests(NamespaceOrAllSuggestionProvider)
                        .executes {
                            val namespace = StringArgumentType.getString(it, "namespace")
                            profileAction(it.source, namespace, "sniffer.commands.jvmtimer.profile.start") { id ->
                                ProfiledFunctions.register(id)
                            }
                        }
                    )
                )
                .then(literal<CommandSourceStack>("end")
                    .then(argument("namespace", StringArgumentType.greedyString())
                        .suggests(NamespaceOrAllSuggestionProvider)
                        .executes {
                            val namespace = StringArgumentType.getString(it, "namespace")
                            profileAction(it.source, namespace, "sniffer.commands.jvmtimer.profile.end") { id ->
                                ProfiledFunctions.unregister(id)
                            }
                        }
                    )
                )
                .then(literal<CommandSourceStack>("reset")
                    .then(argument("namespace", StringArgumentType.greedyString())
                        .suggests(NamespaceOrAllSuggestionProvider)
                        .executes {
                            val namespace = StringArgumentType.getString(it, "namespace")
                            profileAction(it.source, namespace, "sniffer.commands.jvmtimer.profile.reset") { id ->
                                getTimer(id).reset()
                            }
                        }
                    )
                )
                .then(literal<CommandSourceStack>("report")
                    .then(argument("namespace", StringArgumentType.greedyString())
                        .suggests(NamespaceOrAllSuggestionProvider)
                        .executes {
                            val namespace = StringArgumentType.getString(it, "namespace")
                            val matched = resolveNamespace(it.source, namespace)
                            if (matched.isEmpty()) {
                                SnifferChat.fail(it.source, "sniffer.commands.jvmtimer.profile.not_found", namespace)
                                return@executes 0
                            }
                            val matchedTimers = matched.map { id -> getTimer(id) }
                            // Dispatch to the current strategy's report generator
                            matchedTimers.first().strategy.generateReport(it.source, namespace, matchedTimers)
                        }
                    )
                )
            ).then(literal<CommandSourceStack>("mode")
                .then(literal<CommandSourceStack>("count")
                    .executes {
                        fullMode = false
                        timers.values.forEach { t -> t.switchToCount() }
                        SnifferChat.reply(it.source, "sniffer.commands.jvmtimer.mode", "count")
                        1
                    }
                )
                .then(literal<CommandSourceStack>("full")
                    .executes {
                        fullMode = true
                        timers.values.forEach { t -> t.switchToProfile() }
                        SnifferChat.reply(it.source, "sniffer.commands.jvmtimer.mode", "full")
                        1
                    }
                )
            ).then(literal<CommandSourceStack>("disable")
                .then(argument("id", StringArgumentType.greedyString())
                    .suggests(JvmtimerSuggestionProvider)
                    .executes {
                        val id = StringArgumentType.getString(it ,"id")
                        getTimer(id).disable()
                        SnifferChat.reply(it.source, "sniffer.commands.jvmtimer.disable", id)
                        1
                    }
                )
            )

    private fun profileAction(source: CommandSourceStack, namespace: String, key: String, action: (String) -> Unit): Int {
        val matched = resolveNamespace(source, namespace)
        if (matched.isEmpty()) {
            SnifferChat.fail(source, "sniffer.commands.jvmtimer.profile.not_found", namespace)
            return 0
        }
        matched.forEach(action)
        replyWithFunctionList(source, key, matched, namespace)
        return 1
    }

    private fun replyWithFunctionList(source: CommandSourceStack, key: String, functions: List<String>, namespace: String) {
        val text = Component.empty()
        text.appendLine(Component.translatable(key, functions.size.toString(), namespace))
        functions.sorted().forEach { id ->
            text.appendLine(Component.literal(" - $id"))
        }
        SnifferChat.reply(source, text)
    }

    private fun resolveNamespace(source: CommandSourceStack, namespace: String): List<String> {
        val library = (source.server.functions as ServerFunctionManagerAccessors).library as ServerFunctionLibraryAccessors
        val functions = library.functions
        return if (namespace == "*") {
            functions.keys.map { id -> id.toString() }
        } else {
            functions.keys.filter { id -> id.namespace == namespace }.map { id -> id.toString() }
        }
    }

    fun getTimer(id: String): JvmTimer {
        return timers[id] ?: JvmTimer(id).also {
            if (fullMode) it.switchToProfile()
            timers[id] = it
        }
    }

    class TimerEvent(val id: String, val startNano: Long, var endNano: Long = -1L, val parent: TimerEvent? = null) {
        val pending: Boolean get() = endNano == -1L
        val duration: Long get() = endNano - startNano
        /** Assigned by [ProfileReportGenerator] at report time. */
        @JvmField var nodeId: Int = -1
    }

    /** Recording strategy — decides what data to keep at runtime. */
    interface TimerStrategy {
        val isRunning: Boolean
        fun start(id: String, parent: TimerEvent?): Boolean
        fun end(): Boolean
        fun pendingEvent(): TimerEvent?
        fun completedEvents(): List<TimerEvent>
        fun reset()
        fun generateReport(source: CommandSourceStack, namespace: String, timers: List<JvmTimer>): Int

        // Stats for /jvmtimer get
        val count: Int
        val totalTime: Long
        val minTime: Long
        val maxTime: Long
    }

    /** Lightweight strategy: only tracks aggregate stats, zero per-event allocation. */
    class CountStrategy : TimerStrategy {
        override var isRunning = false; private set
        override var count = 0; private set
        override var totalTime = 0L; private set
        override var minTime = Long.MAX_VALUE; private set
        override var maxTime = 0L; private set
        private var startNano = 0L

        override fun start(id: String, parent: TimerEvent?): Boolean {
            startNano = System.nanoTime()
            isRunning = true
            return true
        }

        override fun end(): Boolean {
            val dur = System.nanoTime() - startNano
            isRunning = false
            count++
            totalTime += dur
            if (dur < minTime) minTime = dur
            if (dur > maxTime) maxTime = dur
            return true
        }

        override fun pendingEvent(): TimerEvent? = null
        override fun completedEvents(): List<TimerEvent> = emptyList()
        override fun reset() {
            isRunning = false
            count = 0; totalTime = 0L; minTime = Long.MAX_VALUE; maxTime = 0L
        }

        override fun generateReport(source: CommandSourceStack, namespace: String, timers: List<JvmTimer>): Int {
            return CountReportGenerator.generate(source, namespace, timers)
        }
    }

    /** Full strategy: stores every event for profile report generation. */
    class ProfileStrategy : TimerStrategy {
        private val events = mutableListOf<TimerEvent>()

        override val isRunning: Boolean get() = events.lastOrNull()?.pending == true
        override val count: Int get() = events.count { !it.pending }
        override val totalTime: Long get() = events.filter { !it.pending }.sumOf { it.duration }
        override val minTime: Long get() = events.filter { !it.pending }.minOfOrNull { it.duration } ?: 0L
        override val maxTime: Long get() = events.filter { !it.pending }.maxOfOrNull { it.duration } ?: 0L

        override fun start(id: String, parent: TimerEvent?): Boolean {
            events.add(TimerEvent(id, System.nanoTime(), parent = parent))
            return true
        }

        override fun end(): Boolean {
            events.last().endNano = System.nanoTime()
            return true
        }

        override fun pendingEvent(): TimerEvent? = events.lastOrNull()?.takeIf { it.pending }
        override fun completedEvents(): List<TimerEvent> = events.filter { !it.pending }
        override fun reset() { events.clear() }

        override fun generateReport(source: CommandSourceStack, namespace: String, timers: List<JvmTimer>): Int {
            // TODO: generate Chrome DevTools Performance-compatible trace report (.json.gz)
            return 0
        }
    }

    class JvmTimer(val id: String) {

        private var enabled = true
        var strategy: TimerStrategy = CountStrategy(); private set

        fun switchToProfile() { strategy = ProfileStrategy() }
        fun switchToCount() { strategy = CountStrategy() }

        fun completedEvents(): List<TimerEvent> = strategy.completedEvents()
        fun pendingEvent(): TimerEvent? = strategy.pendingEvent()

        fun start(source: CommandSourceStack, parent: TimerEvent? = null): Boolean {
            if (!enabled) {
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.disable", id)
                return false
            }
            if (strategy.isRunning) {
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.already_started", id)
                enabled = false
                return false
            }
            return strategy.start(id, parent)
        }

        fun end(source: CommandSourceStack): Boolean {
            if (!enabled) {
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.disable", id)
                return false
            }
            if (!strategy.isRunning) {
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.not_running", id)
                enabled = false
                return false
            }
            return strategy.end()
        }

        fun get(ctx: CommandContext<CommandSourceStack>) {
            if (!enabled) {
                SnifferChat.reply(ctx.source, "sniffer.commands.jvmtimer.disable", id)
                return
            }
            if (strategy.count == 0) {
                if (strategy.isRunning)
                    SnifferChat.reply(ctx.source, "sniffer.commands.jvmtimer.not_finished", id)
                else
                    SnifferChat.reply(ctx.source, "sniffer.commands.jvmtimer.not_started", id)
                return
            }
            val text = Component.empty()
            text.title("sniffer.commands.jvmtimer.info.id").value(id)
                .desc("sniffer.commands.jvmtimer.info.total").value("${strategy.totalTime / 1_000_000.0}ms")
                .desc("sniffer.commands.jvmtimer.info.count").value(strategy.count.toString())
                .desc("sniffer.commands.jvmtimer.info.average").value("${strategy.totalTime / strategy.count / 1_000_000.0}ms")
                .desc("sniffer.commands.jvmtimer.info.min_max").value("${strategy.minTime / 1_000.0}us/${strategy.maxTime / 1_000.0}us")
            SnifferChat.reply(ctx.source, text)
        }

        fun reset() {
            strategy.reset()
            enabled = true
        }

        fun disable() {
            reset()
            enabled = false
        }

        private fun MutableComponent.title(str: String): MutableComponent =
            this.appendLine(Component.translatable(str).withStyle { it.withColor(CommonColors.HIGH_CONTRAST_DIAMOND).withBold(true) })

        private fun MutableComponent.desc(str: String): MutableComponent =
            this.appendLine(Component.translatable(str).withStyle { it.withColor(CommonColors.WHITE).withBold(false) })

        private fun MutableComponent.value(str: String): MutableComponent =
            this.appendLine(Component.literal(str).withStyle { it.withColor(CommonColors.HIGH_CONTRAST_DIAMOND).withBold(false) })
    }
}

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
import kotlin.math.max
import kotlin.math.min
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
                            val result = AggregatedReportGenerator.generate(it.source, namespace, matchedTimers)
                            if (result > 0) matchedTimers.forEach { timer -> timer.reset() }
                            result
                        }
                    )
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

    fun getTimer(id: String): JvmTimer{
        return timers[id] ?: JvmTimer(id).also { timers[id] = it }
    }

    class JvmTimer(val id: String) {

        private var startTime = -1L

        internal var totalTime = 0L; private set

        internal var count = 0; private set

        private var enabled = true

        internal var minTime = Long.MAX_VALUE; private set

        internal var maxTime = Long.MIN_VALUE; private set

        fun start(source: CommandSourceStack): Boolean {
            if(!enabled){
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.disable", id)
                return false
            }
            if((startTime != -1L)){
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.already_started", id)
                enabled = false
                startTime = -1L
                return false
            }
            startTime = System.nanoTime()
            return true
        }

        fun end(source: CommandSourceStack): Boolean {
            if(!enabled){
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.disable", id)
                return false
            }
            if((startTime == -1L)){
                SnifferChat.fail(source, "sniffer.commands.jvmtimer.not_running", id)
                enabled = false
                return false
            }
            val delta = System.nanoTime() - startTime
            totalTime += delta
            minTime = min(minTime, delta)
            maxTime = max(maxTime, delta)
            count++
            startTime = -1L
            return true
        }

        fun get(ctx: CommandContext<CommandSourceStack>){
            if(count == 0){
                if(startTime != -1L)
                    SnifferChat.reply(ctx.source, "sniffer.commands.jvmtimer.not_finished", id)
                else
                    SnifferChat.reply(ctx.source, "sniffer.commands.jvmtimer.not_started", id)
                return
            }
            if(!enabled){
                SnifferChat.reply(ctx.source, "sniffer.commands.jvmtimer.disable", id)
                return
            }
            val text = Component.empty()
            text.title("sniffer.commands.jvmtimer.info.id").value(id)
                .desc("sniffer.commands.jvmtimer.info.total").value("${totalTime / 1_000_000.0}ms")
                .desc("sniffer.commands.jvmtimer.info.count").value(count.toString())
                .desc("sniffer.commands.jvmtimer.info.average").value("${totalTime / count / 1_000_000.0}ms")
                .desc("sniffer.commands.jvmtimer.info.min_max").value("${minTime / 1_000.0}μs/${maxTime / 1_000.0}μs")
            SnifferChat.reply(ctx.source, text)
        }
        fun reset(){
            startTime = -1L
            totalTime = 0L
            count = 0
            enabled = true
            maxTime = Long.MIN_VALUE
            minTime = Long.MAX_VALUE
        }

        fun disable(){
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
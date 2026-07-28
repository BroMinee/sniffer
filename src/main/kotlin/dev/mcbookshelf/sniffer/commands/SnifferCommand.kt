package dev.mcbookshelf.sniffer.commands

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import dev.mcbookshelf.sniffer.state.FunctionTextLoader
import dev.mcbookshelf.sniffer.util.Extension.addSnifferPrefix
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.Permissions
import net.minecraft.ChatFormatting

/**
 * Registers the `/sniffer` command tree.
 *
 * `/sniffer preprocess <name>` dumps the preprocessed lines of a
 * loaded function to chat, equivalent to `gcc -E`.
 */
object SnifferCommand {

    @JvmStatic
    fun onInitialize() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal<CommandSourceStack>("sniffer")
                    .requires{it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)}
                    .then(
                        literal<CommandSourceStack>("preprocess")
                            .then(
                                argument("name", StringArgumentType.greedyString())
                                    .suggests(FunctionIdSuggestionProvider)
                                    .executes { context ->
                                        val raw = StringArgumentType.getString(context, "name")
                                        val id = Identifier.tryParse(raw)
                                        if (id == null) {
                                            context.source.sendFailure(
                                                addSnifferPrefix(Component.translatable("sniffer.commands.sniffer.preprocess.invalid_id", raw).withStyle(ChatFormatting.RED))
                                            )
                                            return@executes 0
                                        }
                                        val lines = FunctionTextLoader.getPreprocessed(id)
                                        if (lines.isEmpty()) {
                                            context.source.sendFailure(
                                                addSnifferPrefix(Component.translatable("sniffer.commands.sniffer.preprocess.not_found", raw).withStyle(ChatFormatting.RED))
                                            )
                                            return@executes 0
                                        }
                                        val header = addSnifferPrefix(
                                            Component.translatable("sniffer.commands.sniffer.preprocess.header", raw).withStyle(ChatFormatting.WHITE)
                                        )
                                        val sb = StringBuilder()
                                        for ((i, line) in lines.withIndex()) {
                                            sb.append(String.format("%3d: %s", i + 1, line))
                                            if (i < lines.size - 1) sb.append("\n")
                                        }
                                        val body = Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE)
                                        context.source.sendSuccess({ header }, false)
                                        context.source.sendSuccess({ body }, false)
                                        1
                                    }
                            )
                    )
            )
        }
    }
}

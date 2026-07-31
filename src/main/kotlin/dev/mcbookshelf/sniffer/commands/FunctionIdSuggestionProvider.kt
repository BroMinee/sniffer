package dev.mcbookshelf.sniffer.commands

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.mcbookshelf.sniffer.state.FunctionTextLoader
import net.minecraft.commands.CommandSourceStack
import java.util.concurrent.CompletableFuture

/**
 * Suggests loaded function identifiers (e.g. `mypack:foo/bar`) for tab-completion,
 * sourced from [FunctionTextLoader].
 */
object FunctionIdSuggestionProvider : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remaining.lowercase()
        for (id in FunctionTextLoader.allFunctionIds()) {
            val str = id.toString()
            if (str.startsWith(remaining)) builder.suggest(str)
        }
        return builder.buildFuture()
    }
}

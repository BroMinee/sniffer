package dev.mcbookshelf.sniffer.features.jvmtimer

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.mcbookshelf.sniffer.mixin.ServerFunctionLibraryAccessors
import dev.mcbookshelf.sniffer.mixin.ServerFunctionManagerAccessors
import net.minecraft.commands.CommandSourceStack
import java.util.concurrent.CompletableFuture

/**
 * Suggests the namespaces of all loaded `.mcfunction` files.
 * Open so subclasses can extend the suggestion list (e.g. [NamespaceOrAllSuggestionProvider]).
 */
open class NamespaceSuggestionProvider : SuggestionProvider<CommandSourceStack> {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val library = (context.source.server.functions as ServerFunctionManagerAccessors).library as ServerFunctionLibraryAccessors
        library.functions.keys
            .map { it.namespace }
            .distinct()
            .forEach { builder.suggest(it) }
        return builder.buildFuture()
    }

    companion object {
        val INSTANCE = NamespaceSuggestionProvider()
    }
}

/**
 * Extends [NamespaceSuggestionProvider] with a `*` wildcard.
 */
object NamespaceOrAllSuggestionProvider : NamespaceSuggestionProvider() {
    override fun getSuggestions(
        context: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        builder.suggest("*")
        return super.getSuggestions(context, builder)
    }
}

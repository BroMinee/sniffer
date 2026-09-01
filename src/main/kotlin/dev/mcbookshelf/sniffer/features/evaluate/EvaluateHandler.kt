package dev.mcbookshelf.sniffer.features.evaluate

import dev.mcbookshelf.sniffer.features.variables.NbtVariableBuilder
import dev.mcbookshelf.sniffer.features.callstack.ScopeManager
import dev.mcbookshelf.sniffer.features.variables.VariableManager
import dev.mcbookshelf.sniffer.dispatch.Context
import dev.mcbookshelf.sniffer.dispatch.Handler
import dev.mcbookshelf.sniffer.dispatch.Output
import net.minecraft.nbt.CompoundTag

/**
 * Evaluates a debug expression against the source a command would run as.
 *
 * First tries to parse the expression as the Sniffer mini-language (`data`, `score`, `name`).
 * If that fails, treats the expression as a raw Minecraft command and delegates to [runCommandHandler],
 * returning the integer result value. This means both syntaxes work from any context (watch, repl).
 *
 * A [CompoundTag] result from the mini-language is registered as a variable subtree so the client can expand it.
 * The [EvaluationSession] remembers that subtree, and evaluating the same expression again drops it first.
 *
 * @author theogiraudet
 */
class EvaluateHandler(
    private val scopeManager: ScopeManager,
    private val evaluationSession: EvaluationSession,
    private val runCommandHandler: RunCommandHandler,
) : Handler<EvaluateInput> {

    override val inputType = EvaluateInput::class

    override fun handle(input: EvaluateInput, ctx: Context): Output {
        evaluationSession.clearPrevious(input.expression)

        val parseResult = VariableManager.evaluate(input.expression)
        if (parseResult.isFailure) {
            val cmdOutput = runCommandHandler.handle(RunCommandInput(input.expression), ctx) as RunCommandOutput
            return EvaluateOutput(result = cmdOutput.result.toString(), variablesReference = 0)
        }
        val debugData = parseResult.getOrThrow()

        val source = scopeManager.commandSource(ctx.source)

        return try {
            val value = debugData.get(source)
            if (value is CompoundTag) {
                val node = NbtVariableBuilder.build("debug", value, isRoot = true, registry = scopeManager.registry)
                evaluationSession.store(input.expression, node)
                EvaluateOutput(result = value.toString(), variablesReference = node.id)
            } else {
                EvaluateOutput(result = value.toString(), variablesReference = 0)
            }
        } catch (e: Exception) {
            EvaluateOutput(result = e.message ?: "Evaluation error", variablesReference = 0)
        }
    }
}

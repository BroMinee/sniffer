package dev.mcbookshelf.sniffer.handlers

import dev.mcbookshelf.sniffer.state.EvaluationSession
import dev.mcbookshelf.sniffer.state.NbtVariableBuilder
import dev.mcbookshelf.sniffer.state.ScopeManager
import dev.mcbookshelf.sniffer.state.VariableManager
import dev.mcbookshelf.sniffer.commands.DebugData
import dev.mcbookshelf.sniffer.dispatch.Context
import dev.mcbookshelf.sniffer.dispatch.Handler
import dev.mcbookshelf.sniffer.dispatch.Output
import dev.mcbookshelf.sniffer.input.EvaluateInput
import dev.mcbookshelf.sniffer.output.EvaluateOutput
import net.minecraft.commands.CommandSourceStack
import net.minecraft.nbt.CompoundTag

/**
 * Evaluates a debug expression in the current scope.
 *
 * Parses the expression via [VariableManager.evaluate], resolves it against
 * the current scope's executor, and — if the result is a [CompoundTag] —
 * registers a new [dev.mcbookshelf.sniffer.state.VariableNode] subtree with
 * the shared registry so that subsequent [ResolveVariablesHandler] calls can
 * expand it. The [EvaluationSession] tracks the root id per expression so a
 * repeat evaluation drops the previous subtree first.
 */
class EvaluateHandler(
    private val scopeManager: ScopeManager,
    private val evaluationSession: EvaluationSession,
) : Handler<EvaluateInput> {

    override val inputType = EvaluateInput::class

    override fun handle(input: EvaluateInput, ctx: Context): Output {
        val scope = scopeManager.currentScope.orElse(null)
            ?: return EvaluateOutput(result = "No active debug scope")

        val source = scope.executor
        if (source !is CommandSourceStack) {
            return EvaluateOutput(result = "Source is not a server command source")
        }

        val parseResult = VariableManager.evaluate(input.expression)
        return parseResult.fold(
            onSuccess = { debugData -> evaluateExpression(input.expression, source, debugData) },
            onFailure = { executeCommand(input.expression, source, ctx) }
        )
    }

    private fun executeCommand(expression: String, source: CommandSourceStack, ctx: Context): EvaluateOutput {
        return try {
            val cmd = expression.trimStart('/')
            var returnValue = 0
            val trackedSource = source.withCallback { success, value -> returnValue = value }
            ctx.server.commands.performPrefixedCommand(trackedSource, cmd)
            EvaluateOutput(result = returnValue.toString(), type = "int")
        } catch (e: Exception) {
            EvaluateOutput(result = e.message ?: "Command execution error")
        }
    }

    private fun evaluateExpression(expression: String, source: CommandSourceStack, debugData: DebugData): EvaluateOutput {
        evaluationSession.clearPrevious(expression)

        return try {
            val value = debugData.get(source)
            if (value is CompoundTag) {
                val node = NbtVariableBuilder.build("debug", value, isRoot = true, registry = scopeManager.registry)
                evaluationSession.store(expression, node)
                EvaluateOutput(result = value.toString(), variablesReference = node.id)
            } else {
                EvaluateOutput(result = value.toString())
            }
        } catch (e: Exception) {
            EvaluateOutput(result = e.message ?: "Evaluation error")
        }
    }
}

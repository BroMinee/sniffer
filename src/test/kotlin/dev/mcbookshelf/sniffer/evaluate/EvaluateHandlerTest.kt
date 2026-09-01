package dev.mcbookshelf.sniffer.evaluate

import dev.mcbookshelf.sniffer.dispatch.Context
import dev.mcbookshelf.sniffer.features.evaluate.*
import dev.mcbookshelf.sniffer.features.variables.VariableRegistry
import net.minecraft.commands.CommandSourceStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Unit tests for the routing logic inside [EvaluateHandler].
 *
 * When the expression is not a Sniffer mini-language expression, [EvaluateHandler] must delegate
 * to [RunCommandHandler] and return its integer result without touching [ScopeManager].
 * When the expression is a Sniffer expression, [RunCommandHandler] must not be called at all.
 */
class EvaluateHandlerTest {

    private val runCommandHandler: RunCommandHandler = mock(RunCommandHandler::class.java)
    private val evaluationSession = EvaluationSession(VariableRegistry())
    private val handler = EvaluateHandler(
        scopeManager = mock(),
        evaluationSession = evaluationSession,
        runCommandHandler = runCommandHandler,
    )
    private val ctx = Context(mock(CommandSourceStack::class.java))

    @Test
    fun `a raw command is forwarded to RunCommandHandler and its result is returned`() {
        `when`(runCommandHandler.handle(RunCommandInput("fill 0 1 0 0 27 0 stone"), ctx))
            .thenReturn(RunCommandOutput(feedback = emptyList(), success = true, result = 27))

        val output = handler.handle(EvaluateInput("fill 0 1 0 0 27 0 stone"), ctx) as EvaluateOutput

        assertEquals("27", output.result)
        assertEquals(0, output.variablesReference)
    }

    @Test
    fun `a command with a leading slash is also forwarded to RunCommandHandler`() {
        `when`(runCommandHandler.handle(RunCommandInput("/say hello"), ctx))
            .thenReturn(RunCommandOutput(feedback = emptyList(), success = true, result = 1))

        val output = handler.handle(EvaluateInput("/say hello"), ctx) as EvaluateOutput

        assertEquals("1", output.result)
    }

    @Test
    fun `an unknown command returns result 0`() {
        `when`(runCommandHandler.handle(RunCommandInput("nvseilfhesi"), ctx))
            .thenReturn(RunCommandOutput(feedback = emptyList(), success = false, result = 0))

        val output = handler.handle(EvaluateInput("nvseilfhesi"), ctx) as EvaluateOutput

        assertEquals("0", output.result)
        assertEquals(0, output.variablesReference)
    }

    @Test
    fun `a sniffer expression never reaches RunCommandHandler`() {
        try {
            handler.handle(EvaluateInput("data entity @s Health"), ctx)
        } catch (_: Exception) { }

        verify(runCommandHandler, never()).handle(RunCommandInput("data entity @s Health"), ctx)
    }
}

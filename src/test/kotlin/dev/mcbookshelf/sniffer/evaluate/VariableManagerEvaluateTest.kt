package dev.mcbookshelf.sniffer.evaluate

import dev.mcbookshelf.sniffer.features.variables.VariableManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [VariableManager.evaluate], which is the gate between the Sniffer language
 * and the raw Minecraft command fallback.
 *
 * The function must return [Result.success] for expressions that belong to the language
 * and [Result.failure] for anything else, so that [EvaluateHandler] can decide which path to take
 * without attempting to execute the expression.
 */
class VariableManagerEvaluateTest {

    @Test
    fun `data expression is recognised`() {
        assertTrue(VariableManager.evaluate("data entity @s Health").isSuccess)
    }

    @Test
    fun `score expression is recognised`() {
        assertTrue(VariableManager.evaluate("score @s my_objective").isSuccess)
    }

    @Test
    fun `name expression is recognised`() {
        assertTrue(VariableManager.evaluate("name @s").isSuccess)
    }

    @Test
    fun `braced expression is recognised`() {
        assertTrue(VariableManager.evaluate("{ (name @s) == \"steve\" }").isSuccess)
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        assertTrue(VariableManager.evaluate("  data storage pack:x value  ").isSuccess)
    }

    @Test
    fun `a bare minecraft command without slash is not a sniffer expression`() {
        assertTrue(VariableManager.evaluate("fill 0 0 0 10 10 10 stone").isFailure)
    }

    @Test
    fun `a minecraft command with a leading slash is not a sniffer expression`() {
        assertTrue(VariableManager.evaluate("/fill 0 0 0 10 10 10 stone").isFailure)
    }

    @Test
    fun `give command is not a sniffer expression`() {
        assertTrue(VariableManager.evaluate("give @s diamond 1").isFailure)
    }

    @Test
    fun `the minecraft command syntax data get is not the sniffer data expression`() {
        assertTrue(VariableManager.evaluate("data get entity @s Health").isFailure)
    }

    @Test
    fun `the word data alone without a trailing space is not a sniffer expression`() {
        assertTrue(VariableManager.evaluate("datapack enable foo").isFailure)
    }
}

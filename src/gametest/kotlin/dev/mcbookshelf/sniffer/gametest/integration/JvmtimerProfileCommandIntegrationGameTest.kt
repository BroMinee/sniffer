package dev.mcbookshelf.sniffer.gametest.integration

import dev.mcbookshelf.sniffer.features.jvmtimer.JvmtimerCommand
import dev.mcbookshelf.sniffer.features.jvmtimer.ProfiledFunctions
import dev.mcbookshelf.sniffer.gametest.support.DebugSession
import dev.mcbookshelf.sniffer.gametest.support.assertFalse
import dev.mcbookshelf.sniffer.gametest.support.assertTrue
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * Tests for `/jvmtimer profile start/end/reset <namespace|*>`.
 */
class JvmtimerProfileCommandIntegrationGameTest {

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_start_namespace")
    fun profileStartRegistersAllFunctionsInTheNamespace(helper: GameTestHelper) {
        val session = DebugSession(helper)
        ProfiledFunctions.unregister(NOT_PROFILED)
        JvmtimerCommand.getTimer(NOT_PROFILED).reset()

        session.run("jvmtimer profile start sniffer_test")

        assertTrue(ProfiledFunctions.isProfiled(NOT_PROFILED), "An undecorated function should be profiled after profile start")

        session.run("function $NOT_PROFILED")
        val report = session.runCapturing("jvmtimer get $NOT_PROFILED").joinToString("\n")
        assertTrue(report.hasMeasurement(), "A function profiled via namespace should record a timing, got: $report")

        assertFalse(ProfiledFunctions.isProfiled(OTHER_SIMPLE), "A function in another namespace should not be profiled")
        // Clean up: unregister non-profiled function
        ProfiledFunctions.unregister(NOT_PROFILED)
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_start_all")
    fun profileStartStarRegistersAllNamespaces(helper: GameTestHelper) {
        val session = DebugSession(helper)
        ProfiledFunctions.unregister(OTHER_SIMPLE)
        JvmtimerCommand.getTimer(OTHER_SIMPLE).reset()

        session.run("jvmtimer profile start *")

        assertTrue(ProfiledFunctions.isProfiled(NOT_PROFILED), "sniffer_test functions should be profiled")
        assertTrue(ProfiledFunctions.isProfiled(OTHER_SIMPLE), "other_test functions should be profiled")

        session.run("function $OTHER_SIMPLE")
        val report = session.runCapturing("jvmtimer get $OTHER_SIMPLE").joinToString("\n")
        assertTrue(report.hasMeasurement(), "A function profiled via * should record a timing, got: $report")
        // Clean up: unregister non-profiled functions
        ProfiledFunctions.unregister(NOT_PROFILED)
        ProfiledFunctions.unregister(OTHER_SIMPLE)
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_end_namespace")
    fun profileEndUnregistersAllFunctionsInTheNamespace(helper: GameTestHelper) {
        val session = DebugSession(helper)
        session.run("jvmtimer profile start sniffer_test")
        assertTrue(ProfiledFunctions.isProfiled(NOT_PROFILED), "Should be profiled after start")

        session.run("jvmtimer profile end sniffer_test")
        assertFalse(ProfiledFunctions.isProfiled(NOT_PROFILED), "Should no longer be profiled after end")
        DIRECTIVE_PROFILED.forEach { ProfiledFunctions.register(it) }
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_end_preserves_timers")
    fun profileEndPreservesAccumulatedTimers(helper: GameTestHelper) {
        val session = DebugSession(helper)
        JvmtimerCommand.getTimer(NOT_PROFILED).reset()

        session.run("jvmtimer profile start sniffer_test")
        session.run("function $NOT_PROFILED")

        val beforeEnd = session.runCapturing("jvmtimer get $NOT_PROFILED").joinToString("\n")
        assertTrue(beforeEnd.hasMeasurement(), "Should have a timing before end, got: $beforeEnd")

        session.run("jvmtimer profile end sniffer_test")

        val afterEnd = session.runCapturing("jvmtimer get $NOT_PROFILED").joinToString("\n")
        assertTrue(afterEnd.hasMeasurement(), "Timers should be preserved after profile end, got: $afterEnd")
        DIRECTIVE_PROFILED.forEach { ProfiledFunctions.register(it) }
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_end_overrides_directive")
    fun profileEndAlsoUnregistersDirectiveProfiledFunctions(helper: GameTestHelper) {
        val session = DebugSession(helper)
        assertTrue(ProfiledFunctions.isProfiled(PROFILED_SIMPLE), "Should be profiled by directive")

        session.run("jvmtimer profile end sniffer_test")
        assertFalse(ProfiledFunctions.isProfiled(PROFILED_SIMPLE), "profile end should override the directive")
        // Re-register all directive-profiled functions so other tests are not affected
        DIRECTIVE_PROFILED.forEach { ProfiledFunctions.register(it) }
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_reset_namespace")
    fun profileResetClearsTimersButKeepsProfiling(helper: GameTestHelper) {
        val session = DebugSession(helper)
        JvmtimerCommand.getTimer(NOT_PROFILED).reset()

        session.run("jvmtimer profile start sniffer_test")
        session.run("function $NOT_PROFILED")

        val beforeReset = session.runCapturing("jvmtimer get $NOT_PROFILED").joinToString("\n")
        assertTrue(beforeReset.hasMeasurement(), "Should have a timing before reset, got: $beforeReset")

        session.run("jvmtimer profile reset sniffer_test")

        val afterReset = session.runCapturing("jvmtimer get $NOT_PROFILED").joinToString("\n")
        assertFalse(afterReset.hasMeasurement(), "Timers should be cleared after reset")
        assertTrue(ProfiledFunctions.isProfiled(NOT_PROFILED), "Functions should still be profiled after reset")
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_start_unknown")
    fun profileStartOnUnknownNamespaceFailsGracefully(helper: GameTestHelper) {
        val session = DebugSession(helper)
        val chat = session.runCapturing("jvmtimer profile start does_not_exist")
        assertTrue(chat.any { "does_not_exist" in it }, "Error message should mention the namespace, got: $chat")
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_profile/profile_start_idempotent")
    fun profileStartOnAlreadyDirectiveProfiledFunctionCountsOnce(helper: GameTestHelper) {
        val session = DebugSession(helper)
        JvmtimerCommand.getTimer(PROFILED_SIMPLE).reset()

        assertTrue(ProfiledFunctions.isProfiled(PROFILED_SIMPLE), "Should already be profiled by directive")

        session.run("jvmtimer profile start sniffer_test")
        session.run("function $PROFILED_SIMPLE")

        val report = session.runCapturing("jvmtimer get $PROFILED_SIMPLE").joinToString("\n")
        assertTrue(report.hasMeasurement(), "Should record a timing, got: $report")
        assertTrue("1" in report, "The count should be 1 (not 2), got: $report")
        helper.succeed()
    }

    private fun String.hasMeasurement(): Boolean = MEASUREMENT in this

    private companion object {
        const val PROFILED_SIMPLE = "sniffer_test:jvmtimer/profiled_simple"
        const val NOT_PROFILED = "sniffer_test:jvmtimer/not_profiled"
        const val OTHER_SIMPLE = "other_test:simple"

        /** Functions that have `#!jvmtimer profile file` and must be re-registered after a destructive test. */
        val DIRECTIVE_PROFILED = listOf(
            PROFILED_SIMPLE,
            "sniffer_test:jvmtimer/profiled_with_return",
            "sniffer_test:jvmtimer/profiled_return_fn",
            "sniffer_test:jvmtimer/profiled_caller",
            "sniffer_test:jvmtimer/profiled_heavy_sub",
            "sniffer_test:jvmtimer/profiled_macro",
            "sniffer_test:jvmtimer/profiled_recursive"
        )

        /** A duration as the report prints it, for instance `0.042ms`. */
        val MEASUREMENT = Regex("""\d+\.\d+ms""")
    }
}

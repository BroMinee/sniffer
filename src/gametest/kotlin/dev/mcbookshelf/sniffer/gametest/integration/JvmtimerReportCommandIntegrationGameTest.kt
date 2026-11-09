package dev.mcbookshelf.sniffer.gametest.integration

import dev.mcbookshelf.sniffer.chat.SnifferChat
import dev.mcbookshelf.sniffer.features.jvmtimer.JvmtimerCommand
import dev.mcbookshelf.sniffer.features.jvmtimer.ProfiledFunctions
import dev.mcbookshelf.sniffer.gametest.support.DebugSession
import dev.mcbookshelf.sniffer.gametest.support.assertFalse
import dev.mcbookshelf.sniffer.gametest.support.assertTrue
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component

/**
 * `/jvmtimer profile report <namespace>` generates an aggregated report from profiled timer data.
 * When timers have recorded measurements it replies with a success message and resets the timers.
 * When no measurements exist it reports that the namespace is empty.
 * When the namespace does not match any function it fails with a not-found message.
 */
class JvmtimerReportCommandIntegrationGameTest {

    @GameTest(environment = "sniffer_test:jvmtimer_report/report_with_data")
    fun reportOnNamespaceWithDataRepliesAndResetsTimers(helper: GameTestHelper) {
        val session = DebugSession(helper)
        JvmtimerCommand.getTimer(PROFILED_SIMPLE).reset()

        session.run("jvmtimer profile start sniffer_test")
        session.run("function $PROFILED_SIMPLE")

        val beforeReport = session.runCapturing("jvmtimer get $PROFILED_SIMPLE").joinToString("\n")
        assertTrue(beforeReport.hasMeasurement(), "Should have a timing before report, got: $beforeReport")

        val chat = session.runCapturing("jvmtimer profile report sniffer_test")
        assertTrue(chat.any { msg("report.saved") in it }, "Should contain the saved message, got: $chat")

        val afterReport = session.runCapturing("jvmtimer get $PROFILED_SIMPLE").joinToString("\n")
        assertFalse(afterReport.hasMeasurement(), "Timers should be reset after a successful report, got: $afterReport")

        // Clean up
        session.run("jvmtimer profile end sniffer_test")
        DIRECTIVE_PROFILED.forEach { ProfiledFunctions.register(it) }
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_report/report_empty_namespace")
    fun reportOnNamespaceWithNoDataReportsEmpty(helper: GameTestHelper) {
        val session = DebugSession(helper)
        JvmtimerCommand.getTimer(PROFILED_SIMPLE).reset()

        session.run("jvmtimer profile start sniffer_test")
        // Do NOT run any function — all timers have count == 0

        val chat = session.runCapturing("jvmtimer profile report sniffer_test")
        assertTrue(chat.any { msg("report.empty") in it }, "Should contain the empty message, got: $chat")

        // Clean up
        session.run("jvmtimer profile end sniffer_test")
        DIRECTIVE_PROFILED.forEach { ProfiledFunctions.register(it) }
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_report/report_unknown_namespace")
    fun reportOnUnknownNamespaceFailsWithNotFound(helper: GameTestHelper) {
        val session = DebugSession(helper)
        val chat = session.runCapturing("jvmtimer profile report does_not_exist")
        assertTrue(chat.any { "does_not_exist" in it }, "Error message should mention the namespace, got: $chat")
        helper.succeed()
    }

    @GameTest(environment = "sniffer_test:jvmtimer_report/report_does_not_reset_on_empty")
    fun reportDoesNotResetTimersWhenNothingWasRecorded(helper: GameTestHelper) {
        val session = DebugSession(helper)
        JvmtimerCommand.getTimer(PROFILED_SIMPLE).reset()

        session.run("jvmtimer profile start sniffer_test")
        session.run("function $PROFILED_SIMPLE")

        val beforeReport = session.runCapturing("jvmtimer get $PROFILED_SIMPLE").joinToString("\n")
        assertTrue(beforeReport.hasMeasurement(), "Should have a timing before report, got: $beforeReport")

        // Report on a different namespace that has no data — should NOT reset sniffer_test timers
        session.run("jvmtimer profile start other_test")
        session.runCapturing("jvmtimer profile report other_test")

        val afterReport = session.runCapturing("jvmtimer get $PROFILED_SIMPLE").joinToString("\n")
        assertTrue(afterReport.hasMeasurement(), "Timers in another namespace should be untouched, got: $afterReport")

        // Clean up
        session.run("jvmtimer profile end sniffer_test")
        session.run("jvmtimer profile end other_test")
        DIRECTIVE_PROFILED.forEach { ProfiledFunctions.register(it) }
        ProfiledFunctions.unregister(OTHER_SIMPLE)
        helper.succeed()
    }

    private fun String.hasMeasurement(): Boolean = MEASUREMENT in this

    private fun msg(keySuffix: String): String =
        Component.translatable("sniffer.commands.jvmtimer.profile.$keySuffix").string

    private companion object {
        const val PROFILED_SIMPLE = "sniffer_test:jvmtimer/profiled_simple"
        const val OTHER_SIMPLE = "other_test:simple"

        val DIRECTIVE_PROFILED = listOf(
            PROFILED_SIMPLE,
            "sniffer_test:jvmtimer/profiled_with_return",
            "sniffer_test:jvmtimer/profiled_return_fn",
            "sniffer_test:jvmtimer/profiled_caller",
            "sniffer_test:jvmtimer/profiled_heavy_sub",
            "sniffer_test:jvmtimer/profiled_macro",
            "sniffer_test:jvmtimer/profiled_recursive"
        )

        val MEASUREMENT = Regex("""\d+\.\d+ms""")
    }
}

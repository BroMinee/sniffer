package dev.mcbookshelf.sniffer.features.jvmtimer

import dev.mcbookshelf.sniffer.chat.SnifferChat
import net.minecraft.commands.CommandSourceStack

/**
 * Generates a aggregated report from profiled timer data.
 */
object AggregatedReportGenerator {

    fun generate(source: CommandSourceStack, namespace: String, timers: List<JvmtimerCommand.JvmTimer>): Int {
        val entries = timers.filter { it.count > 0 }
        if (entries.isEmpty()) {
            SnifferChat.fail(source, "sniffer.commands.jvmtimer.profile.report.empty", namespace)
            return 0
        }

        SnifferChat.reply(source, "sniffer.commands.jvmtimer.profile.report.saved", entries.size.toString(), namespace)
        return 1
    }
}

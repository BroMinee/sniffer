package dev.mcbookshelf.sniffer.features.jvmtimer

import dev.mcbookshelf.sniffer.chat.SnifferChat
import dev.mcbookshelf.sniffer.dap.DapClient
import net.minecraft.commands.CommandSourceStack

/**
 * Generates a aggregated report and sends it to the connected editor via DAP.
 */
object AggregatedReportGenerator {

    fun generate(source: CommandSourceStack, namespace: String, timers: List<JvmtimerCommand.JvmTimer>): Int {
        val entries = timers.filter { it.count > 0 }
        if (entries.isEmpty()) {
            SnifferChat.fail(source, "sniffer.commands.jvmtimer.profile.report.empty", namespace)
            return 0
        }

        DapClient.of(ProfilerClient::class.java)?.countReport(
            CountReportArguments(
                namespace = namespace,
                timers = entries.map { timer ->
                    CountReportEntry(timer.id, timer.count.toLong(), timer.totalTime, timer.minTime, timer.maxTime)
                }
            )
        )

        SnifferChat.reply(source, "sniffer.commands.jvmtimer.profile.report.saved", entries.size.toString(), namespace)
        return 1
    }
}

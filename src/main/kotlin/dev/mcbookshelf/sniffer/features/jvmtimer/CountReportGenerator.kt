package dev.mcbookshelf.sniffer.features.jvmtimer

import dev.mcbookshelf.sniffer.chat.SnifferChat
import dev.mcbookshelf.sniffer.dap.DapClient
import net.minecraft.commands.CommandSourceStack

/**
 * Generates a count report and sends it to the connected editor via DAP.
 */
object CountReportGenerator {

    fun generate(source: CommandSourceStack, namespace: String, timers: List<JvmtimerCommand.JvmTimer>): Int {
        val entries = timers.filter { it.strategy.count > 0 }
        if (entries.isEmpty()) {
            SnifferChat.fail(source, "sniffer.commands.jvmtimer.profile.report.empty", namespace)
            return 0
        }

        DapClient.of(ProfilerClient::class.java)?.countReport(
            CountReportArguments(
                namespace = namespace,
                timers = entries.map { timer ->
                    val s = timer.strategy
                    CountReportEntry(timer.id, s.count.toLong(), s.totalTime, s.minTime, s.maxTime)
                }
            )
        )

        SnifferChat.reply(source, "sniffer.commands.jvmtimer.profile.report.saved", entries.size.toString(), namespace)
        return 1
    }
}

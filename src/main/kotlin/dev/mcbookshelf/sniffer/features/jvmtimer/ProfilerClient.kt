package dev.mcbookshelf.sniffer.features.jvmtimer

import dev.mcbookshelf.sniffer.dap.DapRemote
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

interface ProfilerClient : DapRemote {

    @JsonNotification("snifferProfilerCountReport")
    fun countReport(args: CountReportArguments)

    @JsonNotification("snifferProfilerTimerList")
    fun timerList(args: TimerListArguments)
}

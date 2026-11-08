package dev.mcbookshelf.sniffer.features.jvmtimer

data class CountReportArguments(
    val namespace: String,
    val timers: List<CountReportEntry>,
)

data class CountReportEntry(
    val function: String,
    val count: Long,
    val totalNs: Long,
    val minNs: Long,
    val maxNs: Long,
)

data class TimerListArguments(
    val namespaces: List<String>,
)


import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object InferReport {

    fun write(results: List<InferResult>, outputDir: File): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val reportFile = File(outputDir, "infer-report-$timestamp.md")

        reportFile.bufferedWriter().use { w ->
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
            val total     = results.size
            val accepted  = results.count { it.decision == MicroDecision.ACCEPTED }
            val escalated = results.count { it.decision == MicroDecision.ESCALATED }
            val microPct  = if (total > 0) accepted * 100 / total else 0

            w.writeln("# Infer Report")
            w.writeln()
            w.writeln("Generated: $now  ")
            w.writeln("Micro model: `qwen3-embedding:8b`  ")
            w.writeln("Fallback model: `qwen3.6:35b`  ")
            w.writeln("Inputs: $total")
            w.writeln()
            w.writeln("---")
            w.writeln()

            // ── Summary ───────────────────────────────────────────────────────
            w.writeln("## Summary")
            w.writeln()
            w.writeln("| Метрика | Значение |")
            w.writeln("|---------|----------|")
            w.writeln("| Обработано micro-моделью | $accepted / $total ($microPct%) |")
            w.writeln("| Эскалировано в LLM | $escalated / $total (${100 - microPct}%) |")

            val embedAvg = results.map { it.microLatencyMs }.average().toLong()
            val fallbackResults = results.filter { it.decision == MicroDecision.ESCALATED }
            val fallbackAvg = if (fallbackResults.isNotEmpty())
                fallbackResults.map { it.fallbackLatencyMs }.average().toLong() else 0L
            val totalAvg = results.map { it.totalLatencyMs }.average().toLong()

            w.writeln("| Avg embed latency | ${embedAvg}ms |")
            w.writeln("| Avg fallback latency | ${if (fallbackAvg > 0) "${fallbackAvg}ms" else "—"} |")
            w.writeln("| Avg total latency | ${totalAvg}ms |")
            w.writeln()

            // ── Distribution by intent ────────────────────────────────────────
            w.writeln("## Распределение по классам")
            w.writeln()
            w.writeln("| Класс | Кол-во | % | Micro | LLM |")
            w.writeln("|-------|:------:|:-:|:-----:|:---:|")

            val intents = listOf("call", "meeting", "document", "email", "other")
            for (intent in intents) {
                val intentResults = results.filter { it.intent == intent }
                val n      = intentResults.size
                val pct    = if (total > 0) n * 100 / total else 0
                val micro  = intentResults.count { it.decision == MicroDecision.ACCEPTED }
                val llm    = intentResults.count { it.decision == MicroDecision.ESCALATED }
                w.writeln("| $intent | $n | $pct% | $micro | $llm |")
            }
            w.writeln()

            // ── Per-case results ──────────────────────────────────────────────
            w.writeln("## Результаты по каждому запросу")
            w.writeln()
            w.writeln("| # | Вход | Класс | Score | Уровень | Путь | Latency |")
            w.writeln("|---|------|-------|:-----:|:-------:|:----:|--------:|")

            results.forEachIndexed { idx, r ->
                val path  = if (r.decision == MicroDecision.ACCEPTED) "micro" else "LLM"
                val score = "%.3f".format(r.confidenceScore)
                val input = r.input.replace("|", "\\|")
                w.writeln("| ${idx + 1} | $input | ${r.intent} | $score | ${r.confidenceLevel} | $path | ${r.totalLatencyMs}ms |")
            }
            w.writeln()

            // ── Escalated list ────────────────────────────────────────────────
            if (escalated > 0) {
                w.writeln("## Эскалированные запросы (вызов LLM)")
                w.writeln()
                fallbackResults.forEach { r ->
                    w.writeln("- \"${r.input}\" → ${r.intent} (embed score=${"%.3f".format(r.confidenceScore)}, ${r.confidenceLevel})")
                }
                w.writeln()
            }
        }

        return reportFile
    }

    private fun java.io.BufferedWriter.writeln(line: String = "") {
        write(line)
        newLine()
    }
}
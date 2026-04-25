import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object MicroReport {

    fun write(entries: List<MicroEntry>, outputDir: File): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val reportFile = File(outputDir, "micro-report-$timestamp.md")

        reportFile.bufferedWriter().use { w ->
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))

            val accepted  = entries.count { it.decision == MicroDecision.ACCEPTED }
            val escalated = entries.count { it.decision == MicroDecision.ESCALATED }
            val total     = entries.size
            val microPct  = if (total > 0) accepted * 100 / total else 0

            val embedAvgMs    = entries.map { it.microLatencyMs }.average().toLong()
            val fallbackEntries = entries.filter { it.decision == MicroDecision.ESCALATED }
            val fallbackAvgMs = if (fallbackEntries.isNotEmpty())
                fallbackEntries.map { it.fallbackLatencyMs }.average().toLong() else 0L
            val totalAvgMs    = entries.map { it.totalLatencyMs }.average().toLong()
            val llmCalls      = entries.sumOf { it.llmCalls }

            w.writeln("# Micro-model Report")
            w.writeln()
            w.writeln("Generated: $now  ")
            w.writeln("Micro model: `qwen3-embedding:8b` (cosine similarity)  ")
            w.writeln("Fallback model: `qwen3.6:35b` (LLM classification)  ")
            w.writeln("Test cases: $total")
            w.writeln()
            w.writeln("---")
            w.writeln()

            // ── Summary ───────────────────────────────────────────────────────
            w.writeln("## Summary")
            w.writeln()
            w.writeln("| Метрика | Значение |")
            w.writeln("|---------|----------|")
            val labeled = entries.filter { it.expectedType != null }
            val correct = labeled.count { it.isCorrect == true }
            val accuracy = if (labeled.isNotEmpty()) correct * 100 / labeled.size else 0

            w.writeln("| Принято micro-моделью | $accepted / $total ($microPct%) |")
            w.writeln("| Эскалировано в LLM | $escalated / $total (${100 - microPct}%) |")
            w.writeln("| **Точность (accuracy)** | **$correct / ${labeled.size} ($accuracy%)** |")
            w.writeln("| LLM вызовов (qwen3.6:35b) | $llmCalls |")
            w.writeln("| Avg embed latency | ${embedAvgMs}ms |")
            w.writeln("| Avg fallback latency | ${if (fallbackAvgMs > 0) "${fallbackAvgMs}ms" else "—"} |")
            w.writeln("| Avg total latency | ${totalAvgMs}ms |")
            w.writeln()

            // ── By category ───────────────────────────────────────────────────
            w.writeln("## По категориям")
            w.writeln()
            w.writeln("| Категория | N | ACCEPTED | ESCALATED | Avg Score |")
            w.writeln("|-----------|:-:|:--------:|:---------:|:---------:|")

            TestCategory.values().forEach { cat ->
                val catEntries = entries.filter { it.testCase.category == cat }
                val catAccepted  = catEntries.count { it.decision == MicroDecision.ACCEPTED }
                val catEscalated = catEntries.count { it.decision == MicroDecision.ESCALATED }
                val avgScore = catEntries.mapNotNull { it.microResult?.confidenceScore }.average()
                val label = cat.label()
                w.writeln("| $label | ${catEntries.size} | $catAccepted | $catEscalated | ${"%.3f".format(avgScore)} |")
            }
            w.writeln()

            // ── Latency breakdown ─────────────────────────────────────────────
            w.writeln("## Latency Breakdown")
            w.writeln()
            w.writeln("| Уровень | N запросов | Avg latency | Min | Max |")
            w.writeln("|---------|:----------:|:-----------:|:---:|:---:|")

            val embedTimes = entries.map { it.microLatencyMs }
            w.writeln("| Embed (Level 1) | $total | ${embedTimes.average().toLong()}ms | ${embedTimes.min()}ms | ${embedTimes.max()}ms |")

            if (fallbackEntries.isNotEmpty()) {
                val fbTimes = fallbackEntries.map { it.fallbackLatencyMs }
                w.writeln("| LLM fallback (Level 2) | ${fallbackEntries.size} | ${fbTimes.average().toLong()}ms | ${fbTimes.min()}ms | ${fbTimes.max()}ms |")
            }
            w.writeln()

            // ── Per-case details ──────────────────────────────────────────────
            w.writeln("## Детальные результаты")
            w.writeln()

            TestCategory.values().forEach { cat ->
                val catEntries = entries.filter { it.testCase.category == cat }
                if (catEntries.isEmpty()) return@forEach

                w.writeln("### ${cat.sectionTitle()}")
                w.writeln()
                w.writeln("| # | Вход | Ожидан. | Score | Уровень | Класс | Результат | Latency |")
                w.writeln("|---|------|:-------:|:-----:|:-------:|-------|:---------:|--------:|")

                catEntries.forEachIndexed { idx, entry ->
                    val score    = entry.microResult?.confidenceScore?.let { "%.3f".format(it) } ?: "—"
                    val level    = entry.microResult?.confidenceLevel ?: "—"
                    val cls      = entry.finalResult?.type ?: "—"
                    val expected = entry.expectedType ?: "—"
                    val dec      = if (entry.decision == MicroDecision.ACCEPTED) "ACCEPTED" else "ESCALATED"
                    val correct  = when (entry.isCorrect) {
                        true  -> "✅ $dec"
                        false -> "❌ $dec"
                        null  -> "— $dec"
                    }
                    val lat   = "${entry.totalLatencyMs}ms"
                    val input = entry.testCase.userInput.replace("|", "\\|")
                    w.writeln("| ${idx + 1} | $input | $expected | $score | $level | $cls | $correct | $lat |")
                }
                w.writeln()

                // Escalation details
                val escalatedInCat = catEntries.filter { it.decision == MicroDecision.ESCALATED }
                if (escalatedInCat.isNotEmpty()) {
                    w.writeln("**Эскалированные случаи:**")
                    w.writeln()
                    escalatedInCat.forEach { entry ->
                        val microCls   = entry.microResult?.type ?: "—"
                        val finalCls   = entry.finalResult?.type ?: "—"
                        val reason     = entry.escalationReason ?: "—"
                        val changed    = if (microCls != finalCls) " → LLM изменил на **$finalCls**" else " → LLM подтвердил **$finalCls**"
                        w.writeln("- \"${entry.testCase.userInput}\": embed→$microCls ($reason)$changed")
                    }
                    w.writeln()
                }
            }
        }

        return reportFile
    }

    private fun java.io.BufferedWriter.writeln(line: String = "") {
        write(line)
        newLine()
    }

    private fun TestCategory.label() = when (this) {
        TestCategory.CLEAR -> "Чёткие"
        TestCategory.EDGE  -> "Пограничные"
        TestCategory.NOISY -> "Шумные"
    }

    private fun TestCategory.sectionTitle() = when (this) {
        TestCategory.CLEAR -> "Чёткие запросы"
        TestCategory.EDGE  -> "Пограничные случаи"
        TestCategory.NOISY -> "Шумные / сложные входные данные"
    }
}
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CalibrationReport {

    fun write(entries: List<CalibrationEntry>, outputDir: File): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val reportFile = File(outputDir, "calibration-report-$timestamp.md")

        reportFile.bufferedWriter().use { w ->
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
            w.writeln("# Calibration Report")
            w.writeln()
            w.writeln("Generated: $now  ")
            w.writeln("Model: `gpt-4o-mini`  ")
            w.writeln("Test cases: ${entries.size} | Approaches: Constraint-based, Scoring, Redundancy")
            w.writeln()
            w.writeln("---")
            w.writeln()

            // ── Summary by approach ───────────────────────────────────────────
            w.writeln("## Summary by Approach")
            w.writeln()
            w.writeln("| Подход | OK | UNSURE | FAIL | Отклонено | Retry | Avg Latency | Total Tokens | Est. Cost USD |")
            w.writeln("|--------|:--:|:------:|:----:|:---------:|:-----:|:-----------:|:------------:|:-------------:|")

            listOf(
                "Constraint" to entries.map { it.constraint },
                "Scoring"    to entries.map { it.scoring },
                "Redundancy" to entries.map { it.redundancy }
            ).forEach { (name, results) ->
                val ok       = results.count { it.status == ConfidenceStatus.OK }
                val unsure   = results.count { it.status == ConfidenceStatus.UNSURE }
                val fail     = results.count { it.status == ConfidenceStatus.FAIL }
                val rejected = unsure + fail
                val retries  = results.sumOf { it.retriesUsed }
                val avgMs    = results.map { it.latencyMs }.average().toLong()
                val tokens   = results.sumOf { it.totalTokens }
                val cost     = results.sumOf { it.estimatedCostUsd }
                w.writeln("| $name | $ok | $unsure | $fail | $rejected | $retries | ${avgMs}ms | $tokens | \$${String.format("%.5f", cost)} |")
            }
            w.writeln()

            // ── Summary by category ───────────────────────────────────────────
            w.writeln("## Summary by Category")
            w.writeln()
            w.writeln("| Категория | N | Constraint OK | Scoring OK | Redundancy OK |")
            w.writeln("|-----------|:-:|:-------------:|:----------:|:-------------:|")

            TestCategory.values().forEach { cat ->
                val cat_entries = entries.filter { it.testCase.category == cat }
                val cOK = cat_entries.count { it.constraint.status  == ConfidenceStatus.OK }
                val sOK = cat_entries.count { it.scoring.status     == ConfidenceStatus.OK }
                val rOK = cat_entries.count { it.redundancy.status  == ConfidenceStatus.OK }
                val label = cat.label()
                w.writeln("| $label | ${cat_entries.size} | $cOK | $sOK | $rOK |")
            }
            w.writeln()

            // ── Latency & cost breakdown ──────────────────────────────────────
            val allResults = entries.flatMap { listOf(it.constraint, it.scoring, it.redundancy) }
            val totalTokens = allResults.sumOf { it.totalTokens }
            val totalCost   = allResults.sumOf { it.estimatedCostUsd }
            val totalRetries = allResults.sumOf { it.retriesUsed }
            val totalRejected = allResults.count { it.status != ConfidenceStatus.OK }

            w.writeln("## Overall Metrics")
            w.writeln()
            w.writeln("| Метрика | Значение |")
            w.writeln("|---------|----------|")
            w.writeln("| Всего API вызовов (оценка) | ${entries.size * 5} |")
            w.writeln("| Отклонено ответов | $totalRejected / ${allResults.size} |")
            w.writeln("| Повторных попыток (retry) | $totalRetries |")
            w.writeln("| Всего токенов | $totalTokens |")
            w.writeln("| Расчётная стоимость | \$${String.format("%.5f", totalCost)} |")
            w.writeln()

            // ── Detailed results per test case ────────────────────────────────
            w.writeln("## Детальные результаты")
            w.writeln()

            TestCategory.values().forEach { cat ->
                w.writeln("### ${cat.sectionTitle()}")
                w.writeln()

                entries.filter { it.testCase.category == cat }.forEach { entry ->
                    w.writeln("#### \"${entry.testCase.userInput}\"")
                    w.writeln()
                    w.writeln("_${entry.testCase.description}_")
                    w.writeln()
                    w.writeln("| Подход | Статус | Причина | Latency | Tokens | Retry |")
                    w.writeln("|--------|--------|---------|--------:|-------:|:-----:|")

                    listOf(
                        "Constraint" to entry.constraint,
                        "Scoring"    to entry.scoring,
                        "Redundancy" to entry.redundancy
                    ).forEach { (name, r) ->
                        val icon   = r.status.icon()
                        val reason = r.reason.replace("|", "\\|").take(70)
                        w.writeln("| $name | $icon | $reason | ${r.latencyMs}ms | ${r.totalTokens} | ${r.retriesUsed} |")
                    }
                    w.writeln()

                    // Show accepted task if at least one checker passed
                    val accepted = listOf(entry.constraint, entry.scoring, entry.redundancy)
                        .firstOrNull { it.status == ConfidenceStatus.OK }?.task
                    if (accepted != null) {
                        w.writeln("**Принятый результат:**")
                        w.writeln("- **Задача:** ${accepted.задача}")
                        w.writeln("- **Дата:** ${accepted.дата}")
                        w.writeln("- **Рекомендация:** ${accepted.рекомендация}")
                        w.writeln()
                    } else {
                        w.writeln("_Ни один подход не принял результат._")
                        w.writeln()
                    }
                }
            }
        }

        return reportFile
    }

    private fun java.io.BufferedWriter.writeln(line: String = "") {
        write(line)
        newLine()
    }

    private fun ConfidenceStatus.icon() = when (this) {
        ConfidenceStatus.OK     -> "✅ OK"
        ConfidenceStatus.UNSURE -> "⚠️ UNSURE"
        ConfidenceStatus.FAIL   -> "❌ FAIL"
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
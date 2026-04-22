import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RouterReport {

    fun write(entries: List<RoutingEntry>, outputDir: File): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val reportFile = File(outputDir, "routing-report-$timestamp.md")

        reportFile.bufferedWriter().use { w ->
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
            w.ln("# Routing Report")
            w.ln()
            w.ln("Generated: $now  ")
            w.ln("Primary model: `gpt-4o-mini` | Fallback model: `gpt-4o`  ")
            w.ln("Heuristics: confidence score (MEDIUM/LOW → escalate) + response length (< 5 chars → escalate)")
            w.ln()
            w.ln("---")
            w.ln()

            // ── Overall summary ───────────────────────────────────────────────
            val stayed    = entries.count { it.decision == RoutingDecision.STAYED }
            val escalated = entries.count { it.decision == RoutingDecision.ESCALATED }
            val totalCost = entries.sumOf { it.totalCostUsd }
            val miniOnlyCost = entries.sumOf { RoutingEntry.miniCost(it.primaryTokens + it.fallbackTokens) }
            val savings   = miniOnlyCost - totalCost  // routing saves vs always using mini? No, opposite
            // Savings = what we saved vs always using gpt-4o
            val allGpt4oCost = entries.sumOf { RoutingEntry.gpt4oCost(it.primaryTokens + it.fallbackTokens) }
            val savedVsAlwaysStrong = allGpt4oCost - totalCost

            w.ln("## Overall Summary")
            w.ln()
            w.ln("| Метрика | Значение |")
            w.ln("|---------|----------|")
            w.ln("| Всего запросов | ${entries.size} |")
            w.ln("| Остались на gpt-4o-mini (STAYED) | $stayed |")
            w.ln("| Эскалировали на gpt-4o (ESCALATED) | $escalated |")
            w.ln("| Процент эскалации | ${String.format("%.0f", escalated.toDouble() / entries.size * 100)}% |")
            w.ln("| Итоговая стоимость (routing) | \$${String.format("%.6f", totalCost)} |")
            w.ln("| Стоимость без routing (всё на gpt-4o) | \$${String.format("%.6f", allGpt4oCost)} |")
            w.ln("| Экономия vs «всегда gpt-4o» | \$${String.format("%.6f", savedVsAlwaysStrong)} |")
            w.ln()

            // ── By category ───────────────────────────────────────────────────
            w.ln("## Результаты по категориям")
            w.ln()
            w.ln("| Категория | N | STAYED | ESCALATED | Avg Latency |")
            w.ln("|-----------|:-:|:------:|:---------:|:-----------:|")

            TestCategory.values().forEach { cat ->
                val catEntries = entries.filter { it.testCase.category == cat }
                val cStayed    = catEntries.count { it.decision == RoutingDecision.STAYED }
                val cEscalated = catEntries.count { it.decision == RoutingDecision.ESCALATED }
                val avgMs      = catEntries.map { it.totalLatencyMs }.average().toLong()
                val label      = cat.displayName()
                w.ln("| $label | ${catEntries.size} | $cStayed | $cEscalated | ${avgMs}ms |")
            }
            w.ln()

            // ── Cost breakdown ────────────────────────────────────────────────
            w.ln("## Стоимость по запросам")
            w.ln()
            w.ln("| Запрос | Решение | Confidence | Primary tokens | Fallback tokens | Cost USD |")
            w.ln("|--------|---------|:----------:|:--------------:|:---------------:|:--------:|")

            entries.forEach { e ->
                val decIcon = if (e.decision == RoutingDecision.STAYED) "✅ STAYED" else "⬆️ ESCALATED"
                w.ln("| ${e.testCase.userInput} | $decIcon | ${e.primaryConfidence} | ${e.primaryTokens} | ${e.fallbackTokens} | \$${String.format("%.6f", e.totalCostUsd)} |")
            }
            w.ln()

            // ── Latency breakdown ─────────────────────────────────────────────
            w.ln("## Latency по запросам")
            w.ln()
            w.ln("| Запрос | Решение | Primary ms | Fallback ms | Total ms |")
            w.ln("|--------|---------|:----------:|:-----------:|:--------:|")

            entries.forEach { e ->
                val decIcon = if (e.decision == RoutingDecision.STAYED) "✅ STAYED" else "⬆️ ESCALATED"
                w.ln("| ${e.testCase.userInput} | $decIcon | ${e.primaryLatencyMs} | ${e.fallbackLatencyMs} | ${e.totalLatencyMs} |")
            }
            w.ln()

            // ── Detailed results ──────────────────────────────────────────────
            w.ln("## Детальные результаты")
            w.ln()

            TestCategory.values().forEach { cat ->
                w.ln("### ${cat.sectionTitle()}")
                w.ln()

                entries.filter { it.testCase.category == cat }.forEach { e ->
                    val decIcon = if (e.decision == RoutingDecision.STAYED) "✅ STAYED" else "⬆️ ESCALATED"
                    w.ln("#### \"${e.testCase.userInput}\" — $decIcon")
                    w.ln()
                    w.ln("- **Confidence (gpt-4o-mini):** ${e.primaryConfidence}")
                    if (e.escalationReason != null) {
                        w.ln("- **Причина эскалации:** ${e.escalationReason}")
                    }
                    w.ln("- **Статус финального результата:** ${e.finalStatus}")
                    w.ln("- **Latency:** ${e.primaryLatencyMs}ms primary + ${e.fallbackLatencyMs}ms fallback = ${e.totalLatencyMs}ms")
                    w.ln()

                    if (e.primaryTask != null) {
                        w.ln("**gpt-4o-mini результат:**")
                        w.ln("- Задача: ${e.primaryTask.задача}")
                        w.ln("- Дата: ${e.primaryTask.дата}")
                        w.ln("- Рекомендация: ${e.primaryTask.рекомендация}")
                        w.ln()
                    }

                    if (e.decision == RoutingDecision.ESCALATED && e.finalTask != null) {
                        w.ln("**gpt-4o финальный результат:**")
                        w.ln("- Задача: ${e.finalTask.задача}")
                        w.ln("- Дата: ${e.finalTask.дата}")
                        w.ln("- Рекомендация: ${e.finalTask.рекомендация}")
                        w.ln()
                    }
                }
            }
        }

        return reportFile
    }

    private fun java.io.BufferedWriter.ln(line: String = "") {
        write(line); newLine()
    }

    private fun TestCategory.displayName() = when (this) {
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
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DecomposeReport {

    fun write(entries: List<DecomposeEntry>, outputDir: File): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = File(outputDir, "decompose-report-$timestamp.md")

        file.bufferedWriter().use { w ->
            w.writeln("# Decompose Report — $timestamp")
            w.writeln()
            w.writeln("Сравнение **Monolithic** (1 запрос) и **Multi-Stage** (3 этапа).")
            w.writeln("Тест-кейсы: ${entries.size} (CLEAR / EDGE / NOISY).")
            w.writeln()

            writeSummary(w, entries)
            writeCategoryBreakdown(w, entries)
            writeStageAnalysis(w, entries)
            writeCostComparison(w, entries)
            writePerCaseDetails(w, entries)
        }

        return file
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private fun writeSummary(w: BufferedWriter, entries: List<DecomposeEntry>) {
        val monoOk    = entries.count { it.monoOk }
        val multiOk   = entries.count { it.multiOk }
        val total     = entries.size

        val monoAvgMs  = entries.map { it.monoLatencyMs }.average().toLong()
        val multiAvgMs = entries.map { it.multiLatencyMs }.average().toLong()

        val monoTotalCost  = entries.sumOf { it.monoCostUsd }
        val multiTotalCost = entries.sumOf { it.multiCostUsd }

        w.writeln("## Summary")
        w.writeln()
        w.writeln("| Метрика | Monolithic | Multi-Stage |")
        w.writeln("|---------|-----------|-------------|")
        w.writeln("| OK rate | $monoOk/$total | $multiOk/$total |")
        w.writeln("| Avg latency | ${monoAvgMs}ms | ${multiAvgMs}ms |")
        w.writeln("| Total cost | \$${monoTotalCost.fmt(6)} | \$${multiTotalCost.fmt(6)} |")
        w.writeln("| Models used | gpt-4o-mini | mini + (gpt-4o if needed) |")
        w.writeln()
    }

    // ── Category breakdown ────────────────────────────────────────────────────

    private fun writeCategoryBreakdown(w: BufferedWriter, entries: List<DecomposeEntry>) {
        w.writeln("## Breakdown по категориям")
        w.writeln()
        w.writeln("| Категория | Кейсов | Mono OK | Multi OK | Multi→gpt-4o |")
        w.writeln("|-----------|--------|---------|----------|--------------|")

        for (cat in TestCategory.values()) {
            val catEntries = entries.filter { it.testCase.category == cat }
            val monoOk  = catEntries.count { it.monoOk }
            val multiOk = catEntries.count { it.multiOk }
            val usedFull = catEntries.count { it.stage3Model == "gpt-4o" }
            w.writeln("| $cat | ${catEntries.size} | $monoOk | $multiOk | $usedFull |")
        }
        w.writeln()
    }

    // ── Stage analysis ────────────────────────────────────────────────────────

    private fun writeStageAnalysis(w: BufferedWriter, entries: List<DecomposeEntry>) {
        val s1Ok   = entries.count { it.stage1?.ok == true }
        val s2Ok   = entries.count { it.stage2?.ok == true }
        val s3Mini = entries.count { it.stage3Model == "gpt-4o-mini" && it.multiOk }
        val s3Full = entries.count { it.stage3Model == "gpt-4o"      && it.multiOk }

        val clarityDist = entries.groupBy { it.stage1?.clarity ?: "?" }
            .mapValues { it.value.size }
        val typeDist = entries.groupBy { it.stage2?.type ?: "?" }
            .mapValues { it.value.size }

        w.writeln("## Stage Analysis (Multi-Stage)")
        w.writeln()
        w.writeln("| Этап | Успешно | Детали |")
        w.writeln("|------|---------|--------|")
        w.writeln("| Stage 1 (normalize) | $s1Ok/${entries.size} | clarity: ${clarityDist.entries.joinToString { "${it.key}=${it.value}" }} |")
        w.writeln("| Stage 2 (classify)  | $s2Ok/${entries.size} | type: ${typeDist.entries.joinToString { "${it.key}=${it.value}" }} |")
        w.writeln("| Stage 3 → mini      | $s3Mini | |")
        w.writeln("| Stage 3 → gpt-4o    | $s3Full | escalated by Stage 2 |")
        w.writeln()
    }

    // ── Cost comparison ───────────────────────────────────────────────────────

    private fun writeCostComparison(w: BufferedWriter, entries: List<DecomposeEntry>) {
        val monoTotal  = entries.sumOf { it.monoCostUsd }
        val multiTotal = entries.sumOf { it.multiCostUsd }
        val diff = multiTotal - monoTotal
        val sign = if (diff >= 0) "+" else ""

        w.writeln("## Cost Breakdown")
        w.writeln()
        w.writeln("| Подход | Токены (всего) | Стоимость |")
        w.writeln("|--------|----------------|-----------|")
        w.writeln("| Monolithic | ${entries.sumOf { it.monoTokens }} | \$${monoTotal.fmt(6)} |")
        w.writeln("| Multi-Stage (mini part) | ${entries.sumOf { it.multiTokensMini }} | — |")
        w.writeln("| Multi-Stage (gpt-4o part) | ${entries.sumOf { it.multiTokensFull }} | — |")
        w.writeln("| Multi-Stage (total) | ${entries.sumOf { it.multiTokensMini + it.multiTokensFull }} | \$${multiTotal.fmt(6)} |")
        w.writeln("| Разница | | $sign\$${diff.fmt(6)} |")
        w.writeln()
        w.writeln("> Стоимость рассчитана по усреднённой ставке (input+output)/2 для каждой модели.")
        w.writeln()
    }

    // ── Per-case details ──────────────────────────────────────────────────────

    private fun writePerCaseDetails(w: BufferedWriter, entries: List<DecomposeEntry>) {
        w.writeln("## Per-Case Details")
        w.writeln()

        var currentCategory: TestCategory? = null

        entries.forEach { e ->
            if (e.testCase.category != currentCategory) {
                currentCategory = e.testCase.category
                w.writeln("### ${e.testCase.category}")
                w.writeln()
            }

            w.writeln("#### \"${e.testCase.userInput}\"")
            w.writeln("_${e.testCase.description}_")
            w.writeln()

            // Monolithic result
            w.writeln("**Monolithic** — ${if (e.monoOk) "OK" else "FAIL"} | ${e.monoLatencyMs}ms | ${e.monoTokens} tok | \$${e.monoCostUsd.fmt(6)}")
            if (e.monoTask != null) {
                w.writeln()
                w.writeln("| Поле | Значение |")
                w.writeln("|------|----------|")
                w.writeln("| Задача | ${e.monoTask.задача} |")
                w.writeln("| Дата | ${e.monoTask.дата} |")
                w.writeln("| Рекомендация | ${e.monoTask.рекомендация} |")
                w.writeln("| Тип | ${e.monoTask.тип} |")
                w.writeln("| Приоритет | ${e.monoTask.приоритет} |")
            }
            w.writeln()

            // Multi-stage result
            val s1 = e.stage1
            val s2 = e.stage2
            val multiStatusStr = if (e.multiOk) "OK" else "FAIL"
            w.writeln("**Multi-Stage** — $multiStatusStr | ${e.multiLatencyMs}ms | mini=${e.multiTokensMini} tok, full=${e.multiTokensFull} tok | \$${e.multiCostUsd.fmt(6)}")
            w.writeln()

            if (s1 != null) {
                w.writeln("Stage 1 (normalize): clarity=**${s1.clarity}**, entities=${s1.entities}, has_date=${s1.hasDate}")
                w.writeln("> normalized: \"${s1.normalized}\"")
            }
            if (s2 != null) {
                w.writeln()
                w.writeln("Stage 2 (classify): type=**${s2.type}**, urgency=**${s2.urgency}**, model_hint=**${s2.modelHint}**")
                w.writeln("Stage 3 model used: **${e.stage3Model}**")
            }
            if (e.stage3Task != null) {
                w.writeln()
                w.writeln("| Поле | Значение |")
                w.writeln("|------|----------|")
                w.writeln("| Задача | ${e.stage3Task.задача} |")
                w.writeln("| Дата | ${e.stage3Task.дата} |")
                w.writeln("| Рекомендация | ${e.stage3Task.рекомендация} |")
                w.writeln("| Тип | ${e.stage3Task.тип} |")
                w.writeln("| Приоритет | ${e.stage3Task.приоритет} |")
            }
            w.writeln()
            w.writeln("---")
            w.writeln()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun BufferedWriter.writeln(line: String = "") {
        write(line)
        newLine()
    }

    private fun Double.fmt(decimals: Int) = "%.${decimals}f".format(this)
}
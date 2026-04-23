object DecomposeRunner {

    suspend fun run(client: OpenAiClient): List<DecomposeEntry> {
        val results = mutableListOf<DecomposeEntry>()

        CalibrationRunner.TEST_CASES.forEachIndexed { idx, tc ->
            val num = "[${idx + 1}/${CalibrationRunner.TEST_CASES.size}]"
            println("  $num ${tc.description}")
            println("         Input: \"${tc.userInput}\"")

            // ── Monolithic ────────────────────────────────────────────────────
            print("         Mono   → ")
            val (monoTask, monoLatency, monoTokens) = MonolithicInference.run(tc.userInput, client)
            val monoOk = monoTask != null
            if (monoOk) {
                println("OK  (${monoLatency}ms, ${monoTokens} tok, тип=${monoTask.тип}, приоритет=${monoTask.приоритет})")
            } else {
                println("FAIL (${monoLatency}ms)")
            }

            // ── Multi-Stage ───────────────────────────────────────────────────
            print("         Multi  → ")
            val multi = MultiStageInference.run(tc.userInput, client)

            if (multi.ok) {
                val s1 = multi.stage1
                val s2 = multi.stage2
                val hint = if (multi.stage3Model == "gpt-4o") "→ gpt-4o" else "→ mini"
                println("OK  Stage1=${s1?.clarity} → Stage2=${s2?.type}/${s2?.urgency} $hint (${multi.latencyMs}ms)")
            } else {
                val failAt = when {
                    multi.stage1?.ok == false || multi.stage1 == null -> "Stage1"
                    multi.stage2?.ok == false || multi.stage2 == null -> "Stage2"
                    else                                               -> "Stage3"
                }
                println("FAIL at $failAt (${multi.latencyMs}ms)")
            }
            println()

            results += DecomposeEntry(
                testCase       = tc,
                monoTask       = monoTask,
                monoOk         = monoOk,
                monoLatencyMs  = monoLatency,
                monoTokens     = monoTokens,
                stage1         = multi.stage1,
                stage2         = multi.stage2,
                stage3Task     = multi.stage3Task,
                stage3Model    = multi.stage3Model,
                multiOk        = multi.ok,
                multiLatencyMs = multi.latencyMs,
                multiTokensMini = multi.tokensMini,
                multiTokensFull = multi.tokensFull
            )
        }

        return results
    }
}
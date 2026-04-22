object RouterRunner {

    suspend fun run(client: OpenAiClient): List<RoutingEntry> {
        val results = mutableListOf<RoutingEntry>()
        val cases = CalibrationRunner.TEST_CASES

        cases.forEachIndexed { idx, tc ->
            print("  [${idx + 1}/${cases.size}] \"${tc.userInput}\" → ")
            val entry = ModelRouter.route(tc, client)
            val decisionLabel = when (entry.decision) {
                RoutingDecision.STAYED    -> "STAYED    (${entry.primaryConfidence}, ${entry.totalLatencyMs}ms)"
                RoutingDecision.ESCALATED -> "ESCALATED (${entry.primaryConfidence} → gpt-4o, reason: ${entry.escalationReason}, ${entry.totalLatencyMs}ms)"
            }
            println(decisionLabel)
            results += entry
        }

        return results
    }
}
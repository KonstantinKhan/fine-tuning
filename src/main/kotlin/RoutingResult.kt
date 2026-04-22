enum class RoutingDecision { STAYED, ESCALATED }

data class RoutingEntry(
    val testCase: TestCase,
    val decision: RoutingDecision,
    val escalationReason: String?,   // null если STAYED
    val primaryConfidence: String,   // HIGH / MEDIUM / LOW
    val primaryTask: TaskResult?,    // результат gpt-4o-mini
    val finalTask: TaskResult?,      // финальный принятый результат
    val finalStatus: ConfidenceStatus,
    val primaryLatencyMs: Long,
    val fallbackLatencyMs: Long,     // 0 если не эскалировали
    val primaryTokens: Int,
    val fallbackTokens: Int
) {
    companion object {
        // gpt-4o-mini: $0.15 / 1M input,  $0.60 / 1M output
        private const val MINI_INPUT_COST  = 0.00000015
        private const val MINI_OUTPUT_COST = 0.00000060
        // gpt-4o:      $2.50 / 1M input, $10.00 / 1M output
        private const val GPT4O_INPUT_COST  = 0.0000025
        private const val GPT4O_OUTPUT_COST = 0.000010
        // Разбиваем токены 70/30 (input/output) для оценки — точнее нет без разбивки
        fun miniCost(tokens: Int): Double =
            tokens * 0.7 * MINI_INPUT_COST + tokens * 0.3 * MINI_OUTPUT_COST
        fun gpt4oCost(tokens: Int): Double =
            tokens * 0.7 * GPT4O_INPUT_COST + tokens * 0.3 * GPT4O_OUTPUT_COST
    }

    val totalCostUsd: Double
        get() = miniCost(primaryTokens) + if (decision == RoutingDecision.ESCALATED) gpt4oCost(fallbackTokens) else 0.0

    val totalLatencyMs: Long get() = primaryLatencyMs + fallbackLatencyMs
}
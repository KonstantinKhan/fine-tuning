enum class MicroDecision { ACCEPTED, ESCALATED }

data class IntentResult(
    val type: String,            // call | meeting | document | email | other
    val confidenceScore: Double, // 0.0–1.0  (cosine similarity for micro, 1.0 for LLM)
    val confidenceLevel: String  // HIGH | MEDIUM | LOW
)

data class MicroEntry(
    val testCase: TestCase,
    val decision: MicroDecision,
    val escalationReason: String?,     // null if ACCEPTED
    val microResult: IntentResult?,    // embedding level result
    val finalResult: IntentResult?,    // final answer (micro if ACCEPTED, LLM if ESCALATED)
    val microLatencyMs: Long,
    val fallbackLatencyMs: Long,       // 0 if ACCEPTED
    val embeddingCalls: Int,           // always 1 per test case
    val llmCalls: Int,                 // 0 or 1
    val expectedType: String? = null   // ground truth label, null for unlabeled cases
) {
    val totalLatencyMs: Long get() = microLatencyMs + fallbackLatencyMs
    val isCorrect: Boolean? get() = expectedType?.let { it == finalResult?.type }
}
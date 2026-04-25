data class InferResult(
    val input: String,
    val intent: String,            // call | meeting | document | email | other
    val confidenceScore: Double,   // cosine similarity score
    val confidenceLevel: String,   // HIGH | MEDIUM | LOW
    val decision: MicroDecision,   // ACCEPTED | ESCALATED
    val microLatencyMs: Long,
    val fallbackLatencyMs: Long    // 0 if ACCEPTED
) {
    val totalLatencyMs: Long get() = microLatencyMs + fallbackLatencyMs
}
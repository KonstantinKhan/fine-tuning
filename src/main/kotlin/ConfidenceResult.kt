enum class ConfidenceStatus { OK, UNSURE, FAIL }

enum class CheckerType { CONSTRAINT, SCORING, REDUNDANCY }

data class TaskResult(
    val задача: String,
    val дата: String,
    val рекомендация: String
)

data class ConfidenceResult(
    val status: ConfidenceStatus,
    val task: TaskResult?,
    val reason: String,
    val retriesUsed: Int = 0,
    val latencyMs: Long,
    val promptTokens: Int,
    val completionTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    // gpt-4o-mini pricing: $0.15 / 1M input tokens, $0.60 / 1M output tokens
    val estimatedCostUsd: Double
        get() = promptTokens * 0.00000015 + completionTokens * 0.00000060
}
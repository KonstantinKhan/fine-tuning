data class Stage1Result(
    val clarity: String,        // "clear" | "ambiguous" | "noisy"
    val normalized: String,     // cleaned / corrected input text
    val entities: List<String>, // extracted named entities
    val hasDate: Boolean,       // whether user mentioned any date
    val latencyMs: Long,
    val tokens: Int,
    val ok: Boolean             // false if JSON couldn't be parsed
)

data class Stage2Result(
    val type: String,           // "call" | "meeting" | "document" | "email" | "other"
    val urgency: String,        // "high" | "medium" | "low"
    val modelHint: String,      // "mini" | "full" — recommended model for Stage 3
    val latencyMs: Long,
    val tokens: Int,
    val ok: Boolean
)

data class EnrichedTask(
    val задача: String,
    val дата: String,
    val рекомендация: String,
    val тип: String,            // mirrors Stage2 type
    val приоритет: String       // mirrors Stage2 urgency
)

private const val MINI_INPUT_PRICE  = 0.15   // $ per 1M tokens
private const val MINI_OUTPUT_PRICE = 0.60
private const val FULL_INPUT_PRICE  = 2.50
private const val FULL_OUTPUT_PRICE = 10.00

data class DecomposeEntry(
    val testCase: TestCase,

    // ── Monolithic (Variant A) ──────────────────────────────────────────────
    val monoTask: EnrichedTask?,
    val monoOk: Boolean,
    val monoLatencyMs: Long,
    val monoTokens: Int,

    // ── Multi-Stage (Variant B) ─────────────────────────────────────────────
    val stage1: Stage1Result?,
    val stage2: Stage2Result?,
    val stage3Task: EnrichedTask?,
    val stage3Model: String,       // "gpt-4o-mini" or "gpt-4o"
    val multiOk: Boolean,
    val multiLatencyMs: Long,      // sum of all three stages
    val multiTokensMini: Int,      // tokens billed at mini rate
    val multiTokensFull: Int       // tokens billed at gpt-4o rate
) {
    /** Rough cost assuming 50/50 input/output split — sufficient for comparison. */
    val monoCostUsd: Double get() =
        monoTokens.toDouble() / 1_000_000 * (MINI_INPUT_PRICE + MINI_OUTPUT_PRICE) / 2

    val multiCostUsd: Double get() {
        val miniCost = multiTokensMini.toDouble() / 1_000_000 * (MINI_INPUT_PRICE + MINI_OUTPUT_PRICE) / 2
        val fullCost = multiTokensFull.toDouble() / 1_000_000 * (FULL_INPUT_PRICE + FULL_OUTPUT_PRICE) / 2
        return miniCost + fullCost
    }
}
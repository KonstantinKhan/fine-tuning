import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Routes a request between gpt-4o-mini (cheap) and gpt-4o (strong).
 *
 * Escalation heuristics:
 *   1. Confidence score — MEDIUM or LOW from ScoringChecker  (primary signal)
 *   2. Response length  — Задача field shorter than MIN_TASK_LENGTH chars
 *                         (safety net for empty/trivial responses only)
 */
object ModelRouter {

    private const val PRIMARY_MODEL  = "gpt-4o-mini"
    private const val FALLBACK_MODEL = "gpt-4o"
    private const val MIN_TASK_LENGTH = 5  // catches empty/trivial only, not short-but-valid tasks

    // Plain task prompt used for the gpt-4o fallback call
    private fun fallbackSystemPrompt() = """
Ты — помощник в постановке задач. Сегодня: ${currentDateContext()}.
Верни ТОЛЬКО валидный JSON без markdown:
{"Задача":"<глагол + конкретное действие>","Дата":"<DD.MM.YYYY или {дата в формате DD.MM.YYYY}>","Рекомендация":"<практический совет>"}

Правила для Дата: если пользователь указал конкретный день или относительную дату (сегодня, завтра, в пятницу) — вычисли и укажи дату в формате DD.MM.YYYY; если дата не указана — используй {дата в формате DD.MM.YYYY}.
""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun route(testCase: TestCase, client: OpenAiClient): RoutingEntry {
        // ── Step 1: primary call via ScoringChecker (uses gpt-4o-mini by default) ──
        val primaryStart = System.currentTimeMillis()
        val primaryResult = ScoringChecker.check(testCase.userInput, client)
        val primaryLatency = System.currentTimeMillis() - primaryStart

        val confidence = extractConfidence(primaryResult.reason)
        val primaryTask = primaryResult.task

        // ── Step 2: decide whether to escalate ───────────────────────────────────
        val escalationReason = escalationReason(confidence, primaryTask)

        if (escalationReason == null) {
            // STAYED — cheap model result accepted
            return RoutingEntry(
                testCase         = testCase,
                decision         = RoutingDecision.STAYED,
                escalationReason = null,
                primaryConfidence = confidence,
                primaryTask      = primaryTask,
                finalTask        = primaryTask,
                finalStatus      = primaryResult.status,
                primaryLatencyMs = primaryLatency,
                fallbackLatencyMs = 0L,
                primaryTokens    = primaryResult.totalTokens,
                fallbackTokens   = 0
            )
        }

        // ── Step 3: fallback call to gpt-4o ─────────────────────────────────────
        val fallbackStart = System.currentTimeMillis()
        val fallbackResponse = try {
            client.chat(fallbackSystemPrompt(), testCase.userInput, model = FALLBACK_MODEL)
        } catch (e: Exception) {
            return RoutingEntry(
                testCase          = testCase,
                decision          = RoutingDecision.ESCALATED,
                escalationReason  = escalationReason,
                primaryConfidence = confidence,
                primaryTask       = primaryTask,
                finalTask         = null,
                finalStatus       = ConfidenceStatus.FAIL,
                primaryLatencyMs  = primaryLatency,
                fallbackLatencyMs = System.currentTimeMillis() - fallbackStart,
                primaryTokens     = primaryResult.totalTokens,
                fallbackTokens    = 0
            )
        }
        val fallbackLatency = System.currentTimeMillis() - fallbackStart

        val fallbackTask = parseTask(fallbackResponse.content)
        val fallbackStatus = if (fallbackTask != null) ConfidenceStatus.OK else ConfidenceStatus.FAIL

        return RoutingEntry(
            testCase          = testCase,
            decision          = RoutingDecision.ESCALATED,
            escalationReason  = escalationReason,
            primaryConfidence = confidence,
            primaryTask       = primaryTask,
            finalTask         = fallbackTask,
            finalStatus       = fallbackStatus,
            primaryLatencyMs  = primaryLatency,
            fallbackLatencyMs = fallbackLatency,
            primaryTokens     = primaryResult.totalTokens,
            fallbackTokens    = fallbackResponse.promptTokens + fallbackResponse.completionTokens
        )
    }

    /**
     * Returns the escalation reason string, or null if the result should be accepted.
     *
     * Heuristic 1 — confidence: MEDIUM / LOW → escalate  (main signal)
     * Heuristic 2 — length:     Задача < MIN_TASK_LENGTH chars → escalate  (safety net)
     */
    private fun escalationReason(confidence: String, task: TaskResult?): String? {
        val reasons = mutableListOf<String>()

        if (confidence == "MEDIUM" || confidence == "LOW") {
            reasons += "confidence=$confidence"
        }

        val taskLength = task?.задача?.length ?: 0
        if (taskLength < MIN_TASK_LENGTH) {
            reasons += "задача too short (${taskLength} < $MIN_TASK_LENGTH chars)"
        }

        return if (reasons.isEmpty()) null else reasons.joinToString(", ")
    }

    /** Extracts HIGH / MEDIUM / LOW from ScoringChecker reason string "[HIGH] ..." */
    private fun extractConfidence(reason: String): String {
        val match = Regex("""\[(HIGH|MEDIUM|LOW)\]""").find(reason)
        return match?.groupValues?.get(1) ?: "LOW"
    }

    private fun parseTask(content: String): TaskResult? = try {
        val obj = json.parseToJsonElement(content.trim()).jsonObject
        TaskResult(
            задача       = obj["Задача"]!!.jsonPrimitive.content,
            дата         = obj["Дата"]!!.jsonPrimitive.content,
            рекомендация = obj["Рекомендация"]!!.jsonPrimitive.content
        )
    } catch (e: Exception) { null }
}
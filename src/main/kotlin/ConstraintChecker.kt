import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approach 1 — Constraint-based.
 * Validates the model's JSON output against structural and semantic rules.
 * Retries up to MAX_RETRIES times on failure (demonstrates retry flow).
 * Zero extra API calls on success.
 */
object ConstraintChecker {

    private const val MAX_RETRIES = 1

    private fun systemPrompt() = """
Ты — помощник в постановке задач. Сегодня: ${currentDateContext()}.
Верни ТОЛЬКО валидный JSON без markdown и без лишнего текста:
{"Задача":"<глагол + конкретное действие>","Дата":"<DD.MM.YYYY или {дата в формате DD.MM.YYYY}>","Рекомендация":"<практический совет>"}

Правила для Дата: если пользователь указал конкретный день или относительную дату (сегодня, завтра, в пятницу) — вычисли и укажи дату в формате DD.MM.YYYY; если дата не указана — используй {дата в формате DD.MM.YYYY}.
""".trimIndent()

    private val DATE_VALID = Regex("""\d{2}\.\d{2}\.\d{4}""")
    private val DATE_PLACEHOLDER = Regex("""\{[^}]*дата[^}]*\}""", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(userInput: String, client: OpenAiClient): ConfidenceResult {
        val overallStart = System.currentTimeMillis()
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var lastFailReason = "Unknown error"

        for (attempt in 0..MAX_RETRIES) {
            val response = client.chat(systemPrompt(), userInput, temperature = 0.0)
            totalPromptTokens += response.promptTokens
            totalCompletionTokens += response.completionTokens

            val failReason = validate(response.content)
            if (failReason == null) {
                return ConfidenceResult(
                    status = ConfidenceStatus.OK,
                    task = parseTask(response.content),
                    reason = "All constraints passed",
                    retriesUsed = attempt,
                    latencyMs = System.currentTimeMillis() - overallStart,
                    promptTokens = totalPromptTokens,
                    completionTokens = totalCompletionTokens
                )
            }
            lastFailReason = failReason
        }

        return ConfidenceResult(
            status = ConfidenceStatus.FAIL,
            task = null,
            reason = "Constraint failed after ${MAX_RETRIES + 1} attempt(s): $lastFailReason",
            retriesUsed = MAX_RETRIES,
            latencyMs = System.currentTimeMillis() - overallStart,
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens
        )
    }

    /** Returns null if all constraints pass, or a human-readable reason if any fail. */
    private fun validate(content: String): String? {
        val obj = try {
            json.parseToJsonElement(content.trim()).jsonObject
        } catch (e: Exception) {
            return "JSON parse error: ${e.message}"
        }

        val задача = obj["Задача"]?.jsonPrimitive?.content?.trim() ?: return "Missing field: Задача"
        val дата = obj["Дата"]?.jsonPrimitive?.content?.trim() ?: return "Missing field: Дата"
        val рек = obj["Рекомендация"]?.jsonPrimitive?.content?.trim() ?: return "Missing field: Рекомендация"

        if (задача.isBlank()) return "Empty field: Задача"
        if (дата.isBlank()) return "Empty field: Дата"
        if (рек.isBlank()) return "Empty field: Рекомендация"

        if (задача.length < 10) return "Задача too short (${задача.length} chars, min 10)"
        if (рек.length < 10) return "Рекомендация too short (${рек.length} chars, min 10)"

        if (!DATE_VALID.containsMatchIn(дата) && !DATE_PLACEHOLDER.containsMatchIn(дата)) {
            return "Invalid Дата format: '$дата'"
        }

        return null
    }

    private fun parseTask(content: String): TaskResult? = try {
        val obj = json.parseToJsonElement(content.trim()).jsonObject
        TaskResult(
            задача = obj["Задача"]!!.jsonPrimitive.content,
            дата = obj["Дата"]!!.jsonPrimitive.content,
            рекомендация = obj["Рекомендация"]!!.jsonPrimitive.content
        )
    } catch (e: Exception) { null }
}
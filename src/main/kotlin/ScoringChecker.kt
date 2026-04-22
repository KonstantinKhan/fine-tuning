import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approach 2 — Scoring (self-assessment).
 * A single API call where the model returns both the structured result and
 * its own confidence rating (HIGH / MEDIUM / LOW) with a reason.
 * HIGH → OK, MEDIUM → UNSURE, LOW → FAIL. No retries.
 */
object ScoringChecker {

    private val SYSTEM_PROMPT = """
Ты — помощник в постановке задач. Верни ТОЛЬКО валидный JSON без markdown:
{"result":{"Задача":"<глагол + конкретное действие>","Дата":"<DD.MM.YYYY или {дата в формате DD.MM.YYYY}>","Рекомендация":"<практический совет>"},"confidence":"HIGH","reason":"<краткое пояснение уверенности>"}

Правила confidence:
- HIGH   — намерение понятно, задача сформулирована однозначно
- MEDIUM — есть неоднозначность или нехватка деталей
- LOW    — запрос слишком размытый, непонятный или бессмысленный
""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(userInput: String, client: OpenAiClient): ConfidenceResult {
        val start = System.currentTimeMillis()
        val response = client.chat(SYSTEM_PROMPT, userInput, temperature = 0.0)
        val latencyMs = System.currentTimeMillis() - start

        return parse(response, latencyMs)
    }

    private fun parse(response: ChatResponse, latencyMs: Long): ConfidenceResult {
        val obj = try {
            json.parseToJsonElement(response.content.trim()).jsonObject
        } catch (e: Exception) {
            return ConfidenceResult(
                status = ConfidenceStatus.FAIL,
                task = null,
                reason = "JSON parse error: ${e.message}",
                latencyMs = latencyMs,
                promptTokens = response.promptTokens,
                completionTokens = response.completionTokens
            )
        }

        val confidence = obj["confidence"]?.jsonPrimitive?.content?.uppercase() ?: "LOW"
        val reason = obj["reason"]?.jsonPrimitive?.content ?: "No reason provided"
        val resultObj = obj["result"]?.jsonObject

        val task = resultObj?.let {
            try {
                TaskResult(
                    задача = it["Задача"]!!.jsonPrimitive.content,
                    дата = it["Дата"]!!.jsonPrimitive.content,
                    рекомендация = it["Рекомендация"]!!.jsonPrimitive.content
                )
            } catch (e: Exception) { null }
        }

        val status = when (confidence) {
            "HIGH" -> if (task != null) ConfidenceStatus.OK else ConfidenceStatus.FAIL
            "MEDIUM" -> ConfidenceStatus.UNSURE
            else -> ConfidenceStatus.FAIL
        }

        return ConfidenceResult(
            status = status,
            task = task,
            reason = "[$confidence] $reason",
            latencyMs = latencyMs,
            promptTokens = response.promptTokens,
            completionTokens = response.completionTokens
        )
    }
}
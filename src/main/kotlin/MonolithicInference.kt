import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Variant A — Monolithic inference.
 * One prompt asks the model to produce all 5 fields at once:
 * Задача, Дата, Рекомендация, Тип, Приоритет.
 */
object MonolithicInference {

    private fun systemPrompt() = """
Ты — помощник в постановке задач. Сегодня: ${currentDateContext()}.
Верни ТОЛЬКО валидный JSON без markdown:
{"Задача":"<глагол + конкретное действие>","Дата":"<DD.MM.YYYY или {дата в формате DD.MM.YYYY}>","Рекомендация":"<практический совет>","Тип":"<call|meeting|document|email|other>","Приоритет":"<high|medium|low>"}

Правила для Дата: если пользователь указал конкретный день или относительную дату — вычисли и укажи дату в формате DD.MM.YYYY; если дата не указана — используй {дата в формате DD.MM.YYYY}.
Правила для Тип: call — звонок; meeting — встреча; document — документ/отчёт; email — письмо; other — прочее.
Правила для Приоритет: high — срочно или явно важно; medium — стандартная задача; low — несрочно или неопределённо.
""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns (task or null, latencyMs, totalTokens). */
    suspend fun run(userInput: String, client: OpenAiClient): Triple<EnrichedTask?, Long, Int> {
        val start = System.currentTimeMillis()
        return try {
            val response = client.chat(systemPrompt(), userInput, temperature = 0.0)
            val latency = System.currentTimeMillis() - start
            val task = parse(response.content)
            Triple(task, latency, response.promptTokens + response.completionTokens)
        } catch (e: Exception) {
            Triple(null, System.currentTimeMillis() - start, 0)
        }
    }

    private fun parse(content: String): EnrichedTask? = try {
        val obj = json.parseToJsonElement(content.trim()).jsonObject
        EnrichedTask(
            задача       = obj["Задача"]!!.jsonPrimitive.content,
            дата         = obj["Дата"]!!.jsonPrimitive.content,
            рекомендация = obj["Рекомендация"]!!.jsonPrimitive.content,
            тип          = obj["Тип"]!!.jsonPrimitive.content.lowercase(),
            приоритет    = obj["Приоритет"]!!.jsonPrimitive.content.lowercase()
        )
    } catch (e: Exception) { null }
}
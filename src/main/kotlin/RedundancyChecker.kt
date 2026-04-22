import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Approach 3 — Redundancy.
 * Runs the same prompt N times in parallel with temperature > 0.
 * Compares the Задача field across all runs using Jaccard word-overlap similarity.
 *
 * Agreement:
 *   N/N match → OK
 *   ≥2/N match → UNSURE (majority answer is returned)
 *   all differ  → FAIL
 */
object RedundancyChecker {

    private const val RUNS = 3
    private const val TEMPERATURE = 0.7
    private const val SIMILARITY_THRESHOLD = 0.5

    private fun systemPrompt() = """
Ты — помощник в постановке задач. Сегодня: ${currentDateContext()}.
Верни ТОЛЬКО валидный JSON без markdown:
{"Задача":"<глагол + конкретное действие>","Дата":"<DD.MM.YYYY или {дата в формате DD.MM.YYYY}>","Рекомендация":"<практический совет>"}

Правила для Дата: если пользователь указал конкретный день или относительную дату (сегодня, завтра, в пятницу) — вычисли и укажи дату в формате DD.MM.YYYY; если дата не указана — используй {дата в формате DD.MM.YYYY}.
""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(userInput: String, client: OpenAiClient): ConfidenceResult = coroutineScope {
        val start = System.currentTimeMillis()

        val responses = (1..RUNS).map {
            async {
                try { client.chat(systemPrompt(), userInput, temperature = TEMPERATURE) }
                catch (e: Exception) { null }
            }
        }.awaitAll()

        val latencyMs = System.currentTimeMillis() - start
        val promptTokens = responses.filterNotNull().sumOf { it.promptTokens }
        val completionTokens = responses.filterNotNull().sumOf { it.completionTokens }

        val tasks = responses.mapNotNull { it?.let { r -> parseTask(r.content) } }

        if (tasks.isEmpty()) {
            return@coroutineScope ConfidenceResult(
                status = ConfidenceStatus.FAIL,
                task = null,
                reason = "All $RUNS runs failed to produce valid JSON",
                latencyMs = latencyMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
        }

        val (majority, agreementCount) = findMajority(tasks)

        val status = when {
            agreementCount == tasks.size && tasks.size == RUNS -> ConfidenceStatus.OK
            agreementCount >= 2 -> ConfidenceStatus.UNSURE
            else -> ConfidenceStatus.FAIL
        }

        ConfidenceResult(
            status = status,
            task = majority,
            reason = "$agreementCount/${tasks.size} runs produced similar results (threshold: $SIMILARITY_THRESHOLD)",
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens
        )
    }

    private fun parseTask(content: String): TaskResult? = try {
        val obj = json.parseToJsonElement(content.trim()).jsonObject
        TaskResult(
            задача = obj["Задача"]!!.jsonPrimitive.content,
            дата = obj["Дата"]!!.jsonPrimitive.content,
            рекомендация = obj["Рекомендация"]!!.jsonPrimitive.content
        )
    } catch (e: Exception) { null }

    /** Returns the task with the highest agreement count, along with that count. */
    private fun findMajority(tasks: List<TaskResult>): Pair<TaskResult, Int> {
        var bestTask = tasks.first()
        var bestCount = 0

        for (candidate in tasks) {
            val count = tasks.count { other ->
                jaccardSimilarity(candidate.задача, other.задача) >= SIMILARITY_THRESHOLD
            }
            if (count > bestCount) {
                bestCount = count
                bestTask = candidate
            }
        }

        return bestTask to bestCount
    }

    /** Word-level Jaccard similarity (ignores short words ≤2 chars). */
    private fun jaccardSimilarity(a: String, b: String): Double {
        val wordsA = a.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val wordsB = b.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty() && wordsB.isEmpty()) return 1.0
        val intersection = (wordsA intersect wordsB).size
        val union = (wordsA union wordsB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
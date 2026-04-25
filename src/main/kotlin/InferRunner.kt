import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object InferRunner {

    private const val EMBED_MODEL    = "qwen3-embedding:8b"
    private const val FALLBACK_MODEL = "qwen3.6:35b"

    private const val HIGH_THRESHOLD   = 0.75
    private const val MEDIUM_THRESHOLD = 0.55

    private val FALLBACK_PROMPT = """
Определи категорию задачи. Верни ТОЛЬКО JSON без markdown:
{"type":"<call|meeting|document|email|other>"}

call     — звонок: позвонить, набрать, перезвонить, связаться по телефону
meeting  — встреча или совещание: провести встречу, запланировать совещание, обсудить, интервью
document — документ: написать отчёт, подготовить договор, согласовать, оформить, презентация, инструкция
email    — письмо или сообщение: написать письмо, отправить email, написать в чат, скинуть в мессенджер
other    — не подходит ни к одной категории выше
""".trimIndent()

    private val VALID_TYPES = setOf("call", "meeting", "document", "email", "other")
    private val json = Json { ignoreUnknownKeys = true }

    // ── Warm-up ───────────────────────────────────────────────────────────────

    suspend fun warmUpAnchors(client: OllamaClient): Map<String, List<FloatArray>> {
        val total = MicroRunner.ANCHORS.values.sumOf { it.size }
        print("  Warming up anchor embeddings ($total texts)... ")
        val start = System.currentTimeMillis()
        val result = MicroRunner.ANCHORS.mapValues { (_, examples) ->
            examples.map { client.embed(it, EMBED_MODEL) }
        }
        println("done (${System.currentTimeMillis() - start}ms)")
        return result
    }

    // ── Single inference ──────────────────────────────────────────────────────

    suspend fun classify(
        input: String,
        anchorEmbeddings: Map<String, List<FloatArray>>,
        client: OllamaClient
    ): InferResult {
        val microStart = System.currentTimeMillis()
        val inputVec = client.embed(input, EMBED_MODEL)
        val (bestClass, bestScore) = findBestClass(inputVec, anchorEmbeddings)
        val microLatency = System.currentTimeMillis() - microStart

        val level = when {
            bestScore >= HIGH_THRESHOLD   -> "HIGH"
            bestScore >= MEDIUM_THRESHOLD -> "MEDIUM"
            else                          -> "LOW"
        }

        if (level == "HIGH") {
            return InferResult(input, bestClass, bestScore, level, MicroDecision.ACCEPTED, microLatency, 0L)
        }

        // LLM fallback
        val fallbackStart = System.currentTimeMillis()
        return try {
            val response = client.chat(FALLBACK_PROMPT, input, model = FALLBACK_MODEL)
            val fallbackLatency = System.currentTimeMillis() - fallbackStart
            val type = parseFallbackResponse(response.content)
            InferResult(input, type, bestScore, level, MicroDecision.ESCALATED, microLatency, fallbackLatency)
        } catch (e: Exception) {
            val fallbackLatency = System.currentTimeMillis() - fallbackStart
            println("  WARN: LLM error for \"$input\": ${e.message}")
            InferResult(input, bestClass, bestScore, level, MicroDecision.ESCALATED, microLatency, fallbackLatency)
        }
    }

    // ── Batch inference ───────────────────────────────────────────────────────

    suspend fun run(
        inputs: List<String>,
        anchorEmbeddings: Map<String, List<FloatArray>>,
        client: OllamaClient
    ): List<InferResult> {
        val results = mutableListOf<InferResult>()

        inputs.forEachIndexed { idx, input ->
            print("  [${idx + 1}/${inputs.size}] \"$input\" → ")
            val result = classify(input, anchorEmbeddings, client)
            val via = if (result.decision == MicroDecision.ACCEPTED) "micro" else "LLM"
            println("${result.intent} (${result.confidenceLevel}, $via, ${result.totalLatencyMs}ms)")
            results += result
        }

        return results
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findBestClass(
        inputVec: FloatArray,
        anchorEmbeddings: Map<String, List<FloatArray>>
    ): Pair<String, Double> {
        var bestClass = "other"
        var bestScore = -1.0
        for ((cls, vecs) in anchorEmbeddings) {
            val mean = vecs.map { cosineSimilarity(inputVec, it) }.average()
            if (mean > bestScore) { bestScore = mean; bestClass = cls }
        }
        return bestClass to bestScore
    }

    private fun parseFallbackResponse(content: String): String {
        val stripped = content.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
        return try {
            val type = json.parseToJsonElement(stripped).jsonObject["type"]
                ?.jsonPrimitive?.content?.lowercase()?.trim() ?: "other"
            if (type in VALID_TYPES) type else "other"
        } catch (e: Exception) {
            val lower = stripped.lowercase()
            VALID_TYPES.firstOrNull { lower.contains("\"$it\"") } ?: "other"
        }
    }
}
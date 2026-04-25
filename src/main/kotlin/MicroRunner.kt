import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MicroRunner {

    private const val EMBED_MODEL    = "qwen3-embedding:8b"
    private const val FALLBACK_MODEL = "qwen3.6:35b"

    // ── Thresholds ────────────────────────────────────────────────────────────
    private const val HIGH_THRESHOLD   = 0.75
    private const val MEDIUM_THRESHOLD = 0.55

    // ── Anchor examples per intent class ─────────────────────────────────────
    val ANCHORS: Map<String, List<String>> = mapOf(
        "call" to listOf(
            "Позвонить Иванову",
            "Набрать клиента",
            "Call the client",
            "Связаться по телефону",
            "Позвонить директору"
        ),
        "meeting" to listOf(
            "Встреча с командой",
            "Провести совещание",
            "Запланировать встречу",
            "Schedule a meeting",
            "Встреча с клиентом"
        ),
        "document" to listOf(
            "Написать отчёт",
            "Подготовить договор",
            "Оформить документ",
            "Написать письмо команде",
            "Подготовить презентацию"
        ),
        "email" to listOf(
            "Отправить email директору",
            "Написать письмо заказчику",
            "Send email to client",
            "Направить документы по электронке",
            "Написать письмо партнёрам"
        ),
        "other" to listOf(
            "Что-то сделать",
            "???",
            "непонятно",
            "not sure what to do",
            "что-нибудь"
        )
    )

    // ── Labeled eval cases from dataset-eval.jsonl ────────────────────────────
    private data class LabeledCase(
        val testCase: TestCase,
        val expectedType: String
    )

    private val EVAL_CASES: List<LabeledCase> = listOf(
        // ── call (5) ──────────────────────────────────────────────────────────
        LabeledCase(TestCase(TestCategory.CLEAR, "Набрать Иванову",                             "call: набрать Иванову"),                  "call"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Позвонить заказчику",                         "call: позвонить заказчику"),              "call"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Связаться с Ивановым по текущему проекту",    "call: связаться с Ивановым"),             "call"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Перезвонить клиенту",                         "call: перезвонить клиенту"),              "call"),
        LabeledCase(TestCase(TestCategory.EDGE,  "Набрать партнёра по поводу договора",         "call: набрать партнёра"),                 "call"),

        // ── email (6) ─────────────────────────────────────────────────────────
        LabeledCase(TestCase(TestCategory.CLEAR, "Письмо команде проекта",                      "email: письмо команде"),                  "email"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Направить документы заказчику по электронке", "email: направить документы"),             "email"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Написать коллегам в мессенджер",              "email: написать коллегам"),               "email"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Подготовить благодарственное письмо",         "email: благодарственное письмо"),         "email"),
        LabeledCase(TestCase(TestCategory.EDGE,  "Скинуть в чат инфу по встрече",               "email: скинуть в чат"),                   "email"),
        LabeledCase(TestCase(TestCategory.EDGE,  "На неделе разобрать почту",                   "email: разобрать почту"),                 "email"),

        // ── meeting (5) ───────────────────────────────────────────────────────
        LabeledCase(TestCase(TestCategory.CLEAR, "Провести совещание по проекту завтра",        "meeting: провести совещание"),            "meeting"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Запланировать встречу с клиентом",            "meeting: запланировать встречу"),         "meeting"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Провести интервью",                           "meeting: провести интервью"),             "meeting"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Согласовать встречу с командой",              "meeting: согласовать встречу"),           "meeting"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Обсудить с командой статус проекта",          "meeting: обсудить статус"),               "meeting"),

        // ── document (6) ─────────────────────────────────────────────────────
        LabeledCase(TestCase(TestCategory.CLEAR, "Согласовать документы до конца дня",          "document: согласовать документы"),        "document"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Написать отчет руководителю",                 "document: написать отчет"),               "document"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Подготовить презентацию к совещанию",         "document: подготовить презентацию"),      "document"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Подготовить договор",                         "document: подготовить договор"),          "document"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Подготовить инструкцию для новичка",          "document: подготовить инструкцию"),       "document"),
        LabeledCase(TestCase(TestCategory.EDGE,  "Проверить документы по проекту завтра",       "document: проверить документы"),          "document"),

        // ── other (5) ────────────────────────────────────────────────────────
        LabeledCase(TestCase(TestCategory.CLEAR, "Купить продукты на неделю",                   "other: купить продукты"),                 "other"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Записаться к врачу",                          "other: записаться к врачу"),              "other"),
        LabeledCase(TestCase(TestCategory.CLEAR, "Оформить командировку",                       "other: оформить командировку"),           "other"),
        LabeledCase(TestCase(TestCategory.EDGE,  "Что-то надо сделать",                         "other: что-то надо сделать"),             "other"),
        LabeledCase(TestCase(TestCategory.EDGE,  "Разобраться с этим",                          "other: разобраться с этим"),              "other"),
    )

    // ── Fallback system prompt ────────────────────────────────────────────────
    private val FALLBACK_PROMPT = """
Classify this task request into exactly one category.
Return ONLY valid JSON, no markdown, no thinking:
{"type":"<call|meeting|document|email|other>"}

call=phone call or contact by phone; meeting=in-person or online meeting; document=report, contract, presentation or written document; email=email or chat message; other=unclear or none of the above.
""".trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    // ── Main entry point ──────────────────────────────────────────────────────

    suspend fun run(client: OllamaClient): List<MicroEntry> {
        // Step 1: warm up anchor embeddings
        println("  Warming up anchor embeddings (${ANCHORS.values.sumOf { it.size }} texts)...")
        val warmupStart = System.currentTimeMillis()
        val anchorEmbeddings: Map<String, List<FloatArray>> = ANCHORS.mapValues { (_, examples) ->
            examples.map { client.embed(it, EMBED_MODEL) }
        }
        println("  Warm-up done (${System.currentTimeMillis() - warmupStart}ms)")
        println()

        // Step 2: run eval cases
        val results = mutableListOf<MicroEntry>()

        EVAL_CASES.forEachIndexed { idx, labeled ->
            val tc  = labeled.testCase
            val num = "[${idx + 1}/${EVAL_CASES.size}]"
            println("  $num ${tc.description}")
            println("         Input:    \"${tc.userInput}\"")
            println("         Expected: ${labeled.expectedType}")

            // Level 1 — embedding
            val microStart = System.currentTimeMillis()
            val inputVec = client.embed(tc.userInput, EMBED_MODEL)
            val (bestClass, bestScore) = classify(inputVec, anchorEmbeddings)
            val microLatency = System.currentTimeMillis() - microStart

            val confidenceLevel = when {
                bestScore >= HIGH_THRESHOLD   -> "HIGH"
                bestScore >= MEDIUM_THRESHOLD -> "MEDIUM"
                else                          -> "LOW"
            }

            val microResult = IntentResult(bestClass, bestScore, confidenceLevel)

            if (confidenceLevel == "HIGH") {
                val correct = if (bestClass == labeled.expectedType) "✓" else "✗ (expected ${labeled.expectedType})"
                println("         Embed  → $bestClass (score=${"%.3f".format(bestScore)}, HIGH) → ACCEPTED $correct (${microLatency}ms)")
                println()
                results += MicroEntry(
                    testCase          = tc,
                    decision          = MicroDecision.ACCEPTED,
                    escalationReason  = null,
                    microResult       = microResult,
                    finalResult       = microResult,
                    microLatencyMs    = microLatency,
                    fallbackLatencyMs = 0L,
                    embeddingCalls    = 1,
                    llmCalls          = 0,
                    expectedType      = labeled.expectedType
                )
                return@forEachIndexed
            }

            // Level 2 — LLM fallback
            val reason = "score=${"%.3f".format(bestScore)} ($confidenceLevel)"
            print("         Embed  → $bestClass (score=${"%.3f".format(bestScore)}, $confidenceLevel) → ESCALATED → $FALLBACK_MODEL ... ")

            val fallbackStart = System.currentTimeMillis()
            val fallbackResult = try {
                val response = client.chat(FALLBACK_PROMPT, tc.userInput, model = FALLBACK_MODEL)
                val fallbackLatency = System.currentTimeMillis() - fallbackStart
                val type = parseFallbackResponse(response.content)
                val correct = if (type == labeled.expectedType) "✓" else "✗ (expected ${labeled.expectedType})"
                println("$type $correct (${fallbackLatency}ms)")
                IntentResult(type, 1.0, "HIGH") to fallbackLatency
            } catch (e: Exception) {
                val fallbackLatency = System.currentTimeMillis() - fallbackStart
                println("ERROR: ${e.message} (${fallbackLatency}ms)")
                IntentResult("other", 0.0, "LOW") to fallbackLatency
            }

            println()
            results += MicroEntry(
                testCase          = tc,
                decision          = MicroDecision.ESCALATED,
                escalationReason  = reason,
                microResult       = microResult,
                finalResult       = fallbackResult.first,
                microLatencyMs    = microLatency,
                fallbackLatencyMs = fallbackResult.second,
                embeddingCalls    = 1,
                llmCalls          = 1,
                expectedType      = labeled.expectedType
            )
        }

        return results
    }

    // ── Classification ────────────────────────────────────────────────────────

    /** Returns (bestClass, bestScore) based on mean cosine similarity to anchors. */
    private fun classify(
        inputVec: FloatArray,
        anchorEmbeddings: Map<String, List<FloatArray>>
    ): Pair<String, Double> {
        var bestClass = "other"
        var bestScore = -1.0

        for ((cls, vecs) in anchorEmbeddings) {
            val meanScore = vecs.map { cosineSimilarity(inputVec, it) }.average()
            if (meanScore > bestScore) {
                bestScore = meanScore
                bestClass = cls
            }
        }

        return bestClass to bestScore
    }

    // ── Fallback response parser ──────────────────────────────────────────────

    private val VALID_TYPES = setOf("call", "meeting", "document", "email", "other")

    /**
     * Extracts the "type" field from the LLM response.
     * Handles <think>...</think> tags that qwen3 may prepend.
     */
    private fun parseFallbackResponse(content: String): String {
        // Strip <think>...</think> blocks
        val stripped = content.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()

        return try {
            val obj = json.parseToJsonElement(stripped).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content?.lowercase()?.trim() ?: "other"
            if (type in VALID_TYPES) type else "other"
        } catch (e: Exception) {
            // Try to find first valid type keyword in raw response
            val lower = stripped.lowercase()
            VALID_TYPES.firstOrNull { lower.contains("\"$it\"") } ?: "other"
        }
    }
}
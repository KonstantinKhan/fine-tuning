import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Variant B — Multi-stage inference.
 *
 * Stage 1 (gpt-4o-mini): normalise / analyse raw input → clarity, entities, has_date
 * Stage 2 (gpt-4o-mini): classify task type & urgency, recommend model for Stage 3
 * Stage 3 (mini or gpt-4o per model_hint): produce final EnrichedTask
 *
 * Each stage has a short, focused prompt with strict JSON output.
 */
object MultiStageInference {

    private const val MINI_MODEL = "gpt-4o-mini"
    private const val FULL_MODEL = "gpt-4o"

    private val json = Json { ignoreUnknownKeys = true }

    // ── Stage 1 ───────────────────────────────────────────────────────────────

    private fun stage1Prompt() = """
Ты — нормализатор текста. Исправь опечатки, расшифруй аббревиатуры, определи сущности.
Верни ТОЛЬКО JSON без markdown:
{"clarity":"<clear|ambiguous|noisy>","normalized":"<исправленный текст>","entities":["..."],"has_date":<true|false>}

clarity: clear — намерение понятно; ambiguous — намерение есть, но деталей не хватает; noisy — текст нечитаем или бессмысленный.
has_date: true если пользователь явно упомянул любую дату, день недели или слово «сегодня/завтра».
""".trimIndent()

    private suspend fun stage1(input: String, client: OpenAiClient): Pair<Stage1Result?, Int> {
        return try {
            val response = client.chat(stage1Prompt(), input, temperature = 0.0, model = MINI_MODEL)
            val tokens = response.promptTokens + response.completionTokens
            val obj = json.parseToJsonElement(response.content.trim()).jsonObject
            val result = Stage1Result(
                clarity    = obj["clarity"]!!.jsonPrimitive.content.lowercase(),
                normalized = obj["normalized"]!!.jsonPrimitive.content,
                entities   = obj["entities"]!!.jsonArray.map { it.jsonPrimitive.content },
                hasDate    = obj["has_date"]!!.jsonPrimitive.content.toBoolean(),
                latencyMs  = 0L, // filled by caller
                tokens     = tokens,
                ok         = true
            )
            result to tokens
        } catch (e: Exception) {
            Stage1Result("noisy", input, emptyList(), false, 0L, 0, false) to 0
        }
    }

    // ── Stage 2 ───────────────────────────────────────────────────────────────

    private val stage2Prompt = """
Ты — классификатор задач. На входе — уже нормализованный текст.
Верни ТОЛЬКО JSON без markdown:
{"type":"<call|meeting|document|email|other>","urgency":"<high|medium|low>","model_hint":"<mini|full>"}

type: call — звонок; meeting — встреча; document — документ/отчёт; email — письмо; other — прочее.
urgency: high — срочно; medium — обычная задача; low — несрочно.
model_hint: выбери "full" (gpt-4o) если clarity=noisy ИЛИ urgency=high; иначе "mini".
""".trimIndent()

    private suspend fun stage2(normalized: String, clarity: String, client: OpenAiClient): Pair<Stage2Result?, Int> {
        // Inject clarity into user message so model can apply model_hint rule correctly
        val userMsg = "Clarity: $clarity\nText: $normalized"
        return try {
            val response = client.chat(stage2Prompt, userMsg, temperature = 0.0, model = MINI_MODEL)
            val tokens = response.promptTokens + response.completionTokens
            val obj = json.parseToJsonElement(response.content.trim()).jsonObject
            val result = Stage2Result(
                type       = obj["type"]!!.jsonPrimitive.content.lowercase(),
                urgency    = obj["urgency"]!!.jsonPrimitive.content.lowercase(),
                modelHint  = obj["model_hint"]!!.jsonPrimitive.content.lowercase(),
                latencyMs  = 0L,
                tokens     = tokens,
                ok         = true
            )
            result to tokens
        } catch (e: Exception) {
            Stage2Result("other", "low", "mini", 0L, 0, false) to 0
        }
    }

    // ── Stage 3 ───────────────────────────────────────────────────────────────

    private fun stage3Prompt(s1: Stage1Result, s2: Stage2Result) = """
Ты — помощник в постановке задач. Сегодня: ${currentDateContext()}.
Контекст (результаты предыдущих этапов):
  Нормализованный текст: "${s1.normalized}"
  Тип задачи: ${s2.type} | Приоритет: ${s2.urgency} | Дата упомянута: ${s1.hasDate}

Верни ТОЛЬКО валидный JSON без markdown:
{"Задача":"<глагол + конкретное действие>","Дата":"<DD.MM.YYYY или {дата в формате DD.MM.YYYY}>","Рекомендация":"<практический совет>","Тип":"${s2.type}","Приоритет":"${s2.urgency}"}

Правила для Дата: если has_date=true — вычисли точную дату из нормализованного текста; иначе — используй {дата в формате DD.MM.YYYY}.
""".trimIndent()

    private suspend fun stage3(
        originalInput: String,
        s1: Stage1Result,
        s2: Stage2Result,
        client: OpenAiClient,
        model: String
    ): Pair<EnrichedTask?, Int> {
        return try {
            val response = client.chat(stage3Prompt(s1, s2), originalInput, temperature = 0.0, model = model)
            val tokens = response.promptTokens + response.completionTokens
            val obj = json.parseToJsonElement(response.content.trim()).jsonObject
            val task = EnrichedTask(
                задача       = obj["Задача"]!!.jsonPrimitive.content,
                дата         = obj["Дата"]!!.jsonPrimitive.content,
                рекомендация = obj["Рекомендация"]!!.jsonPrimitive.content,
                тип          = obj["Тип"]!!.jsonPrimitive.content.lowercase(),
                приоритет    = obj["Приоритет"]!!.jsonPrimitive.content.lowercase()
            )
            task to tokens
        } catch (e: Exception) {
            null to 0
        }
    }

    // ── Public entry point ────────────────────────────────────────────────────

    data class MultiResult(
        val stage1: Stage1Result?,
        val stage2: Stage2Result?,
        val stage3Task: EnrichedTask?,
        val stage3Model: String,
        val latencyMs: Long,
        val tokensMini: Int,
        val tokensFull: Int,
        val ok: Boolean
    )

    suspend fun run(userInput: String, client: OpenAiClient): MultiResult {
        val overallStart = System.currentTimeMillis()

        // Stage 1
        val s1Start = System.currentTimeMillis()
        val (s1Raw, s1Tokens) = stage1(userInput, client)
        val s1LatencyMs = System.currentTimeMillis() - s1Start
        val s1 = s1Raw?.copy(latencyMs = s1LatencyMs)

        if (s1 == null || !s1.ok) {
            return MultiResult(s1, null, null, MINI_MODEL,
                System.currentTimeMillis() - overallStart, s1Tokens, 0, false)
        }

        // Stage 2
        val s2Start = System.currentTimeMillis()
        val (s2Raw, s2Tokens) = stage2(s1.normalized, s1.clarity, client)
        val s2LatencyMs = System.currentTimeMillis() - s2Start
        val s2 = s2Raw?.copy(latencyMs = s2LatencyMs)

        if (s2 == null || !s2.ok) {
            return MultiResult(s1, s2, null, MINI_MODEL,
                System.currentTimeMillis() - overallStart, s1Tokens + s2Tokens, 0, false)
        }

        // Stage 3 — model chosen by Stage 2
        val stage3Model = if (s2.modelHint == "full") FULL_MODEL else MINI_MODEL
        val s3Start = System.currentTimeMillis()
        val (task, s3Tokens) = stage3(userInput, s1, s2, client, stage3Model)
        val s3LatencyMs = System.currentTimeMillis() - s3Start

        val tokensMini = s1Tokens + s2Tokens + if (stage3Model == MINI_MODEL) s3Tokens else 0
        val tokensFull = if (stage3Model == FULL_MODEL) s3Tokens else 0

        return MultiResult(
            stage1      = s1,
            stage2      = s2,
            stage3Task  = task,
            stage3Model = stage3Model,
            latencyMs   = System.currentTimeMillis() - overallStart,
            tokensMini  = tokensMini,
            tokensFull  = tokensFull,
            ok          = task != null
        )
    }
}
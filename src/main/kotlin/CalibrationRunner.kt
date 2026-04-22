enum class TestCategory { CLEAR, EDGE, NOISY }

data class TestCase(
    val category: TestCategory,
    val userInput: String,
    val description: String
)

data class CalibrationEntry(
    val testCase: TestCase,
    val constraint: ConfidenceResult,
    val scoring: ConfidenceResult,
    val redundancy: ConfidenceResult
)

object CalibrationRunner {

    val TEST_CASES = listOf(
        // Чёткие — однозначные запросы с явным действием
        TestCase(TestCategory.CLEAR, "Позвонить Иванову сегодня",
            "Чёткий: звонок с датой"),
        TestCase(TestCategory.CLEAR, "Отправить отчёт директору до пятницы",
            "Чёткий: письмо с дедлайном"),
        TestCase(TestCategory.CLEAR, "Написать письмо команде по итогам встречи",
            "Чёткий: письмо команде"),

        // Пограничные — есть намерение, но нет деталей
        TestCase(TestCategory.EDGE, "Иванов",
            "Пограничный: только имя, нет действия"),
        TestCase(TestCategory.EDGE, "Что-то сделать по проекту",
            "Пограничный: размытое намерение"),
        TestCase(TestCategory.EDGE, "Срочно!!!",
            "Пограничный: только эмоция, нет содержания"),

        // Шумные — опечатки, смешение языков, полная неопределённость
        TestCase(TestCategory.NOISY, "позвни ивнву псл совщ",
            "Шумный: опечатки и аббревиатуры"),
        TestCase(TestCategory.NOISY, "сделай то что надо",
            "Шумный: полностью размытый запрос"),
        TestCase(TestCategory.NOISY, "Call Иванова ASAP re: project",
            "Шумный: смешение языков")
    )

    suspend fun run(client: OpenAiClient): List<CalibrationEntry> {
        val results = mutableListOf<CalibrationEntry>()

        TEST_CASES.forEachIndexed { idx, tc ->
            println("  [${idx + 1}/${TEST_CASES.size}] ${tc.description}")
            println("         Input: \"${tc.userInput}\"")

            print("         Constraint  → ")
            val constraint = ConstraintChecker.check(tc.userInput, client)
            println("${constraint.status} (${constraint.latencyMs}ms, retry=${constraint.retriesUsed})")

            print("         Scoring     → ")
            val scoring = ScoringChecker.check(tc.userInput, client)
            println("${scoring.status} (${scoring.latencyMs}ms)")

            print("         Redundancy  → ")
            val redundancy = RedundancyChecker.check(tc.userInput, client)
            println("${redundancy.status} (${redundancy.latencyMs}ms)")

            println()
            results += CalibrationEntry(tc, constraint, scoring, redundancy)
        }

        return results
    }
}
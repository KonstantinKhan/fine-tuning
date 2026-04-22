import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object AssistantContentParser {

    private val DATE_PLACEHOLDER = Regex("""\{текущая дата в формате DD\.MM\.YYYY\}""")
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val json = Json { prettyPrint = false }

    fun parse(raw: String): String {
        if (!raw.contains("<br>")) return raw

        val currentDate = LocalDate.now().format(DATE_FORMATTER)

        // Strip leading "json" token (case-insensitive) then split by <br>
        val withoutPrefix = raw.trimStart().removePrefix("json").trimStart()
        val lines = withoutPrefix.split("<br>").map { it.trim() }.filter { it.isNotEmpty() }

        val pairs = lines.mapNotNull { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) return@mapNotNull null
            val key = line.substring(0, colonIdx).trim()
            val value = line.substring(colonIdx + 1).trim()
                .let { DATE_PLACEHOLDER.replace(it, currentDate) }
            if (key.isEmpty()) null else key to value
        }

        if (pairs.isEmpty()) return raw

        val jsonObject = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }
}
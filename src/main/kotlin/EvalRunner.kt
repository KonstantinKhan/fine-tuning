import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File

data class EvalResult(
    val index: Int,
    val system: String,
    val user: String,
    val assistant: String
)

object EvalRunner {

    private val json = Json { ignoreUnknownKeys = true }

    // Limit concurrent requests to avoid rate-limiting
    private const val CONCURRENCY = 3

    suspend fun run(jsonlFile: File, client: OpenAiClient): List<EvalResult> = coroutineScope {
        val lines = jsonlFile.readLines().filter { it.isNotBlank() }
        val semaphore = Semaphore(CONCURRENCY)

        lines.mapIndexed { idx, line ->
            async {
                semaphore.withPermit {
                    val messages = json.parseToJsonElement(line).jsonObject["messages"]?.jsonArray
                        ?: error("No 'messages' field in line ${idx + 1}")

                    val system = messages.firstOrNull {
                        it.jsonObject["role"]?.jsonPrimitive?.content == "system"
                    }?.jsonObject?.get("content")?.jsonPrimitive?.content
                        ?: error("No system message in line ${idx + 1}")

                    val user = messages.firstOrNull {
                        it.jsonObject["role"]?.jsonPrimitive?.content == "user"
                    }?.jsonObject?.get("content")?.jsonPrimitive?.content
                        ?: error("No user message in line ${idx + 1}")

                    println("  [${idx + 1}/${lines.size}] → \"$user\"")

                    val assistant = try {
                        client.chat(system, user).content
                    } catch (e: Exception) {
                        println("  [${idx + 1}/${lines.size}] ERROR: ${e.message}")
                        "ERROR: ${e.message}"
                    }

                    EvalResult(idx + 1, system, user, assistant)
                }
            }
        }.awaitAll().sortedBy { it.index }
    }
}
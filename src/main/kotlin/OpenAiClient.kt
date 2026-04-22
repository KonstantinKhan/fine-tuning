import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class OpenAiClient(private val apiKey: String) {

    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(systemPrompt: String, userPrompt: String): String {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val body = response.bodyAsText()

        if (!response.status.isSuccess()) {
            error("OpenAI API error ${response.status.value}: $body")
        }

        return json.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray?.get(0)?.jsonObject?.get("message")
            ?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: error("Unexpected response format: $body")
    }

    fun close() = httpClient.close()
}
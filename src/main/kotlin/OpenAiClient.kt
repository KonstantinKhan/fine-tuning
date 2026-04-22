import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

data class ChatResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int
)

class OpenAiClient(private val apiKey: String) {

    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.0,
        model: String = "gpt-4o-mini"
    ): ChatResponse {
        val requestBody = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
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

        val parsed = json.parseToJsonElement(body).jsonObject
        val content = parsed["choices"]
            ?.jsonArray?.get(0)?.jsonObject?.get("message")
            ?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: error("Unexpected response format: $body")

        val usage = parsed["usage"]?.jsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0

        return ChatResponse(content, promptTokens, completionTokens)
    }

    fun close() = httpClient.close()
}
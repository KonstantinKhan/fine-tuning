import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.math.sqrt

class OllamaClient(
    private val baseUrl: String = "http://localhost:11434"
) {
    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 120_000 // 2 minutes — qwen3.6:35b may take up to 15s per request
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    // ── Embeddings ────────────────────────────────────────────────────────────

    /** Returns the embedding vector for [text] using the given embedding [model]. */
    suspend fun embed(
        text: String,
        model: String = "qwen3-embedding:8b"
    ): FloatArray {
        val requestBody = buildJsonObject {
            put("model", model)
            put("input", text)
        }

        val response = httpClient.post("$baseUrl/v1/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Ollama embed error ${response.status.value}: $body")
        }

        val parsed = json.parseToJsonElement(body).jsonObject
        val vector = parsed["data"]
            ?.jsonArray?.get(0)?.jsonObject?.get("embedding")?.jsonArray
            ?: error("No embedding in response: $body")

        return FloatArray(vector.size) { i -> vector[i].jsonPrimitive.float }
    }

    // ── Chat completions ──────────────────────────────────────────────────────

    /** Calls Ollama chat completions. Reuses [ChatResponse] from OpenAiClient. */
    suspend fun chat(
        systemPrompt: String,
        userPrompt: String,
        model: String = "qwen3.6:35b",
        temperature: Double = 0.0
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

        val response = httpClient.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Ollama chat error ${response.status.value}: $body")
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

// ── Math helpers ──────────────────────────────────────────────────────────────

fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (sqrt(normA) * sqrt(normB))
}
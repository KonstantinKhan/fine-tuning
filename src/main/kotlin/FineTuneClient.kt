import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class FineTuneJob(
    val id: String,
    val model: String,
    val status: String,            // validating_files | queued | running | succeeded | failed | cancelled
    val fineTunedModel: String?,
    val trainedTokens: Int?,
    val createdAt: Long,
    val finishedAt: Long?
)

class FineTuneClient(private val apiKey: String) {

    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    // ── Upload ────────────────────────────────────────────────────────────────

    /** Uploads a JSONL file for fine-tuning. Returns the file_id. */
    suspend fun uploadFile(file: File): String {
        val response = httpClient.post("https://api.openai.com/v1/files") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "fine-tune")
                        append(
                            key = "file",
                            value = file.readBytes(),
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition,
                                    "form-data; name=\"file\"; filename=\"${file.name}\"")
                                append(HttpHeaders.ContentType, "application/jsonl")
                            }
                        )
                    }
                )
            )
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("File upload failed (${response.status.value}): $body")
        }

        return json.parseToJsonElement(body).jsonObject["id"]
            ?.jsonPrimitive?.content
            ?: error("No file id in response: $body")
    }

    // ── Create job ────────────────────────────────────────────────────────────

    /** Creates a fine-tuning job. Returns the job object. */
    suspend fun createJob(fileId: String, model: String = "gpt-4o-mini"): FineTuneJob {
        val requestBody = buildJsonObject {
            put("training_file", fileId)
            put("model", model)
        }

        val response = httpClient.post("https://api.openai.com/v1/fine_tuning/jobs") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Create job failed (${response.status.value}): $body")
        }

        return parseJob(json.parseToJsonElement(body).jsonObject)
    }

    // ── Get job status ────────────────────────────────────────────────────────

    /** Returns the current state of a fine-tuning job. */
    suspend fun getJob(jobId: String): FineTuneJob {
        val response = httpClient.get("https://api.openai.com/v1/fine_tuning/jobs/$jobId") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Get job failed (${response.status.value}): $body")
        }

        return parseJob(json.parseToJsonElement(body).jsonObject)
    }

    // ── Poll ──────────────────────────────────────────────────────────────────

    /**
     * Polls job status every [pollIntervalMs] ms until the job reaches a terminal state
     * (succeeded / failed / cancelled). Prints a status line on each poll.
     */
    suspend fun pollUntilDone(jobId: String, pollIntervalMs: Long = 30_000L): FineTuneJob {
        val terminal = setOf("succeeded", "failed", "cancelled")
        while (true) {
            val job = getJob(jobId)
            val time = timeFmt.format(Instant.now())
            val tokens = job.trainedTokens?.let { " (trained_tokens: $it)" } ?: ""
            println("  [$time] status: ${job.status}$tokens")

            if (job.status in terminal) return job

            delay(pollIntervalMs)
        }
    }

    fun close() = httpClient.close()

    // ── Parser ────────────────────────────────────────────────────────────────

    private fun parseJob(obj: JsonObject) = FineTuneJob(
        id              = obj["id"]!!.jsonPrimitive.content,
        model           = obj["model"]!!.jsonPrimitive.content,
        status          = obj["status"]!!.jsonPrimitive.content,
        fineTunedModel  = obj["fine_tuned_model"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        trainedTokens   = obj["trained_tokens"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
        createdAt       = obj["created_at"]!!.jsonPrimitive.long,
        finishedAt      = obj["finished_at"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
    )
}
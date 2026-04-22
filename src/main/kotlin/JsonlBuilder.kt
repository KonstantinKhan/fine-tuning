import kotlinx.serialization.Serializable

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class FineTuningEntry(val messages: List<Message>)

object JsonlBuilder {

    fun build(rows: List<RawRow>): List<FineTuningEntry> = rows.map { row ->
        FineTuningEntry(
            messages = listOf(
                Message(role = "system", content = row.system),
                Message(role = "user", content = row.user),
                Message(role = "assistant", content = AssistantContentParser.parse(row.assistant))
            )
        )
    }
}
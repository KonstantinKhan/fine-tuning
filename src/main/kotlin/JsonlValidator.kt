data class ValidationResult(
    val valid: List<IndexedEntry>,
    val invalid: List<InvalidEntry>
)

data class IndexedEntry(val index: Int, val entry: FineTuningEntry)
data class InvalidEntry(val index: Int, val entry: FineTuningEntry, val reason: String)

object JsonlValidator {

    private val REQUIRED_ROLES = setOf("system", "user", "assistant")

    fun validate(entries: List<FineTuningEntry>): ValidationResult {
        val valid = mutableListOf<IndexedEntry>()
        val invalid = mutableListOf<InvalidEntry>()

        entries.forEachIndexed { idx, entry ->
            val reason = checkEntry(entry)
            if (reason == null) {
                valid += IndexedEntry(idx + 2, entry) // +2: 1-based + skip header
            } else {
                invalid += InvalidEntry(idx + 2, entry, reason)
            }
        }

        return ValidationResult(valid, invalid)
    }

    private fun checkEntry(entry: FineTuningEntry): String? {
        val presentRoles = entry.messages.map { it.role }.toSet()
        val missingRoles = REQUIRED_ROLES - presentRoles
        if (missingRoles.isNotEmpty()) {
            return "Missing roles: ${missingRoles.joinToString()}"
        }

        val emptyContent = entry.messages.filter { it.content.isBlank() }
        if (emptyContent.isNotEmpty()) {
            return "Empty content in roles: ${emptyContent.joinToString { it.role }}"
        }

        return null
    }
}
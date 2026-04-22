import kotlinx.serialization.json.Json
import java.io.File

object JsonlWriter {

    private val json = Json { encodeDefaults = true }

    fun write(entries: List<IndexedEntry>, sourceFile: File, resourcesDir: File): File {
        val baseName = sourceFile.nameWithoutExtension
        val outputFile = File(resourcesDir, "$baseName.jsonl")

        outputFile.bufferedWriter().use { writer ->
            entries.forEach { (_, entry) ->
                writer.write(json.encodeToString(FineTuningEntry.serializer(), entry))
                writer.newLine()
            }
        }

        return outputFile
    }
}
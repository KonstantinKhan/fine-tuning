import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReportWriter {

    fun write(results: List<EvalResult>, outputDir: File): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val reportFile = File(outputDir, "eval-report-$timestamp.md")

        reportFile.bufferedWriter().use { writer ->
            results.forEach { result ->
                writer.write("## System\n")
                writer.write("${result.system}\n\n")
                writer.write("## Role\n")
                writer.write("${result.user}\n\n")
                writer.write("## Assistant\n")
                writer.write("${result.assistant}\n\n")
                writer.write("---\n\n")
            }
        }

        return reportFile
    }
}
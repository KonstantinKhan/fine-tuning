import kotlinx.coroutines.runBlocking
import java.io.File

fun main() {
    println("=== Fine-Tuning Tool ===")
    println()
    println("Select mode:")
    println("  1. Excel → JSONL (generate fine-tuning dataset)")
    println("  2. Eval  → MD report (run JSONL eval via OpenAI API)")
    println()
    print("Choice [1/2]: ")

    when (readLine()?.trim()) {
        "1" -> runExcelToJsonl()
        "2" -> runEval()
        else -> println("Invalid choice.")
    }
}

// ─── Mode 1: Excel → JSONL ────────────────────────────────────────────────────

fun runExcelToJsonl() {
    println()
    val resourcesDir = File("src/main/resources")
    if (!resourcesDir.exists()) {
        println("ERROR: Directory src/main/resources not found. Run from project root.")
        return
    }

    val xlsxFiles = resourcesDir.listFiles { f -> f.extension == "xlsx" }?.sorted() ?: emptyList()

    val selectedFile: File = if (xlsxFiles.isNotEmpty()) {
        println("Available Excel files:")
        xlsxFiles.forEachIndexed { idx, f -> println("  ${idx + 1}. ${f.name}") }
        println("  0. Enter path manually")
        println()
        print("Select [1-${xlsxFiles.size}] or 0: ")

        when (val input = readLine()?.trim()) {
            "0", null -> {
                print("Enter full path to xlsx file: ")
                val path = readLine()?.trim() ?: return
                val file = File(path)
                if (!file.exists()) { println("File not found: $path"); return }
                file
            }
            else -> {
                val idx = input.toIntOrNull()?.minus(1)
                if (idx == null || idx !in xlsxFiles.indices) {
                    println("Invalid selection.")
                    return
                }
                xlsxFiles[idx]
            }
        }
    } else {
        println("No xlsx files found in src/main/resources.")
        print("Enter full path to xlsx file: ")
        val path = readLine()?.trim() ?: return
        val file = File(path)
        if (!file.exists()) { println("File not found: $path"); return }
        file
    }

    println()
    println("Reading: ${selectedFile.name} ...")

    val rows = try {
        ExcelReader.read(selectedFile)
    } catch (e: Exception) {
        println("ERROR reading Excel file: ${e.message}")
        return
    }

    println("Rows read: ${rows.size}")
    println()
    println("Building JSONL entries ...")

    val entries = JsonlBuilder.build(rows)

    println("Validating ...")
    val result = JsonlValidator.validate(entries)

    println()
    println("--- Validation Results ---")
    println("Valid   : ${result.valid.size}")
    println("Invalid : ${result.invalid.size}")

    if (result.invalid.isNotEmpty()) {
        println()
        println("Invalid rows:")
        result.invalid.forEach { (rowNum, _, reason) ->
            println("  Row $rowNum: $reason")
        }
    }

    if (result.valid.isEmpty()) {
        println()
        println("No valid entries to save. Exiting.")
        return
    }

    println()
    print("Save ${result.valid.size} valid entries to ${resourcesDir.path}? [y/n]: ")
    val confirm = readLine()?.trim()?.lowercase()

    if (confirm != "y") {
        println("Cancelled.")
        return
    }

    val outputFile = try {
        JsonlWriter.write(result.valid, selectedFile, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing file: ${e.message}")
        return
    }

    println()
    println("Saved: ${outputFile.absolutePath}")
    println("Done.")
}

// ─── Mode 2: Eval via OpenAI API ─────────────────────────────────────────────

fun runEval() {
    println()

    val apiKey = System.getenv("OPEN_AI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("ERROR: Environment variable OPEN_AI_API_KEY is not set.")
        return
    }

    val resourcesDir = File("src/main/resources")
    if (!resourcesDir.exists()) {
        println("ERROR: Directory src/main/resources not found. Run from project root.")
        return
    }

    val jsonlFiles = resourcesDir.listFiles { f -> f.extension == "jsonl" }?.sorted() ?: emptyList()

    val selectedFile: File = if (jsonlFiles.isNotEmpty()) {
        println("Available JSONL files:")
        jsonlFiles.forEachIndexed { idx, f -> println("  ${idx + 1}. ${f.name}") }
        println("  0. Enter path manually")
        println()
        print("Select [1-${jsonlFiles.size}] or 0: ")

        when (val input = readLine()?.trim()) {
            "0", null -> {
                print("Enter full path to jsonl file: ")
                val path = readLine()?.trim() ?: return
                val file = File(path)
                if (!file.exists()) { println("File not found: $path"); return }
                file
            }
            else -> {
                val idx = input.toIntOrNull()?.minus(1)
                if (idx == null || idx !in jsonlFiles.indices) {
                    println("Invalid selection.")
                    return
                }
                jsonlFiles[idx]
            }
        }
    } else {
        println("No jsonl files found in src/main/resources.")
        print("Enter full path to jsonl file: ")
        val path = readLine()?.trim() ?: return
        val file = File(path)
        if (!file.exists()) { println("File not found: $path"); return }
        file
    }

    println()
    println("Running eval on: ${selectedFile.name}")
    println("Model: gpt-4o-mini | Concurrency: 3")
    println()

    val client = OpenAiClient(apiKey)
    val results = try {
        runBlocking { EvalRunner.run(selectedFile, client) }
    } catch (e: Exception) {
        println("ERROR during eval: ${e.message}")
        return
    } finally {
        client.close()
    }

    println()
    println("Completed: ${results.size} entries processed.")

    val reportFile = try {
        ReportWriter.write(results, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing report: ${e.message}")
        return
    }

    println("Report saved: ${reportFile.absolutePath}")
    println("Done.")
}
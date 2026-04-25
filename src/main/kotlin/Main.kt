import kotlinx.coroutines.runBlocking
import java.io.File

fun main() {
    println("=== Fine-Tuning Tool ===")
    println()
    println("Select mode:")
    println("  1. Excel → JSONL        (generate fine-tuning dataset)")
    println("  2. Eval  → MD report    (run JSONL eval via OpenAI API)")
    println("  3. Calibration          (confidence & quality control)")
    println("  4. Routing              (cheap → strong model fallback)")
    println("  5. Decompose            (monolithic vs multi-stage inference)")
    println("  6. Fine-tune            (upload JSONL → create job → poll status)")
    println("  7. Micro eval           (embedding classifier, labeled accuracy)")
    println("  8. Micro infer          (classify arbitrary inputs, no labels needed)")
    println()
    print("Choice [1/2/3/4/5/6/7/8]: ")

    when (readLine()?.trim()) {
        "1" -> runExcelToJsonl()
        "2" -> runEval()
        "3" -> runCalibration()
        "4" -> runRouting()
        "5" -> runDecompose()
        "6" -> runFineTune()
        "7" -> runMicro()
        "8" -> runInfer()
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

// ─── Mode 6: Fine-tune via OpenAI API ────────────────────────────────────────

fun runFineTune() {
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

    // ── Select JSONL file ──────────────────────────────────────────────────────
    val jsonlFiles = resourcesDir.listFiles { f -> f.extension == "jsonl" }?.sorted() ?: emptyList()

    val selectedFile: File = if (jsonlFiles.isNotEmpty()) {
        println("Available JSONL files:")
        jsonlFiles.forEachIndexed { idx, f ->
            val kb = f.length() / 1024
            println("  ${idx + 1}. ${f.name}  (${kb} KB)")
        }
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

    // ── Select base model ──────────────────────────────────────────────────────
    println()
    print("Base model [gpt-4o-mini]: ")
    val modelInput = readLine()?.trim()
    val model = if (modelInput.isNullOrBlank()) "gpt-4o-mini" else modelInput

    // ── Confirm ────────────────────────────────────────────────────────────────
    println()
    println("File  : ${selectedFile.name}  (${selectedFile.length() / 1024} KB)")
    println("Model : $model")
    println()
    print("Start fine-tuning job? [y/n]: ")
    if (readLine()?.trim()?.lowercase() != "y") {
        println("Cancelled.")
        return
    }

    val client = FineTuneClient(apiKey)
    try {
        // ── Upload ─────────────────────────────────────────────────────────────
        println()
        print("Uploading ${selectedFile.name} ... ")
        val fileId = runBlocking { client.uploadFile(selectedFile) }
        println("OK")
        println("file_id: $fileId")

        // ── Create job ─────────────────────────────────────────────────────────
        println()
        print("Creating fine-tuning job ... ")
        val job = runBlocking { client.createJob(fileId, model) }
        println("OK")
        println("job_id : ${job.id}")
        println("status : ${job.status}")

        // ── Poll ───────────────────────────────────────────────────────────────
        println()
        println("Polling every 30s (Ctrl+C to stop and check manually):")
        val finalJob = runBlocking { client.pollUntilDone(job.id) }

        // ── Result ─────────────────────────────────────────────────────────────
        println()
        when (finalJob.status) {
            "succeeded" -> {
                println("Fine-tuning complete!")
                println("Model: ${finalJob.fineTunedModel}")
                finalJob.trainedTokens?.let { println("Trained tokens: $it") }
            }
            "failed" -> println("Fine-tuning FAILED. Check the OpenAI dashboard for details.")
            else -> println("Final status: ${finalJob.status}")
        }
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
    } finally {
        client.close()
    }

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

// ─── Mode 4: Routing between models ──────────────────────────────────────────

fun runRouting() {
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

    println("Routing: ${CalibrationRunner.TEST_CASES.size} test cases")
    println("Primary : gpt-4o-mini")
    println("Fallback: gpt-4o")
    println("Escalate if: confidence MEDIUM/LOW OR задача length < 20 chars")
    println()

    val client = OpenAiClient(apiKey)
    val entries = try {
        runBlocking { RouterRunner.run(client) }
    } catch (e: Exception) {
        println("ERROR during routing: ${e.message}")
        return
    } finally {
        client.close()
    }

    val stayed    = entries.count { it.decision == RoutingDecision.STAYED }
    val escalated = entries.count { it.decision == RoutingDecision.ESCALATED }
    println()
    println("Results: $stayed stayed on gpt-4o-mini, $escalated escalated to gpt-4o")

    println("Writing report...")
    val reportFile = try {
        RouterReport.write(entries, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing report: ${e.message}")
        return
    }

    println("Report saved: ${reportFile.absolutePath}")
    println("Done.")
}

// ─── Mode 5: Decompose — Monolithic vs Multi-Stage ───────────────────────────

fun runDecompose() {
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

    println("Decompose: ${CalibrationRunner.TEST_CASES.size} test cases × 2 variants")
    println("Variant A: Monolithic  — 1 prompt, gpt-4o-mini")
    println("Variant B: Multi-Stage — Stage1(mini) → Stage2(mini) → Stage3(mini or gpt-4o)")
    println()

    val client = OpenAiClient(apiKey)
    val entries = try {
        runBlocking { DecomposeRunner.run(client) }
    } catch (e: Exception) {
        println("ERROR during decompose: ${e.message}")
        return
    } finally {
        client.close()
    }

    val monoOk  = entries.count { it.monoOk }
    val multiOk = entries.count { it.multiOk }
    val usedFull = entries.count { it.stage3Model == "gpt-4o" }
    println()
    println("Results: Mono OK=$monoOk/${entries.size}  Multi OK=$multiOk/${entries.size}  Stage3→gpt-4o=$usedFull")

    println("Writing report...")
    val reportFile = try {
        DecomposeReport.write(entries, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing report: ${e.message}")
        return
    }

    println("Report saved: ${reportFile.absolutePath}")
    println("Done.")
}

// ─── Mode 8: Micro Infer ─────────────────────────────────────────────────────

fun runInfer() {
    println()

    val resourcesDir = File("src/main/resources")
    if (!resourcesDir.exists()) {
        println("ERROR: Directory src/main/resources not found. Run from project root.")
        return
    }

    println("Micro infer — classify inputs without labels")
    println("Micro  : qwen3-embedding:8b (cosine similarity)")
    println("Fallback: qwen3.6:35b (called only when confidence is LOW/MEDIUM)")
    println()
    println("Input source:")
    println("  1. Interactive (enter text one by one)")
    println("  2. Plain text file (one query per line)")
    println("  3. JSONL dataset (extracts user.content)")
    println()
    print("Choice [1/2/3]: ")

    val inputs: List<String> = when (readLine()?.trim()) {
        "1" -> {
            println()
            println("Enter queries (empty line to finish):")
            val lines = mutableListOf<String>()
            while (true) {
                print("> ")
                val line = readLine()?.trim() ?: break
                if (line.isEmpty()) break
                lines += line
            }
            if (lines.isEmpty()) { println("No input. Cancelled."); return }
            lines
        }
        "2" -> {
            print("Path to file: ")
            val path = readLine()?.trim() ?: return
            val file = File(path)
            if (!file.exists()) { println("File not found: $path"); return }
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        "3" -> {
            val jsonlFiles = resourcesDir.listFiles { f -> f.extension == "jsonl" }?.sorted() ?: emptyList()
            if (jsonlFiles.isEmpty()) { println("No JSONL files found."); return }
            println()
            println("Available JSONL files:")
            jsonlFiles.forEachIndexed { idx, f -> println("  ${idx + 1}. ${f.name}") }
            print("Select [1-${jsonlFiles.size}]: ")
            val idx = readLine()?.trim()?.toIntOrNull()?.minus(1)
            if (idx == null || idx !in jsonlFiles.indices) { println("Invalid selection."); return }
            val file = jsonlFiles[idx]
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        val root = json.parseToJsonElement(line).let {
                            it as? kotlinx.serialization.json.JsonObject
                        } ?: return@mapNotNull null
                        val messages = root["messages"]
                            ?.let { it as? kotlinx.serialization.json.JsonArray } ?: return@mapNotNull null
                        messages.firstOrNull { msg ->
                            (msg as? kotlinx.serialization.json.JsonObject)
                                ?.get("role")
                                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                                ?.content == "user"
                        }?.let { msg ->
                            (msg as? kotlinx.serialization.json.JsonObject)
                                ?.get("content")
                                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                                ?.content
                        }
                    } catch (e: Exception) { null }
                }
        }
        else -> { println("Invalid choice."); return }
    }

    if (inputs.isEmpty()) { println("No inputs to process."); return }

    println()
    println("Inputs: ${inputs.size}")
    println()

    val client = OllamaClient()
    val results = try {
        runBlocking {
            val anchors = InferRunner.warmUpAnchors(client)
            println()
            InferRunner.run(inputs, anchors, client)
        }
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        return
    } finally {
        client.close()
    }

    val accepted  = results.count { it.decision == MicroDecision.ACCEPTED }
    val escalated = results.count { it.decision == MicroDecision.ESCALATED }
    println()
    println("Done: $accepted handled by micro-model, $escalated escalated to LLM")

    println("Writing report...")
    val reportFile = try {
        InferReport.write(results, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing report: ${e.message}")
        return
    }
    println("Report saved: ${reportFile.absolutePath}")
}

// ─── Mode 7: Micro-model First ───────────────────────────────────────────────

fun runMicro() {
    println()

    val resourcesDir = File("src/main/resources")
    if (!resourcesDir.exists()) {
        println("ERROR: Directory src/main/resources not found. Run from project root.")
        return
    }

    println("Micro-model first pipeline")
    println("Level 1 : qwen3-embedding:8b (cosine similarity, Ollama)")
    println("Level 2 : qwen3.6:35b        (LLM fallback, Ollama)")
    println("Cases   : 27 (10 eval + 6 train + 11 new, labeled ground truth)")
    println()

    val client = OllamaClient()
    val entries = try {
        runBlocking { MicroRunner.run(client) }
    } catch (e: Exception) {
        println("ERROR during micro run: ${e.message}")
        return
    } finally {
        client.close()
    }

    val accepted  = entries.count { it.decision == MicroDecision.ACCEPTED }
    val escalated = entries.count { it.decision == MicroDecision.ESCALATED }
    println()
    println("Results: $accepted accepted (micro-only), $escalated escalated to LLM")

    println("Writing report...")
    val reportFile = try {
        MicroReport.write(entries, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing report: ${e.message}")
        return
    }

    println("Report saved: ${reportFile.absolutePath}")
    println("Done.")
}

// ─── Mode 3: Confidence Calibration ──────────────────────────────────────────

fun runCalibration() {
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

    println("Calibration: ${CalibrationRunner.TEST_CASES.size} test cases × 3 approaches")
    println("Approaches : Constraint-based | Scoring | Redundancy (×3 runs)")
    println("Model      : gpt-4o-mini")
    println()

    val client = OpenAiClient(apiKey)
    val entries = try {
        runBlocking { CalibrationRunner.run(client) }
    } catch (e: Exception) {
        println("ERROR during calibration: ${e.message}")
        return
    } finally {
        client.close()
    }

    println("Writing report...")
    val reportFile = try {
        CalibrationReport.write(entries, resourcesDir)
    } catch (e: Exception) {
        println("ERROR writing report: ${e.message}")
        return
    }

    println("Report saved: ${reportFile.absolutePath}")
    println("Done.")
}
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File

data class RawRow(val system: String, val user: String, val assistant: String)

object ExcelReader {

    fun read(file: File): List<RawRow> {
        WorkbookFactory.create(file).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val headerRow = sheet.getRow(0) ?: error("Excel file has no header row")

            val headers = (0 until headerRow.lastCellNum).associate { idx ->
                idx to headerRow.getCell(idx)?.stringCellValue?.trim()?.lowercase()
            }

            val systemIdx = headers.entries.firstOrNull { it.value == "system" }?.key
                ?: error("Column 'system' not found. Available columns: ${headers.values}")
            val userIdx = headers.entries.firstOrNull { it.value == "user" }?.key
                ?: error("Column 'user' not found. Available columns: ${headers.values}")
            val assistantIdx = headers.entries.firstOrNull { it.value == "assistant" }?.key
                ?: error("Column 'assistant' not found. Available columns: ${headers.values}")

            val rows = mutableListOf<RawRow>()
            for (rowIdx in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue
                val system = cellString(row.getCell(systemIdx))
                val user = cellString(row.getCell(userIdx))
                val assistant = cellString(row.getCell(assistantIdx))
                rows += RawRow(system, user, assistant)
            }
            return rows
        }
    }

    private fun cellString(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> cell.cachedFormulaResultType.let { cellString(cell) }
            else -> ""
        }
    }
}
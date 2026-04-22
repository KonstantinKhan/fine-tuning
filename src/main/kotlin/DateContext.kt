import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Returns a date string like "среда, 22.04.2026" for injection into system prompts. */
fun currentDateContext(): String {
    val today = LocalDate.now()
    val date = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    val dayOfWeek = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
    return "$dayOfWeek, $date"
}
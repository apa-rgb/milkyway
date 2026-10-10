package pl.apargb.milkyway

import androidx.compose.ui.graphics.Color

// Readable markers in existing shared fields survive snapshots written by older clients.
enum class ButterProductKind(val title: String) { BUTTER("Masło"), MIX("Mix");
    internal val marker get() = "Rodzaj produktu: $title"
}

internal val ProductionQueueEntry.butterKind: ButterProductKind?
    get() = if (line == ProductionLine.BUTTER) ButterProductKind.entries.firstOrNull { description.substringBefore('\n') == it.marker } else null

internal fun productionDescription(description: String, line: ProductionLine): String =
    if (line == ProductionLine.BUTTER && ButterProductKind.entries.any { description.substringBefore('\n') == it.marker })
        description.substringAfter('\n', "") else description

internal val ProductionQueueEntry.planDescription get() = productionDescription(description, line)
internal fun withButterKind(description: String, kind: ButterProductKind?): String =
    if (kind == null) description.trim() else kind.marker + description.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()

private const val IMPORTANT_MARKER = "[Ważne]"
private val authorPattern = Regex("^\\[Autor: ([^\\[\\]\\r\\n]{1,60})\\](?:\\n|$)")
internal fun isImportantText(text: String) = text == IMPORTANT_MARKER || text.startsWith("$IMPORTANT_MARKER\n")
private fun withoutImportance(text: String) = if (isImportantText(text)) text.removePrefix(IMPORTANT_MARKER).removePrefix("\n") else text
internal fun noteAuthor(text: String): String? = authorPattern.find(withoutImportance(text))?.groupValues?.get(1)
private fun withoutAuthor(text: String) = withoutImportance(text).let { body -> authorPattern.find(body)?.let { body.removeRange(it.range) } ?: body }
private val reminderPattern = Regex("^\\[Przypomnienie: ([0-9]{1,15})\\|([a-f0-9-]{36})\\|([0-9a-z,]*)\\|([01])\\](?:\\n|$)")
internal fun noteReminder(text: String): NoteReminder? = reminderPattern.find(withoutAuthor(text))?.let { match ->
    val at = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: return@let null
    val readers = match.groupValues[3].split(',').filter { it.isNotEmpty() }.toSet()
    if (readers.any { it !in reminderReaders }) return@let null
    NoteReminder(at, match.groupValues[2], readers, match.groupValues[4] == "1")
}
internal fun noteBody(text: String): String = withoutAuthor(text).let { body ->
    if (noteReminder(text) != null) body.removeRange(reminderPattern.find(body)!!.range) else body
}
private fun annotatedNote(text: String, important: Boolean, author: String?, reminder: NoteReminder?): String = listOfNotNull(
    IMPORTANT_MARKER.takeIf { important },
    author?.let(::normalizeOperatorName)?.takeIf { it.isNotBlank() }?.let { "[Autor: $it]" },
    reminder?.let { "[Przypomnienie: ${it.at}|${it.token}|${it.readBy.sorted().joinToString(",")}|${if (it.done) 1 else 0}]" },
    text.trim().takeIf { it.isNotEmpty() }
).joinToString("\n")
internal fun withImportantText(text: String, important: Boolean) = annotatedNote(noteBody(text), important, noteAuthor(text), noteReminder(text))
internal fun withNoteAuthor(text: String, author: String?) = annotatedNote(noteBody(text), isImportantText(text), author, noteReminder(text))
internal fun withNoteReminder(text: String, reminder: NoteReminder?) = annotatedNote(noteBody(text), isImportantText(text), noteAuthor(text), reminder)
internal val ProductNote.important get() = isImportantText(text)
internal val ProductNote.visibleText get() = noteBody(text)
internal val ProductNote.author get() = noteAuthor(text)
internal val WorkNote.important get() = isImportantText(body)
internal val WorkNote.visibleBody get() = noteBody(body)
internal val WorkNote.author get() = noteAuthor(body)
internal val ImportantNoteColor = Color(0xFFB3261E)

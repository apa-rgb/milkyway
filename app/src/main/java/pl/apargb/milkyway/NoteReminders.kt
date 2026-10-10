package pl.apargb.milkyway

import java.util.UUID

internal data class NoteReminder(val at: Long, val token: String = UUID.randomUUID().toString(),
    val readBy: Set<String> = emptySet(), val done: Boolean = false) {
    fun due(reader: String, now: Long) = at <= now && !done && reader !in readBy
}
internal enum class ReminderAction { READ, DONE, URGENT }
internal val reminderReaders = operatorNumbers.toSet() + "local"
internal val CloudSessionState.reminderReader get() = if (configured) number.orEmpty() else "local"
internal val WorkNote.reminder get() = noteReminder(body)
internal val ProductNote.reminder get() = noteReminder(text)
internal fun validateReminder(reminder: NoteReminder, now: Long) {
    require(reminder.at > now) { "Wybierz przyszłą datę i godzinę przypomnienia." }
    require(reminder.readBy.all { it in reminderReaders }) { "Nieprawidłowe konto przypomnienia." }
}
internal fun changedReminderText(text: String, token: String, action: ReminderAction, reader: String): String {
    require(reader in reminderReaders) { "Zaloguj się ponownie." }
    val current = noteReminder(text) ?: throw IllegalArgumentException("Przypomnienie zostało usunięte.")
    require(current.token == token) { "Termin przypomnienia został zmieniony. Otwórz je ponownie." }
    return when (action) {
        ReminderAction.READ -> withNoteReminder(text, current.copy(readBy = current.readBy + reader))
        ReminderAction.DONE -> withNoteReminder(text, current.copy(done = true))
        ReminderAction.URGENT -> withImportantText(text, true)
    }
}
internal data class ReminderItem(val domain: String, val noteId: String, val title: String, val text: String,
    val author: String?, val important: Boolean, val reminder: NoteReminder, val caption: String) {
    val key get() = "$domain:$noteId:${reminder.token}"
}
internal fun workReminderItems(notes: List<WorkNote>): List<ReminderItem> = notes.mapNotNull { note ->
    note.reminder?.let { reminder -> ReminderItem("notes", note.id, note.title, note.visibleBody, note.author, note.important,
        reminder.copy(done = reminder.done || note.completed), if (note.scope == 0) "Tablica ogłoszeń" else "${note.kind.title} · Zmiana ${note.scope}") }
}
internal fun productReminderItems(entries: List<ProductionQueueEntry>, notes: List<ProductNote>): List<ReminderItem> {
    val byId = entries.associateBy { it.id }
    return notes.mapNotNull { note ->
        val entry = byId[note.entryId] ?: return@mapNotNull null
        if (note.isProductionCode()) return@mapNotNull null
        note.reminder?.let { ReminderItem("production", note.id, entry.title, note.visibleText, note.author, note.important,
            it, "${entry.line.title} · ${note.stage.title}") }
    }
}

package pl.apargb.milkyway

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class NoteKind(val title: String, val addLabel: String) {
    REMINDER("Przypomnienia", "Dodaj przypomnienie"),
    AIR_CONDITIONING("Klimatyzacja", "Dodaj wpis"),
    WASHING("Mycie", "Dodaj wpis"),
    PRODUCTION("Produkcja", "Dodaj wpis"),
    CURRENT_NOTES("Bieżące notatki", "Dodaj notatkę")
}

/** Scope 1–3 belongs to a shift; 0 is reserved for shared entries. */
data class WorkNote(val id: String, val scope: Int, val kind: NoteKind, val title: String, val body: String,
                    val completed: Boolean, val createdAt: Long, val updatedAt: Long)

internal class WorkNotesDatabase(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE work_notes (
            id TEXT PRIMARY KEY, scope INTEGER NOT NULL CHECK(scope BETWEEN 0 AND 3),
            kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
            completed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX work_notes_scope_kind ON work_notes(scope, kind)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported work notes migration: $oldVersion → $newVersion")
    companion object { const val NAME = "work_notes.db" }
}

internal class WorkNotesRepository(context: Context, private val helper: WorkNotesDatabase = WorkNotesDatabase(context)) {

    @Synchronized fun load(): List<WorkNote> = helper.readableDatabase.query("work_notes", null,
        null, null, null, null, "updated_at DESC, rowid DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.note()) }
    }

    @Synchronized fun save(id: String, scope: Int, kind: NoteKind, title: String, body: String, now: Long, author: String? = null,
                           completed: Boolean? = null): List<WorkNote> {
        require(id.isNotBlank()) { "Brak identyfikatora wpisu." }
        require(scope in 0..3) { "Wybierz zmianę 1, 2 lub 3." }
        require(title.isNotBlank() || noteBody(body).isNotBlank()) { "Wpisz treść notatki." }
        require(title.trim().length <= 120 && noteBody(body).length <= 10000) { "Tytuł może mieć do 120 znaków, a treść do 10 000." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = db.query("work_notes", null, "id = ?", arrayOf(id), null, null, null)
                .use { if (it.moveToFirst()) it.note() else null }
            require(old == null || (old.scope == scope && old.kind == kind)) { "Wpis należy do innej zmiany lub sekcji." }
            val incoming = noteReminder(body)
            val reminder = if (incoming == null || incoming.token == old?.reminder?.token) old?.reminder ?: incoming
                else incoming.also { validateReminder(it, now) }
            val rescheduled = incoming != null && incoming.token != old?.reminder?.token
            val done = completed ?: if (rescheduled) false else (old?.completed == true || reminder?.done == true)
            val values = ContentValues().apply {
                put("id", id); put("scope", scope); put("kind", kind.name)
                put("title", title.trim()); put("body", withNoteReminder(withNoteAuthor(body, if (old == null) author else old.author), reminder?.copy(done = done)))
                put("completed", if (done) 1 else 0)
                put("created_at", old?.createdAt ?: now); put("updated_at", now)
            }
            if (old == null) db.insertOrThrow("work_notes", null, values)
            else db.update("work_notes", values, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun setCompleted(note: WorkNote, completed: Boolean, now: Long): List<WorkNote> {
        val current = currentNote(note)
        val values = ContentValues().apply {
            put("completed", if (completed) 1 else 0); put("updated_at", now)
            current.reminder?.let { put("body", withNoteReminder(current.body, it.copy(done = completed))) }
        }
        require(helper.writableDatabase.update("work_notes", values, "id = ? AND scope = ? AND kind = ?",
            arrayOf(note.id, note.scope.toString(), note.kind.name)) == 1) { "Nie znaleziono przypomnienia." }
        return load()
    }

    private fun currentNote(note: WorkNote): WorkNote = helper.writableDatabase.query("work_notes", null,
        "id = ? AND scope = ? AND kind = ?", arrayOf(note.id, note.scope.toString(), note.kind.name), null, null, null)
        .use { if (it.moveToFirst()) it.note() else throw IllegalArgumentException("Nie znaleziono notatki.") }

    @Synchronized fun scheduleReminder(note: WorkNote, reminder: NoteReminder, now: Long): List<WorkNote> {
        validateReminder(reminder, now)
        return changeReminder(note, now, resetComplete = true) { withNoteReminder(it.body, reminder) }
    }

    @Synchronized fun actReminder(note: WorkNote, token: String, action: ReminderAction, reader: String, now: Long): List<WorkNote> =
        changeReminder(note, now, action == ReminderAction.DONE) { changedReminderText(it.body, token, action, reader) }

    private fun changeReminder(note: WorkNote, now: Long, complete: Boolean = false, resetComplete: Boolean = false, change: (WorkNote) -> String): List<WorkNote> {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = currentNote(note)
            val values = ContentValues().apply {
                put("body", change(current)); put("updated_at", now)
                if (complete) put("completed", 1)
                if (resetComplete) put("completed", 0)
            }
            db.update("work_notes", values, "id = ?", arrayOf(current.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun delete(note: WorkNote): List<WorkNote> {
        require(helper.writableDatabase.delete("work_notes", "id = ? AND scope = ? AND kind = ?",
            arrayOf(note.id, note.scope.toString(), note.kind.name)) == 1) { "Nie znaleziono wpisu." }
        return load()
    }

    @Synchronized fun close() = helper.close()
}

private fun Cursor.note(): WorkNote {
    fun string(column: String) = getString(getColumnIndexOrThrow(column))
    fun long(column: String) = getLong(getColumnIndexOrThrow(column))
    return WorkNote(string("id"), long("scope").toInt(), NoteKind.valueOf(string("kind")), string("title"), string("body"),
        long("completed") != 0L, long("created_at"), long("updated_at"))
}

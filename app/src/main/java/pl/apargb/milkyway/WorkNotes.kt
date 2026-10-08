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

internal class WorkNotesDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, 1) {
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

internal class WorkNotesRepository(context: Context) {
    private val helper = WorkNotesDatabase(context)

    @Synchronized fun load(): List<WorkNote> = helper.readableDatabase.query("work_notes", null,
        null, null, null, null, "updated_at DESC, rowid DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.note()) }
    }

    @Synchronized fun save(id: String, scope: Int, kind: NoteKind, title: String, body: String, now: Long): List<WorkNote> {
        require(id.isNotBlank()) { "Brak identyfikatora wpisu." }
        require(scope in 0..3) { "Wybierz zmianę 1, 2 lub 3." }
        require(title.trim().isNotEmpty()) { "Podaj tytuł wpisu." }
        require(title.trim().length <= 120 && body.length <= 10000) { "Tytuł może mieć do 120 znaków, a treść do 10 000." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = db.query("work_notes", null, "id = ?", arrayOf(id), null, null, null)
                .use { if (it.moveToFirst()) it.note() else null }
            require(old == null || (old.scope == scope && old.kind == kind)) { "Wpis należy do innej zmiany lub sekcji." }
            val values = ContentValues().apply {
                put("id", id); put("scope", scope); put("kind", kind.name)
                put("title", title.trim()); put("body", body.trim())
                put("completed", if (old?.completed == true && kind == NoteKind.REMINDER) 1 else 0)
                put("created_at", old?.createdAt ?: now); put("updated_at", now)
            }
            if (old == null) db.insertOrThrow("work_notes", null, values)
            else db.update("work_notes", values, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun setCompleted(note: WorkNote, completed: Boolean, now: Long): List<WorkNote> {
        require(note.kind == NoteKind.REMINDER) { "Stan wykonania dotyczy przypomnień." }
        val values = ContentValues().apply { put("completed", if (completed) 1 else 0); put("updated_at", now) }
        require(helper.writableDatabase.update("work_notes", values, "id = ? AND scope = ? AND kind = ?",
            arrayOf(note.id, note.scope.toString(), note.kind.name)) == 1) { "Nie znaleziono przypomnienia." }
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

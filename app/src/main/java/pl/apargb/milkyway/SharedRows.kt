package pl.apargb.milkyway

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import java.util.Base64

internal enum class SharedDomain(val path: String, val tables: List<String>) {
    INVENTORY("inventory", listOf("tank_states", "movements")),
    NOTES("notes", listOf("work_notes")),
    PRODUCTION("production", listOf("production_queue", "production_completions", "production_product_notes", "production_rejects"));

    fun database(context: Context): SQLiteOpenHelper = when (this) {
        INVENTORY -> InventoryDatabase(context, null)
        NOTES -> WorkNotesDatabase(context, null)
        PRODUCTION -> ProductionQueueDatabase(context, null)
    }
}

internal fun sharedKey(id: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))

// SQLiteOpenHelper implements AutoCloseable only on newer Android releases.
internal inline fun <T> SQLiteOpenHelper.useDatabase(block: (SQLiteOpenHelper) -> T): T =
    try { block(this) } finally { close() }

/** Reuse the same validation and atomic SQLite operations against the latest shared state.
 * SQLite row IDs are retained so histories, top-up pagination and tied timestamps stay ordered.
 * Decimal quantities remain strings: Firebase's floating point numbers never round stock values.
 */
internal object SharedRows {
    @Suppress("UNCHECKED_CAST")
    fun tables(snapshot: Map<String, Any?>): Map<String, Map<String, Map<String, Any?>>> =
        (snapshot["tables"] as? Map<String, Map<String, Map<String, Any?>>>) ?: emptyMap()

    fun restore(helper: SQLiteOpenHelper, domain: SharedDomain, snapshot: Map<String, Any?>) {
        require(snapshot.isEmpty() || (snapshot["schema"] as? Number)?.toInt() == 1) { "Zaktualizuj aplikację: inna wersja wspólnej bazy." }
        val tables = tables(snapshot)
        require(tables.keys.all { it in domain.tables }) { "Nieznana tabela wspólnej bazy." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            domain.tables.forEach { table ->
                db.delete(table, null, null)
                val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                }
                tables[table].orEmpty().values.sortedBy { (it["_rowid"] as? Number)?.toLong() ?: 0L }.forEach { row ->
                    val id = row[if (table == "tank_states") "tank_id" else "id"] as? String
                    require(!id.isNullOrBlank()) { "Niepełny wpis wspólnej bazy." }
                    val values = ContentValues().apply {
                        row.forEach { (column, value) ->
                            if (column in columns || column == "_rowid") {
                                val key = if (column == "_rowid") "rowid" else column
                                when (value) {
                                    null -> putNull(key)
                                    is String -> put(key, value)
                                    is Number -> put(key, value.toLong())
                                    else -> error("Nieprawidłowa wartość kolumny $column")
                                }
                            }
                        }
                    }
                    db.insertOrThrow(table, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun capture(helper: SQLiteOpenHelper, domain: SharedDomain, previous: Map<String, Any?>,
                actor: Map<String, Any?>, now: Long): Map<String, Any?> {
        val previousTables = tables(previous)
        val tables = domain.tables.associateWith { table ->
            helper.readableDatabase.rawQuery("SELECT rowid AS _rowid, * FROM $table ORDER BY rowid", null).use { cursor ->
                buildMap<String, Map<String, Any?>> {
                    while (cursor.moveToNext()) {
                        val row = cursor.columnNames.mapIndexedNotNull { index, name ->
                            if (cursor.isNull(index)) null else name to when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> error("Nieobsługiwany typ wspólnej bazy")
                            }
                        }.toMap()
                        val key = sharedKey(row.getValue(if (table == "tank_states") "tank_id" else "id") as String)
                        val old = previousTables[table]?.get(key)
                        val audit = if (old?.filterKeys { it != "_actor" } == row) old["_actor"] else actor + ("at" to now)
                        put(key, row + ("_actor" to audit))
                    }
                }
            }
        }
        return mapOf("schema" to 1L, "revision" to (((previous["revision"] as? Number)?.toLong() ?: 0L) + 1),
            "updatedAt" to now, "actor" to actor, "tables" to tables)
    }

    fun validateLaboratoryChange(before: Map<String, Any?>, after: Map<String, Any?>, laboratory: Boolean) {
        if (laboratory) return
        val previous = tables(before)["tank_states"].orEmpty()
        tables(after)["tank_states"].orEmpty().forEach { (id, row) ->
            val marker = row["laboratory_at"]
            require(marker == null || marker == previous[id]?.get("laboratory_at")) { "To konto nie ma uprawnień Laboratorium." }
        }
    }
}

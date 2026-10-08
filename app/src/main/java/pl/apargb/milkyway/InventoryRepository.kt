package pl.apargb.milkyway

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.math.BigDecimal
import java.util.UUID

data class InventoryOverview(val states: Map<String, TankStatus>, val latest: Map<String, Movement>)

internal data class TopUpsPage(val events: List<Movement>, val nextBeforeRowId: Long?, val totalCount: Int)

internal class InventoryDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tank_states (
            tank_id TEXT PRIMARY KEY, litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT, filled_at INTEGER,
            evaporator TEXT, crystallizer INTEGER, material TEXT NOT NULL DEFAULT '', oil_type TEXT NOT NULL DEFAULT '', fat_percent TEXT, laboratory_at INTEGER
        )""")
        db.execSQL("""CREATE TABLE movements (
            id TEXT PRIMARY KEY, type TEXT NOT NULL, source_id TEXT, target_id TEXT NOT NULL,
            external_source TEXT, litres TEXT NOT NULL, previous_litres TEXT,
            brix TEXT, ph TEXT, sh TEXT, temperature TEXT,
            occurred_at INTEGER NOT NULL, note TEXT NOT NULL, shift INTEGER, evaporator TEXT, crystallizer INTEGER,
            material TEXT NOT NULL DEFAULT '', oil_type TEXT NOT NULL DEFAULT '', fat_percent TEXT, laboratory_at INTEGER
        )""")
        db.execSQL("CREATE INDEX movements_source ON movements(source_id)")
        db.execSQL("CREATE INDEX movements_target ON movements(target_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(newVersion == 6) { "Unsupported inventory schema migration: $oldVersion → $newVersion" }
        if (oldVersion < 2) {
            listOf("tank_states", "movements").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN evaporator TEXT")
                db.execSQL("ALTER TABLE $table ADD COLUMN crystallizer INTEGER")
            }
        }
        if (oldVersion < 3) {
            listOf("tank_states", "movements").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN material TEXT NOT NULL DEFAULT ''")
            }
        }
        if (oldVersion < 4) {
            listOf("tank_states", "movements").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN oil_type TEXT NOT NULL DEFAULT ''")
            }
        }
        if (oldVersion < 5) {
            listOf("tank_states", "movements").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN fat_percent TEXT")
            }
        }
        if (oldVersion < 6) {
            listOf("tank_states", "movements").forEach { table ->
                db.execSQL("ALTER TABLE $table ADD COLUMN laboratory_at INTEGER")
            }
        }
    }

    companion object { const val NAME = "inventory.db" }
}

internal class InventoryRepository(context: Context, private val helper: InventoryDatabase = InventoryDatabase(context)) {
    @Synchronized fun load(): InventoryOverview {
        val db = helper.readableDatabase
        val states = readStates(db)
        val latest = buildMap {
            AppContent.tanks.forEach { tank -> history(tank.id, 1).firstOrNull()?.let { put(tank.id, it) } }
        }
        return InventoryOverview(states, latest)
    }

    @Synchronized fun history(tankId: String, limit: Int = 100): List<Movement> {
        return helper.readableDatabase.query("movements", null, "source_id = ? OR target_id = ?",
            arrayOf(tankId, tankId), null, null, "rowid DESC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.movement()) }
        }
    }

    /** Incoming material only; the insertion cursor makes every older entry reachable. */
    @Synchronized fun topUps(tankId: String, beforeRowId: Long? = null): TopUpsPage {
        val db = helper.readableDatabase
        val filter = "target_id = ? AND type IN ('RECEIPT', 'TRANSFER')"
        val total = db.rawQuery("SELECT COUNT(*) FROM movements WHERE $filter", arrayOf(tankId))
            .use { it.moveToFirst(); it.getInt(0) }
        val selection = filter + if (beforeRowId == null) "" else " AND rowid < ?"
        val args = if (beforeRowId == null) arrayOf(tankId) else arrayOf(tankId, beforeRowId.toString())
        val rows = db.query("movements", arrayOf("*", "rowid AS page_rowid"), selection,
            args, null, null, "rowid DESC", "51").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(cursor.getColumnIndexOrThrow("page_rowid")) to cursor.movement())
            }
        }
        val page = rows.take(50)
        return TopUpsPage(page.map { it.second }, if (rows.size > 50) page.last().first else null, total)
    }

    /** Both tank updates and their audit entry commit together, or all roll back. */
    @Synchronized fun apply(requestId: String = UUID.randomUUID().toString(), operation: (InventoryRules) -> InventoryChange): InventoryOverview {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val alreadySaved = db.query("movements", arrayOf("id"), "id = ?", arrayOf(requestId), null, null, null)
                .use { it.moveToFirst() }
            if (!alreadySaved) {
                val change = operation(InventoryRules(AppContent.tanks, readStates(db)))
                change.states.forEach { (id, state) ->
                    val values = state.measurements.values().apply {
                        put("tank_id", id)
                        put("litres", state.litres?.toPlainString())
                        put("filled_at", state.filledAt)
                        put("evaporator", state.routing.evaporator)
                        put("crystallizer", state.routing.crystallizer)
                        put("material", state.material)
                        put("oil_type", state.oilType)
                        put("laboratory_at", state.laboratoryMeasuredAt)
                    }
                    db.replaceOrThrow("tank_states", null, values)
                }
                val event = change.movement.copy(id = requestId)
                val values = event.measurements.values().apply {
                    put("id", event.id)
                    put("type", event.type.name)
                    put("source_id", event.sourceId)
                    put("target_id", event.targetId)
                    put("external_source", event.externalSource)
                    put("litres", event.litres.toPlainString())
                    put("previous_litres", event.previousLitres?.toPlainString())
                    put("occurred_at", event.occurredAt)
                    put("note", event.note)
                    put("shift", event.shift)
                    put("evaporator", event.routing.evaporator)
                    put("crystallizer", event.routing.crystallizer)
                    put("material", event.material)
                    put("oil_type", event.oilType)
                    put("laboratory_at", event.laboratoryMeasuredAt)
                }
                db.insertOrThrow("movements", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return load()
    }

    @Synchronized fun close() = helper.close()

    private fun readStates(db: SQLiteDatabase): Map<String, TankStatus> =
        db.query("tank_states", null, null, null, null, null, null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.string("tank_id")!!,
                    TankStatus(cursor.decimal("litres"), cursor.measurements(), cursor.longOrNull("filled_at"), cursor.routing(),
                        cursor.string("material")!!, cursor.string("oil_type")!!, cursor.longOrNull("laboratory_at")))
            }
        }
}

private fun Measurements.values() = ContentValues().apply {
    put("brix", brix?.toPlainString())
    put("fat_percent", fatPercent?.toPlainString())
    put("ph", ph?.toPlainString())
    put("sh", sh?.toPlainString())
    put("temperature", temperature?.toPlainString())
}

private fun Cursor.string(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
private fun Cursor.decimal(column: String): BigDecimal? = string(column)?.toBigDecimal()
private fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.measurements() = Measurements(decimal("brix"), decimal("ph"), decimal("sh"), decimal("temperature"), decimal("fat_percent"))
private fun Cursor.routing() = TankRouting(string("evaporator"), longOrNull("crystallizer")?.toInt())
private fun Cursor.movement() = Movement(
    id = string("id")!!, type = MovementType.valueOf(string("type")!!), sourceId = string("source_id"),
    targetId = string("target_id")!!, externalSource = string("external_source"), litres = decimal("litres")!!,
    previousLitres = decimal("previous_litres"), measurements = measurements(), occurredAt = longOrNull("occurred_at")!!,
    note = string("note")!!, shift = longOrNull("shift")?.toInt(), routing = routing(), material = string("material")!!,
    oilType = string("oil_type")!!, laboratoryMeasuredAt = longOrNull("laboratory_at")
)

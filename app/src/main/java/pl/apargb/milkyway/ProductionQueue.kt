package pl.apargb.milkyway

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.LocalTime
import java.math.BigDecimal

enum class ProductionQuantityUnit(val label: String) { KG("kg"), LITRES("l") }

enum class ProductionLine(val title: String, val tone: Department) {
    BUTTER("Masłownia", Department.Butter), POWDER("Proszkownia", Department.Powder), UHT("UHT", Department.Processing);
    val defaultUnit: ProductionQuantityUnit get() = if (this == UHT) ProductionQuantityUnit.LITRES else ProductionQuantityUnit.KG
}

data class ProductionQueueEntry(val id: String, val line: ProductionLine, val date: LocalDate,
                                val position: Long, val title: String, val description: String,
                                val createdAt: Long, val updatedAt: Long,
                                val plannedAmount: BigDecimal? = null, val producedAmount: BigDecimal = BigDecimal.ZERO,
                                val unit: ProductionQuantityUnit = line.defaultUnit,
                                val pendingOrder: Boolean = false, val scheduledTime: LocalTime? = null) {
    // Pending orders retain their receipt day in date; only scheduled entries belong to a daily plan.
    val productionDate: LocalDate? get() = if (pendingOrder) null else date
    val remainingAmount: BigDecimal? get() = plannedAmount?.subtract(producedAmount)?.max(BigDecimal.ZERO)
    val excessAmount: BigDecimal get() = plannedAmount?.let { (producedAmount - it).max(BigDecimal.ZERO) } ?: BigDecimal.ZERO
    val completed: Boolean get() = !pendingOrder && remainingAmount?.signum() == 0
}

data class ProductionCompletion(val id: String, val entryId: String, val amount: BigDecimal,
                                val producedOn: LocalDate, val occurredAt: Long,
                                val warehouseRemovedAt: Long? = null, val warehouseRemovalId: String? = null)
enum class ProductNoteStage(val title: String) { ORDER("Zamówienie"), PRODUCTION("Produkcja"), COMPLETED("Wyprodukowano") }
data class ProductNote(val id: String, val entryId: String, val stage: ProductNoteStage, val text: String, val createdAt: Long)
data class RejectedGoods(val id: String, val line: ProductionLine, val date: LocalDate, val description: String,
                         val kilograms: BigDecimal, val createdAt: Long)
internal data class ProductionQueueSnapshot(val entries: List<ProductionQueueEntry>, val completions: List<ProductionCompletion>,
                                            val productNotes: List<ProductNote>, val rejectedGoods: List<RejectedGoods>)

internal class ProductionQueueDatabase(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE production_queue (
            id TEXT PRIMARY KEY, line TEXT NOT NULL CHECK(line IN ('BUTTER', 'POWDER', 'UHT')),
            plan_date TEXT NOT NULL, position INTEGER NOT NULL CHECK(position >= 0),
            title TEXT NOT NULL, description TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            planned_amount TEXT, produced_amount TEXT NOT NULL DEFAULT '0', unit TEXT NOT NULL DEFAULT 'KG',
            pending_order INTEGER NOT NULL DEFAULT 0 CHECK(pending_order IN (0, 1)), planned_time TEXT,
            UNIQUE(line, plan_date, position)
        )""")
        createCompletions(db)
        createProductNotes(db)
        createRejectedGoods(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..5 && newVersion == 6)
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE production_queue ADD COLUMN planned_amount TEXT")
            db.execSQL("ALTER TABLE production_queue ADD COLUMN produced_amount TEXT NOT NULL DEFAULT '0'")
            db.execSQL("ALTER TABLE production_queue ADD COLUMN unit TEXT NOT NULL DEFAULT 'KG'")
            db.execSQL("UPDATE production_queue SET unit = 'LITRES' WHERE line = 'UHT'")
            createCompletions(db)
        }
        // Existing plans stay scheduled with an unknown hour; never invent a start time.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE production_queue ADD COLUMN pending_order INTEGER NOT NULL DEFAULT 0 CHECK(pending_order IN (0, 1))")
            db.execSQL("ALTER TABLE production_queue ADD COLUMN planned_time TEXT")
        }
        if (oldVersion < 4) createProductNotes(db)
        if (oldVersion < 5) createRejectedGoods(db)
        if (oldVersion < 6) {
            val columns = db.rawQuery("PRAGMA table_info(production_completions)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            if ("warehouse_removed_at" !in columns) db.execSQL("ALTER TABLE production_completions ADD COLUMN warehouse_removed_at INTEGER")
            if ("warehouse_removal_id" !in columns) db.execSQL("ALTER TABLE production_completions ADD COLUMN warehouse_removal_id TEXT")
        }
    }

    private fun createRejectedGoods(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE production_rejects (
            id TEXT PRIMARY KEY, line TEXT NOT NULL CHECK(line IN ('BUTTER', 'POWDER', 'UHT')),
            record_date TEXT NOT NULL, description TEXT NOT NULL, kilograms TEXT NOT NULL, created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX production_rejects_day ON production_rejects(record_date, line)")
    }

    private fun createProductNotes(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE production_product_notes (
            id TEXT PRIMARY KEY, entry_id TEXT NOT NULL, stage TEXT NOT NULL CHECK(stage IN ('ORDER', 'PRODUCTION', 'COMPLETED')),
            note TEXT NOT NULL, created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX production_product_notes_entry ON production_product_notes(entry_id)")
    }

    private fun createCompletions(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE production_completions (
            id TEXT PRIMARY KEY, entry_id TEXT NOT NULL, amount TEXT NOT NULL,
            produced_on TEXT NOT NULL, occurred_at INTEGER NOT NULL, warehouse_removed_at INTEGER, warehouse_removal_id TEXT
        )""")
        db.execSQL("CREATE INDEX production_completions_entry ON production_completions(entry_id)")
    }

    companion object { const val NAME = "production_queue.db" }
}

internal class ProductionQueueRepository(context: Context,
                                         private val helper: ProductionQueueDatabase = ProductionQueueDatabase(context),
                                         private val today: () -> LocalDate = { LocalDate.now() }) {
    @Synchronized fun load(): List<ProductionQueueEntry> = helper.readableDatabase.query("production_queue", null,
        null, null, null, null, "line, plan_date, position").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.entry()) }
    }

    @Synchronized fun snapshot(): ProductionQueueSnapshot {
        val db = helper.readableDatabase
        db.beginTransaction()
        try {
            val entries = load()
            val completions = db.query("production_completions", null, null, null, null, null, "occurred_at DESC, rowid DESC").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                        add(ProductionCompletion(string("id"), string("entry_id"), string("amount").toBigDecimal(),
                            LocalDate.parse(string("produced_on")), cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")),
                            cursor.getColumnIndexOrThrow("warehouse_removed_at").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
                            cursor.getColumnIndexOrThrow("warehouse_removal_id").let { if (cursor.isNull(it)) null else cursor.getString(it) }))
                    }
                }
            }
            val notes = db.query("production_product_notes", null, null, null, null, null, "created_at DESC, rowid DESC").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                        add(ProductNote(string("id"), string("entry_id"), ProductNoteStage.valueOf(string("stage")),
                            string("note"), cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))))
                    }
                }
            }
            val rejected = db.query("production_rejects", null, null, null, null, null, "created_at DESC, rowid DESC").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                        add(RejectedGoods(string("id"), ProductionLine.valueOf(string("line")), LocalDate.parse(string("record_date")),
                            string("description"), string("kilograms").toBigDecimal(), cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))))
                    }
                }
            }
            db.setTransactionSuccessful()
            return ProductionQueueSnapshot(entries, completions, notes, rejected)
        } finally { db.endTransaction() }
    }

    @Synchronized fun save(id: String, line: ProductionLine, date: LocalDate, title: String,
                           description: String, now: Long, plannedAmount: BigDecimal? = null,
                           pendingOrder: Boolean? = null, scheduledTime: LocalTime? = null): List<ProductionQueueEntry> {
        require(id.isNotBlank()) { "Brak identyfikatora produkcji." }
        require(title.trim().isNotEmpty()) { "Podaj nazwę produkcji lub produktu." }
        require(title.trim().length <= 120 && productionDescription(description, line).length <= 10000) { "Nazwa może mieć do 120 znaków, a opis do 10 000." }
        require(date.year in 1900..2100) { "Wybierz datę w zakresie 1900–2100." }
        plannedAmount?.let(::validateProductionAmount)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = find(db, id)
            require(old == null || old.line == line) { "Pozycja należy do innego działu." }
            val produced = old?.producedAmount ?: BigDecimal.ZERO
            val savedPlan = plannedAmount ?: old?.plannedAmount
            require(savedPlan == null && (old?.producedAmount?.signum() ?: 0) == 0 ||
                savedPlan != null && savedPlan >= (old?.plannedAmount?.min(old.producedAmount) ?: old?.producedAmount ?: BigDecimal.ZERO)) {
                "Nie zmniejszaj planu poniżej ilości już zrealizowanego zamówienia."
            }
            val pending = pendingOrder ?: old?.pendingOrder ?: false
            require(!pending || produced.signum() == 0 ||
                savedPlan != null && savedPlan > produced) { "Ten produkt jest już wyprodukowany w całości." }
            val time = if (pending) null else scheduledTime ?: old?.scheduledTime
            require(time == null || time.second == 0 && time.nano == 0) { "Godzinę podaj z dokładnością do minuty." }
            val position = if (old?.date == date && old.pendingOrder == pending && old.scheduledTime == time) old.position else nextPosition(db, line, date)
            val values = ContentValues().apply {
                put("id", id); put("line", line.name); put("plan_date", date.toString()); put("position", position)
                put("title", title.trim()); put("description", description.trim())
                put("created_at", old?.createdAt ?: now); put("updated_at", now)
                put("planned_amount", savedPlan?.toPlainString())
                put("produced_amount", (old?.producedAmount ?: BigDecimal.ZERO).toPlainString())
                put("unit", (old?.unit ?: line.defaultUnit).name)
                put("pending_order", if (pending) 1 else 0); put("planned_time", time?.toString())
            }
            if (old == null) db.insertOrThrow("production_queue", null, values)
            else db.update("production_queue", values, "id = ?", arrayOf(id))
            if (old != null && !pending) updateAutomaticProductionCode(db, id, line, date, old.pendingOrder, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    /** Move the same order into a concrete local day/time, checking the source is still current. */
    @Synchronized fun schedule(entry: ProductionQueueEntry, date: LocalDate, time: LocalTime, now: Long): List<ProductionQueueEntry> {
        require(date.year in 1900..2100) { "Wybierz datę w zakresie 1900–2100." }
        require(time.second == 0 && time.nano == 0) { "Godzinę podaj z dokładnością do minuty." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = find(db, entry.id) ?: throw IllegalArgumentException("Nie znaleziono zamówienia.")
            require(current.line == entry.line && current.date == entry.date &&
                current.pendingOrder == entry.pendingOrder && current.scheduledTime == entry.scheduledTime) {
                "Termin tej pozycji został już zmieniony. Odśwież plan."
            }
            db.update("production_queue", ContentValues().apply {
                put("plan_date", date.toString()); put("planned_time", time.toString()); put("pending_order", 0)
                put("position", nextPosition(db, current.line, date)); put("updated_at", now)
            }, "id = ?", arrayOf(current.id))
            updateAutomaticProductionCode(db, current.id, current.line, date, current.pendingOrder, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    /** Write the code in the same transaction as scheduling, retaining any explicit manual override. */
    private fun updateAutomaticProductionCode(db: SQLiteDatabase, entryId: String, line: ProductionLine,
                                               date: LocalDate, enteringProduction: Boolean, now: Long) {
        if (line != ProductionLine.POWDER) return
        val latest = db.query("production_product_notes", arrayOf("id", "note"), "entry_id = ?", arrayOf(entryId),
            null, null, "created_at DESC, rowid DESC").use { cursor ->
            var found: ProductNote? = null
            while (cursor.moveToNext()) {
                val note = ProductNote(cursor.getString(0), entryId, ProductNoteStage.PRODUCTION, cursor.getString(1), now)
                if (note.isProductionCode()) { found = note; break }
            }
            found
        }
        if (latest == null && !enteringProduction || latest != null && !latest.isAutomaticProductionCode()) return
        val code = productionDateCode(date)
        if (latest != null && productionCode(listOf(latest), entryId) == code) return
        db.insertOrThrow("production_product_notes", null, ContentValues().apply {
            put("id", AUTOMATIC_PRODUCTION_CODE_PREFIX + java.util.UUID.randomUUID().toString())
            put("entry_id", entryId); put("stage", ProductNoteStage.PRODUCTION.name)
            put("note", "Kod produkcji: $code"); put("created_at", now)
        })
    }

    /** Remove the remaining work from the daily plan, retaining the product and its execution history. */
    @Synchronized fun returnToPending(entry: ProductionQueueEntry, now: Long): List<ProductionQueueEntry> {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = find(db, entry.id) ?: throw IllegalArgumentException("Nie znaleziono produktu.")
            require(current.line == entry.line && current.date == entry.date &&
                current.pendingOrder == entry.pendingOrder && current.scheduledTime == entry.scheduledTime) {
                "Termin tej pozycji został już zmieniony. Odśwież plan."
            }
            require(!current.completed) { "Ten produkt jest już wyprodukowany w całości." }
            if (!current.pendingOrder) db.update("production_queue", ContentValues().apply {
                put("pending_order", 1); putNull("planned_time")
                put("position", nextPosition(db, current.line, current.date)); put("updated_at", now)
            }, "id = ?", arrayOf(current.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    /** Resolve the neighbour from the current database order, then swap both positions atomically. */
    @Synchronized fun move(id: String, line: ProductionLine, date: LocalDate, direction: Int,
                           now: Long): List<ProductionQueueEntry> {
        require(direction == -1 || direction == 1) { "Nieprawidłowy kierunek przesunięcia." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = find(db, id) ?: throw IllegalArgumentException("Nie znaleziono produkcji.")
            require(current.line == line && current.date == date) { "Pozycja nie należy już do tej kolejki. Odśwież plan." }
            require(!current.completed) { "Ta pozycja jest już w katalogu Wyprodukowano." }
            val before = direction == -1
            val neighbour = db.query("production_queue", null,
                "line = ? AND plan_date = ? AND pending_order = ? AND ${if (current.scheduledTime == null) "planned_time IS NULL" else "planned_time = ?"} AND position ${if (before) "<" else ">"} ?",
                buildList { add(line.name); add(date.toString()); add(if (current.pendingOrder) "1" else "0")
                    current.scheduledTime?.let { add(it.toString()) }; add(current.position.toString()) }.toTypedArray(), null, null,
                "position ${if (before) "DESC" else "ASC"}").use { cursor ->
                    var result: ProductionQueueEntry? = null
                    while (cursor.moveToNext()) {
                        val candidate = cursor.entry()
                        if (!candidate.completed) { result = candidate; break }
                    }
                    result
                }
            if (neighbour != null) {
                updatePosition(db, current.id, nextPosition(db, line, date), now)
                updatePosition(db, neighbour.id, current.position, now)
                updatePosition(db, current.id, neighbour.position, now)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    /** Place an active product next to a drop target within the same time group. */
    @Synchronized fun reorder(entry: ProductionQueueEntry, target: ProductionQueueEntry, after: Boolean, now: Long): List<ProductionQueueEntry> {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = find(db, entry.id) ?: throw IllegalArgumentException("Nie znaleziono produkcji.")
            val destination = find(db, target.id) ?: throw IllegalArgumentException("Nie znaleziono miejsca w kolejce.")
            require(current.line == entry.line && current.date == entry.date && current.scheduledTime == entry.scheduledTime &&
                !current.pendingOrder && !current.completed && destination.line == current.line && destination.date == current.date &&
                destination.scheduledTime == current.scheduledTime && !destination.pendingOrder && !destination.completed) {
                "Kolejka została zmieniona. Odśwież plan."
            }
            val peers = db.query("production_queue", null,
                "line = ? AND plan_date = ? AND pending_order = 0 AND ${if (current.scheduledTime == null) "planned_time IS NULL" else "planned_time = ?"}",
                buildList { add(current.line.name); add(current.date.toString()); current.scheduledTime?.let { add(it.toString()) } }.toTypedArray(),
                null, null, "position").use { cursor -> buildList { while (cursor.moveToNext()) cursor.entry().takeUnless { it.completed }?.let(::add) } }
            if (current.id != destination.id) {
                val ordered = peers.filter { it.id != current.id }.toMutableList()
                ordered.add(ordered.indexOfFirst { it.id == destination.id } + if (after) 1 else 0, current)
                val temporary = nextPosition(db, current.line, current.date)
                ordered.forEachIndexed { index, value -> updatePosition(db, value.id, temporary + index, now) }
                ordered.forEachIndexed { index, value -> updatePosition(db, value.id, peers[index].position, now) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    /** A unique receipt ID prevents retries from adding the same partial production twice. */
    @Synchronized fun recordProduction(requestId: String, id: String, line: ProductionLine, date: LocalDate,
                                      amount: BigDecimal?, now: Long): List<ProductionQueueEntry> {
        require(requestId.isNotBlank()) { "Brak identyfikatora zapisu." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val recordedFor = db.query("production_completions", arrayOf("entry_id"), "id = ?", arrayOf(requestId), null, null, null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (recordedFor != null) {
                require(recordedFor == id) { "Ten zapis należy do innej pozycji." }
            } else {
                val current = find(db, id) ?: throw IllegalArgumentException("Nie znaleziono produkcji.")
                require(current.line == line && current.date == date) { "Pozycja nie należy już do wybranego dnia i działu." }
                require(!current.pendingOrder) { "Najpierw zaplanuj zamówienie w produkcji." }
                require(date == today()) { "Wykonanie można zapisać tylko w kolejce bieżącego dnia." }
                val remaining = current.remainingAmount ?: throw IllegalArgumentException("Najpierw uzupełnij planowaną ilość.")
                require(remaining.signum() > 0) { "Ta pozycja jest już w katalogu Wyprodukowano." }
                val producedNow = amount ?: remaining
                validateProductionAmount(producedNow)
                db.update("production_queue", ContentValues().apply {
                    put("produced_amount", (current.producedAmount + producedNow).toPlainString()); put("updated_at", now)
                }, "id = ?", arrayOf(id))
                db.insertOrThrow("production_completions", null, ContentValues().apply {
                    put("id", requestId); put("entry_id", id); put("amount", producedNow.toPlainString())
                    put("produced_on", date.toString()); put("occurred_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun addProductNote(requestId: String, entry: ProductionQueueEntry, stage: ProductNoteStage,
                                     text: String, now: Long, author: String? = null): List<ProductionQueueEntry> {
        require(requestId.isNotBlank()) { "Brak identyfikatora notatki." }
        require(noteBody(text).trim().isNotEmpty() && noteBody(text).trim().length <= 4000) { "Wpisz notatkę (do 4000 znaków)." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val old = db.query("production_product_notes", arrayOf("entry_id"), "id = ?", arrayOf(requestId), null, null, null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (old != null) require(old == entry.id) { "Ten zapis należy do innego produktu." }
            else {
                noteReminder(text)?.let { validateReminder(it, now) }
                val current = find(db, entry.id) ?: throw IllegalArgumentException("Nie znaleziono produktu.")
                require(current.line == entry.line) { "Produkt należy do innego działu." }
                require(when (stage) {
                    ProductNoteStage.ORDER -> current.pendingOrder
                    ProductNoteStage.PRODUCTION -> !current.pendingOrder && !current.completed
                    ProductNoteStage.COMPLETED -> current.producedAmount.signum() > 0
                }) { "Etap produktu został zmieniony. Otwórz notatki ponownie." }
                db.insertOrThrow("production_product_notes", null, ContentValues().apply {
                    put("id", requestId); put("entry_id", current.id); put("stage", stage.name)
                    put("note", withNoteAuthor(text, author)); put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun setProductNoteImportant(note: ProductNote, important: Boolean): List<ProductionQueueEntry> {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = db.query("production_product_notes", arrayOf("entry_id", "note"), "id = ?", arrayOf(note.id), null, null, null)
                .use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
                ?: throw IllegalArgumentException("Nie znaleziono notatki.")
            require(current.first == note.entryId && !note.copy(text = current.second).isProductionCode()) { "To nie jest notatka produktu." }
            db.update("production_product_notes", ContentValues().apply { put("note", withImportantText(current.second, important)) },
                "id = ?", arrayOf(note.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun scheduleProductReminder(note: ProductNote, reminder: NoteReminder, now: Long): List<ProductionQueueEntry> {
        validateReminder(reminder, now)
        return changeProductReminder(note) { withNoteReminder(withCompletedText(it, false), reminder) }
    }

    @Synchronized fun setProductNoteCompleted(note: ProductNote, completed: Boolean): List<ProductionQueueEntry> =
        changeProductReminder(note) { withCompletedText(it, completed) }

    @Synchronized fun actProductReminder(note: ProductNote, token: String, action: ReminderAction, reader: String): List<ProductionQueueEntry> =
        changeProductReminder(note) { changedReminderText(it, token, action, reader) }

    private fun changeProductReminder(note: ProductNote, change: (String) -> String): List<ProductionQueueEntry> {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = db.query("production_product_notes", arrayOf("entry_id", "note"), "id = ?", arrayOf(note.id), null, null, null)
                .use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
                ?: throw IllegalArgumentException("Nie znaleziono notatki.")
            require(current.first == note.entryId && !note.copy(text = current.second).isProductionCode()) { "To nie jest notatka produktu." }
            db.update("production_product_notes", ContentValues().apply { put("note", change(current.second)) }, "id = ?", arrayOf(note.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun setProductionCode(requestId: String, entry: ProductionQueueEntry, code: String, now: Long): List<ProductionQueueEntry> {
        require(code.isEmpty() || code.matches(Regex("[0-9]{3}"))) { "Kod produkcji musi mieć dokładnie 3 cyfry albo być pusty." }
        return addProductNote(requestId, entry, if (entry.pendingOrder) ProductNoteStage.ORDER else ProductNoteStage.PRODUCTION,
            "Kod produkcji: ${code.ifEmpty { "—" }}", now)
    }

    @Synchronized fun delete(entry: ProductionQueueEntry, pin: String): List<ProductionQueueEntry> {
        require(warehousePinMatches(pin)) { "Nieprawidłowy PIN." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = find(db, entry.id) ?: throw IllegalArgumentException("Nie znaleziono produktu.")
            require(current.producedAmount.signum() == 0) { "Wyprodukowany towar usuń z widoku magazynu po podaniu PIN-u." }
            require(db.delete("production_queue", "id = ? AND line = ? AND plan_date = ?",
                arrayOf(entry.id, entry.line.name, entry.date.toString())) == 1) { "Nie znaleziono produkcji w tym dniu i dziale." }
            db.delete("production_completions", "entry_id = ?", arrayOf(entry.id))
            db.delete("production_product_notes", "entry_id = ?", arrayOf(entry.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun addRejectedGoods(id: String, line: ProductionLine, date: LocalDate, description: String,
                                       kilograms: BigDecimal, now: Long): List<ProductionQueueEntry> {
        require(id.isNotBlank()) { "Brak identyfikatora wpisu." }
        require(date.year in 1900..2100) { "Wybierz datę w zakresie 1900–2100." }
        val text = description.trim()
        require(text.isNotEmpty() && text.length <= 4000) { "Podaj opis wybrakowanego towaru (do 4000 znaków)." }
        validateProductionAmount(kilograms)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.query("production_rejects", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                    require(string("line") == line.name && string("record_date") == date.toString() &&
                        string("description") == text && string("kilograms").toBigDecimal().compareTo(kilograms) == 0) {
                        "Ten identyfikator należy do innego wpisu."
                    }
                } else db.insertOrThrow("production_rejects", null, ContentValues().apply {
                    put("id", id); put("line", line.name); put("record_date", date.toString())
                    put("description", text); put("kilograms", kilograms.toPlainString()); put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    /** Remove this day's warehouse record, retaining the production ledger and remaining queue. */
    @Synchronized fun removeFromWarehouse(requestId: String, entryId: String, date: LocalDate,
                                         receiptIds: Set<String>, pin: String, now: Long): List<ProductionQueueEntry> {
        require(warehousePinMatches(pin)) { "Nieprawidłowy PIN." }
        require(requestId.isNotBlank() && receiptIds.isNotEmpty()) { "Wybierz wpis magazynu do usunięcia." }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val previous = db.query("production_completions", arrayOf("id", "entry_id", "produced_on"),
                "warehouse_removal_id = ?", arrayOf(requestId), null, null, null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        require(cursor.getString(1) == entryId && cursor.getString(2) == date.toString()) { "Identyfikator usunięcia należy do innego wpisu." }
                        add(cursor.getString(0))
                    }
                }
            }
            if (previous.isNotEmpty()) require(previous == receiptIds) { "Identyfikator usunięcia należy do innego wpisu." }
            else {
                val current = db.query("production_completions", arrayOf("id"),
                    "entry_id = ? AND produced_on = ? AND warehouse_removed_at IS NULL", arrayOf(entryId, date.toString()),
                    null, null, null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                require(current == receiptIds) { "Wpis magazynu został zmieniony. Otwórz usuwanie ponownie." }
                db.update("production_completions", ContentValues().apply {
                    put("warehouse_removed_at", now); put("warehouse_removal_id", requestId)
                }, "entry_id = ? AND produced_on = ? AND warehouse_removed_at IS NULL", arrayOf(entryId, date.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return load()
    }

    @Synchronized fun deleteRejectedGoods(id: String, pin: String): List<ProductionQueueEntry> {
        require(warehousePinMatches(pin)) { "Nieprawidłowy PIN." }
        require(helper.writableDatabase.delete("production_rejects", "id = ?", arrayOf(id)) == 1) { "Nie znaleziono wpisu wybrakowanego towaru." }
        return load()
    }

    @Synchronized fun close() = helper.close()

    private fun find(db: SQLiteDatabase, id: String): ProductionQueueEntry? =
        db.query("production_queue", null, "id = ?", arrayOf(id), null, null, null)
            .use { if (it.moveToFirst()) it.entry() else null }

    private fun nextPosition(db: SQLiteDatabase, line: ProductionLine, date: LocalDate): Long =
        db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM production_queue WHERE line = ? AND plan_date = ?",
            arrayOf(line.name, date.toString())).use { it.moveToFirst(); it.getLong(0) }

    private fun updatePosition(db: SQLiteDatabase, id: String, position: Long, now: Long) {
        db.update("production_queue", ContentValues().apply { put("position", position); put("updated_at", now) },
            "id = ?", arrayOf(id))
    }
}

private fun Cursor.entry(): ProductionQueueEntry {
    fun string(column: String) = getString(getColumnIndexOrThrow(column))
    fun long(column: String) = getLong(getColumnIndexOrThrow(column))
    return ProductionQueueEntry(string("id"), ProductionLine.valueOf(string("line")), LocalDate.parse(string("plan_date")),
        long("position"), string("title"), string("description"), long("created_at"), long("updated_at"),
        getColumnIndexOrThrow("planned_amount").let { if (isNull(it)) null else getString(it).toBigDecimal() },
        string("produced_amount").toBigDecimal(), ProductionQuantityUnit.valueOf(string("unit")), long("pending_order") == 1L,
        getColumnIndexOrThrow("planned_time").let { if (isNull(it)) null else LocalTime.parse(getString(it)) })
}

internal fun validateProductionAmount(amount: BigDecimal) {
    require(amount.signum() > 0) { "Ilość musi być większa od zera." }
    require(amount.precision() <= 24 && amount.stripTrailingZeros().scale() <= 3) { "Ilość podaj z dokładnością do 0,001 jednostki (do 24 cyfr)." }
}

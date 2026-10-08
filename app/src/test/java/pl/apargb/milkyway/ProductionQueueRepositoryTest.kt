package pl.apargb.milkyway

import android.content.Context
import android.database.sqlite.SQLiteException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProductionQueueRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ProductionQueueDatabase
    private lateinit var repository: ProductionQueueRepository
    private val today = LocalDate.of(2026, 10, 7)
    private val tomorrow = today.plusDays(1)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(ProductionQueueDatabase.NAME)
        database = ProductionQueueDatabase(context)
        repository = ProductionQueueRepository(context, database, today = { today })
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(ProductionQueueDatabase.NAME) }

    private fun save(id: String, date: LocalDate = today, line: ProductionLine = ProductionLine.BUTTER, now: Long = 100L) =
        repository.save(id, line, date, "Produkcja $id", "Opis $id", now)

    private fun ids(date: LocalDate = today, line: ProductionLine = ProductionLine.BUTTER) =
        repository.load().filter { it.line == line && it.date == date }.map { it.id }

    @Test fun plansAreSeparatedByDayAndLineAndSurviveReopening() {
        save("butter-today")
        save("butter-tomorrow", tomorrow)
        save("powder-today", line = ProductionLine.POWDER)
        save("uht-today", line = ProductionLine.UHT)
        save("uht-year-end", LocalDate.of(2026, 12, 31), ProductionLine.UHT)
        repository.close()
        repository = ProductionQueueRepository(context)
        assertEquals(listOf("butter-today"), ids())
        assertEquals(listOf("butter-tomorrow"), ids(tomorrow))
        assertEquals(listOf("powder-today"), ids(line = ProductionLine.POWDER))
        assertEquals(listOf("uht-today"), ids(line = ProductionLine.UHT))
        assertEquals(listOf("uht-year-end"), ids(LocalDate.of(2026, 12, 31), ProductionLine.UHT))
        assertTrue(ids(today.plusDays(2)).isEmpty())
    }

    @Test fun editingPreservesOrderAndReschedulingAppendsToDestinationDayWithoutDuplicate() {
        save("a", now = 300L); save("b", now = 200L); save("c", now = 100L)
        save("future", tomorrow)
        repository.save("b", ProductionLine.BUTTER, today, "Poprawiona produkcja", "Nowy opis", 500L)
        assertEquals(listOf("a", "b", "c"), ids())
        val edited = repository.load().single { it.id == "b" }
        assertEquals(200L, edited.createdAt)
        assertEquals(500L, edited.updatedAt)
        val reschedule = { repository.save("b", ProductionLine.BUTTER, tomorrow, "Poprawiona produkcja", "Nowy opis", 600L) }
        reschedule(); reschedule()
        assertEquals(listOf("a", "c"), ids())
        assertEquals(listOf("future", "b"), ids(tomorrow))
        assertEquals(4, repository.load().size)
        assertEquals(200L, repository.load().single { it.id == "b" }.createdAt)
    }

    @Test fun movingUsesCurrentOrderHandlesGapsAndBoundariesAndDoesNotChangeOtherPlans() {
        listOf("a", "b", "c", "d").forEach { save(it) }
        save("tomorrow", tomorrow); save("powder", line = ProductionLine.POWDER)
        repository.delete(repository.load().single { it.id == "b" })
        repository.move("d", ProductionLine.BUTTER, today, -1, 200L)
        repository.move("d", ProductionLine.BUTTER, today, -1, 300L)
        assertEquals(listOf("d", "a", "c"), ids())
        repository.move("d", ProductionLine.BUTTER, today, -1, 400L)
        assertEquals(listOf("d", "a", "c"), ids())
        repository.move("d", ProductionLine.BUTTER, today, 1, 500L)
        repository.close()
        repository = ProductionQueueRepository(context)
        assertEquals(listOf("a", "d", "c"), ids())
        assertEquals(listOf("tomorrow"), ids(tomorrow))
        assertEquals(listOf("powder"), ids(line = ProductionLine.POWDER))
        save("e")
        assertEquals(listOf("a", "d", "c", "e"), ids())
    }

    @Test fun aFailedSwapRollsBackBothPositions() {
        save("a"); save("b")
        val before = repository.load()
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_swap BEFORE UPDATE ON production_queue
            WHEN NEW.id = 'a' BEGIN SELECT RAISE(ABORT, 'simulated swap failure'); END""")
        assertThrows(SQLiteException::class.java) { repository.move("b", ProductionLine.BUTTER, today, -1, 200L) }
        assertEquals(before, repository.load())
    }

    @Test fun staleDatesWrongLinesAndInvalidFieldsDoNotModifyPlans() {
        save("a")
        val entry = repository.load().single()
        assertThrows(IllegalArgumentException::class.java) { repository.move("a", ProductionLine.UHT, today, 1, 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.move("a", ProductionLine.BUTTER, tomorrow, 1, 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.move("a", ProductionLine.BUTTER, today, 2, 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.delete(entry.copy(date = tomorrow)) }
        assertThrows(IllegalArgumentException::class.java) { save("a", line = ProductionLine.UHT) }
        assertThrows(IllegalArgumentException::class.java) { repository.save("a", ProductionLine.BUTTER, today, " ", "", 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.save("a", ProductionLine.BUTTER, today, "x".repeat(121), "", 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.save("a", ProductionLine.BUTTER, today, "A", "x".repeat(10001), 200L) }
        assertEquals(listOf(entry), repository.load())
    }

    @Test fun partialProductionSumsExactlyRetriesOnceAndFullCompletesOnlyRemainingAmount() {
        repository.save("a", ProductionLine.BUTTER, today, "Masło", "", 100L, BigDecimal("1000.125"))
        repository.recordProduction("receipt-1", "a", ProductionLine.BUTTER, today, BigDecimal("200.025"), 200L)
        repository.recordProduction("receipt-1", "a", ProductionLine.BUTTER, today, BigDecimal("200.025"), 200L)
        repository.recordProduction("receipt-2", "a", ProductionLine.BUTTER, today, BigDecimal("300.100"), 300L)
        val partial = repository.load().single()
        assertEquals(0, BigDecimal("500.125").compareTo(partial.producedAmount))
        assertEquals(0, BigDecimal("500.000").compareTo(partial.remainingAmount))
        assertEquals(ProductionQuantityUnit.KG, partial.unit)
        repository.close()
        repository = ProductionQueueRepository(context, today = { today })
        repository.recordProduction("receipt-full", "a", ProductionLine.BUTTER, today, null, 400L)
        repository.recordProduction("receipt-full", "a", ProductionLine.BUTTER, today, null, 400L)
        val full = repository.load().single()
        assertEquals(0, full.plannedAmount!!.compareTo(full.producedAmount))
        assertEquals(0, full.remainingAmount!!.signum())
        assertEquals(100L, full.createdAt)
        val verificationDatabase = ProductionQueueDatabase(context)
        try {
            verificationDatabase.readableDatabase.rawQuery("SELECT COUNT(*) FROM production_completions", null).use {
                it.moveToFirst(); assertEquals(3, it.getInt(0))
            }
        } finally { verificationDatabase.close() }
    }

    @Test fun rejectedInvalidProductionDoesNotAffectAnotherDayOrLine() {
        repository.save("a", ProductionLine.UHT, today, "Mleko", "", 100L, BigDecimal("1000"))
        repository.save("future", ProductionLine.UHT, tomorrow, "Mleko jutro", "", 100L, BigDecimal("1000"))
        val before = repository.load()
        listOf(BigDecimal.ZERO, BigDecimal("-1"), BigDecimal("0.0001")).forEachIndexed { i, amount ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordProduction("invalid-$i", "a", ProductionLine.UHT, today, amount, 200L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { repository.recordProduction("future", "future", ProductionLine.UHT, tomorrow, null, 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.recordProduction("wrong-line", "a", ProductionLine.BUTTER, today, null, 200L) }
        assertEquals(before, repository.load())
        repository.recordProduction("current-uht", "a", ProductionLine.UHT, today, BigDecimal("250"), 300L)
        assertEquals(ProductionQuantityUnit.LITRES, repository.load().single { it.id == "a" }.unit)
        assertEquals(BigDecimal("750"), repository.load().single { it.id == "a" }.remainingAmount)
    }

    @Test fun editingAndReschedulingRetainExecutionAndDoNotAllowPlanBelowProducedAmount() {
        repository.save("a", ProductionLine.POWDER, today, "Proszek", "", 100L, BigDecimal("1000"))
        repository.recordProduction("powder-partial", "a", ProductionLine.POWDER, today, BigDecimal("200"), 200L)
        assertThrows(IllegalArgumentException::class.java) {
            repository.save("a", ProductionLine.POWDER, today, "Proszek", "", 300L, BigDecimal("199"))
        }
        repository.save("a", ProductionLine.POWDER, tomorrow, "Proszek jutro", "Pozostała produkcja", 400L, BigDecimal("1200"))
        val updated = repository.load().single()
        assertEquals(tomorrow, updated.date)
        assertEquals(BigDecimal("200"), updated.producedAmount)
        assertEquals(BigDecimal("1000"), updated.remainingAmount)
        assertEquals(ProductionQuantityUnit.KG, updated.unit)
        assertThrows(IllegalArgumentException::class.java) { repository.delete(updated) }
        assertEquals(listOf(updated), repository.load())
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM production_completions", null).use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun receiptInsertFailureRollsBackRecordedProduction() {
        repository.save("a", ProductionLine.BUTTER, today, "Masło", "", 100L, BigDecimal("1000"))
        val before = repository.load()
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_completion BEFORE INSERT ON production_completions
            BEGIN SELECT RAISE(ABORT, 'simulated receipt failure'); END""")
        assertThrows(SQLiteException::class.java) {
            repository.recordProduction("failed", "a", ProductionLine.BUTTER, today, BigDecimal("250"), 200L)
        }
        assertEquals(before, repository.load())
    }

    @Test fun schemaOneUpgradePreservesUnknownAmountsAndUhtUnits() {
        context.openOrCreateDatabase(ProductionQueueDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE production_queue (
                id TEXT PRIMARY KEY, line TEXT NOT NULL, plan_date TEXT NOT NULL, position INTEGER NOT NULL,
                title TEXT NOT NULL, description TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                UNIQUE(line, plan_date, position)
            )""")
            db.execSQL("""INSERT INTO production_queue VALUES ('old', 'UHT', '2026-10-07', 2, 'Mleko', 'Opis', 100, 200)""")
            db.version = 1
        }
        val migrated = repository.load().single()
        assertEquals("Mleko", migrated.title)
        assertEquals(2L, migrated.position)
        assertEquals(100L, migrated.createdAt)
        assertNull(migrated.plannedAmount)
        assertEquals(BigDecimal.ZERO, migrated.producedAmount)
        assertEquals(ProductionQuantityUnit.LITRES, migrated.unit)
        assertThrows(IllegalArgumentException::class.java) {
            repository.recordProduction("missing-plan", "old", ProductionLine.UHT, today, BigDecimal("100"), 300L)
        }
        repository.save("old", ProductionLine.UHT, today, "Mleko", "Opis", 300L, BigDecimal("1000"))
        repository.recordProduction("old-complete", "old", ProductionLine.UHT, today, null, 400L)
        assertEquals(BigDecimal.ZERO, repository.load().single().remainingAmount)
    }

    @Test fun utcCalendarDaysDoNotShiftAcrossTimeZonesMonthEndsAndLeapDays() {
        val previousZone = java.util.TimeZone.getDefault()
        try {
            listOf("Europe/Warsaw", "Pacific/Honolulu", "Pacific/Kiritimati").forEach { zone ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                listOf(LocalDate.of(2028, 2, 29), LocalDate.of(2026, 12, 31), today).forEach { date ->
                    assertEquals(date, calendarDate(date.calendarMillis()))
                }
            }
        } finally { java.util.TimeZone.setDefault(previousZone) }
    }
    @Test fun waitingOrdersAreUnscheduledAndSchedulingRetainsOneEntryAcrossAllLines() {
        ProductionLine.entries.forEach { line ->
            repository.save(line.name, line, today, "Zamówienie", "Klient", 100L, BigDecimal("500"), pendingOrder = true)
            val order = repository.load().single { it.line == line }
            assertTrue(order.pendingOrder); assertNull(order.productionDate); assertNull(order.scheduledTime)
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordProduction("unplanned-${line.name}", order.id, line, today, null, 200L)
            }
            repository.schedule(order, tomorrow, java.time.LocalTime.of(14, 35), 300L)
            assertThrows(IllegalArgumentException::class.java) { repository.schedule(order, today, java.time.LocalTime.NOON, 400L) }
            val planned = repository.load().single { it.line == line }
            assertFalse(planned.pendingOrder); assertEquals(tomorrow, planned.productionDate)
            assertEquals(java.time.LocalTime.of(14, 35), planned.scheduledTime)
            assertEquals(order.id, planned.id); assertEquals(order.createdAt, planned.createdAt)
            assertEquals(BigDecimal("500"), planned.remainingAmount)
        }
        repository.close(); repository = ProductionQueueRepository(context, today = { today })
        assertEquals(3, repository.load().size)
        assertTrue(repository.load().all { it.productionDate == tomorrow && it.scheduledTime == java.time.LocalTime.of(14, 35) })
    }

    @Test fun hourReorderingDoesNotCrossSlotsOrOrdersAndReschedulingRetainsReceipts() {
        val line = ProductionLine.BUTTER
        fun timed(id: String, hour: Int) = repository.save(id, line, today, id, "", 100L,
            BigDecimal("1000"), scheduledTime = java.time.LocalTime.of(hour, 0))
        timed("a", 8); timed("other-hour", 9); timed("b", 8)
        repository.save("order", line, today, "Order", "", 100L, BigDecimal("500"), pendingOrder = true)
        repository.move("b", line, today, -1, 200L)
        assertEquals(listOf("b", "a"), repository.load().filter { it.scheduledTime == java.time.LocalTime.of(8, 0) }.map { it.id })
        repository.move("b", line, today, -1, 201L)
        assertEquals(java.time.LocalTime.of(9, 0), repository.load().single { it.id == "other-hour" }.scheduledTime)
        assertTrue(repository.load().single { it.id == "order" }.pendingOrder)
        repository.recordProduction("partial", "a", line, today, BigDecimal("250"), 300L)
        val partial = repository.load().single { it.id == "a" }
        repository.schedule(partial, tomorrow, java.time.LocalTime.of(23, 45), 400L)
        val planned = repository.load().single { it.id == "a" }
        assertEquals(BigDecimal("250"), planned.producedAmount); assertEquals(BigDecimal("750"), planned.remainingAmount)
        repository.save("a", line, tomorrow, "a", "", 500L, pendingOrder = true)
        val waiting = repository.load().single { it.id == "a" }
        assertTrue(waiting.pendingOrder); assertNull(waiting.scheduledTime)
        assertEquals(BigDecimal("250"), waiting.producedAmount); assertEquals(BigDecimal("750"), waiting.remainingAmount)
        database.readableDatabase.rawQuery("SELECT produced_on FROM production_completions WHERE entry_id = 'a'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(today.toString(), it.getString(0))
        }
    }

    @Test fun failedSchedulingAndInvalidTimesLeaveInboxAndPlanIntact() {
        repository.save("order", ProductionLine.UHT, today, "Mleko", "", 100L, BigDecimal("1000"), pendingOrder = true)
        val before = repository.load(); val order = before.single()
        assertThrows(IllegalArgumentException::class.java) { repository.schedule(order, tomorrow, java.time.LocalTime.of(8, 0, 1), 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.schedule(order.copy(line = ProductionLine.POWDER), tomorrow, java.time.LocalTime.NOON, 200L) }
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_schedule BEFORE UPDATE ON production_queue
            BEGIN SELECT RAISE(ABORT, 'simulated schedule failure'); END""")
        assertThrows(SQLiteException::class.java) { repository.schedule(order, tomorrow, java.time.LocalTime.NOON, 200L) }
        assertEquals(before, repository.load())
        assertEquals(java.time.LocalTime.of(8, 30), parseProductionTime("08:30"))
        listOf("24:00", "8:30", "12:60", "text", "08:30:00").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { parseProductionTime(value) }
        }
    }

    @Test fun schemaTwoUpgradeKeepsDailyPlanAmountsOrderAndCompletionLedgerWithoutInventingHour() {
        repository.close(); context.deleteDatabase(ProductionQueueDatabase.NAME)
        context.openOrCreateDatabase(ProductionQueueDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE production_queue (
                id TEXT PRIMARY KEY, line TEXT NOT NULL, plan_date TEXT NOT NULL, position INTEGER NOT NULL,
                title TEXT NOT NULL, description TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                planned_amount TEXT, produced_amount TEXT NOT NULL DEFAULT '0', unit TEXT NOT NULL DEFAULT 'KG',
                UNIQUE(line, plan_date, position))""")
            db.execSQL("""CREATE TABLE production_completions (id TEXT PRIMARY KEY, entry_id TEXT NOT NULL,
                amount TEXT NOT NULL, produced_on TEXT NOT NULL, occurred_at INTEGER NOT NULL)""")
            db.execSQL("INSERT INTO production_queue VALUES ('old', 'BUTTER', '2026-10-07', 4, 'Masło', 'Opis', 100, 200, '1000', '200', 'KG')")
            db.execSQL("INSERT INTO production_completions VALUES ('receipt', 'old', '200', '2026-10-07', 200)")
            db.version = 2
        }
        repository = ProductionQueueRepository(context, today = { today })
        val migrated = repository.load().single()
        assertFalse(migrated.pendingOrder); assertNull(migrated.scheduledTime); assertEquals(today, migrated.productionDate)
        assertEquals(4L, migrated.position); assertEquals(BigDecimal("800"), migrated.remainingAmount)
        repository.recordProduction("receipt", "old", ProductionLine.BUTTER, today, BigDecimal("200"), 300L)
        assertEquals(BigDecimal("200"), repository.load().single().producedAmount)
    }

    @Test fun excessCompletesAllLinesKeepsOrderedAmountAndRecordsExecutionExactlyOnce() {
        ProductionLine.entries.forEach { line ->
            repository.save(line.name, line, today, "Towar", "", 100L, BigDecimal("1000"))
            repository.recordProduction("partial-${line.name}", line.name, line, today, BigDecimal("400"), 200L)
            val partial = repository.load().single { it.line == line }
            assertFalse(partial.completed); assertEquals(BigDecimal("600"), partial.remainingAmount)
            repository.recordProduction("excess-${line.name}", line.name, line, today, BigDecimal("800"), 300L)
            repository.recordProduction("excess-${line.name}", line.name, line, today, BigDecimal("800"), 300L)
            val completed = repository.load().single { it.line == line }
            assertTrue(completed.completed); assertEquals(BigDecimal("1200"), completed.producedAmount)
            assertEquals(BigDecimal("1000"), completed.plannedAmount); assertEquals(BigDecimal("200"), completed.excessAmount)
            assertEquals(BigDecimal.ZERO, completed.remainingAmount)
            repository.save(line.name, line, today, "Poprawiony opis", "Uwagi", 400L, BigDecimal("1000"))
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordProduction("after-completed-${line.name}", line.name, line, today, BigDecimal.ONE, 500L)
            }
        }
        repository.close(); repository = ProductionQueueRepository(context, today = { today })
        val snapshot = repository.snapshot()
        assertEquals(3, snapshot.entries.size); assertEquals(6, snapshot.completions.size)
        assertTrue(snapshot.entries.all { it.completed && it.excessAmount == BigDecimal("200") })
        assertTrue(snapshot.completions.all { it.producedOn == today })
    }

    @Test fun completionCatalogDatesAndAmountsSurviveReschedulingPartialRemainder() {
        repository.save("part", ProductionLine.POWDER, today, "Proszek", "", 100L, BigDecimal("1000.125"))
        repository.recordProduction("first", "part", ProductionLine.POWDER, today, BigDecimal("250.125"), 200L)
        val part = repository.load().single()
        repository.schedule(part, tomorrow, java.time.LocalTime.NOON, 300L)
        val snapshot = repository.snapshot()
        assertEquals(tomorrow, snapshot.entries.single().date)
        assertEquals(BigDecimal("750.000"), snapshot.entries.single().remainingAmount)
        assertEquals(BigDecimal("250.125"), snapshot.completions.single().amount)
        assertEquals(today, snapshot.completions.single().producedOn)
        assertEquals(200L, snapshot.completions.single().occurredAt)
    }

    @Test fun reorderingActiveGoodsSkipsEntriesAlreadyMovedToCompletedCatalog() {
        listOf("a", "completed", "c").forEach { id ->
            repository.save(id, ProductionLine.BUTTER, today, id, "", 100L, BigDecimal("1000"))
        }
        repository.recordProduction("full", "completed", ProductionLine.BUTTER, today, null, 200L)
        repository.move("c", ProductionLine.BUTTER, today, -1, 300L)
        assertEquals(listOf("c", "a"), repository.load().filter { !it.completed }.map { it.id })
        assertEquals(BigDecimal("1000"), repository.load().single { it.id == "completed" }.producedAmount)
    }

    @Test fun productNotesFollowGoodsAcrossStagesRetryOnceAndDoNotChangeProduction() {
        ProductionLine.entries.forEach { line ->
            repository.save(line.name, line, today, "Towar", "Pierwotny plan", 100L, BigDecimal("1000"), pendingOrder = true)
            val order = repository.load().single { it.line == line }
            repository.addProductNote("order-note-${line.name}", order, ProductNoteStage.ORDER, "Wymaganie klienta", 101L)
            repository.addProductNote("order-note-${line.name}", order, ProductNoteStage.ORDER, "Wymaganie klienta", 101L)
            assertEquals(order, repository.load().single { it.line == line })
            repository.schedule(order, today, java.time.LocalTime.of(9, 15), 200L)
            val plan = repository.load().single { it.line == line }
            repository.addProductNote("plan-note-${line.name}", plan, ProductNoteStage.PRODUCTION, "Uwagi operatora", 201L)
            repository.recordProduction("partial-${line.name}", line.name, line, today, BigDecimal("400"), 300L)
            val partial = repository.load().single { it.line == line }
            repository.addProductNote("partial-note-${line.name}", partial, ProductNoteStage.COMPLETED, "Kontrola partii", 301L)
            repository.recordProduction("full-${line.name}", line.name, line, today, null, 400L)
            repository.addProductNote("done-note-${line.name}", repository.load().single { it.line == line }, ProductNoteStage.COMPLETED, "Gotowe do odbioru", 401L)
        }
        val before = repository.snapshot()
        repository.close(); repository = ProductionQueueRepository(context, today = { today })
        assertEquals(before, repository.snapshot())
        assertEquals(12, before.productNotes.size); assertEquals(6, before.completions.size)
        assertTrue(before.entries.all { it.completed && it.description == "Pierwotny plan" && it.producedAmount == BigDecimal("1000") })
        assertEquals(ProductNoteStage.COMPLETED, before.productNotes.first().stage)
        assertThrows(IllegalArgumentException::class.java) { repository.delete(before.entries.first()) }
        assertEquals(before, repository.snapshot())
        repository.save("unstarted", ProductionLine.BUTTER, today, "Do usunięcia", "", 500L, BigDecimal("100"), pendingOrder = true)
        val unstarted = repository.load().single { it.id == "unstarted" }
        repository.addProductNote("unstarted-note", unstarted, ProductNoteStage.ORDER, "Opis", 501L)
        repository.delete(unstarted)
        assertEquals(before, repository.snapshot())
    }

    @Test fun invalidStaleAndFailedNotesDoNotModifyGoodsOrLeaveHistoryRecords() {
        repository.save("order", ProductionLine.BUTTER, today, "Towar", "", 100L, BigDecimal("1000"), pendingOrder = true)
        val order = repository.load().single()
        assertThrows(IllegalArgumentException::class.java) { repository.addProductNote("bad", order, ProductNoteStage.ORDER, " ", 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.addProductNote("bad", order, ProductNoteStage.ORDER, "x".repeat(4001), 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.addProductNote("bad", order.copy(line = ProductionLine.UHT), ProductNoteStage.ORDER, "Opis", 200L) }
        assertThrows(IllegalArgumentException::class.java) { repository.addProductNote("bad", order, ProductNoteStage.COMPLETED, "Opis", 200L) }
        repository.schedule(order, tomorrow, java.time.LocalTime.NOON, 200L)
        assertThrows(IllegalArgumentException::class.java) { repository.addProductNote("stale", order, ProductNoteStage.ORDER, "Opis", 300L) }
        val before = repository.snapshot()
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_note BEFORE INSERT ON production_product_notes
            BEGIN SELECT RAISE(ABORT, 'simulated note failure'); END""")
        assertThrows(SQLiteException::class.java) { repository.addProductNote("failed", before.entries.single(), ProductNoteStage.PRODUCTION, "Opis", 300L) }
        assertEquals(before, repository.snapshot())
    }

    @Test fun returningUnstartedAndPartiallyProducedProductsToPendingPreservesNotesAmountsAndReceiptsInEveryLine() {
        ProductionLine.entries.forEach { line ->
            repository.save(line.name, line, today, "Produkt", "Opis klienta", 100L, BigDecimal("1000"),
                scheduledTime = java.time.LocalTime.of(9, 15))
            val unstarted = repository.load().single { it.id == line.name }
            repository.addProductNote("note-${line.name}", unstarted, ProductNoteStage.PRODUCTION, "Notatka", 101L)
            repository.returnToPending(unstarted, 200L)
            val waiting = repository.load().single { it.id == line.name }
            assertTrue(waiting.pendingOrder); assertNull(waiting.productionDate); assertNull(waiting.scheduledTime)
            assertEquals(BigDecimal("1000"), waiting.remainingAmount)
            repository.schedule(waiting, today, java.time.LocalTime.NOON, 300L)
            repository.recordProduction("partial-${line.name}", line.name, line, today, BigDecimal("400"), 400L)
            val before = repository.snapshot()
            repository.returnToPending(before.entries.single { it.id == line.name }, 500L)
            val after = repository.snapshot()
            assertEquals(before.completions, after.completions); assertEquals(before.productNotes, after.productNotes)
            val partialWaiting = after.entries.single { it.id == line.name }
            assertTrue(partialWaiting.pendingOrder); assertNull(partialWaiting.scheduledTime)
            assertEquals("Opis klienta", partialWaiting.description); assertEquals(100L, partialWaiting.createdAt)
            assertEquals(BigDecimal("1000"), partialWaiting.plannedAmount)
            assertEquals(BigDecimal("400"), partialWaiting.producedAmount); assertEquals(BigDecimal("600"), partialWaiting.remainingAmount)
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordProduction("not-scheduled-${line.name}", line.name, line, today, null, 501L)
            }
            repository.schedule(partialWaiting, tomorrow, java.time.LocalTime.of(14, 30), 600L)
            val rescheduled = repository.load().single { it.id == line.name }
            assertEquals(tomorrow, rescheduled.productionDate); assertEquals(BigDecimal("600"), rescheduled.remainingAmount)
            assertEquals(today, repository.snapshot().completions.single { it.entryId == line.name }.producedOn)
        }
        val beforeReopen = repository.snapshot()
        repository.close(); repository = ProductionQueueRepository(context, today = { today })
        assertEquals(beforeReopen, repository.snapshot()); assertEquals(3, repository.load().size)
    }

    @Test fun staleCompletedAndFailedReturnsLeavePlanAndHistoryIntact() {
        repository.save("a", ProductionLine.UHT, today, "Mleko", "", 100L, BigDecimal("1000"),
            scheduledTime = java.time.LocalTime.of(8, 0))
        val old = repository.load().single()
        repository.schedule(old, tomorrow, java.time.LocalTime.NOON, 200L)
        val scheduled = repository.load().single()
        val before = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.returnToPending(old, 300L) }
        assertThrows(IllegalArgumentException::class.java) { repository.returnToPending(scheduled.copy(line = ProductionLine.BUTTER), 300L) }
        assertThrows(IllegalArgumentException::class.java) { repository.returnToPending(scheduled.copy(id = "missing"), 300L) }
        assertEquals(before, repository.snapshot())
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_return BEFORE UPDATE ON production_queue
            WHEN NEW.pending_order = 1 BEGIN SELECT RAISE(ABORT, 'simulated return failure'); END""")
        assertThrows(SQLiteException::class.java) { repository.returnToPending(scheduled, 300L) }
        assertEquals(before, repository.snapshot())
        database.writableDatabase.execSQL("DROP TRIGGER reject_return")
        repository.schedule(scheduled, today, java.time.LocalTime.NOON, 400L)
        repository.recordProduction("full", "a", ProductionLine.UHT, today, null, 500L)
        val completed = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.returnToPending(completed.entries.single(), 600L) }
        assertThrows(IllegalArgumentException::class.java) { repository.save("a", ProductionLine.UHT, today, "Mleko", "", 600L, pendingOrder = true) }
        assertEquals(completed, repository.snapshot())
    }

    @Test fun rejectedGoodsAreSeparateKilogramRecordsWithDatesRetryProtectionAndRestrictedDeletion() {
        repository.save("ordinary", ProductionLine.BUTTER, today, "Masło", "Opis", 100L, BigDecimal("1000"))
        repository.recordProduction("ordinary-receipt", "ordinary", ProductionLine.BUTTER, today, BigDecimal("400"), 200L)
        val before = repository.snapshot()
        ProductionLine.entries.forEach { line ->
            repository.addRejectedGoods("reject-${line.name}", line, today, "  Brak jakościowy  ", BigDecimal("12.345"), 300L)
            repository.addRejectedGoods("reject-${line.name}", line, today, "Brak jakościowy", BigDecimal("12.3450"), 301L)
        }
        repository.addRejectedGoods("yesterday", ProductionLine.UHT, today.minusDays(1), "Poprzedni dzień", BigDecimal("25"), 400L)
        var snapshot = repository.snapshot()
        assertEquals(4, snapshot.rejectedGoods.size)
        assertEquals(3, snapshot.rejectedGoods.count { it.date == today })
        assertEquals(BigDecimal("12.345"), snapshot.rejectedGoods.single { it.id == "reject-UHT" }.kilograms)
        assertEquals(before.entries, snapshot.entries); assertEquals(before.completions, snapshot.completions)
        assertThrows(IllegalArgumentException::class.java) { repository.deleteRejectedGoods("ordinary") }
        assertThrows(IllegalArgumentException::class.java) { repository.deleteRejectedGoods("ordinary-receipt") }
        assertThrows(IllegalArgumentException::class.java) { repository.delete(snapshot.entries.single()) }
        assertThrows(IllegalArgumentException::class.java) { repository.delete(snapshot.entries.single().copy(producedAmount = BigDecimal.ZERO)) }
        assertEquals(snapshot, repository.snapshot())
        repository.close(); repository = ProductionQueueRepository(context, today = { today })
        assertEquals(snapshot, repository.snapshot())
        repository.deleteRejectedGoods("reject-UHT")
        snapshot = repository.snapshot()
        assertEquals(3, snapshot.rejectedGoods.size)
        assertEquals(before.entries, snapshot.entries); assertEquals(before.completions, snapshot.completions)
        assertEquals(before.productNotes, snapshot.productNotes)
    }

    @Test fun invalidAndFailedRejectedGoodsDoNotChangeProductionOrLeavePartialRecords() {
        repository.save("ordinary", ProductionLine.POWDER, today, "Proszek", "", 100L, BigDecimal("1000"))
        val before = repository.snapshot()
        listOf(BigDecimal.ZERO, BigDecimal("-1"), BigDecimal("0.0001")).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) { repository.addRejectedGoods("bad", ProductionLine.UHT, today, "Brak", amount, 200L) }
        }
        listOf(" ", "x".repeat(4001)).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { repository.addRejectedGoods("bad", ProductionLine.UHT, today, text, BigDecimal.ONE, 200L) }
        }
        assertThrows(IllegalArgumentException::class.java) { repository.addRejectedGoods("bad", ProductionLine.UHT, LocalDate.of(2200, 1, 1), "Brak", BigDecimal.ONE, 200L) }
        assertEquals(before, repository.snapshot())
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_brak BEFORE INSERT ON production_rejects
            BEGIN SELECT RAISE(ABORT, 'simulated rejected goods failure'); END""")
        assertThrows(SQLiteException::class.java) { repository.addRejectedGoods("failed", ProductionLine.UHT, today, "Brak", BigDecimal.ONE, 200L) }
        assertEquals(before, repository.snapshot())
        database.writableDatabase.execSQL("DROP TRIGGER reject_brak")
        repository.addRejectedGoods("a", ProductionLine.UHT, today, "Brak", BigDecimal.ONE, 300L)
        val saved = repository.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repository.addRejectedGoods("a", ProductionLine.BUTTER, today, "Brak", BigDecimal.ONE, 301L) }
        assertEquals(saved, repository.snapshot())
    }

    @Test fun schemaFourUpgradeRetainsProductionHistoryAndNotesAndAddsAnEmptyRejectRegister() {
        repository.save("a", ProductionLine.BUTTER, today, "Masło", "Opis", 100L, BigDecimal("1000"))
        val entry = repository.load().single()
        repository.addProductNote("note", entry, ProductNoteStage.PRODUCTION, "Notatka", 101L)
        repository.recordProduction("partial", "a", ProductionLine.BUTTER, today, BigDecimal("250"), 200L)
        val before = repository.snapshot()
        repository.close()
        context.openOrCreateDatabase(ProductionQueueDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP TABLE production_rejects"); db.version = 4
        }
        repository = ProductionQueueRepository(context, today = { today })
        assertEquals(before, repository.snapshot())
        repository.addRejectedGoods("new", ProductionLine.BUTTER, today, "Po aktualizacji", BigDecimal("10"), 300L)
        assertEquals(1, repository.snapshot().rejectedGoods.size)
        assertEquals(before.completions, repository.snapshot().completions)
    }

    @Test fun schemaThreeUpgradeAddsNotesWithoutChangingScheduledHoursAmountsOrCompletionHistory() {
        repository.save("a", ProductionLine.UHT, today, "Mleko", "Opis zamówienia", 100L, BigDecimal("1000"), scheduledTime = java.time.LocalTime.of(9, 15))
        repository.recordProduction("part", "a", ProductionLine.UHT, today, BigDecimal("250"), 200L)
        val before = repository.snapshot()
        repository.close()
        context.openOrCreateDatabase(ProductionQueueDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP TABLE production_product_notes")
            db.execSQL("DROP TABLE production_rejects")
            db.version = 3
        }
        repository = ProductionQueueRepository(context, today = { today })
        val after = repository.snapshot()
        assertEquals(before.entries, after.entries); assertEquals(before.completions, after.completions)
        assertTrue(after.productNotes.isEmpty())
        repository.addProductNote("new", after.entries.single(), ProductNoteStage.PRODUCTION, "Po aktualizacji", 300L)
        assertEquals("Po aktualizacji", repository.snapshot().productNotes.single().text)
    }

}

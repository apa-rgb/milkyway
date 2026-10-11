package pl.apargb.milkyway

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WindowsOrdersIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 10, 11)
    private fun fixture(): Map<String, Any?> {
        fun convert(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { convert(value.get(it)) }
            JSONObject.NULL -> null
            else -> value
        }
        val json = javaClass.classLoader!!.getResourceAsStream("windows-orders.json")!!.bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST") return convert(JSONObject(json)) as Map<String, Any?>
    }
    @Test fun desktopOrdersAppearAsPendingWithNumbersKindsQuantitiesAndNotesInAllLines() {
        ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, fixture())
            val state = ProductionQueueRepository(context, helper as ProductionQueueDatabase, today = { day }).snapshot()
            assertEquals(3, state.entries.size)
            assertEquals(listOf(1L, 2L, 3L), state.entries.map { it.orderNumber })
            assertTrue(state.entries.all { it.pendingOrder && it.scheduledTime == null && it.plannedAmount == BigDecimal("1250.5") })
            assertEquals(ButterProductKind.MIX, state.entries.first().butterKind)
            assertEquals(ProductionQuantityUnit.LITRES, state.entries.last().unit)
            assertTrue(state.productNotes.all { it.author == "Anna" && it.important && it.visibleText == "Ważna dostawa dla klienta" })
            assertEquals("#000001 · Zamówienie BUTTER", state.entries.first().displayTitle)
        }
    }
    @Test fun androidEditingSchedulingAndPartialExecutionPreserveNumberAndSequenceForWindows() {
        val initial = fixture()
        val captured = ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, initial)
            val repository = ProductionQueueRepository(context, helper as ProductionQueueDatabase, today = { day })
            val entry = repository.load().first()
            repository.save(entry.id, entry.line, entry.date, "Nowa nazwa", entry.description, 100L, entry.plannedAmount, pendingOrder = true)
            repository.schedule(repository.load().first(), day, LocalTime.of(10, 0), 101L)
            repository.recordProduction("partial", entry.id, entry.line, day, BigDecimal("250"), productionTimestamp(day))
            SharedRows.capture(helper, SharedDomain.PRODUCTION, initial, mapOf("uid" to "android-user", "account" to "02"), 102L)
        }
        assertEquals(3L, (captured["orderSequence"] as Number).toLong())
        ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, captured)
            val state = ProductionQueueRepository(context, helper as ProductionQueueDatabase).snapshot()
            val product = completedProductsForDay(state.entries, state.completions, day).single()
            assertEquals(1L, product.entry.orderNumber); assertEquals("#000001 · Nowa nazwa", product.entry.displayTitle)
            assertEquals(BigDecimal("250"), product.amount); assertEquals(BigDecimal("1000.5"), product.entry.remainingAmount)
        }
    }
    @Test fun deletingTheLastPendingOrderDoesNotResetTheSharedCounter() {
        val initial = fixture()
        ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, initial)
            val repository = ProductionQueueRepository(context, helper as ProductionQueueDatabase)
            repository.delete(repository.load().last(), "5522")
            val captured = SharedRows.capture(helper, SharedDomain.PRODUCTION, initial, mapOf("uid" to "android-user", "account" to "02"), 100L)
            assertEquals(3L, (captured["orderSequence"] as Number).toLong())
            assertEquals(2, SharedRows.tables(captured).getValue("production_queue").size)
        }
    }
    @Test fun databaseSixUpgradePreservesExistingPlanAndAddsEmptyNumber() {
        val name = "windows-order-migration.db"; context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("""CREATE TABLE production_queue (
                id TEXT PRIMARY KEY, line TEXT NOT NULL, plan_date TEXT NOT NULL, position INTEGER NOT NULL,
                title TEXT NOT NULL, description TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                planned_amount TEXT, produced_amount TEXT NOT NULL, unit TEXT NOT NULL, pending_order INTEGER NOT NULL, planned_time TEXT
            )""")
            db.execSQL("INSERT INTO production_queue VALUES ('old', 'BUTTER', '2026-10-11', 0, 'Masło', '', 1, 1, '100', '20', 'KG', 0, '10:00')")
            db.version = 6
        }
        ProductionQueueDatabase(context, name).useDatabase { helper ->
            val entry = ProductionQueueRepository(context, helper as ProductionQueueDatabase).load().single()
            assertNull(entry.orderNumber); assertEquals(BigDecimal("20"), entry.producedAmount); assertEquals(LocalTime.of(10, 0), entry.scheduledTime)
        }
        context.deleteDatabase(name)
    }
}

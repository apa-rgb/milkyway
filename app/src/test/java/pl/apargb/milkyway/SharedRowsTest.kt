package pl.apargb.milkyway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedRowsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val actor1 = mapOf<String, Any?>("uid" to "user1", "account" to "01")
    private val actor2 = mapOf<String, Any?>("uid" to "user2", "account" to "02")

    private fun inventory(previous: Map<String, Any?>, actor: Map<String, Any?> = actor1,
                          operation: (InventoryRepository) -> Unit): Map<String, Any?> = InventoryDatabase(context, null).useDatabase { helper ->
        SharedRows.restore(helper, SharedDomain.INVENTORY, previous)
        operation(InventoryRepository(context, helper as InventoryDatabase))
        SharedRows.capture(helper, SharedDomain.INVENTORY, previous, actor, 1000L)
    }

    @Test fun twoDevicesTransferAgainstCurrentStockAtomicallyAndRetryWithoutDuplicatingAudit() {
        var snapshot = inventory(emptyMap()) {
            it.apply("initial") { rules -> rules.setState("LBT 1", BigDecimal("300.001"), Measurements(), 1L) }
            it.apply("target-empty") { rules -> rules.setState("LBT 2", BigDecimal.ZERO, Measurements(), 1L) }
        }
        snapshot = inventory(snapshot) { it.apply("device1") { rules -> rules.transfer("LBT 1", "LBT 2", BigDecimal("100"), Measurements(), 2L) } }
        snapshot = inventory(snapshot, actor2) { it.apply("device2") { rules -> rules.transfer("LBT 1", "LBT 2", BigDecimal("200.001"), Measurements(), 3L) } }
        snapshot = inventory(snapshot, actor2) { it.apply("device2") { rules -> rules.transfer("LBT 1", "LBT 2", BigDecimal("200.001"), Measurements(), 3L) } }
        InventoryDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.INVENTORY, snapshot)
            val repository = InventoryRepository(context, helper as InventoryDatabase)
            assertEquals(BigDecimal.ZERO, repository.load().states.getValue("LBT 1").litres)
            assertEquals(BigDecimal("300.001"), repository.load().states.getValue("LBT 2").litres)
            assertEquals(3, repository.history("LBT 1").size)
            assertEquals(listOf("device2", "device1"), repository.topUps("LBT 2").events.map { it.id })
        }
        val movements = SharedRows.tables(snapshot).getValue("movements")
        assertEquals(actor1 + ("at" to 1000L), movements.getValue(sharedKey("initial"))["_actor"])
        assertEquals(actor2 + ("at" to 1000L), movements.getValue(sharedKey("device2"))["_actor"])
    }

    @Test fun sharedTopUpsRetainInsertionCursorAndEveryOlderEntryAcrossRestores() {
        val snapshot = inventory(emptyMap()) { repo ->
            repo.apply("empty") { rules -> rules.setState("LBT 1", BigDecimal.ZERO, Measurements(), 1L) }
            repeat(55) { number ->
                repo.apply("fill-$number") { rules -> rules.receive("LBT 1", BigDecimal("0.001"), "Dostawa", Measurements(), 1L) }
            }
        }
        InventoryDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.INVENTORY, snapshot)
            val repo = InventoryRepository(context, helper as InventoryDatabase)
            val first = repo.topUps("LBT 1")
            val second = repo.topUps("LBT 1", first.nextBeforeRowId)
            assertEquals(55, first.totalCount); assertEquals(50, first.events.size); assertEquals(5, second.events.size)
            assertEquals(55, (first.events + second.events).map { it.id }.toSet().size)
            assertEquals("fill-54", first.events.first().id); assertEquals("fill-0", second.events.last().id)
            assertEquals(BigDecimal("0.055"), repo.load().states.getValue("LBT 1").litres)
        }
    }

    @Test fun productionRetriesSumBothOperatorsExecutionsAndKeepCompletedRecordsProtected() {
        val day = LocalDate.now()
        fun apply(previous: Map<String, Any?>, actor: Map<String, Any?>, block: (ProductionQueueRepository) -> Unit) =
            ProductionQueueDatabase(context, null).useDatabase { helper ->
                SharedRows.restore(helper, SharedDomain.PRODUCTION, previous)
                block(ProductionQueueRepository(context, helper as ProductionQueueDatabase))
                SharedRows.capture(helper, SharedDomain.PRODUCTION, previous, actor, 2000L)
            }
        var snapshot = apply(emptyMap(), actor1) { it.save("product", ProductionLine.BUTTER, day, "Masło", "", 1L, BigDecimal("1000")) }
        snapshot = apply(snapshot, actor1) { it.recordProduction("first", "product", ProductionLine.BUTTER, day, BigDecimal("400"), 2L) }
        snapshot = apply(snapshot, actor2) { it.recordProduction("second", "product", ProductionLine.BUTTER, day, BigDecimal("350"), 3L) }
        snapshot = apply(snapshot, actor2) { it.recordProduction("second", "product", ProductionLine.BUTTER, day, BigDecimal("350"), 3L) }
        ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, snapshot)
            val repo = ProductionQueueRepository(context, helper as ProductionQueueDatabase)
            val entry = repo.load().single()
            assertEquals(BigDecimal("750"), entry.producedAmount); assertEquals(BigDecimal("250"), entry.remainingAmount)
            assertEquals(2, repo.snapshot().completions.size)
            assertThrows(IllegalArgumentException::class.java) { repo.delete(entry.copy(producedAmount = BigDecimal.ZERO)) }
            repo.recordProduction("finish", "product", ProductionLine.BUTTER, day, null, 4L)
            assertEquals(BigDecimal("1000"), repo.load().single().producedAmount)
        }
    }

    @Test fun operatorsCannotAddLaboratoryMarkersButCanPreserveOrClearExistingOnes() {
        val initial = inventory(emptyMap()) { it.apply("initial") { r -> r.setState("LBT 1", BigDecimal("100"), Measurements(), 1L) } }
        val lab = inventory(initial) { it.apply("lab") { r -> r.updateMeasurements("LBT 1", Measurements(brix = BigDecimal("12")), 2L) } }
        assertThrows(IllegalArgumentException::class.java) { SharedRows.validateLaboratoryChange(initial, lab, false) }
        SharedRows.validateLaboratoryChange(initial, lab, true)
        val unchanged = inventory(lab) { it.apply("details") { r -> r.updateDetails("LBT 1", "Mleko", Measurements(brix = BigDecimal("12")), 3L) } }
        SharedRows.validateLaboratoryChange(lab, unchanged, false)
        val clear = inventory(lab) { it.apply("clear") { r -> r.setState("LBT 1", BigDecimal.ZERO, Measurements(), 4L) } }
        SharedRows.validateLaboratoryChange(lab, clear, false)
    }

    @Test fun incompatibleSharedSchemasDoNotOverwritePrivateDeviceRecords() {
        InventoryDatabase(context, null).useDatabase { helper ->
            val repo = InventoryRepository(context, helper as InventoryDatabase)
            repo.apply("local") { r -> r.setState("LBT 1", BigDecimal("123"), Measurements(), 1L) }
            assertThrows(IllegalArgumentException::class.java) { SharedRows.restore(helper, SharedDomain.INVENTORY, mapOf("schema" to 2L)) }
            assertEquals(BigDecimal("123"), repo.load().states.getValue("LBT 1").litres)
        }
    }

    @Test fun noteEditsRetainOtherOperatorsNotesAndRecordOnlyTheEditedRowsAuthor() {
        fun apply(previous: Map<String, Any?>, actor: Map<String, Any?>, block: (WorkNotesRepository) -> Unit) = WorkNotesDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.NOTES, previous); block(WorkNotesRepository(context, helper as WorkNotesDatabase))
            SharedRows.capture(helper, SharedDomain.NOTES, previous, actor, 3000L)
        }
        var snapshot = apply(emptyMap(), actor1) { it.save("a", 1, NoteKind.CURRENT_NOTES, "Pierwsza", "", 1L) }
        snapshot = apply(snapshot, actor2) { it.save("b", 2, NoteKind.REMINDER, "Druga", "", 2L) }
        val rows = SharedRows.tables(snapshot).getValue("work_notes")
        assertEquals(actor1 + ("at" to 3000L), rows.getValue(sharedKey("a"))["_actor"])
        assertEquals(actor2 + ("at" to 3000L), rows.getValue(sharedKey("b"))["_actor"])
        WorkNotesDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.NOTES, snapshot)
            val repo = WorkNotesRepository(context, helper as WorkNotesDatabase)
            assertEquals(setOf("a", "b"), repo.load().map { it.id }.toSet())
            repo.setCompleted(repo.load().first { it.id == "b" }, true, 3L)
            assertTrue(repo.load().first { it.id == "b" }.completed)
        }
    }
}

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
            assertThrows(IllegalArgumentException::class.java) { repo.delete(entry.copy(producedAmount = BigDecimal.ZERO), "5522") }
            repo.recordProduction("finish", "product", ProductionLine.BUTTER, day, null, 4L)
            assertEquals(BigDecimal("1000"), repo.load().single().producedAmount)
        }
    }

    @Test fun sharedWarehouseRemovalUpgradesOldSchemaAndPersistsWithoutChangingProductionAmounts() {
        val day = LocalDate.now()
        val old = ProductionQueueDatabase(context, null).useDatabase { helper ->
            val repo = ProductionQueueRepository(context, helper as ProductionQueueDatabase)
            repo.save("product", ProductionLine.BUTTER, day, "Masło", "", 1L, BigDecimal("1000"))
            repo.recordProduction("receipt", "product", ProductionLine.BUTTER, day, BigDecimal("400"), 2L)
            SharedRows.capture(helper, SharedDomain.PRODUCTION, emptyMap(), actor1, 3L) + ("schema" to 1L)
        }
        val updated = ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, old)
            ProductionQueueRepository(context, helper as ProductionQueueDatabase)
                .removeFromWarehouse("remove", "product", day, setOf("receipt"), "5522", 4L)
            SharedRows.capture(helper, SharedDomain.PRODUCTION, old, actor2, 5L)
        }
        assertEquals(2L, updated["schema"])
        ProductionQueueDatabase(context, null).useDatabase { helper ->
            SharedRows.restore(helper, SharedDomain.PRODUCTION, updated)
            val snapshot = ProductionQueueRepository(context, helper as ProductionQueueDatabase).snapshot()
            assertEquals(BigDecimal("400"), snapshot.entries.single().producedAmount)
            assertEquals(BigDecimal("600"), snapshot.entries.single().remainingAmount)
            assertEquals(4L, snapshot.completions.single().warehouseRemovedAt)
            assertTrue(completedProductsForDay(snapshot.entries, snapshot.completions, day).isEmpty())
            assertEquals(actor2 + ("at" to 5L), SharedRows.tables(updated).getValue("production_completions").values.single()["_actor"])
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

    @Test fun butterTypesAndImportantProductNotesSurviveTwoOperatorsSchedulingAndPartialProduction() {
        val day = LocalDate.now()
        fun apply(previous: Map<String, Any?>, actor: Map<String, Any?>, block: (ProductionQueueRepository) -> Unit) =
            ProductionQueueDatabase(context, null).useDatabase { helper ->
                SharedRows.restore(helper, SharedDomain.PRODUCTION, previous)
                block(ProductionQueueRepository(context, helper as ProductionQueueDatabase))
                SharedRows.capture(helper, SharedDomain.PRODUCTION, previous, actor, 2000L)
            }
        var snapshot = apply(emptyMap(), actor1) { repo ->
            ButterProductKind.entries.forEach { kind ->
                repo.save(kind.name, ProductionLine.BUTTER, day, "Partia ${kind.title}",
                    withButterKind("Pełny opis klienta", kind), 1L, BigDecimal("1000"), pendingOrder = true)
                repo.addProductNote("note-${kind.name}", repo.load().single { it.id == kind.name }, ProductNoteStage.ORDER,
                    withImportantText("Pilna dostawa przed południem", true), 2L, author = "Łukasz")
            }
        }
        snapshot = apply(snapshot, actor2) { repo ->
            repo.load().forEach { repo.schedule(it, day, java.time.LocalTime.of(8, 30), 3L) }
            repo.recordProduction("partial", "MIX", ProductionLine.BUTTER, day, BigDecimal("400"), 4L)
            // A repeated flag operation must retain the note, original timestamp and stage.
            val note = repo.snapshot().productNotes.single { it.entryId == "MIX" }
            repo.setProductNoteImportant(note, false)
            repo.setProductNoteImportant(note, false)
        }
        snapshot = apply(snapshot, actor1) { repo ->
            val state = repo.snapshot()
            state.entries.forEach { entry ->
                assertEquals(ButterProductKind.valueOf(entry.id), entry.butterKind)
                assertEquals("Pełny opis klienta", entry.planDescription)
                assertEquals(java.time.LocalTime.of(8, 30), entry.scheduledTime)
            }
            val mix = state.entries.single { it.id == "MIX" }
            assertEquals(BigDecimal("600"), mix.remainingAmount)
            assertEquals(2, state.productNotes.size)
            state.productNotes.forEach { note ->
                assertEquals("Pilna dostawa przed południem", note.visibleText)
                assertEquals("Łukasz", note.author)
                assertEquals(2L, note.createdAt); assertEquals(ProductNoteStage.ORDER, note.stage)
                assertEquals(note.entryId == "BUTTER", note.important)
            }
            repo.setProductNoteImportant(state.productNotes.single { it.entryId == "MIX" }, true)
            repo.recordProduction("finish", "MIX", ProductionLine.BUTTER, day, null, 5L)
        }
        apply(snapshot, actor2) { repo ->
            assertEquals(ButterProductKind.MIX, repo.load().single { it.id == "MIX" }.butterKind)
            assertTrue(repo.snapshot().productNotes.all { it.important })
            assertTrue(repo.snapshot().productNotes.all { it.author == "Łukasz" })
            assertEquals(2, repo.snapshot().completions.size)
        }
    }

    @Test fun importantWorkNotesSurviveSharedCompletionAndEditsWithoutLosingCreationTimeOrScope() {
        fun apply(previous: Map<String, Any?>, actor: Map<String, Any?>, block: (WorkNotesRepository) -> Unit) =
            WorkNotesDatabase(context, null).useDatabase { helper ->
                SharedRows.restore(helper, SharedDomain.NOTES, previous)
                block(WorkNotesRepository(context, helper as WorkNotesDatabase))
                SharedRows.capture(helper, SharedDomain.NOTES, previous, actor, 3000L)
            }
        var snapshot = apply(emptyMap(), actor1) { repo ->
            repo.save("board", 0, NoteKind.REMINDER, "Awaria", withImportantText("Sprawdzić instalację", true), 1L, author = "Anna")
            repo.save("shift", 1, NoteKind.CURRENT_NOTES, "Pilne", withImportantText("", true), 2L)
        }
        snapshot = apply(snapshot, actor2) { repo ->
            val board = repo.load().single { it.id == "board" }
            assertTrue(board.important); assertEquals("Sprawdzić instalację", board.visibleBody)
            repo.setCompleted(board, true, 3L)
            repo.save(board.id, board.scope, board.kind, board.title, withImportantText(board.visibleBody, false), 4L, author = "Piotr")
        }
        apply(snapshot, actor1) { repo ->
            val board = repo.load().single { it.id == "board" }
            assertFalse(board.important); assertTrue(board.completed)
            assertEquals(1L, board.createdAt); assertEquals(0, board.scope)
            assertEquals("Sprawdzić instalację", board.visibleBody)
            assertEquals("Anna", board.author)
            val shift = repo.load().single { it.id == "shift" }
            assertTrue(shift.important); assertEquals("", shift.visibleBody); assertEquals(1, shift.scope)
            assertNull(shift.author)
        }
    }
}

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
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InventoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: InventoryDatabase
    private lateinit var repository: InventoryRepository

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(InventoryDatabase.NAME)
        database = InventoryDatabase(context)
        repository = InventoryRepository(context, database)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(InventoryDatabase.NAME)
    }

    private fun initialize() {
        repository.apply { it.setState("Silos 1", BigDecimal("5000.125"), Measurements(ph = BigDecimal("6.7")), 100L, material = "Serwatka") }
        repository.apply { it.setState("Kryst. 1", BigDecimal.ZERO, Measurements(), 200L) }
    }

    @Test fun transferAndRouteSurviveReopeningDatabaseAndHistoryAppearsForBothTanks() {
        initialize()
        repository.apply { it.setRouting("Silos 1", TankRouting("C", 1), 300L) }
        repository.apply("transfer-one") {
            it.transfer("Silos 1", "Kryst. 1", BigDecimal("1000.025"),
                Measurements(brix = BigDecimal("19.5"), ph = BigDecimal("6.8"), sh = BigDecimal("7.1"), temperature = BigDecimal("4.5")),
                400L, "Przez wyparkę C", 3, "Serwatka — partia A")
        }
        repository.close()
        repository = InventoryRepository(context)
        val loaded = repository.load()
        assertEquals(0, BigDecimal("4000.100").compareTo(loaded.states["Silos 1"]!!.litres))
        assertEquals(0, BigDecimal("1000.025").compareTo(loaded.states["Kryst. 1"]!!.litres))
        assertEquals(TankRouting("C", 1), loaded.states["Silos 1"]!!.routing)
        assertEquals(400L, loaded.states["Kryst. 1"]!!.filledAt)
        assertEquals(BigDecimal("19.5"), loaded.states["Kryst. 1"]!!.measurements.brix)
        assertEquals(BigDecimal("6.8"), loaded.states["Kryst. 1"]!!.measurements.ph)
        assertEquals(BigDecimal("7.1"), loaded.states["Kryst. 1"]!!.measurements.sh)
        assertEquals(BigDecimal("4.5"), loaded.states["Kryst. 1"]!!.measurements.temperature)
        assertEquals("Serwatka — partia A", loaded.states["Kryst. 1"]!!.material)
        val sourceHistory = repository.history("Silos 1")
        val destinationHistory = repository.history("Kryst. 1")
        assertEquals("transfer-one", sourceHistory.first().id)
        assertEquals(sourceHistory.first(), destinationHistory.first())
        assertEquals("Przez wyparkę C", sourceHistory.first().note)
        assertEquals(3, sourceHistory.first().shift)
        assertEquals("Serwatka — partia A", sourceHistory.first().material)
    }

    @Test fun allTopUpsIncludeExternalAndInternalInflowsAndSurviveEmptyingAndReopening() {
        initialize()
        repository.apply("first-top-up") {
            it.receive("Silos 1", BigDecimal("250.125"), "Cysterna A", Measurements(brix = BigDecimal("19.5")),
                300L, "Partia A", 2, "Serwatka")
        }
        val second: (InventoryRules) -> InventoryChange = {
            it.receive("Silos 1", BigDecimal("500"), "Cysterna B", Measurements(ph = BigDecimal("6.8")), 400L, material = "Mieszanina")
        }
        repository.apply("second-top-up", second)
        repository.apply("second-top-up", second)
        repository.apply { it.receive("Kryst. 1", BigDecimal("1000"), "Dostawa", Measurements(), 500L) }
        repository.apply("incoming-transfer") { it.transfer("Kryst. 1", "Silos 1", BigDecimal("300"), Measurements(), 600L, material = "Mieszanina") }
        repository.apply("outgoing-transfer") { it.transfer("Silos 1", "Kryst. 1", BigDecimal("100"), Measurements(), 700L) }
        repository.apply { it.setState("Silos 1", BigDecimal.ZERO, Measurements(), 800L) }
        repository.close()
        repository = InventoryRepository(context)
        val page = repository.topUps("Silos 1")
        assertEquals(3, page.totalCount)
        assertNull(page.nextBeforeRowId)
        assertEquals(listOf("incoming-transfer", "second-top-up", "first-top-up"), page.events.map { it.id })
        assertEquals("Kryst. 1", page.events.first().sourceId)
        assertEquals(BigDecimal("5750.250"), page.events.first().previousLitres)
        val first = page.events.last()
        assertEquals(BigDecimal("5000.125"), first.previousLitres)
        assertEquals(BigDecimal("250.125"), first.litres)
        assertEquals("Cysterna A", first.externalSource)
        assertEquals("Serwatka", first.material)
        assertEquals("Partia A", first.note)
        assertEquals(BigDecimal("19.5"), first.measurements.brix)
        assertEquals(2, first.shift)
        assertEquals(300L, first.occurredAt)
        assertEquals(BigDecimal.ZERO, repository.load().states.getValue("Silos 1").litres)
        assertEquals(listOf("outgoing-transfer"), repository.topUps("Kryst. 1").events.filter { it.type == MovementType.TRANSFER }.map { it.id })
    }

    @Test fun topUpsPagingReachesEveryEntryBeyondHistoryLimitWithoutIncludingOtherOperations() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            repeat(131) { i ->
                // Timestamps deliberately run backwards: pagination uses insertion order.
                db.execSQL("""INSERT INTO movements (id, type, target_id, source_id, external_source, litres, occurred_at, note)
                    VALUES (?, ?, 'Silos 1', ?, ?, '10', ?, '')""",
                    arrayOf<Any?>("top-up-$i", if (i % 2 == 0) "RECEIPT" else "TRANSFER",
                        if (i % 2 == 0) null else "Kryst. 1", if (i % 2 == 0) "Dostawa $i" else null, 1000L - i))
                db.execSQL("""INSERT INTO movements (id, type, target_id, litres, occurred_at, note)
                    VALUES (?, 'SET_STATE', 'Silos 1', '20', 2000, '')""", arrayOf("edit-$i"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        assertEquals(100, repository.history("Silos 1").size)
        val pages = mutableListOf<TopUpsPage>()
        var cursor: Long? = null
        do {
            val page = repository.topUps("Silos 1", cursor)
            pages.add(page)
            cursor = page.nextBeforeRowId
        } while (cursor != null)
        assertEquals(listOf(50, 50, 31), pages.map { it.events.size })
        assertTrue(pages.all { it.totalCount == 131 })
        assertEquals((130 downTo 0).map { "top-up-$it" }, pages.flatMap { it.events }.map { it.id })
        assertTrue(pages.flatMap { it.events }.all { it.previousLitres == null })
        assertEquals(0, repository.topUps("Silos 2").totalCount)
    }

    @Test fun repeatingTheSameRequestDoesNotDoubleCountTransferOrAddHistory() {
        initialize()
        val operation: (InventoryRules) -> InventoryChange = {
            it.transfer("Silos 1", "Kryst. 1", BigDecimal("1000"), Measurements(), 400L)
        }
        val first = repository.apply("same-request", operation)
        val retried = repository.apply("same-request", operation)
        assertEquals(first, retried)
        assertEquals(1, repository.history("Silos 1").count { it.id == "same-request" })
    }

    @Test fun historyInsertFailureRollsBackBothTankUpdates() {
        initialize()
        val before = repository.load()
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_history BEFORE INSERT ON movements
            BEGIN SELECT RAISE(ABORT, 'simulated history failure'); END""")
        assertThrows(SQLiteException::class.java) {
            repository.apply { it.transfer("Silos 1", "Kryst. 1", BigDecimal("1000"), Measurements(), 400L) }
        }
        assertEquals(before, repository.load())
        assertEquals(1, repository.history("Silos 1").size)
        assertEquals(1, repository.history("Kryst. 1").size)
    }

    @Test fun rejectedTransferLeavesPersistentStockAndHistoryUnchanged() {
        initialize()
        val before = repository.load()
        assertThrows(IllegalArgumentException::class.java) {
            repository.apply { it.transfer("Silos 1", "Kryst. 1", BigDecimal("6000"), Measurements(), 400L) }
        }
        assertEquals(before, repository.load())
    }

    @Test fun oilTypePersistsSeparatelyFromStockAndAppearsInHistory() {
        repository.apply { it.setOilType("Olej 12", "Rzepakowy", 100L, 2) }
        repository.apply { it.setState("Olej 12", BigDecimal.ZERO, Measurements(), 200L) }
        repository.close()
        repository = InventoryRepository(context)
        val state = repository.load().states.getValue("Olej 12")
        assertEquals("Rzepakowy", state.oilType)
        assertEquals(0, state.litres!!.signum())
        assertNull(state.filledAt)
        assertEquals("Rzepakowy", repository.history("Olej 12").last().oilType)
    }

    @Test fun schemaThreeUpgradePreservesStockMaterialAndHistoryWhenAddingOilType() {
        context.openOrCreateDatabase(InventoryDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE tank_states (
                tank_id TEXT PRIMARY KEY, litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT, filled_at INTEGER,
                evaporator TEXT, crystallizer INTEGER, material TEXT NOT NULL DEFAULT ''
            )""")
            db.execSQL("""CREATE TABLE movements (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, source_id TEXT, target_id TEXT NOT NULL,
                external_source TEXT, litres TEXT NOT NULL, previous_litres TEXT,
                brix TEXT, ph TEXT, sh TEXT, temperature TEXT,
                occurred_at INTEGER NOT NULL, note TEXT NOT NULL, shift INTEGER, evaporator TEXT, crystallizer INTEGER,
                material TEXT NOT NULL DEFAULT ''
            )""")
            db.execSQL("INSERT INTO tank_states (tank_id, litres, material, filled_at) VALUES ('Olej 12', '5000', 'Partia A', 100)")
            db.execSQL("""INSERT INTO movements (id, type, target_id, litres, occurred_at, note, material)
                VALUES ('before-oil-type', 'SET_STATE', 'Olej 12', '5000', 100, 'Pomiar', 'Partia A')""")
            db.version = 3
        }
        val state = repository.load().states.getValue("Olej 12")
        assertEquals(BigDecimal("5000"), state.litres)
        assertEquals("Partia A", state.material)
        assertEquals(100L, state.filledAt)
        assertEquals("", state.oilType)
        assertEquals("before-oil-type", repository.history("Olej 12").single().id)
        repository.apply { it.setOilType("Olej 12", "Rzepakowy", 200L) }
        assertEquals(state.copy(oilType = "Rzepakowy"), repository.load().states.getValue("Olej 12"))
    }

    @Test fun routingCanBeSavedBeforeStockIsKnownAndClearedAfterReopening() {
        repository.apply { it.setRouting("Silos 2", TankRouting("F", 6), 100L) }
        repository.close()
        repository = InventoryRepository(context)
        assertNull(repository.load().states["Silos 2"]!!.litres)
        assertEquals(TankRouting("F", 6), repository.load().states["Silos 2"]!!.routing)
        repository.apply { it.setRouting("Silos 2", TankRouting(), 200L) }
        assertEquals(TankRouting(), repository.load().states["Silos 2"]!!.routing)
        assertEquals(2, repository.history("Silos 2").size)
    }

    @Test fun stoppingProductionClearsOnlyRoutingAndRecordsTheReasonExactlyOnce() {
        initialize()
        repository.apply { it.updateMeasurements("Silos 1", Measurements(brix = BigDecimal("19.5"), ph = BigDecimal("6.7")), 300L) }
        repository.apply { it.setRouting("Silos 1", TankRouting("C", 3), 400L) }
        val before = repository.load().states.getValue("Silos 1")
        val operation: (InventoryRules) -> InventoryChange = { it.resetRouting("Silos 1", 500L, 2) }
        repository.apply("stop-production", operation)
        repository.apply("stop-production", operation)
        repository.close()
        repository = InventoryRepository(context)
        assertEquals(before.copy(routing = TankRouting()), repository.load().states.getValue("Silos 1"))
        val history = repository.history("Silos 1")
        assertEquals(1, history.count { it.id == "stop-production" })
        assertEquals("Produkcja wstrzymana — wyzerowano trasę.", history.first().note)
        assertEquals(2, history.first().shift)
        assertEquals(300L, history.first().laboratoryMeasuredAt)
    }

    @Test fun schemaUpgradeAddsRoutingWithoutLosingExistingStockOrHistory() {
        context.openOrCreateDatabase(InventoryDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE tank_states (
                tank_id TEXT PRIMARY KEY, litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT, filled_at INTEGER
            )""")
            db.execSQL("""CREATE TABLE movements (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, source_id TEXT, target_id TEXT NOT NULL,
                external_source TEXT, litres TEXT NOT NULL, previous_litres TEXT,
                brix TEXT, ph TEXT, sh TEXT, temperature TEXT,
                occurred_at INTEGER NOT NULL, note TEXT NOT NULL, shift INTEGER
            )""")
            db.execSQL("INSERT INTO tank_states (tank_id, litres, brix, filled_at) VALUES ('Silos 1', '1234.5', '19.5', 100)")
            db.execSQL("""INSERT INTO movements (id, type, target_id, litres, occurred_at, note)
                VALUES ('before-upgrade', 'SET_STATE', 'Silos 1', '1234.5', 100, 'Przed migracją')""")
            db.version = 1
        }
        val upgraded = repository.load()
        assertEquals(BigDecimal("1234.5"), upgraded.states["Silos 1"]!!.litres)
        assertEquals(BigDecimal("19.5"), upgraded.states["Silos 1"]!!.measurements.brix)
        assertEquals(100L, upgraded.states["Silos 1"]!!.filledAt)
        assertEquals(TankRouting(), upgraded.states["Silos 1"]!!.routing)
        assertEquals("before-upgrade", repository.history("Silos 1").single().id)
        repository.apply { it.setRouting("Silos 1", TankRouting("F", 2), 200L) }
        assertEquals(BigDecimal("1234.5"), repository.load().states["Silos 1"]!!.litres)
        assertEquals(TankRouting("F", 2), repository.load().states["Silos 1"]!!.routing)
        assertEquals(2, repository.history("Silos 1").size)
    }

    @Test fun manualContentsArePersistedEvenWhenQuantityIsNotKnown() {
        repository.apply { it.updateDetails("LAT 2", "Śmietana — partia A", Measurements(temperature = BigDecimal("4.2")), 100L) }
        repository.close()
        repository = InventoryRepository(context)
        val state = repository.load().states["LAT 2"]!!
        assertNull(state.litres)
        assertNull(state.filledAt)
        assertEquals("Śmietana — partia A", state.material)
        assertEquals(BigDecimal("4.2"), state.measurements.temperature)
        assertEquals("Śmietana — partia A", repository.history("LAT 2").single().material)
    }

    @Test fun fatPercentPersistsThroughManualCorrectionReceiptTransferAndReopening() {
        repository.apply { it.setState("M-Tank 1", BigDecimal("1000"), Measurements(fatPercent = BigDecimal("40.5")), 100L, material = "Śmietana") }
        repository.apply { it.setState("M-Tank 2", BigDecimal.ZERO, Measurements(), 100L) }
        repository.apply { it.receive("M-Tank 1", BigDecimal("500"), "Dostawa", Measurements(fatPercent = BigDecimal("42")), 200L, material = "Śmietana — partia A") }
        repository.apply { it.transfer("M-Tank 1", "M-Tank 2", BigDecimal("250"), Measurements(fatPercent = BigDecimal("41.25")), 300L, material = "Śmietana — partia A") }
        repository.close()
        repository = InventoryRepository(context)
        val states = repository.load().states
        assertEquals(BigDecimal("42"), states.getValue("M-Tank 1").measurements.fatPercent)
        assertEquals(BigDecimal("41.25"), states.getValue("M-Tank 2").measurements.fatPercent)
        assertNull(states.getValue("M-Tank 2").measurements.brix)
        assertEquals(BigDecimal("1250"), states.getValue("M-Tank 1").litres)
        assertEquals(BigDecimal("250"), states.getValue("M-Tank 2").litres)
        assertEquals(BigDecimal("41.25"), repository.history("M-Tank 1").first().measurements.fatPercent)
        assertEquals(BigDecimal("40.5"), repository.history("M-Tank 1").last().measurements.fatPercent)
        repository.apply { it.setState("M-Tank 2", BigDecimal.ZERO, Measurements(), 400L) }
        assertNull(repository.load().states.getValue("M-Tank 2").measurements.fatPercent)
        assertEquals(BigDecimal("41.25"), repository.history("M-Tank 2")[1].measurements.fatPercent)
    }

    @Test fun schemaFourUpgradePreservesExistingMeasurementsWithoutTreatingBrixAsFat() {
        context.openOrCreateDatabase(InventoryDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE tank_states (
                tank_id TEXT PRIMARY KEY, litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT, filled_at INTEGER,
                evaporator TEXT, crystallizer INTEGER, material TEXT NOT NULL DEFAULT '', oil_type TEXT NOT NULL DEFAULT ''
            )""")
            db.execSQL("""CREATE TABLE movements (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, source_id TEXT, target_id TEXT NOT NULL,
                external_source TEXT, litres TEXT NOT NULL, previous_litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT,
                occurred_at INTEGER NOT NULL, note TEXT NOT NULL, shift INTEGER, evaporator TEXT, crystallizer INTEGER,
                material TEXT NOT NULL DEFAULT '', oil_type TEXT NOT NULL DEFAULT ''
            )""")
            db.execSQL("INSERT INTO tank_states (tank_id, litres, brix, ph, filled_at, material) VALUES ('M-Tank 1', '1000', '19.5', '6.7', 100, 'Śmietana')")
            db.execSQL("INSERT INTO tank_states (tank_id, litres, oil_type) VALUES ('Olej 12', '5000', 'Rzepakowy')")
            db.execSQL("""INSERT INTO movements (id, type, target_id, litres, brix, occurred_at, note, material)
                VALUES ('before-fat', 'SET_STATE', 'M-Tank 1', '1000', '19.5', 100, 'Pomiar', 'Śmietana')""")
            db.version = 4
        }
        val state = repository.load().states.getValue("M-Tank 1")
        assertEquals(BigDecimal("19.5"), state.measurements.brix)
        assertNull(state.measurements.fatPercent)
        assertEquals(BigDecimal("6.7"), state.measurements.ph)
        assertEquals("Śmietana", state.material)
        assertEquals(100L, state.filledAt)
        assertEquals("Rzepakowy", repository.load().states.getValue("Olej 12").oilType)
        assertEquals(BigDecimal("19.5"), repository.history("M-Tank 1").single().measurements.brix)
        assertNull(repository.history("M-Tank 1").single().measurements.fatPercent)
        repository.apply { it.updateDetails("M-Tank 1", "Śmietana", Measurements(fatPercent = BigDecimal("40.5")), 200L) }
        assertEquals(BigDecimal("40.5"), repository.load().states.getValue("M-Tank 1").measurements.fatPercent)
        assertEquals(100L, repository.load().states.getValue("M-Tank 1").filledAt)
        assertEquals(BigDecimal("19.5"), repository.history("M-Tank 1").last().measurements.brix)
    }

    @Test fun schemaTwoUpgradePreservesExistingRouteWhileAddingContentsField() {
        context.openOrCreateDatabase(InventoryDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE tank_states (
                tank_id TEXT PRIMARY KEY, litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT, filled_at INTEGER,
                evaporator TEXT, crystallizer INTEGER
            )""")
            db.execSQL("""CREATE TABLE movements (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, source_id TEXT, target_id TEXT NOT NULL,
                external_source TEXT, litres TEXT NOT NULL, previous_litres TEXT,
                brix TEXT, ph TEXT, sh TEXT, temperature TEXT,
                occurred_at INTEGER NOT NULL, note TEXT NOT NULL, shift INTEGER, evaporator TEXT, crystallizer INTEGER
            )""")
            db.execSQL("INSERT INTO tank_states (tank_id, litres, evaporator, crystallizer) VALUES ('Silos 1', '5000', 'F', 2)")
            db.version = 2
        }
        val state = repository.load().states["Silos 1"]!!
        assertEquals(TankRouting("F", 2), state.routing)
        assertEquals(BigDecimal("5000"), state.litres)
        assertEquals("", state.material)
        repository.apply { it.updateDetails("Silos 1", "Serwatka", Measurements(), 200L) }
        assertEquals("Serwatka", repository.load().states["Silos 1"]!!.material)
        assertEquals(TankRouting("F", 2), repository.load().states["Silos 1"]!!.routing)
    }

    @Test fun laboratoryMarkerPersistsAndHistoryRetainsItAfterManualMeasurementCorrection() {
        repository.apply { it.setState("Silos 1", BigDecimal("1000"), Measurements(), 100L, material = "Serwatka") }
        repository.apply { it.updateMeasurements("Silos 1", Measurements(brix = BigDecimal("20")), 500L, 1) }
        repository.close()
        repository = InventoryRepository(context)
        val saved = repository.load().states.getValue("Silos 1")
        assertEquals(500L, saved.laboratoryMeasuredAt)
        assertEquals(100L, saved.filledAt)
        assertEquals("Serwatka", saved.material)
        assertEquals(500L, repository.history("Silos 1").first().laboratoryMeasuredAt)
        repository.apply { it.setState("Silos 1", BigDecimal("900"), Measurements(brix = BigDecimal("20.0")), 600L, material = "Serwatka") }
        assertEquals(500L, repository.load().states.getValue("Silos 1").laboratoryMeasuredAt)
        repository.apply { it.updateDetails("Silos 1", "Serwatka", Measurements(brix = BigDecimal("21")), 700L) }
        assertNull(repository.load().states.getValue("Silos 1").laboratoryMeasuredAt)
        assertEquals(500L, repository.history("Silos 1")[2].laboratoryMeasuredAt)
    }

    @Test fun schemaFiveUpgradePreservesFatAndDoesNotMarkManualMeasurementsAsLaboratory() {
        context.openOrCreateDatabase(InventoryDatabase.NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE tank_states (
                tank_id TEXT PRIMARY KEY, litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT, filled_at INTEGER,
                evaporator TEXT, crystallizer INTEGER, material TEXT NOT NULL DEFAULT '', oil_type TEXT NOT NULL DEFAULT '', fat_percent TEXT
            )""")
            db.execSQL("""CREATE TABLE movements (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, source_id TEXT, target_id TEXT NOT NULL,
                external_source TEXT, litres TEXT NOT NULL, previous_litres TEXT, brix TEXT, ph TEXT, sh TEXT, temperature TEXT,
                occurred_at INTEGER NOT NULL, note TEXT NOT NULL, shift INTEGER, evaporator TEXT, crystallizer INTEGER,
                material TEXT NOT NULL DEFAULT '', oil_type TEXT NOT NULL DEFAULT '', fat_percent TEXT
            )""")
            db.execSQL("INSERT INTO tank_states (tank_id, litres, fat_percent, filled_at, material) VALUES ('M-Tank 1', '1000', '41', 100, 'Śmietana')")
            db.execSQL("""INSERT INTO movements (id, type, target_id, litres, fat_percent, occurred_at, note)
                VALUES ('before-lab', 'SET_STATE', 'M-Tank 1', '1000', '41', 100, 'Pomiar ręczny')""")
            db.version = 5
        }
        val saved = repository.load().states.getValue("M-Tank 1")
        assertEquals(BigDecimal("41"), saved.measurements.fatPercent)
        assertEquals("Śmietana", saved.material)
        assertEquals(100L, saved.filledAt)
        assertNull(saved.laboratoryMeasuredAt)
        assertNull(repository.history("M-Tank 1").single().laboratoryMeasuredAt)
        repository.apply { it.updateMeasurements("M-Tank 1", saved.measurements, 500L) }
        assertEquals(500L, repository.load().states.getValue("M-Tank 1").laboratoryMeasuredAt)
    }
}

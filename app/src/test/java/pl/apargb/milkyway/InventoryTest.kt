package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class InventoryTest {
    private val measurements = Measurements(brix = BigDecimal("6.5"), ph = BigDecimal("6.7"),
        sh = BigDecimal("7.2"), temperature = BigDecimal("4.5"))
    private fun rules(states: Map<String, TankStatus>) = InventoryRules(AppContent.tanks, states)
    private fun states(source: String = "1000", target: String = "200") = mapOf(
        "LBT 1" to TankStatus(source.toBigDecimal(), measurements, 100L),
        "LAT 1" to TankStatus(target.toBigDecimal(), Measurements(), 200L)
    )
    private fun assertVolume(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, expected.toBigDecimal().compareTo(actual))
    }
    private fun reject(operation: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, operation)
    }

    @Test fun transferUpdatesBothTanksAndConservesVolumeExactly() {
        val result = rules(states("1000.125", "200.375"))
            .transfer("LBT 1", "LAT 1", BigDecimal("300.025"), measurements, 500L, "Próba", 2)
        assertVolume("700.100", result.states["LBT 1"]?.litres)
        assertVolume("500.400", result.states["LAT 1"]?.litres)
        assertVolume("1200.5", result.states.values.mapNotNull { it.litres }.reduce(BigDecimal::add))
        assertEquals("LBT 1", result.movement.sourceId)
        assertEquals("LAT 1", result.movement.targetId)
        assertEquals("Próba", result.movement.note)
        assertEquals(2, result.movement.shift)
        assertEquals(100L, result.states["LBT 1"]?.filledAt)
        assertEquals(500L, result.states["LAT 1"]?.filledAt)
    }

    @Test fun receiptAddsVolumeAndRecordsItsSourceAndAutomaticDate() {
        val result = rules(states()).receive("LAT 1", BigDecimal("120.25"), " Dostawa A ", measurements, 500L)
        assertVolume("320.25", result.states["LAT 1"]?.litres)
        assertEquals("Dostawa A", result.movement.externalSource)
        assertEquals(500L, result.states["LAT 1"]?.filledAt)
        assertEquals(measurements, result.states["LAT 1"]?.measurements)
    }

    @Test fun drainingCompletelyClearsCurrentMeasurementsAndFillDate() {
        val result = rules(states()).transfer("LBT 1", "LAT 1", BigDecimal("1000"), measurements, 500L)
        assertVolume("0", result.states["LBT 1"]?.litres)
        assertNull(result.states["LBT 1"]?.filledAt)
        assertEquals(Measurements(), result.states["LBT 1"]?.measurements)
        val corrected = rules(states()).setState("LBT 1", BigDecimal.ZERO, measurements, 500L)
        assertEquals(Measurements(), corrected.states["LBT 1"]?.measurements)
        assertNull(corrected.states["LBT 1"]?.filledAt)
    }

    @Test fun unknownLevelsAreNotAssumedToBeEmpty() {
        reject { rules(emptyMap()).transfer("LBT 1", "LAT 1", BigDecimal.ONE, measurements, 500L) }
        reject { rules(states().filterKeys { it != "LAT 1" }).transfer("LBT 1", "LAT 1", BigDecimal.ONE, measurements, 500L) }
        reject { rules(emptyMap()).receive("LAT 1", BigDecimal.ONE, "Dostawa", measurements, 500L) }
    }

    @Test fun insufficientSourceOrExcessCapacityIsRejectedBeforeAnyUpdate() {
        reject { rules(states()).transfer("LBT 1", "LAT 1", BigDecimal("1001"), measurements, 500L) }
        reject { rules(states(target = "99999")).transfer("LBT 1", "LAT 1", BigDecimal("2"), measurements, 500L) }
        reject { rules(states(target = "99999")).receive("LAT 1", BigDecimal("2"), "Dostawa", measurements, 500L) }
        val allowed = rules(states(target = "99999")).receive("LAT 1", BigDecimal.ONE, "Dostawa", measurements, 500L)
        assertVolume("100000", allowed.states["LAT 1"]?.litres)
    }

    @Test fun zeroNegativeSelfTransfersAndExcessPrecisionAreRejected() {
        listOf("0", "-1", "0.0001").forEach {
            reject { rules(states()).transfer("LBT 1", "LAT 1", it.toBigDecimal(), measurements, 500L) }
            reject { rules(states()).receive("LAT 1", it.toBigDecimal(), "Dostawa", measurements, 500L) }
        }
        reject { rules(states()).transfer("LBT 1", "LBT 1", BigDecimal.ONE, measurements, 500L) }
        reject { rules(states()).setState("LBT 1", BigDecimal("-1"), measurements, 500L) }
    }

    @Test fun onlyIncreasingStockChangesFillDateAndFirstPositiveStateGetsDate() {
        val edited = rules(states()).setState("LBT 1", BigDecimal("1000"), Measurements(ph = BigDecimal("6.8")), 500L)
        assertEquals(100L, edited.states["LBT 1"]?.filledAt)
        val reduced = rules(states()).setState("LBT 1", BigDecimal("900"), measurements, 500L)
        assertEquals(100L, reduced.states["LBT 1"]?.filledAt)
        val increased = rules(states()).setState("LBT 1", BigDecimal("1001"), measurements, 500L)
        assertEquals(500L, increased.states["LBT 1"]?.filledAt)
        val first = rules(emptyMap()).setState("LBT 1", BigDecimal.ONE, measurements, 500L)
        assertEquals(500L, first.states["LBT 1"]?.filledAt)
    }

    @Test fun newFillDoesNotRetainStaleMeasurementsOrComputeAverages() {
        val result = rules(states()).receive("LBT 1", BigDecimal.ONE, "Dostawa", Measurements(), 500L)
        assertEquals(Measurements(), result.states["LBT 1"]?.measurements)
    }

    @Test fun invalidMeasurementsAndMissingReceiptSourceAreRejected() {
        listOf(Measurements(ph = BigDecimal("15")), Measurements(brix = BigDecimal("101")),
            Measurements(sh = BigDecimal("-1"))).forEach { m ->
            reject { rules(states()).receive("LAT 1", BigDecimal.ONE, "Dostawa", m, 500L) }
        }
        reject { rules(states()).receive("LAT 1", BigDecimal.ONE, " ", measurements, 500L) }
        Measurements(temperature = BigDecimal("-10")).validate()
    }

    @Test fun numericFieldsAcceptPolishCommaButRejectMalformedValues() {
        assertEquals(BigDecimal("6.25"), parseDecimal("6,25", "Brix"))
        assertNull(parseDecimal("", "SH"))
        listOf("NaN", "1,2,3", "12abc", "1e3").forEach { reject { parseDecimal(it, "Ilość") } }
        reject { parseDecimal("", "Ilość", required = true) }
    }

    @Test fun fatPercentageAcceptsEndpointsAndRejectsImpossibleMeasurements() {
        listOf("0", "40.5", "100").forEach { Measurements(fatPercent = it.toBigDecimal()).validate() }
        listOf("-0.1", "100.01").forEach { value ->
            reject { rules(emptyMap()).setState("M-Tank 1", BigDecimal.ONE, Measurements(fatPercent = value.toBigDecimal()), 100L) }
        }
    }

    @Test fun routingOnlyAcceptsCOrFAndActualCrystallizerNumbers() {
        listOf("C", "F").forEach { evaporator ->
            crystallizerNumbers.forEach { number -> TankRouting(evaporator, number).validate() }
        }
        TankRouting().validate()
        reject { TankRouting("A", 1).validate() }
        reject { TankRouting("C", 7).validate() }
        reject { rules(emptyMap()).setRouting("LBT 1", TankRouting("C", 1), 500L) }
    }

    @Test fun routeMarkerDoesNotChangeVolumeMeasurementsOrDateAndCanBeCleared() {
        val old = TankStatus(BigDecimal("5000"), measurements, 100L)
        val changed = rules(mapOf("Silos 1" to old)).setRouting("Silos 1", TankRouting("F", 3), 500L)
        assertEquals(old.copy(routing = TankRouting("F", 3)), changed.states["Silos 1"])
        assertEquals(MovementType.ROUTING, changed.movement.type)
        val cleared = rules(changed.states).setRouting("Silos 1", TankRouting(), 600L)
        assertEquals(old, cleared.states["Silos 1"])
    }

    @Test fun routingPersistsDuringReceiptsAndTransfersIncludingDraining() {
        val route = TankRouting("C", 6)
        val old = mapOf("Silos 1" to TankStatus(BigDecimal("1000"), measurements, 100L, route),
            "Kryst. 6" to TankStatus(BigDecimal.ZERO))
        val received = rules(old).receive("Silos 1", BigDecimal.ONE, "Dostawa", measurements, 500L)
        assertEquals(route, received.states["Silos 1"]?.routing)
        val moved = rules(old).transfer("Silos 1", "Kryst. 6", BigDecimal("1000"), measurements, 500L)
        assertEquals(route, moved.states["Silos 1"]?.routing)
        assertEquals(route, moved.movement.routing)
    }

    @Test fun manualQuantityAndContentsCorrectionsPreserveRoutingAndAuditPreviousQuantity() {
        val old = TankStatus(BigDecimal("1000"), measurements, 100L, TankRouting("F", 3), "Serwatka")
        val changed = rules(mapOf("Silos 1" to old)).setState("Silos 1", BigDecimal("900"), measurements,
            500L, "Korekta pomiaru", material = " Maślanka ")
        assertVolume("900", changed.states["Silos 1"]?.litres)
        assertEquals("Maślanka", changed.states["Silos 1"]?.material)
        assertEquals(old.routing, changed.states["Silos 1"]?.routing)
        assertEquals(100L, changed.states["Silos 1"]?.filledAt)
        assertVolume("1000", changed.movement.previousLitres)
        assertEquals("Maślanka", changed.movement.material)
    }

    @Test fun editingOnlyContentsDoesNotInventQuantityOrChangeFillDate() {
        val unknown = rules(emptyMap()).updateDetails("Silos 1", "Serwatka", measurements, 500L)
        assertNull(unknown.states["Silos 1"]?.litres)
        assertNull(unknown.states["Silos 1"]?.filledAt)
        assertEquals("Serwatka", unknown.states["Silos 1"]?.material)
        val known = TankStatus(BigDecimal("1000"), measurements, 100L, material = "Serwatka")
        val edited = rules(mapOf("Silos 1" to known)).updateDetails("Silos 1", "Maślanka", measurements, 500L)
        assertVolume("1000", edited.states["Silos 1"]?.litres)
        assertEquals(100L, edited.states["Silos 1"]?.filledAt)
    }

    @Test fun oilTypeCanBeChangedBeforeStockIsKnownAndSurvivesEmptyingWithoutChangingOtherFields() {
        val named = rules(emptyMap()).setOilType("Olej 12", " Rzepakowy ", 100L)
        assertNull(named.states["Olej 12"]!!.litres)
        assertNull(named.states["Olej 12"]!!.filledAt)
        assertEquals("Rzepakowy", named.states["Olej 12"]!!.oilType)
        assertEquals(MovementType.OIL_TYPE, named.movement.type)
        val filled = rules(named.states).setState("Olej 12", BigDecimal("1000"), measurements, 200L)
        val empty = rules(filled.states).setState("Olej 12", BigDecimal.ZERO, measurements, 300L)
        assertEquals("Rzepakowy", empty.states["Olej 12"]!!.oilType)
        val cleared = rules(empty.states).setOilType("Olej 12", "", 400L)
        assertEquals(empty.states["Olej 12"]!!.copy(oilType = ""), cleared.states["Olej 12"])
        reject { rules(emptyMap()).setOilType("LBT 1", "Rzepakowy", 500L) }
    }

    @Test fun transferPreservesSourceContentsAndWritesDestinationContentsWhileDrainingClearsThem() {
        val before = mapOf("Silos 1" to TankStatus(BigDecimal("1000"), material = "Serwatka"),
            "Kryst. 1" to TankStatus(BigDecimal.ZERO))
        val partial = rules(before).transfer("Silos 1", "Kryst. 1", BigDecimal("500"), measurements, 500L, material = "Serwatka — partia A")
        assertEquals("Serwatka", partial.states["Silos 1"]?.material)
        assertEquals("Serwatka — partia A", partial.states["Kryst. 1"]?.material)
        assertEquals("Serwatka — partia A", partial.movement.material)
        val empty = rules(before).transfer("Silos 1", "Kryst. 1", BigDecimal("1000"), measurements, 500L, material = "Serwatka")
        assertEquals("", empty.states["Silos 1"]?.material)
    }

    @Test fun oilExpiryBoundaryAndMissingOrEmptyStock() {
        val today = java.time.LocalDate.of(2026, 10, 10)
        fun state(days: Long, litres: BigDecimal? = BigDecimal.ONE) = TankStatus(litres = litres,
            oilBatch = OilBatch(today.minusDays(5), today.plusDays(days)))
        assertFalse(state(3).oilExpiresSoon(today))
        assertTrue(state(2).oilExpiresSoon(today))
        assertTrue(state(0).oilExpiresSoon(today))
        assertTrue(state(-1).oilExpiresSoon(today))
        assertFalse(state(-1, BigDecimal.ZERO).oilExpiresSoon(today))
        assertFalse(state(1, null).oilExpiresSoon(today))
        assertFalse(TankStatus(BigDecimal.ONE).oilExpiresSoon(today))
        reject { OilBatch(today, today.minusDays(1)).validate() }
    }

    @Test fun resetClearsCurrentBatchAndMeasurementsAndKeepsAuditOfPreviousVolume() {
        val date = java.time.LocalDate.of(2026, 10, 10)
        val old = TankStatus(BigDecimal("200"), measurements, 100L, material = "Olej", oilType = "Rzepakowy",
            oilBatch = OilBatch(date, date.plusDays(2)))
        val change = rules(mapOf("Olej 12" to old)).resetTank("Olej 12", 200L, 3)
        assertEquals(TankStatus(litres = BigDecimal.ZERO), change.states.getValue("Olej 12"))
        assertEquals(BigDecimal("200"), change.movement.previousLitres)
        assertEquals(MovementType.SET_STATE, change.movement.type)
        assertEquals(3, change.movement.shift)
    }

}

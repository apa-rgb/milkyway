package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ProductionTest {
    private val powder = AppContent.tanks.single { it.id == "Silos 1" }
    private val butter = AppContent.tanks.single { it.id == "M-Tank 1" }
    private fun assumptions(density: String, content: String, recovery: String = "100") =
        ProductionAssumptions(density.toBigDecimal(), content.toBigDecimal(), recovery.toBigDecimal())

    @Test fun powderEstimateUsesDensityBrixProductDryMatterAndRecovery() {
        val state = TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("20")))
        val theoretical = estimateProduction(powder, state, assumptions("1.05", "96"))
        assertEquals(0, BigDecimal("218.75").compareTo(theoretical.kilograms))
        val reduced = estimateProduction(powder, state, assumptions("1.05", "96", "96"))
        assertEquals(0, BigDecimal("210").compareTo(reduced.kilograms))
        val increased = estimateProduction(powder, state.copy(litres = BigDecimal("2000")), assumptions("1.05", "96"))
        assertEquals(0, BigDecimal("437.5").compareTo(increased.kilograms))
    }

    @Test fun butterEstimateUsesFatRatherThanBrixAndNeverCapacity() {
        val state = TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("90"), fatPercent = BigDecimal("41")))
        assertEquals(0, BigDecimal("500").compareTo(estimateProduction(butter, state, assumptions("1", "82")).kilograms))
    }

    @Test fun unknownInputsRemainUnknownAndEmptyTanksHaveZeroProduction() {
        assertNull(estimateProduction(powder, TankStatus(), assumptions("1", "96")).kilograms)
        assertNull(estimateProduction(powder, TankStatus(BigDecimal("1000")), assumptions("1", "96")).kilograms)
        assertNull(estimateProduction(butter, TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("20"))), assumptions("1", "82")).kilograms)
        assertNull(estimateProduction(powder, TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("20"))), ProductionAssumptions()).kilograms)
        assertEquals(BigDecimal.ZERO, estimateProduction(butter, TankStatus(BigDecimal.ZERO), ProductionAssumptions()).kilograms)
    }

    @Test fun impossibleAssumptionsAreRejectedAndZeroRecoveryIsAllowed() {
        listOf(assumptions("0", "96"), assumptions("-1", "96"), assumptions("1", "0"),
            assumptions("1", "101"), assumptions("1", "96", "-1"), assumptions("1", "96", "101")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        val zero = estimateProduction(powder, TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("20"))), assumptions("1", "96", "0"))
        assertEquals(0, zero.kilograms!!.signum())
    }

    @Test fun laboratoryPinRequiresTheExactFourDigits() {
        assertTrue(laboratoryPinMatches("2426"))
        listOf("", "242", "24260", "1234", " 2426", "٢٤٢٦").forEach { assertFalse(laboratoryPinMatches(it)) }
    }

    @Test fun laboratoryMeasurementsPreserveStockContentsAndFillDate() {
        val old = TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("20")), 100L, material = "Serwatka")
        val values = Measurements(brix = BigDecimal("21"), ph = BigDecimal("6.7"))
        val change = InventoryRules(AppContent.tanks, mapOf(powder.id to old)).updateMeasurements(powder.id, values, 500L, 2)
        assertEquals(old.copy(measurements = values, laboratoryMeasuredAt = 500L), change.states[powder.id])
        assertEquals(500L, change.movement.occurredAt)
        assertEquals("Pomiar laboratoryjny", change.movement.note)
        assertEquals(2, change.movement.shift)
        assertThrows(IllegalArgumentException::class.java) {
            InventoryRules(AppContent.tanks, mapOf(powder.id to TankStatus(BigDecimal.ZERO))).updateMeasurements(powder.id, values, 600L)
        }
    }

    @Test fun laboratoryMarkerSurvivesStockCorrectionAndIsClearedByManualMeasurementChanges() {
        val powderValues = Measurements(brix = BigDecimal("20.0"), ph = BigDecimal("6.7"))
        val old = TankStatus(BigDecimal("1000"), powderValues, 100L, material = "Serwatka", laboratoryMeasuredAt = 500L)
        val rules = InventoryRules(AppContent.tanks, mapOf(powder.id to old))
        val equivalent = powderValues.copy(brix = BigDecimal("20"))
        val correction = rules.setState(powder.id, BigDecimal("900"), equivalent, 600L, material = "Serwatka")
        assertEquals(500L, correction.states[powder.id]!!.laboratoryMeasuredAt)
        val changed = rules.updateDetails(powder.id, "Serwatka", powderValues.copy(ph = BigDecimal("6.8")), 600L)
        assertNull(changed.states[powder.id]!!.laboratoryMeasuredAt)
        assertEquals(500L, rules.updateDetails(powder.id, "Partia A", equivalent, 600L).states[powder.id]!!.laboratoryMeasuredAt)
        assertThrows(IllegalArgumentException::class.java) { rules.updateMeasurements(powder.id, Measurements(), 600L) }
    }

    @Test fun incomingMaterialAndEmptyingClearTheCurrentLaboratoryMarker() {
        val old = TankStatus(BigDecimal("1000"), Measurements(brix = BigDecimal("20")), laboratoryMeasuredAt = 500L)
        val target = TankStatus(BigDecimal("100"), old.measurements, laboratoryMeasuredAt = 500L)
        val rules = InventoryRules(AppContent.tanks, mapOf(powder.id to old, "Kryst. 1" to target))
        assertNull(rules.receive(powder.id, BigDecimal.ONE, "Dostawa", old.measurements, 600L).states[powder.id]!!.laboratoryMeasuredAt)
        val partial = rules.transfer(powder.id, "Kryst. 1", BigDecimal("500"), old.measurements, 600L)
        assertEquals(500L, partial.states[powder.id]!!.laboratoryMeasuredAt)
        assertNull(partial.states["Kryst. 1"]!!.laboratoryMeasuredAt)
        val empty = rules.transfer(powder.id, "Kryst. 1", BigDecimal("1000"), old.measurements, 600L)
        assertNull(empty.states[powder.id]!!.laboratoryMeasuredAt)
        assertNull(rules.setState(powder.id, BigDecimal.ZERO, Measurements(), 600L).states[powder.id]!!.laboratoryMeasuredAt)
    }
}

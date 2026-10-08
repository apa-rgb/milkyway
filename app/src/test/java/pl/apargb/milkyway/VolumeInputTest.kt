package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class VolumeInputTest {
    private fun assertLitres(expected: String, text: String) {
        assertEquals(0, BigDecimal(expected).compareTo(parseCubicMetres(text)))
    }

    @Test fun convertsCommaAndDotToLitresWithoutRounding() {
        assertLitres("5500", "5,5")
        assertLitres("250", "0.25")
        assertLitres("0.001", "0,000001")
        assertLitres("12345.678", "12.345678")
        assertLitres("0", "0")
        assertLitres("1000", "1.0000000")
    }

    @Test fun reopeningExistingLitresPreservesTheExactValue() {
        listOf("0", "5500", "12345.678", "0.001", "150000").forEach { litres ->
            val existing = BigDecimal(litres)
            assertEquals(0, existing.compareTo(parseCubicMetres(existing.cubicMetresInputText())))
        }
        assertEquals("5,5", BigDecimal("5500").cubicMetresInputText())
        assertEquals("", (null as BigDecimal?).cubicMetresInputText())
    }

    @Test fun rejectsNegativeMalformedAndOverPreciseAmountsAndKeepsBlankOptional() {
        listOf("-1", "0.0000001", "NaN", "1,2,3", "1e3").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { parseCubicMetres(input) }
        }
        assertNull(parseCubicMetres(" "))
        assertThrows(IllegalArgumentException::class.java) { parseCubicMetres("", required = true) }
    }

    @Test fun convertedTransferRespectsCapacityAndConservesLitres() {
        val states = mapOf("LBT 1" to TankStatus(BigDecimal("1000.001")),
            "LAT 1" to TankStatus(BigDecimal("99999")))
        val rules = InventoryRules(AppContent.tanks, states)
        val change = rules.transfer("LBT 1", "LAT 1", parseCubicMetres("0,001")!!, Measurements(), 100L)
        assertEquals(0, BigDecimal("100000").compareTo(change.states.getValue("LAT 1").litres))
        assertEquals(0, BigDecimal("999.001").compareTo(change.states.getValue("LBT 1").litres))
        assertThrows(IllegalArgumentException::class.java) {
            rules.transfer("LBT 1", "LAT 1", parseCubicMetres("0,001001")!!, Measurements(), 100L)
        }
    }
}

package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test

class ContentTest {
    @Test fun missingCapacitiesAreNotShownAsZero() {
        assertEquals("Do uzupełnienia", capacityLabel(null))
        assertEquals(setOf("MBT 1", "MBT 2", "MBT 3", "M-Tank 5"),
            AppContent.tanks.filter { it.capacityLitres == null }.map { it.name }.toSet())
    }

    @Test fun importedCatalogHasAllTanksAndUniqueIdentifiers() {
        assertEquals(46, AppContent.tanks.size)
        assertEquals(AppContent.tanks.size, AppContent.tanks.map { it.id }.distinct().size)
        assertTrue(AppContent.tanks.all { it.description.isNotBlank() && it.group.isNotBlank() })
    }

    @Test fun capacitiesUseLitresAndPreserveSourceValues() {
        assertEquals("100 000 l", capacityLabel(100000))
        assertEquals(2000L, AppContent.tanks.single { it.name == "M-Tank 6" }.capacityLitres)
        assertEquals(150000L, AppContent.tanks.single { it.name == "Silos 6" }.capacityLitres)
    }
}

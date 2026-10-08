package pl.apargb.milkyway

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProductionPreferencesTest {
    @Test fun initialAssumptionsProvideApproximatePowderAndButterProduction() {
        val first = ProductionViewModel(RuntimeEnvironment.getApplication())
        assertEquals(ProductionAssumptions(BigDecimal.ONE, BigDecimal("96")), first.state.value[Department.Powder])
        assertEquals(ProductionAssumptions(BigDecimal.ONE, BigDecimal("82")), first.state.value[Department.Butter])
    }

    @Test fun assumptionsPersistSeparatelyForBothDepartmentsWithoutChangingStock() {
        val application = RuntimeEnvironment.getApplication()
        val first = ProductionViewModel(application)
        val powder = ProductionAssumptions(BigDecimal("1.05"), BigDecimal("96"), BigDecimal("98"))
        val butter = ProductionAssumptions(BigDecimal("0.99"), BigDecimal("82"))
        first.save(Department.Powder, powder)
        first.save(Department.Butter, butter)
        val reopened = ProductionViewModel(application)
        assertEquals(powder, reopened.state.value[Department.Powder])
        assertEquals(butter, reopened.state.value[Department.Butter])
        assertThrows(IllegalArgumentException::class.java) { reopened.save(Department.Powder, ProductionAssumptions()) }
        assertEquals(powder, reopened.state.value[Department.Powder])
    }
}

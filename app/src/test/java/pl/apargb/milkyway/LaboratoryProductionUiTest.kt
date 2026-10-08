package pl.apargb.milkyway

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaboratoryProductionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun initializeStockAndAssumptions() {
        val model = ViewModelProvider(compose.activity)[InventoryViewModel::class.java]
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        val repository = InventoryRepository(model.getApplication())
        try {
            repository.apply { it.setState("LBT 1", BigDecimal("1000"), Measurements(brix = BigDecimal("6.5")), 100L, material = "Mleko") }
            repository.apply { it.setState("Silos 1", BigDecimal("1000"), Measurements(brix = BigDecimal("20")), 100L, material = "Serwatka") }
            repository.apply { it.setState("Kryst. 1", BigDecimal.ZERO, Measurements(), 100L) }
            repository.apply { it.setState("M-Tank 1", BigDecimal("2000"), Measurements(fatPercent = BigDecimal("41")), 100L, material = "Śmietana") }
        } finally { repository.close() }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        val production = ViewModelProvider(compose.activity)[ProductionViewModel::class.java]
        compose.runOnIdle {
            production.save(Department.Powder, ProductionAssumptions(BigDecimal.ONE, BigDecimal("100")))
            production.save(Department.Butter, ProductionAssumptions(BigDecimal.ONE, BigDecimal("82")))
        }
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val view = WindowInspector.getGlobalWindowViews().last { it.isShown }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/screenshots/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun unlock() {
        compose.onNodeWithTag("lab-pin").performTextInput("2426")
        compose.onNodeWithTag("lab-unlock").performClick()
        compose.onNodeWithTag("lab-list").assertExists()
    }

    @Test fun laboratoryRequiresPinAndRelocksOnExitBackgroundAndRotation() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Laboratory").performScrollTo().performClick()
        compose.onNodeWithTag("lab-list").assertDoesNotExist()
        compose.onNodeWithTag("lab-pin").performTextInput("1111")
        compose.onNodeWithTag("lab-unlock").performClick()
        compose.onNodeWithText("Nieprawidłowy PIN.").assertExists()
        compose.onNodeWithTag("lab-list").assertDoesNotExist()
        screenshot("laboratorium-pin")
        unlock()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.onNodeWithTag("lab-tank-LBT 1").performClick()
        compose.onNodeWithText("Brix [°Bx]").performTextClearance()
        compose.onNodeWithText("Brix [°Bx]").performTextInput("7,1")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("lab-tank-LBT 1") and hasText("Brix: 7,1 °Bx")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("lab-list-marker-LBT 1", useUnmergedTree = true).assertIsDisplayed()
        val model = ViewModelProvider(compose.activity)[InventoryViewModel::class.java]
        compose.runOnIdle {
            val saved = model.state.value.overview.states.getValue("LBT 1")
            assertEquals(BigDecimal("1000"), saved.litres)
            assertEquals("Mleko", saved.material)
            assertEquals(100L, saved.filledAt)
        }
        compose.onNodeWithContentDescription("Zablokuj laboratorium").performClick()
        compose.onNodeWithTag("lab-list").assertDoesNotExist()
        unlock()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("lab-list").assertDoesNotExist()
        unlock()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("lab-list").assertDoesNotExist()
        compose.onNodeWithTag("lab-pin").assertExists()
        unlock()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.runOnIdle { assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        compose.onNodeWithTag("menu-Tanks").performScrollTo().performClick()
        compose.onNodeWithTag("lab-marker-LBT 1", useUnmergedTree = true).assertIsDisplayed()
        screenshot("zbiorniki-laboratorium")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("lab-marker-LBT 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("quantity-LBT 1").performClick()
        compose.onNodeWithText("Brix [°Bx]").performScrollTo().performTextClearance()
        compose.onNodeWithText("Brix [°Bx]").performTextInput("8")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Nowy stan [m³]").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("lab-marker-LBT 1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithTag("menu-Laboratory").performScrollTo().performClick()
        compose.onNodeWithTag("lab-list").assertDoesNotExist()
    }

    @Test fun productionTankEstimatesUseSavedAssumptionsAndUpdateAfterEditing() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Controls").performScrollTo().performClick()
        compose.onNodeWithTag("control-PRODUCTION").performClick()
        compose.onNodeWithTag("production-queue").assertIsDisplayed()
        listOf("tanks", "notes").forEach { compose.onNodeWithTag("production-$it").assertDoesNotExist() }
        compose.onNodeWithContentDescription("Wróć do kontroli parametrów").performClick()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        compose.onNodeWithTag("home-Other").assertIsDisplayed().performClick()
        listOf("tanks", "notes").forEach { compose.onNodeWithTag("other-$it").assertIsDisplayed() }
        compose.onNodeWithTag("production-queue").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("other-menu").assertExists()
        screenshot("menu-inne")
        compose.onNodeWithTag("other-notes").performClick()
        compose.onNodeWithText("Wpisy dla zmiany 1").assertExists()
        compose.onNodeWithText("Brak wpisów").assertExists()
        compose.onNodeWithContentDescription("Wróć do innych").performClick()
        compose.onNodeWithTag("other-tanks").performClick()
        compose.onNodeWithTag("estimate-Silos 1", useUnmergedTree = true).assert(hasText("≈ 200 kg"))
        compose.onNodeWithTag("production-settings").performClick()
        compose.onNodeWithText("Gęstość materiału [kg/l]").performTextClearance()
        compose.onNodeWithText("Gęstość materiału [kg/l]").performTextInput("1,05")
        compose.onNodeWithText("Sucha masa produktu [%]").performTextClearance()
        compose.onNodeWithText("Sucha masa produktu [%]").performTextInput("96")
        compose.onNodeWithText("Zapisz").performClick()
        compose.onNodeWithTag("estimate-Silos 1", useUnmergedTree = true).assert(hasText("≈ 218,8 kg"))
        screenshot("produkcja-proszkownia")
        compose.onNodeWithTag("production-tank-Silos 1").performClick()
        compose.onNodeWithText("Nowy stan [m³]").performTextClearance()
        compose.onNodeWithText("Nowy stan [m³]").performTextInput("2")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("estimate-Silos 1") and hasText("≈ 437,5 kg"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("production-department-Butter").performClick()
        compose.onNodeWithTag("estimate-M-Tank 1", useUnmergedTree = true).assert(hasText("≈ 1 000 kg"))
        compose.onNodeWithTag("production-tank-M-Tank 1").performClick()
        compose.onNodeWithText("Tłuszcz [%]").performScrollTo().performTextClearance()
        compose.onNodeWithText("Tłuszcz [%]").performTextInput("20,5")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("estimate-M-Tank 1") and hasText("≈ 500 kg"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("produkcja-maslownia")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("production-list").assertExists()
        compose.onNodeWithTag("production-department-Butter").assertIsSelected()
        compose.onNodeWithTag("estimate-M-Tank 1", useUnmergedTree = true).assert(hasText("≈ 500 kg"))
        compose.onNodeWithTag("production-department-Powder").performClick()
        compose.onNodeWithTag("estimate-Silos 1", useUnmergedTree = true).assert(hasText("≈ 437,5 kg"))
        compose.onNodeWithContentDescription("Wróć do innych").performClick()
        compose.onNodeWithTag("other-notes").performClick()
        compose.onNodeWithText("Brak wpisów").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("other-menu").assertExists()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        compose.onNodeWithTag("home-Other").assertIsDisplayed()
        screenshot("ekran-glowny-inne")
        compose.onNodeWithTag("home-Production").performClick()
        compose.onNodeWithTag("production-queue").assertIsDisplayed()
        listOf("tanks", "notes").forEach { compose.onNodeWithTag("production-$it").assertDoesNotExist() }
        screenshot("menu-produkcja")
        compose.onNodeWithTag("production-queue").performClick()
        listOf("butter", "powder", "uht").forEach { compose.onNodeWithTag("production-queue_$it").assertIsDisplayed() }
        screenshot("menu-kolejka-produkcji")
        listOf("butter" to "Masłownia", "powder" to "Proszkownia", "uht" to "UHT").forEach { (id, title) ->
            compose.onNodeWithTag("production-queue_$id").performClick()
            compose.onNodeWithTag("production-queue-page").assertExists()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("production-queue-menu").assertExists()
        }
        compose.onNodeWithContentDescription("Wróć do produkcji").performClick()
        compose.onNodeWithTag("production-queue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        compose.onNodeWithTag("home-Production").assertExists()
    }
}

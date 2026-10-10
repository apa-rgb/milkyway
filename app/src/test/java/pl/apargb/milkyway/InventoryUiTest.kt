package pl.apargb.milkyway

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventoryUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val seedDatabase = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            context.deleteDatabase(InventoryDatabase.NAME)
            val repository = InventoryRepository(context)
            try {
                repository.apply { it.setState("Silos 1", BigDecimal("5000"), Measurements(), 100L) }
                repository.apply { it.setState("Kryst. 1", BigDecimal.ZERO, Measurements(), 200L) }
            } finally { repository.close() }
        }
        override fun after() { RuntimeEnvironment.getApplication().deleteDatabase(InventoryDatabase.NAME) }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seedDatabase).around(compose)

    @Before fun seedTheApplicationUsedByTheActivityModel() {
        // Seed the same Application that this Activity's model uses, then reload it.
        // Pump the paused Robolectric main looper while waiting for IO results.
        val model = ViewModelProvider(compose.activity)[InventoryViewModel::class.java]
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        val repository = InventoryRepository(model.getApplication())
        try {
            repository.apply { it.setState("Silos 1", BigDecimal("5000"), Measurements(), 100L) }
            repository.apply { it.setState("Kryst. 1", BigDecimal.ZERO, Measurements(), 200L) }
            repository.apply { it.setRouting("Silos 1", TankRouting(), 100L) }
            repository.apply { it.setRouting("Kryst. 1", TankRouting(), 200L) }
            repository.apply { it.setState("LBT 1", BigDecimal("50000"), Measurements(), 100L) }
            repository.apply { it.setState("LBT 2", BigDecimal("100000"), Measurements(), 100L) }
            repository.apply { it.setState("MBT 1", BigDecimal("1000"), Measurements(), 100L) }
            repository.apply { it.setState("Silos 2", BigDecimal("30000"), Measurements(brix = BigDecimal("19.5"), ph = BigDecimal("6.7"), temperature = BigDecimal("-4.25")), 100L) }
            repository.apply { it.setState("Silos 3", BigDecimal("60000"), Measurements(), 100L) }
        } finally { repository.close() }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
    }

    private fun inSource(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("tank-Silos 1")))

    private fun assertFill(tankId: String, expected: Float) {
        compose.onNodeWithTag("fill-$tankId", useUnmergedTree = true).assert(SemanticsMatcher("Napełnienie: $expected") { node ->
            node.config.contains(SemanticsProperties.ProgressBarRangeInfo) &&
                abs(node.config[SemanticsProperties.ProgressBarRangeInfo].current - expected) < 0.00001f
        })
    }

    private fun screenshot(name: String) {
        // PixelCopy needs a real display; draw the Robolectric window into a bitmap instead.
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isShown }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/screenshots/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun chooseRouteTransferMaterialAndRestoreScreenAfterRotation() {
        screenshot("start")
        compose.openShift(1)
        compose.onNodeWithTag("menu-Tanks").assertExists()
        compose.onNodeWithTag("menu-Controls").assertExists()
        screenshot("shift-panel")
        compose.onNodeWithTag("menu-Tanks").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("tank-row-LBT 1").fetchSemanticsNodes().isNotEmpty() }
        screenshot("zbiorniki-odbieralnia")
        compose.onNodeWithTag("department-Powder").performClick()
        compose.onNodeWithTag("tank-row-LBT 1").assertDoesNotExist()
        assertFill("Silos 1", 0.083333f)
        compose.onNodeWithTag("measurements-Silos 2", useUnmergedTree = true)
            .assertTextEquals("pH 6,7 · Brix 19,5 · Temp -4,25 °C").assertIsDisplayed()
        screenshot("zbiorniki-proszkownia")
        compose.onNodeWithTag("quantity-Silos 1").performClick()
        compose.onNodeWithContentDescription("Wróć do działu").assertDoesNotExist()
        compose.onNodeWithText("Nowy stan [m³]").assert(hasText("5", substring = true))
        compose.onNodeWithText("Nowy stan [m³]").performTextClearance()
        compose.onNodeWithText("Nowy stan [m³]").performTextInput("5,5")
        compose.onNodeWithText("Po przeliczeniu: 5 500 l").assertExists()
        screenshot("edycja-m3")
        compose.onNodeWithText("Zawartość / materiał").performTextInput("Serwatka — partia A")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Nowy stan [m³]").assert(hasText("5,5", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("quantity-Silos 1") and hasText("5 500")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tank-Silos 1").assertDoesNotExist()
        assertFill("Silos 1", 0.091667f)
        compose.onNodeWithTag("tank-row-Silos 1").performClick()
        compose.onNodeWithText("Edytuj").assertDoesNotExist()
        inSource("Zawartość: Serwatka — partia A").assertExists()

        inSource("Wyparka — · Kryst. —").performScrollTo().performClick()
        compose.onNodeWithText("C").performClick()
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Wyparka C · Kryst. 3").fetchSemanticsNodes().isNotEmpty() }

        inSource("Przenieś materiał").performScrollTo().performClick()
        compose.onNodeWithText("Wybierz zbiornik docelowy").performClick()
        compose.onNodeWithTag("target-Kryst. 1").performScrollTo().performClick()
        compose.onNodeWithText("Ilość [m³]").performScrollTo().performTextInput("1")
        compose.onNodeWithText("Brix [°Bx]").performScrollTo().performTextInput("19,5")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Stan: 4 500 l").fetchSemanticsNodes().isNotEmpty() }

        inSource("Historia").performScrollTo().performClick()
        val transferEntry = hasText("Silos 1 → Kryst. 1 · 1 000 l") and hasAnyAncestor(isDialog())
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodes(transferEntry).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(transferEntry).assertExists()
        compose.onNode(hasText("Brix: 19,5 °Bx · pH: — · SH: —") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("Zamknij").performClick()

        inSource("Dolej materiał").performScrollTo().performClick()
        compose.onNodeWithText("Skąd pochodzi materiał").performTextInput("Dostawa B")
        compose.onNodeWithText("Ilość [m³]").performTextInput("0,25")
        compose.onNodeWithText("Zawartość / materiał").performTextClearance()
        compose.onNodeWithText("Zawartość / materiał").performTextInput("Serwatka — partia B")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Stan: 4 750 l").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("detail-quantity-Silos 1").performScrollTo().performClick()
        compose.onNodeWithText("Nowy stan [m³]").assert(hasText("4,75", substring = true))
        compose.onNodeWithText("Anuluj").performClick()

        compose.activityRule.scenario.recreate()
        inSource("Wyparka C · Kryst. 3").assertExists()
        inSource("Stan: 4 750 l").assertExists()
        inSource("Zawartość: Serwatka — partia B").assertExists()
        compose.onNodeWithText("Wybrana zmiana: 1").assertExists()
        compose.onNodeWithContentDescription("Wróć do działu").performClick()
        compose.onNodeWithTag("department-Powder").assertIsSelected()
        compose.onNodeWithTag("quantity-Silos 1").assert(hasText("4 750"))
        compose.onNodeWithTag("measurements-Silos 1", useUnmergedTree = true)
            .assertTextEquals("pH — · Brix — · Temp — °C").assertIsDisplayed()
        assertFill("Silos 1", 0.079167f)
        assertFill("Kryst. 1", 0.066667f)
    }

    @Test fun repeatedTopUpsShowResultingStockAndRemainVisibleAfterRotation() {
        compose.openShift(2)
        compose.onNodeWithTag("menu-Tanks").performClick()
        compose.onNodeWithTag("department-Powder").performClick()
        compose.onNodeWithTag("tank-row-Silos 1").performClick()
        inSource("Dolej materiał").performScrollTo().performClick()
        compose.onNodeWithText("Dolej do: Silos 1").assertExists()
        compose.onNodeWithText("Skąd pochodzi materiał").performTextInput("Dostawa testowa A")
        compose.onNodeWithText("Ilość [m³]").performTextInput("0,25")
        compose.onNodeWithTag("top-up-preview").assertTextEquals("Stan po dolaniu: 5 250 l")
        compose.onNodeWithText("Zawartość / materiał").performTextInput("Serwatka testowa")
        compose.onNodeWithText("Brix [°Bx]").performScrollTo().performTextInput("19,5")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Stan: 5 250 l").fetchSemanticsNodes().isNotEmpty() }
        inSource("Dolej materiał").performScrollTo().performClick()
        compose.onNodeWithText("Zawartość / materiał").assert(hasText("Serwatka testowa"))
        compose.onNodeWithText("Skąd pochodzi materiał").performTextInput("Dostawa testowa B")
        compose.onNodeWithText("Ilość [m³]").performTextInput("0,5")
        compose.onNodeWithTag("top-up-preview").assertTextEquals("Stan po dolaniu: 5 750 l")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("top-up-preview").assertTextEquals("Stan po dolaniu: 5 750 l")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Stan: 5 750 l").fetchSemanticsNodes().isNotEmpty() }
        inSource("Wszystkie dolania").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Dostawa testowa B → Silos 1 · 500 l").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Stan: 5 250 → 5 750 l").assertExists()
        compose.onNodeWithTag("top-ups-list").performScrollToNode(hasText("Dostawa testowa A → Silos 1 · 250 l"))
        compose.onNodeWithText("Dostawa testowa A → Silos 1 · 250 l").assertIsDisplayed()
        compose.onNodeWithText("Stan: 5 000 → 5 250 l").assertExists()
        compose.onNodeWithTag("top-ups-list").performScrollToNode(hasText("Brix: 19,5 °Bx · pH: — · SH: —"))
        compose.onNodeWithText("Brix: 19,5 °Bx · pH: — · SH: —").assertIsDisplayed()
        screenshot("dolania-zbiornika")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Dolania: Silos 1").assertExists()
        compose.onNodeWithContentDescription("Wróć do zbiornika").performClick()
        inSource("Stan: 5 750 l").assertExists()
    }

    @Test fun olderTopUpsCanBeLoadedPastOneHundredEntriesEvenWhenTankIsEmpty() {
        val model = ViewModelProvider(compose.activity)[InventoryViewModel::class.java]
        val database = InventoryDatabase(model.getApplication())
        try {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.delete("movements", "target_id = ? OR source_id = ?", arrayOf("Silos 1", "Silos 1"))
                repeat(101) { i ->
                    db.execSQL("""INSERT INTO movements (id, type, target_id, external_source, litres, occurred_at, note)
                        VALUES (?, 'RECEIPT', 'Silos 1', ?, '10', ?, '')""",
                        arrayOf<Any>("older-top-up-$i", "Dostawa historyczna $i", 1000L + i))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { database.close() }
        InventoryRepository(model.getApplication()).let { repository ->
            try { repository.apply { it.setState("Silos 1", BigDecimal.ZERO, Measurements(), 2000L) } }
            finally { repository.close() }
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        compose.openShift(1)
        compose.onNodeWithTag("menu-Tanks").performClick()
        compose.onNodeWithTag("department-Powder").performClick()
        compose.onNodeWithTag("tank-row-Silos 1").performClick()
        inSource("Napełnij").performScrollTo().assertIsEnabled()
        inSource("Wszystkie dolania").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.topUps.value.events.size == 50 } }
        compose.onNodeWithTag("top-ups-count").assertTextEquals("Wyświetlono 50 z 101")
        compose.onNodeWithTag("top-ups-list").performScrollToNode(hasTestTag("load-older-top-ups"))
        compose.onNodeWithTag("load-older-top-ups").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.topUps.value.events.size == 100 } }
        compose.onNodeWithTag("top-ups-list").performScrollToNode(hasTestTag("load-older-top-ups"))
        compose.onNodeWithTag("load-older-top-ups").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.topUps.value.events.size == 101 } }
        compose.onNodeWithTag("top-ups-list").performScrollToNode(hasText("Dostawa historyczna 0 → Silos 1 · 10 l"))
        compose.onNodeWithText("Dostawa historyczna 0 → Silos 1 · 10 l").assertIsDisplayed()
        compose.onNodeWithTag("top-ups-list").performScrollToNode(hasText("To wszystkie zapisane dolania."))
        compose.onNodeWithText("To wszystkie zapisane dolania.").assertIsDisplayed()
        compose.onNodeWithTag("load-older-top-ups").assertDoesNotExist()
        compose.onNodeWithTag("top-ups-list").performScrollToIndex(0)
        compose.onNodeWithTag("top-ups-count").assertTextEquals("Wyświetlono 101 z 101")
        compose.onNodeWithContentDescription("Wróć do zbiornika").performClick()
        inSource("Stan: 0 l").assertExists()
    }

    @Test fun everyTankCanBeManuallyEditedAndButterFatIsSavedAndRestored() {
        compose.openShift(1)
        compose.onNodeWithTag("menu-Tanks").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("tank-row-LBT 1").fetchSemanticsNodes().isNotEmpty() }
        Department.entries.forEach { department ->
            compose.onNodeWithTag("department-${department.name}").performClick()
            AppContent.tanks.filter { it.department == department }.forEach { tank ->
                compose.onNodeWithTag("quantity-${tank.id}").performScrollTo().performClick()
                compose.onNodeWithText("Edytuj: ${tank.name}").assertExists()
                compose.onNodeWithText("Nowy stan [m³]").assertIsEnabled()
                compose.onNodeWithText("Zawartość / materiał").assertIsEnabled()
                compose.onNodeWithText("Zapisz").assertIsEnabled()
                if (department == Department.Butter) {
                    compose.onNodeWithText("Tłuszcz [%]").assertExists()
                    compose.onNodeWithText("Brix [°Bx]").assertDoesNotExist()
                } else if (department == Department.Oils) {
                    compose.onNodeWithText("Brix [°Bx]").assertDoesNotExist()
                    compose.onNodeWithText("pH").assertDoesNotExist()
                    compose.onNodeWithText("Temperatura [°C]").assertExists()
                } else {
                    compose.onNodeWithText("Brix [°Bx]").assertExists()
                    compose.onNodeWithText("Tłuszcz [%]").assertDoesNotExist()
                }
                compose.onNodeWithText("Anuluj").performClick()
                compose.onNodeWithTag("department-${department.name}").assertIsSelected()
            }
        }
        compose.onNodeWithTag("department-Butter").performClick()
        compose.onNodeWithTag("quantity-M-Tank 1").performClick()
        compose.onNodeWithText("Nowy stan [m³]").performTextClearance()
        compose.onNodeWithText("Nowy stan [m³]").performTextInput("1")
        compose.onNodeWithText("Zawartość / materiał").performTextClearance()
        compose.onNodeWithText("Zawartość / materiał").performTextInput("Śmietana — partia A")
        compose.onNodeWithText("Tłuszcz [%]").performScrollTo().performTextClearance()
        compose.onNodeWithText("Tłuszcz [%]").performScrollTo().performTextInput("40,5")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Tłuszcz [%]").assert(hasText("40,5", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("quantity-M-Tank 1") and hasText("1 000")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tank-heading-M-Tank 1").performClick()
        compose.onNodeWithText("40,5 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Brix").assertDoesNotExist()
        screenshot("maslownia-tluszcz")
        compose.onNodeWithText("Dolej materiał").performScrollTo().performClick()
        compose.onNodeWithText("Skąd pochodzi materiał").performTextInput("Dostawa śmietany")
        compose.onNodeWithText("Ilość [m³]").performTextInput("0,5")
        compose.onNodeWithText("Tłuszcz [%]").performScrollTo().performTextInput("42")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Stan: 1 500 l").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("42 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Historia").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Tłuszcz: 42 % · pH: — · SH: —").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tłuszcz: 42 % · pH: — · SH: —").assertExists()
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithText("Przenieś materiał").performScrollTo().performClick()
        compose.onNodeWithText("Wybierz zbiornik docelowy").performClick()
        compose.onNodeWithTag("target-Kryst. 1").performScrollTo().performClick()
        compose.onNodeWithText("Brix [°Bx]").assertExists()
        compose.onNodeWithText("Tłuszcz [%]").assertDoesNotExist()
        compose.onNodeWithText("Do: Kryst. 1").performClick()
        compose.onNodeWithTag("target-M-Tank 2").performScrollTo().performClick()
        compose.onNodeWithText("Tłuszcz [%]").assertExists()
        compose.onNodeWithText("Brix [°Bx]").assertDoesNotExist()
        compose.onNodeWithText("Anuluj").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("42 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Wróć do działu").performClick()
        compose.onNodeWithTag("measurements-M-Tank 1", useUnmergedTree = true)
            .assertTextEquals("pH — · Tł. 42% · Temp — °C").assertIsDisplayed()
    }

    @Test fun eachDepartmentFitsOnOnePageAndRowRoutingKeepsStockUnchanged() {
        compose.openShift(1)
        compose.onNodeWithTag("menu-Tanks").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("tank-row-LBT 1").fetchSemanticsNodes().isNotEmpty() }
        Department.entries.forEach { department ->
            compose.onNodeWithTag("department-${department.name}").performClick()
            AppContent.tanks.filter { it.department == department }.forEach { tank ->
                compose.onNodeWithTag("tank-row-${tank.id}").assertIsDisplayed()
                compose.onNodeWithTag("measurements-${tank.id}", useUnmergedTree = true).assertIsDisplayed()
            }
            screenshot("compact-${department.name}")
            when (department) {
                Department.Reception -> {
                    assertFill("LBT 1", 0.5f)
                    assertFill("LBT 2", 1f)
                    compose.onNodeWithTag("fill-LBT 3", useUnmergedTree = true)
                        .assert(hasContentDescription("Napełnienie nieustalone"))
                }
                Department.Processing -> compose.onNodeWithTag("fill-MBT 1", useUnmergedTree = true)
                    .assert(hasContentDescription("Napełnienie nieznane: brak pojemności"))
                Department.Powder -> {
                    assertFill("Silos 2", 0.5f)
                    assertFill("Silos 3", 1f)
                    assertFill("Kryst. 1", 0f)
                }
                Department.Butter, Department.Oils -> Unit
            }
        }
        compose.onNodeWithTag("department-Powder").performClick()
        compose.onNodeWithTag("route-Silos 1").performClick()
        compose.onNodeWithText("F").performClick()
        compose.onNodeWithText("2").performClick()
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasText("F · 2") and hasTestTag("route-Silos 1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("quantity-Silos 1").assert(hasText("5 000"))
        compose.onNodeWithTag("tank-row-Silos 1").performClick()
        inSource("Wyparka F · Kryst. 2").assertExists()
        inSource("Wyparka F · Kryst. 2").performScrollTo().performClick()
        compose.onNodeWithTag("reset-routing-Silos 1").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Wyparka — · Kryst. —").fetchSemanticsNodes().isNotEmpty()
        }
        inSource("Stan: 5 000 l").assertExists()
        compose.activityRule.scenario.recreate()
        inSource("Wyparka — · Kryst. —").assertExists()
        inSource("Historia").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Produkcja wstrzymana — wyzerowano trasę.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Produkcja wstrzymana — wyzerowano trasę.").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhoneKeepsTabsVisibleAndCanScrollToLastTankAndOpenDetails() {
        compose.openShift(1)
        compose.onNodeWithTag("menu-Tanks").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("tank-row-LBT 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("department-Powder").performClick()
        compose.onNodeWithTag("measurements-Silos 2", useUnmergedTree = true)
            .assertTextEquals("pH 6,7 · Brix 19,5 · Temp -4,25 °C").assertIsDisplayed()
        screenshot("compact-small-phone-measurements")
        compose.onNodeWithTag("main-list").performScrollToNode(hasTestTag("tank-row-Kryst. 6"))
        compose.onNodeWithTag("tank-row-Kryst. 6").assertIsDisplayed()
        Department.entries.forEach { compose.onNodeWithTag("department-${it.name}").assertIsDisplayed() }
        screenshot("compact-small-phone")
        compose.onNodeWithTag("department-Oils").performClick()
        compose.onNodeWithTag("tank-row-Olej 17").assertIsDisplayed()
        compose.onNodeWithTag("oil-type-Olej 17").performClick()
        compose.onNodeWithText("Rodzaj oleju").performTextInput("Rzepakowy")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Rodzaj: Rzepakowy").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("quantity-Olej 17").performClick()
        compose.onNodeWithText("Nowy stan [m³]").performTextInput("0")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("quantity-Olej 17") and hasText("0")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("quantity-Olej 17").performClick()
        compose.onNodeWithText("Nowy stan [m³]").assert(hasText("0", substring = true))
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("tank-heading-Olej 17").assert(hasText("pusty"))
        compose.onNodeWithTag("oil-type-Olej 17").assert(hasText("Rodzaj: Rzepakowy"))
        screenshot("oleje-pusty")
        compose.onNodeWithTag("tank-heading-Olej 17").performClick()
        compose.onNodeWithText("Edytuj").assertDoesNotExist()
        compose.onNodeWithText("Opis zbiornika: Olej").assertExists()
        compose.onNodeWithText("Pojemność: 60 000 l").assertExists()
        compose.onNodeWithText("Rodzaj oleju: Rzepakowy").assertExists()
        compose.onNodeWithContentDescription("Wróć do działu").performClick()
        compose.onNodeWithTag("department-Powder").performClick()
        compose.onNodeWithTag("tank-row-Silos 1").assertIsDisplayed()
    }

    @Test fun homeTanksMatchShiftViewOilDatesUseCalendarAndResetPreservesAudit() {
        val model = ViewModelProvider(compose.activity)[InventoryViewModel::class.java]
        val today = java.time.LocalDate.now()
        InventoryRepository(model.getApplication()).let { repo ->
            try {
                repo.apply { it.setState("Olej 12", BigDecimal("1000"), Measurements(brix = BigDecimal("5"), ph = BigDecimal("6"), temperature = BigDecimal("20")), 100L) }
                repo.apply { it.setOilBatch("Olej 12", "Rzepakowy", OilBatch(today.minusDays(2), today.plusDays(2)), 200L) }
                repo.apply { it.receive("Olej 12", BigDecimal("100"), "Dostawa oleju", Measurements(temperature = BigDecimal("21")), 300L) }
                repo.apply { it.setState("Olej 14", BigDecimal("1000"), Measurements(temperature = BigDecimal("19")), 400L) }
                repo.apply { it.setOilBatch("Olej 14", "Słonecznikowy", OilBatch(today.minusDays(2), today), 500L) }
            } finally { repo.close() }
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        compose.onNodeWithTag("home-Other").performClick()
        compose.onNodeWithTag("other-tanks").performClick()
        Department.entries.forEach { compose.onNodeWithTag("department-${it.name}").assertExists() }
        compose.onNodeWithTag("department-Oils").performClick()
        compose.onNodeWithTag("measurements-Olej 12", useUnmergedTree = true).assertTextEquals("Temp 21 °C")
        compose.onNodeWithTag("oil-expiry-Olej 12", useUnmergedTree = true)
            .assertTextEquals("Ważność: ${today.plusDays(2).oilDateLabel()}")
        val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("oil-expiry-Olej 12", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        org.junit.Assert.assertEquals(OilWarningColor, textLayouts.single().layoutInput.style.color)
        compose.onNodeWithTag("oil-expiry-Olej 14", useUnmergedTree = true)
            .assertTextEquals("Ważność: ${today.oilDateLabel()}").assertTextColor(androidx.compose.ui.graphics.Color.White)
        compose.onNodeWithTag("measurements-Olej 14", useUnmergedTree = true).assertTextColor(OilUrgentSecondary)
        screenshot("oleje-daty-waznosc")
        compose.onNodeWithTag("tank-heading-Olej 14").performClick()
        compose.onNodeWithTag("detail-oil-expiry-Olej 14", useUnmergedTree = true).performScrollTo()
            .assertTextEquals("Data ważności: ${today.oilDateLabel()}").assertTextColor(androidx.compose.ui.graphics.Color.White)
        screenshot("oleje-bordowy-szczegoly")
        compose.onNodeWithContentDescription("Wróć do działu").performClick()
        compose.onNodeWithTag("oil-type-Olej 12").performClick()
        compose.onNodeWithTag("oil-production-date").performClick()
        compose.onNodeWithTag("oil-production-date-calendar").assertExists()
        compose.onNodeWithText("Wybierz").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("oil-production-date").assert(hasText(today.minusDays(2).oilDateLabel(), substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("tank-row-Olej 12").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reset-tank-Olej 12").performClick()
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("quantity-Olej 12").assert(hasText("1 100"))
        compose.onNodeWithTag("reset-tank-Olej 12").performClick()
        compose.onNodeWithTag("confirm-reset-tank").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodes(hasTestTag("quantity-Olej 12") and hasText("0")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tank-heading-Olej 12").assert(hasText("pusty"))
        compose.onNodeWithTag("oil-expiry-Olej 12", useUnmergedTree = true).assertTextEquals("Ważność: —")
        InventoryRepository(model.getApplication()).let { repo ->
            try {
                org.junit.Assert.assertEquals(TankStatus(litres = BigDecimal.ZERO), repo.load().states.getValue("Olej 12"))
                org.junit.Assert.assertEquals(1, repo.topUps("Olej 12").totalCount)
            } finally { repo.close() }
        }
        compose.onNodeWithContentDescription("Wróć do innych").performClick()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        compose.openShift(1)
        compose.onNodeWithTag("menu-Tanks").performClick()
        compose.onNodeWithTag("department-Oils").assertIsSelected()
        compose.onNodeWithTag("tank-heading-Olej 12").assert(hasText("pusty"))
    }
}

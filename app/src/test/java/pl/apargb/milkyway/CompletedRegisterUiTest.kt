package pl.apargb.milkyway

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompletedRegisterUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ProductionQueueViewModel
    private val today get() = LocalDate.now()

    @Before fun clearRegister() {
        model = ViewModelProvider(compose.activity)[ProductionQueueViewModel::class.java]
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        ProductionQueueDatabase(model.getApplication()).use { helper ->
            listOf("production_rejects", "production_product_notes", "production_completions", "production_queue").forEach {
                helper.writableDatabase.delete(it, null, null)
            }
        }
        refresh()
    }

    private fun refresh() {
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
    }

    private fun screenshot(name: String) = compose.runOnIdle {
        val view = WindowInspector.getGlobalWindowViews().last { it.isShown }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/screenshots/$name.png"); file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun rejectedGoodsAcceptDescriptionsAndKilogramsPersistAndOnlyRejectsCanBeDeleted() {
        ProductionQueueRepository(model.getApplication()).useForTest { repository ->
            repository.save("ordinary", ProductionLine.BUTTER, today, "Masło", "Opis", 100L, BigDecimal("1000"))
            repository.recordProduction("partial", "ordinary", ProductionLine.BUTTER, today, BigDecimal("400"), 200L)
        }
        refresh(); compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-entry-ordinary").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Usuń wybrakowany towar").assertCountEquals(0)
        compose.onNodeWithTag("completed-add-rejected").performClick()
        compose.onNodeWithTag("rejected-line-UHT").performScrollTo().performClick()
        compose.onNodeWithTag("rejected-description").performTextInput("Uszkodzone opakowania partii UHT")
        compose.onNodeWithTag("rejected-mass").performTextInput("0")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Ilość musi być większa od zera.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.runOnIdle { model.state.value.rejectedGoods.isEmpty() })
        compose.onNodeWithTag("rejected-mass").performTextClearance()
        compose.onNodeWithTag("rejected-mass").performTextInput("12,5")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("rejected-description").assert(hasText("Uszkodzone opakowania partii UHT"))
        compose.onNodeWithTag("rejected-mass").assert(hasText("12,5"))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.rejectedGoods.size == 1 } }
        val rejected = compose.runOnIdle { model.state.value.rejectedGoods.single() }
        assertEquals(ProductionLine.UHT, rejected.line); assertEquals(BigDecimal("12.5"), rejected.kilograms)
        compose.onNodeWithTag("rejected-amount-${rejected.id}", useUnmergedTree = true).assertTextEquals("UHT · Wybrakowany · 12,5 kg")
        compose.onNodeWithTag("completed-day-summary").assert(hasText("Wybrakowane: 12,5 kg", substring = true))
        screenshot("wyprodukowano-dzien-i-wybrakowany")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("completed-rejected-${rejected.id}").performClick()
        compose.onNode(hasText("Uszkodzone opakowania partii UHT") and hasAnyAncestor(hasTestTag("rejected-details"))).assertExists()
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithTag("rejected-delete-${rejected.id}").performClick()
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("completed-rejected-${rejected.id}").assertExists()
        compose.onNodeWithTag("rejected-delete-${rejected.id}").performClick()
        compose.onNodeWithTag("rejected-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("warehouse-delete-pin").performTextInput("5522")
        compose.onNodeWithTag("rejected-delete-confirm").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.rejectedGoods.isEmpty() } }
        compose.onNodeWithTag("completed-rejected-${rejected.id}").assertDoesNotExist()
        compose.onNodeWithTag("completed-amount-ordinary", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 400 kg")
        assertEquals(1, compose.runOnIdle { model.state.value.completions.size })
        compose.activityRule.scenario.recreate()
        compose.onAllNodesWithContentDescription("Usuń wybrakowany towar").assertCountEquals(0)
        compose.onNodeWithTag("completed-entry-ordinary").assertExists()
    }

    @Test fun warehousePinRejectsIncorrectValuesClearsOnRotationAndRemovesOnlySelectedDay() {
        var day = today.minusDays(1)
        ProductionQueueRepository(model.getApplication(), today = { day }).useForTest { repository ->
            repository.save("pin-product", ProductionLine.BUTTER, day, "Masło", "", 100L, BigDecimal("1000"))
            repository.recordProduction("yesterday", "pin-product", ProductionLine.BUTTER, day, BigDecimal("100"), 101L)
            day = today
            repository.schedule(repository.load().single(), day, LocalTime.NOON, 102L)
            repository.recordProduction("today", "pin-product", ProductionLine.BUTTER, day, BigDecimal("300"), 103L)
        }
        refresh(); compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-entry-pin-product").performClick()
        compose.onNodeWithTag("warehouse-delete").performClick()
        compose.onNodeWithTag("warehouse-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("warehouse-delete-pin").performTextInput("2426")
        compose.onNodeWithTag("warehouse-delete-confirm").performClick()
        compose.onNodeWithText("Nieprawidłowy PIN.").assertExists()
        assertTrue(compose.runOnIdle { model.state.value.completions.all { it.warehouseRemovedAt == null } })
        compose.onNodeWithTag("warehouse-delete-pin").performTextInput("5522")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("warehouse-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("warehouse-delete-pin").performTextInput("5522")
        screenshot("magazyn-usuwanie-pin")
        compose.onNodeWithTag("warehouse-delete-confirm").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.completions.any { it.warehouseRemovedAt != null } } }
        compose.onNodeWithTag("completed-entry-pin-product").assertDoesNotExist()
        compose.onNodeWithTag("completed-day-summary").assertTextEquals("Produkcja: 0  |  Wybrakowane: 0 kg")
        assertEquals(BigDecimal("400"), compose.runOnIdle { model.state.value.entries.single().producedAmount })
        assertEquals(BigDecimal("600"), compose.runOnIdle { model.state.value.entries.single().remainingAmount })
        compose.onNodeWithTag("completed-previous-day").performClick()
        compose.onNodeWithTag("completed-amount-pin-product", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 100 kg")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("completed-today").performClick()
        compose.onNodeWithTag("completed-entry-pin-product").assertDoesNotExist()
    }

    @Test fun dailyRegisterUsesActualExecutionDaysAndCalendarRetainsTheSelectedDayAfterRotation() {
        var executionDay = today.minusDays(1)
        ProductionQueueRepository(model.getApplication(), today = { executionDay }).useForTest { repository ->
            repository.save("two-days", ProductionLine.BUTTER, executionDay, "Masło dzienne", "", 100L, BigDecimal("1000"))
            repository.recordProduction("first", "two-days", ProductionLine.BUTTER, executionDay, BigDecimal("200"), 101L)
            repository.recordProduction("second", "two-days", ProductionLine.BUTTER, executionDay, BigDecimal("100"), 102L)
            repository.addRejectedGoods("old-reject", ProductionLine.BUTTER, executionDay, "Wczorajszy brak", BigDecimal("5"), 103L)
            executionDay = today
            repository.schedule(repository.load().single(), today, LocalTime.NOON, 200L)
            repository.recordProduction("third", "two-days", ProductionLine.BUTTER, today, BigDecimal("700"), 201L)
            repository.addRejectedGoods("new-reject", ProductionLine.BUTTER, today, "Dzisiejszy brak", BigDecimal("10"), 202L)
        }
        refresh(); compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today)))
        compose.onNodeWithTag("completed-amount-two-days", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 700 kg")
        compose.onNodeWithTag("completed-rejected-old-reject").assertDoesNotExist()
        compose.onNodeWithTag("completed-day-summary").assertTextEquals("Produkcja: 700 kg  |  Wybrakowane: 10 kg")
        compose.onNodeWithTag("completed-previous-day").performClick()
        compose.onNodeWithTag("completed-amount-two-days", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 300 kg")
        compose.onNodeWithTag("completed-status-two-days", useUnmergedTree = true).assertTextEquals("Całość zamówienia")
        compose.onNodeWithTag("completed-rejected-new-reject").assertDoesNotExist()
        compose.onNodeWithTag("completed-day-summary").assertTextEquals("Produkcja: 300 kg  |  Wybrakowane: 5 kg")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today.minusDays(1))))
        compose.onNodeWithTag("completed-date").performClick()
        val label = today.minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.US))
        compose.onNode(hasText(label, substring = true) and isSelected() and hasAnyAncestor(hasTestTag("queue-calendar"))).assertExists()
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("completed-today").performClick()
        compose.onNodeWithTag("completed-entry-two-days").performClick()
        compose.onNodeWithText("Wyprodukowano tego dnia: 700 kg").assertExists()
        compose.onNodeWithText("Łącznie wyprodukowano: 1\u00a0000 kg").assertExists()
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithTag("completed-next-day").performClick()
        compose.onNodeWithText("Brak wyprodukowanego towaru").assertExists()
    }

    @Test fun compactPagesKeepRowsOnOneScreenAndAllDailyProductsRemainReachable() {
        ProductionQueueRepository(model.getApplication()).useForTest { repository ->
            repeat(18) { index ->
                val id = "product-$index"
                repository.save(id, ProductionLine.BUTTER, today, "Masło partia $index", "", 100L + index, BigDecimal("10"))
                repository.recordProduction("receipt-$index", id, ProductionLine.BUTTER, today, null, 200L + index)
            }
        }
        refresh(); compose.onNodeWithTag("home-Completed").performClick()
        val rowMatcher = SemanticsMatcher("Wiersz wyprodukowanego towaru") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("completed-entry-") == true
        }
        val seen = mutableSetOf<String>()
        var more: Boolean
        do {
            val list = compose.onNodeWithTag("completed-list").fetchSemanticsNode().boundsInRoot
            compose.onAllNodes(rowMatcher).fetchSemanticsNodes().forEach { node ->
                val tag = node.config[SemanticsProperties.TestTag]
                compose.onNodeWithTag(tag).assertIsDisplayed()
                assertTrue(node.boundsInRoot.top >= list.top - 1f); assertTrue(node.boundsInRoot.bottom <= list.bottom + 1f)
                seen.add(tag)
            }
            screenshot("wyprodukowano-dzien-zwarte-wiersze-${seen.size}")
            more = compose.onAllNodes(hasTestTag("completed-next-page") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            if (more) {
                compose.onNodeWithTag("completed-next-page").performClick()
                val page = compose.onNodeWithTag("completed-page-number").fetchSemanticsNode().config[SemanticsProperties.Text]
                compose.activityRule.scenario.recreate()
                assertEquals(page, compose.onNodeWithTag("completed-page-number").fetchSemanticsNode().config[SemanticsProperties.Text])
            }
        } while (more)
        assertEquals(18, seen.size)
        compose.onNodeWithTag("completed-filter-POWDER").performScrollTo().performClick()
        compose.onNodeWithText("Brak wyprodukowanego towaru").assertExists()
        compose.onNodeWithText("Wszystkie").performScrollTo().performClick()
        compose.onNodeWithTag("completed-page-number").assert(hasText("Strona 1/", substring = true))
    }

    @Test fun verticalSwipesFollowDaysSynchronizeCalendarAndKeepDailyDataAndSelectionAfterRotation() {
        var executionDay = today.minusDays(1)
        ProductionQueueRepository(model.getApplication(), today = { executionDay }).useForTest { repository ->
            repository.save("swipe-product", ProductionLine.BUTTER, executionDay, "Masło z dwóch dni", "Opis", 100L, BigDecimal("1000"))
            repository.recordProduction("old-production", "swipe-product", ProductionLine.BUTTER, executionDay, BigDecimal("300"), 101L)
            repository.addRejectedGoods("old-brak", ProductionLine.BUTTER, executionDay, "Wczorajszy brak", BigDecimal("25"), 102L)
            executionDay = today
            repository.schedule(repository.load().single(), today, LocalTime.NOON, 200L)
            repository.recordProduction("new-production", "swipe-product", ProductionLine.BUTTER, today, BigDecimal("700"), 201L)
            repository.addRejectedGoods("new-brak", ProductionLine.BUTTER, today, "Dzisiejszy brak", BigDecimal("10"), 202L)
            repository.addRejectedGoods("next-brak", ProductionLine.UHT, today.plusDays(1), "Brak na następny dzień", BigDecimal("5"), 300L)
        }
        refresh(); compose.onNodeWithTag("home-Completed").performClick()
        val before = compose.runOnIdle { Triple(model.state.value.entries, model.state.value.completions, model.state.value.rejectedGoods) }
        compose.onNodeWithTag("completed-days").performTouchInput { swipeUp(durationMillis = 1000) }
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today.plusDays(1))))
        compose.onNodeWithTag("completed-day-summary").assertTextEquals("Produkcja: 0  |  Wybrakowane: 5 kg")
        compose.onNodeWithTag("completed-entry-swipe-product").assertDoesNotExist()
        screenshot("wyprodukowano-przewijanie-nastepny-dzien")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today.plusDays(1))))
        compose.onNodeWithTag("completed-date").performClick()
        val label = today.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.US))
        compose.onNode(hasText(label, substring = true) and isSelected() and hasAnyAncestor(hasTestTag("queue-calendar"))).assertExists()
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("completed-days").performTouchInput { swipeDown(durationMillis = 1000) }
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today)))
        compose.onNodeWithTag("completed-amount-swipe-product", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 700 kg")
        screenshot("wyprodukowano-przewijanie-dzisiaj")
        compose.onNodeWithTag("completed-days").performTouchInput { swipeDown(durationMillis = 1000) }
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today.minusDays(1))))
        compose.onNodeWithTag("completed-amount-swipe-product", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 300 kg")
        compose.onNodeWithTag("completed-day-summary").assertTextEquals("Produkcja: 300 kg  |  Wybrakowane: 25 kg")
        compose.onNodeWithTag("completed-filter-UHT").performScrollTo().performClick()
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today.minusDays(1))))
        compose.onNodeWithText("Brak wyprodukowanego towaru").assertExists()
        compose.onNodeWithTag("completed-days").performTouchInput { swipeUp(durationMillis = 1000) }
        compose.onNodeWithTag("completed-date").assert(hasText(queueDateLabel(today)))
        compose.onNodeWithText("Brak wyprodukowanego towaru").assertExists()
        compose.onNodeWithTag("completed-today").assertIsNotEnabled()
        compose.onNodeWithText("Wszystkie").performScrollTo().performClick()
        compose.onNodeWithTag("completed-amount-swipe-product", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 700 kg")
        val after = compose.runOnIdle { Triple(model.state.value.entries, model.state.value.completions, model.state.value.rejectedGoods) }
        assertEquals(before, after)
    }

    @Test fun warehouseNameAndCompactRowsShowCompletePartialAndExcessOrdersWithDailyAmounts() {
        ProductionQueueRepository(model.getApplication()).useForTest { repository ->
            listOf(Triple("partial", ProductionLine.BUTTER, "400"), Triple("complete", ProductionLine.POWDER, "1000"),
                Triple("excess", ProductionLine.UHT, "1200")).forEach { (id, line, produced) ->
                repository.save(id, line, today, when (id) { "partial" -> "Masło częściowe"; "complete" -> "Proszek pełna partia"; else -> "UHT z nadwyżką" },
                    "", 100L, BigDecimal("1000"))
                repository.recordProduction("receipt-$id", id, line, today, BigDecimal(produced), 200L)
            }
        }
        refresh()
        compose.onNodeWithTag("home-Completed").assert(hasText("Magazyn/", substring = true))
        screenshot("magazyn-przycisk-ekran-glowny")
        compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithText("Magazyn/Wyprodukowano").assertIsDisplayed()
        compose.onNodeWithTag("completed-status-partial", useUnmergedTree = true).assertTextEquals("Brakuje: 600 kg")
        compose.onNodeWithTag("completed-status-complete", useUnmergedTree = true).assertTextEquals("Całość zamówienia")
        compose.onNodeWithTag("completed-order-excess", useUnmergedTree = true).assertTextEquals("Zamówiono: 1\u00a0000 l + nadmiar: 200 l")
        compose.onNodeWithTag("completed-amount-partial", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 400 kg")
        listOf("partial", "complete", "excess").forEach { id ->
            compose.onNodeWithTag("completed-entry-$id").assertIsDisplayed()
            compose.onNodeWithTag("product-notes-$id").assertIsDisplayed()
        }
        screenshot("magazyn-kolory-realizacji")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("completed-status-partial", useUnmergedTree = true).assertTextEquals("Brakuje: 600 kg")
        compose.onNodeWithTag("completed-status-complete", useUnmergedTree = true).assertTextEquals("Całość zamówienia")
        assertEquals(3, compose.runOnIdle { model.state.value.completions.size })
    }

    private fun ProductionQueueRepository.useForTest(action: (ProductionQueueRepository) -> Unit) {
        try { action(this) } finally { close() }
    }
}

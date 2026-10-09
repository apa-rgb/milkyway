package pl.apargb.milkyway

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductionQueueUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: ProductionQueueViewModel
    private val today get() = LocalDate.now()
    private inline fun ProductionQueueRepository.useForTest(block: (ProductionQueueRepository) -> Unit) {
        try { block(this) } finally { close() }
    }

    @Before fun emptyPlans() {
        model = ViewModelProvider(compose.activity)[ProductionQueueViewModel::class.java]
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        ProductionQueueDatabase(model.getApplication()).use {
            it.writableDatabase.delete("production_rejects", null, null)
            it.writableDatabase.delete("production_product_notes", null, null)
            it.writableDatabase.delete("production_completions", null, null)
            it.writableDatabase.delete("production_queue", null, null)
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
    }

    private fun openQueue() {
        compose.onNodeWithTag("home-Production").performClick()
        compose.onNodeWithTag("production-queue").performClick()
    }

    private fun add(title: String, description: String = "") {
        compose.onNodeWithTag("queue-add").performClick()
        compose.onNodeWithTag("queue-editor-production").performClick()
        compose.onNodeWithTag("queue-editor-time").performTextClearance()
        compose.onNodeWithText("Produkcja / produkt").performTextInput(title)
        compose.onNodeWithText(if (title.startsWith("UHT")) "Planowana ilość [l]" else "Planowana ilość [kg]").performTextInput("1000")
        if (description.isNotBlank()) compose.onNodeWithText("Plan / uwagi").performTextInput(description)
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() &&
                compose.runOnIdle { model.state.value.entries.any { it.title == title } }
        }
    }

    private fun dailyTitles(date: LocalDate = today) = compose.runOnIdle {
        model.state.value.entries.filter { it.line == ProductionLine.BUTTER && it.date == date }.sortedBy { it.position }.map { it.title }
    }

    private fun chooseCalendarDay(date: LocalDate) {
        compose.onNodeWithTag("queue-calendar").assertExists()
        screenshot("kalendarz-planowanie")
        val label = date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.US))
        compose.onNode(hasText(label, substring = true) and hasAnyAncestor(hasTestTag("queue-calendar"))).performClick()
        compose.onNodeWithText("Wybierz").performClick()
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

    @Test fun sevenCardsFitInBothColumnsWithMatchingDimensionsAndAllActionsInEveryLine() {
        ProductionQueueRepository(model.getApplication()).useForTest { repo ->
            ProductionLine.entries.forEach { line ->
                repeat(7) { index ->
                    repo.save("dense-order-${line.name}-$index", line, today, "Zamówienie ${index + 1}", "Pilna partia klienta", 100L + index,
                        java.math.BigDecimal("1000"), pendingOrder = true)
                    repo.save("dense-plan-${line.name}-$index", line, today, "Produkt ${index + 1}", "Opis bieżącej partii", 200L + index,
                        java.math.BigDecimal("1000"), pendingOrder = false, scheduledTime = java.time.LocalTime.of(8, 0))
                }
                val entry = repo.load().single { it.id == "dense-plan-${line.name}-0" }
                repo.setProductionCode("dense-code-${line.name}", entry, "007", 300L)
                repo.recordProduction("dense-partial-${line.name}", entry.id, line, today, java.math.BigDecimal("200"), 301L)
            }
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
        openQueue()
        listOf(ProductionLine.BUTTER to "butter", ProductionLine.POWDER to "powder", ProductionLine.UHT to "uht").forEach { (line, route) ->
            compose.onNodeWithTag("production-queue_$route").performClick()
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-hour-$today-8"))
            val ordersViewport = compose.onNodeWithTag("queue-orders").getUnclippedBoundsInRoot()
            val planViewport = compose.onNodeWithTag("queue-list").getUnclippedBoundsInRoot()
            repeat(7) { index ->
                val order = compose.onNodeWithTag("queue-entry-dense-order-${line.name}-$index").getUnclippedBoundsInRoot()
                val plan = compose.onNodeWithTag("queue-entry-dense-plan-${line.name}-$index").getUnclippedBoundsInRoot()
                assertEquals((order.right - order.left).value, (plan.right - plan.left).value, .1f)
                assertEquals(72f, (order.bottom - order.top).value, .1f); assertEquals((order.bottom - order.top).value, (plan.bottom - plan.top).value, .1f)
                org.junit.Assert.assertTrue("Order ${index + 1} fully visible: $order in $ordersViewport",
                    order.top >= ordersViewport.top && order.bottom <= ordersViewport.bottom)
                org.junit.Assert.assertTrue("Product ${index + 1} fully visible: $plan in $planViewport",
                    plan.top >= planViewport.top && plan.bottom <= planViewport.bottom)
            }
            val id = "dense-plan-${line.name}-0"
            if (line == ProductionLine.POWDER) compose.onNodeWithTag("queue-code-$id").assert(hasText("007"))
            else compose.onNodeWithTag("queue-code-$id").assertDoesNotExist()
            compose.onNodeWithTag("queue-entry-$id").assert(hasText("Wykonano: 200 ${line.defaultUnit.label}", substring = true))
            compose.onNodeWithTag("queue-remaining-$id", true).assertTextEquals("Pozostało: 800 ${line.defaultUnit.label}")
            screenshot("cienkie-kafelki-siedem-$route")
            compose.onNodeWithTag("queue-produced-$id").performClick()
            compose.onNodeWithText("Wyprodukowano teraz [${line.defaultUnit.label}]").assertExists()
            compose.onNodeWithText("Anuluj").performClick()
            compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        }
    }

    @Test fun incomingCardsShowTwoWordsKeepTheirSizeAndRetainTheFullNoteInHistory() {
        ProductionQueueRepository(model.getApplication()).useForTest { repo ->
            ProductionLine.entries.forEach { line ->
                val id = "preview-${line.name}"
                repo.save(id, line, today, "Partia mleka", "", 1L, java.math.BigDecimal("1000"), pendingOrder = true)
                repo.addProductNote("short-${line.name}", repo.load().single { it.id == id }, ProductNoteStage.ORDER, "Pilna partia", 2L)
            }
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        openQueue()
        listOf(ProductionLine.BUTTER to "butter", ProductionLine.POWDER to "powder", ProductionLine.UHT to "uht").forEach { (line, menu) ->
            val id = "preview-${line.name}"
            compose.onNodeWithTag("production-queue_$menu").performClick()
            val before = compose.onNodeWithTag("queue-entry-$id").fetchSemanticsNode().boundsInRoot
            val scale = compose.activity.resources.displayMetrics.density
            org.junit.Assert.assertTrue("Compact card height", before.height / scale <= 73f)
            compose.onNodeWithContentDescription("Usuń produkcję: ${line.title}").assertDoesNotExist()
            compose.onNodeWithTag("queue-return-$id").assertDoesNotExist()
            compose.onNodeWithTag("queue-schedule-$id").assertDoesNotExist()
            val fullText = "Pilna\npartia\u00a0do realizacji jutro rano"
            ProductionQueueRepository(model.getApplication()).useForTest { repo ->
                repo.addProductNote("long-${line.name}", repo.load().single { it.id == id }, ProductNoteStage.ORDER, fullText, 3L)
            }
            compose.runOnIdle { model.reload() }
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
            compose.onNodeWithTag("queue-note-preview-$id", useUnmergedTree = true).assertTextEquals("Pilna partia")
            val after = compose.onNodeWithTag("queue-entry-$id").fetchSemanticsNode().boundsInRoot
            assertEquals(before.width, after.width, .1f); assertEquals(before.height, after.height, .1f)
            compose.onNodeWithTag("product-notes-$id").performClick()
            compose.onNodeWithTag("product-note-long-${line.name}").performScrollTo().assertTextEquals(fullText)
            compose.onNodeWithText("Anuluj").performClick()
            if (line == ProductionLine.BUTTER) screenshot("zamowienie-dwa-slowa-notatki")
            val order = compose.runOnIdle { model.state.value.entries.single { it.id == id } }
            compose.runOnIdle { model.schedule(order, today, java.time.LocalTime.MIDNIGHT) }
            compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && !model.state.value.entries.single { it.id == id }.pendingOrder } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-$id"))
            compose.onNodeWithTag("queue-note-preview-$id", useUnmergedTree = true).assertTextEquals("Pilna partia do realizacji jutro rano")
            val inProduction = compose.onNodeWithTag("queue-entry-$id").fetchSemanticsNode().boundsInRoot
            assertEquals(before.width, inProduction.width, .1f); assertEquals(before.height, inProduction.height, .1f)
            compose.activityRule.scenario.recreate()
            val rotated = compose.onNodeWithTag("queue-entry-$id").fetchSemanticsNode().boundsInRoot
            assertEquals(inProduction.height, rotated.height, .1f)
            compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        }
    }

    @Test fun everyLineDefaultsToTodayAndFuturePlansStayOnTheirOwnDayAfterRotation() {
        openQueue()
        listOf("butter" to "Masłownia", "powder" to "Proszkownia", "uht" to "UHT").forEach { (id, title) ->
            compose.onNodeWithTag("production-queue_$id").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today)))
            compose.onNodeWithText("Brak produkcji na ten dzień").assertExists()
            add("$title dzisiaj", "Zbiornik i ilość do przygotowania")
            compose.onNodeWithText("1. $title dzisiaj").assertExists()
            compose.onNodeWithContentDescription("Następny dzień").performClick()
            compose.onNodeWithText("Brak produkcji na ten dzień").assertExists()
            add("$title jutro")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today.plusDays(1))))
            compose.onNodeWithText("1. $title jutro").assertExists()
            val futureId = compose.runOnIdle { model.state.value.entries.single { it.title == "$title jutro" }.id }
            compose.onNodeWithTag("queue-produced-$futureId").assertDoesNotExist()
            compose.onNodeWithText("1. $title dzisiaj").assertDoesNotExist()
            compose.onNodeWithText("Dzisiaj").performClick()
            compose.onNodeWithText("1. $title dzisiaj").assertExists()
            compose.onNodeWithText("1. $title jutro").assertDoesNotExist()
            screenshot("kolejka-$id-dzisiaj")
            compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        }
        compose.onNodeWithTag("production-queue_butter").performClick()
        compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today)))
        compose.onNodeWithText("1. Masłownia dzisiaj").assertExists()
        compose.onNodeWithContentDescription("Następny dzień").performClick()
        compose.onNodeWithText("1. Masłownia jutro").assertExists()
        ProductionQueueRepository(model.getApplication()).let { repository ->
            try { org.junit.Assert.assertEquals(6, repository.load().size) } finally { repository.close() }
        }
    }

    @Test fun currentDayProductionCanBeRecordedPartiallyOrInFullAndShowsRemainingAfterReopening() {
        openQueue()
        compose.onNodeWithTag("production-queue_butter").performClick()
        add("Masło plan", "Planowana partia")
        val id = compose.runOnIdle { model.state.value.entries.single().id }
        compose.onNodeWithTag("queue-produced-$id").performScrollTo().performClick()
        compose.onNodeWithText("Wyprodukowano teraz [kg]").performTextInput("200")
        compose.onNodeWithTag("production-remaining-preview").assertTextEquals("Po zapisie pozostanie: 800 kg")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Wyprodukowano teraz [kg]").assert(hasText("200"))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Wyprodukowano teraz [kg]").fetchSemanticsNodes().isEmpty() &&
                compose.runOnIdle { model.state.value.entries.single().producedAmount.compareTo(java.math.BigDecimal("200")) == 0 }
        }
        compose.onNodeWithTag("queue-remaining-$id", useUnmergedTree = true).assertTextEquals("Pozostało: 800 kg")
        compose.onNodeWithTag("queue-produced-$id").performScrollTo().performClick()
        compose.onNodeWithText("Wyprodukowano teraz [kg]").performTextInput("300")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Wyprodukowano teraz [kg]").fetchSemanticsNodes().isEmpty() &&
                compose.runOnIdle { model.state.value.entries.single().producedAmount.compareTo(java.math.BigDecimal("500")) == 0 }
        }
        compose.onNodeWithTag("queue-remaining-$id", useUnmergedTree = true).assertTextEquals("Pozostało: 500 kg")
        screenshot("produkcja-czesciowa-pozostalo")
        compose.onNodeWithTag("queue-produced-$id").performScrollTo().performClick()
        compose.onNodeWithText("W całości").performClick()
        compose.onNodeWithTag("production-remaining-preview").assertTextEquals("Po zapisie pozostanie: 0 kg")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.state.value.entries.single().completed } }
        compose.onNodeWithTag("queue-produced-$id").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("queue-entry-$id").assertDoesNotExist()
        backToHome()
        compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-entry-$id").assertExists()
        compose.onNodeWithTag("completed-amount-$id", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 1\u00a0000 kg")
        screenshot("produkcja-w-calosci")
        ProductionQueueRepository(model.getApplication()).let { repository ->
            try {
                val entry = repository.load().single()
                org.junit.Assert.assertEquals(java.math.BigDecimal("1000"), entry.producedAmount)
                org.junit.Assert.assertEquals(java.math.BigDecimal.ZERO, entry.remainingAmount)
            } finally { repository.close() }
        }
    }

    @Test fun calendarReorderingEditingReschedulingAndDeletingPreserveDailyPlans() {
        openQueue()
        compose.onNodeWithTag("production-queue_butter").performClick()
        add("A", "Partia pierwsza"); add("B"); add("C")
        val c = compose.runOnIdle { model.state.value.entries.single { it.title == "C" }.id }
        val a = compose.runOnIdle { model.state.value.entries.single { it.title == "A" }.id }
        val b = compose.runOnIdle { model.state.value.entries.single { it.title == "B" }.id }
        reorderCard(c, b, after = false)
        compose.waitUntil(timeoutMillis = 10000) { dailyTitles() == listOf("A", "C", "B") }
        reorderCard(c, a, after = false)
        compose.waitUntil(timeoutMillis = 10000) { dailyTitles() == listOf("C", "A", "B") }
        compose.onNodeWithTag("queue-up-$c").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("1. C").assertExists()
        reorderCard(c, a, after = true)
        compose.waitUntil(timeoutMillis = 10000) { dailyTitles() == listOf("A", "C", "B") }
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-day-$today"))
        compose.onNodeWithText("1. A").performScrollTo().performClick()
        compose.onNodeWithText("Produkcja / produkt").performTextClearance()
        compose.onNodeWithText("Produkcja / produkt").performTextInput("A poprawione")
        compose.onNodeWithText("Plan / uwagi").performTextClearance()
        compose.onNodeWithText("Plan / uwagi").performTextInput("Nowe parametry partii")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Plan / uwagi").assert(hasText("Nowe parametry partii"))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() && dailyTitles() == listOf("A poprawione", "C", "B")
        }
        screenshot("kolejka-produkcji-kolejnosc")
        val otherDate = today.withDayOfMonth(if (today.dayOfMonth == 15) 16 else 15)
        compose.onNodeWithText("1. A poprawione").performScrollTo().performClick()
        compose.onNodeWithTag("queue-editor-date").performClick()
        chooseCalendarDay(otherDate)
        compose.onNodeWithText("Zmiana daty przeniesie pozycję na koniec kolejki wybranego dnia.").assertExists()
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() && dailyTitles() == listOf("C", "B")
        }
        compose.onNodeWithTag("queue-date").performClick()
        chooseCalendarDay(otherDate)
        compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(otherDate)))
        compose.onNodeWithText("1. A poprawione").assertExists()
        compose.onNodeWithText("1. A poprawione").performClick()
        compose.onNodeWithText("Plan / uwagi").assert(hasText("Nowe parametry partii"))
        compose.onNodeWithText("Anuluj").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(otherDate)))
        swipeDelete(a)
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithText("1. A poprawione").assertExists()
        swipeDelete(a)
        compose.onNodeWithTag("queue-delete-pin").performTextInput("5522")
        compose.onNodeWithText("Usuń").performClick()
        compose.waitUntil(timeoutMillis = 10000) { dailyTitles(otherDate).isEmpty() }
        compose.onNodeWithText("Brak produkcji na ten dzień").assertExists()
        compose.onNodeWithText("Dzisiaj").performClick()
        compose.onNodeWithText("1. C").assertExists()
        ProductionQueueRepository(model.getApplication()).let { repository ->
            try { org.junit.Assert.assertEquals(listOf("C", "B"), repository.load().map { it.title }) } finally { repository.close() }
        }
    }
    @Test fun productionCodesAreOnlyInPowderWhileDeletionPinsWorkInAllLines() {
        ProductionQueueRepository(model.getApplication()).useForTest { repo ->
            ProductionLine.entries.forEach { line ->
                repo.save("code-${line.name}", line, today, "Partia ${line.title}", "Materiały do przygotowania", 1L,
                    java.math.BigDecimal("1000"), pendingOrder = false)
                if (line != ProductionLine.POWDER) repo.setProductionCode("legacy-code-${line.name}",
                    repo.load().single { it.id == "code-${line.name}" }, "077", 2L)
            }
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
        openQueue()
        listOf("butter" to ProductionLine.BUTTER, "powder" to ProductionLine.POWDER, "uht" to ProductionLine.UHT).forEach { (route, line) ->
            val id = "code-${line.name}"
            compose.onNodeWithTag("production-queue_$route").performClick()
            if (line == ProductionLine.POWDER) {
                compose.onNodeWithTag("queue-code-$id").performScrollTo().performClick()
                compose.onNodeWithTag("production-code-input").performTextInput("07")
                compose.onNodeWithTag("production-code-save").assertIsNotEnabled()
                compose.onNodeWithTag("production-code-input").performTextInput("7")
                compose.onNodeWithTag("production-code-save").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("production-code-input").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("queue-code-$id").assert(hasText("077"))
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag("queue-code-$id").assert(hasText("077"))
            } else {
                compose.onNodeWithTag("queue-code-$id").assertDoesNotExist()
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag("queue-code-$id").assertDoesNotExist()
            }
            compose.onNodeWithTag("queue-note-preview-$id", true).assertTextEquals("Materiały do przygotowania")
            val beforeDelete = compose.runOnIdle { model.state.value.entries.size }
            swipeDelete(id)
            compose.onNodeWithTag("queue-delete-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("queue-delete-pin").performTextInput("1111")
            compose.onNodeWithTag("queue-delete-confirm").performClick()
            compose.onNodeWithText("Nieprawidłowy PIN.").assertExists()
            compose.runOnIdle { assertEquals(beforeDelete, model.state.value.entries.size) }
            compose.onNodeWithText("Anuluj").performClick()
            dragToPending(id, hold = false)
            compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.single { it.id == id }.pendingOrder } }
            if (line == ProductionLine.POWDER) {
                compose.onNodeWithTag("queue-code-$id").assert(hasText("077")).performClick()
                compose.onNodeWithTag("production-code-input").performTextClearance()
                compose.onNodeWithTag("production-code-save").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("production-code-input").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("queue-code-$id").assert(hasText("Kod"))
            } else compose.onNodeWithTag("queue-code-$id").assertDoesNotExist()
            swipeDelete(id)
            compose.onNodeWithTag("queue-delete-pin").performTextInput("5522")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("queue-delete-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("queue-delete-pin").performTextInput("5522")
            compose.onNodeWithTag("queue-delete-confirm").performClick()
            compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.none { it.id == id } } }
            compose.onNodeWithTag("queue-entry-$id").assertDoesNotExist()
            compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        }
    }

    @Test fun ordersCanBeDraggedIntoAConcreteDayAndHourInEveryLineAndRescheduledWithoutDuplicates() {
        openQueue()
        listOf("butter" to "Masłownia", "powder" to "Proszkownia", "uht" to "UHT").forEach { (route, title) ->
            compose.onNodeWithTag("production-queue_$route").performClick()
            compose.onNodeWithTag("queue-add").performClick()
            compose.onNodeWithText("Produkcja / produkt").performTextInput("$title zamówienie")
            compose.onNodeWithText("Planowana ilość [${if (route == "uht") "l" else "kg"}]").performTextInput("1500")
            compose.onNodeWithText("Plan / uwagi").performTextInput("Partia dla klienta")
            compose.onNodeWithText("Zapisz").performClick()
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() &&
                    compose.runOnIdle { model.state.value.entries.any { it.title == "$title zamówienie" } }
            }
            val order = compose.runOnIdle { model.state.value.entries.single { it.title == "$title zamówienie" } }
            org.junit.Assert.assertTrue(order.pendingOrder)
            compose.onNodeWithTag("queue-entry-${order.id}").assert(hasAnyAncestor(hasTestTag("queue-orders")))
            compose.onNodeWithContentDescription("Następny dzień").performClick()
            compose.onNodeWithTag("queue-entry-${order.id}").assertIsDisplayed()
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-hour-${today.plusDays(1)}-8"))
            screenshot("plan-zamowienia-$route")
            dragCardToHour(order.id, 8)
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle {
                model.state.value.entries.single { it.id == order.id }.let { !it.pendingOrder && it.scheduledTime == java.time.LocalTime.of(8, 0) }
            } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-entry-${order.id}"))
            compose.onNodeWithTag("queue-entry-${order.id}").assert(hasAnyAncestor(hasTestTag("queue-list")))
            org.junit.Assert.assertEquals(today.plusDays(1), compose.runOnIdle { model.state.value.entries.single { it.id == order.id }.productionDate })
            compose.onNodeWithText("Brak oczekujących zamówień").assertExists()
            compose.onNodeWithTag("queue-produced-${order.id}").assertDoesNotExist()
            screenshot("plan-produkcja-$route")
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-hour-${today.plusDays(1)}-10"))
            dragCardToHour(order.id, 10)
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle {
                model.state.value.entries.single { it.id == order.id }.scheduledTime == java.time.LocalTime.of(10, 0)
            } }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today.plusDays(1))))
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-entry-${order.id}"))
            // A horizontal gesture on an empty time slot changes the date without touching the product.
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-hour-${today.plusDays(1)}-16"))
            compose.onNodeWithTag("queue-hour-${today.plusDays(1)}-16").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today.plusDays(2))))
            compose.onNodeWithContentDescription("Poprzedni dzień").performClick()
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-entry-${order.id}"))
            compose.onNodeWithTag("queue-edit-${order.id}").performClick()
            compose.onNodeWithTag("queue-editor-time").performTextClearance()
            compose.onNodeWithTag("queue-editor-time").performTextInput("14:35")
            compose.onNodeWithText("Zapisz").performClick()
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle {
                !model.state.value.saving && model.state.value.entries.single { it.id == order.id }.scheduledTime == java.time.LocalTime.of(14, 35)
            } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-entry-${order.id}"))
            screenshot("plan-godzina-$route")
            compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        }
        ProductionQueueRepository(model.getApplication()).let { repository ->
            try {
                val entries = repository.load()
                org.junit.Assert.assertEquals(3, entries.size)
                org.junit.Assert.assertTrue(entries.all { !it.pendingOrder && it.productionDate == today.plusDays(1) && it.scheduledTime == java.time.LocalTime.of(14, 35) })
            } finally { repository.close() }
        }
    }

    @Test fun productsCanReturnToPendingByQuickDragAndHoldDragInEveryLineKeepingPartialProductionAndNotes() {
        openQueue()
        listOf("butter" to ProductionLine.BUTTER, "powder" to ProductionLine.POWDER, "uht" to ProductionLine.UHT).forEach { (route, line) ->
            compose.onNodeWithTag("production-queue_$route").performClick()
            val id = "return-$route"
            compose.runOnIdle { model.save("seed-$route", id, line, today, "${line.title} produkt", "Opis klienta", java.math.BigDecimal("1000"),
                pendingOrder = false, scheduledTime = java.time.LocalTime.of(8, 0)) }
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.any { it.id == id } } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-$id"))
            appendNote(id, "Notatka przed wstrzymaniem")
            val planned = compose.runOnIdle { model.state.value.entries.single { it.id == id } }
            compose.runOnIdle { model.recordProduction("partial-$route", planned, java.math.BigDecimal("400")) }
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.single { it.id == id }.producedAmount == java.math.BigDecimal("400") } }
            dragToPending(id, hold = false)
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.single { it.id == id }.pendingOrder } }
            compose.onNodeWithTag("queue-entry-$id").assert(hasAnyAncestor(hasTestTag("queue-orders")))
            compose.onNodeWithTag("queue-pending-remaining-$id", useUnmergedTree = true).assertTextEquals("Oczekuje: 600 ${line.defaultUnit.label}")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("queue-entry-$id").assert(hasAnyAncestor(hasTestTag("queue-orders")))
            openScheduleEditor(id, today)
            compose.onNodeWithText("Zapisz").performClick()
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && !model.state.value.entries.single { it.id == id }.pendingOrder } }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-$id"))
            val source = compose.onNodeWithTag("queue-edit-$id").fetchSemanticsNode().boundsInRoot.center
            val destination = compose.onNodeWithTag("queue-pending-column").fetchSemanticsNode().boundsInRoot.center
            val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput {
                down(source - origin); advanceEventTime(750); moveTo(source - origin)
                repeat(12) { step -> moveTo(source + (destination - source) * ((step + 1) / 12f) - origin, delayMillis = 30) }
                up()
            }
            compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.single { it.id == id }.pendingOrder } }
            compose.onNodeWithTag("queue-entry-$id").assert(hasAnyAncestor(hasTestTag("queue-orders")))
            compose.onNodeWithTag("queue-pending-remaining-$id", useUnmergedTree = true).assertTextEquals("Oczekuje: 600 ${line.defaultUnit.label}")
            val returned = compose.runOnIdle { model.state.value.entries.single { it.id == id } }
            org.junit.Assert.assertNull(returned.scheduledTime)
            org.junit.Assert.assertEquals(java.math.BigDecimal("1000"), returned.plannedAmount)
            org.junit.Assert.assertEquals(1, compose.runOnIdle { model.state.value.completions.count { it.entryId == id } })
            org.junit.Assert.assertEquals("Notatka przed wstrzymaniem", compose.runOnIdle { model.state.value.productNotes.single { it.entryId == id }.text })
            screenshot("powrot-do-oczekujacych-$route")
            compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        }
        compose.onNodeWithContentDescription("Wróć do produkcji").performClick()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-list").performScrollToNode(hasTestTag("completed-entry-return-butter"))
        compose.onNodeWithTag("completed-amount-return-butter", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 400 kg")
        compose.onNodeWithTag("completed-entry-return-butter").performClick()
        compose.onNodeWithText("Oczekuje na zaplanowanie: 600 kg").assertExists()
    }

    private fun dragCardToHour(id: String, hour: Int) {
        val destination = compose.onNodeWithTag("queue-hour-${today.plusDays(1)}-$hour").fetchSemanticsNode().boundsInRoot.center
        val hold = !compose.runOnIdle { model.state.value.entries.single { it.id == id }.pendingOrder }
        dragCard(id, destination, hold)
    }

    private fun dragCard(id: String, destination: androidx.compose.ui.geometry.Offset, hold: Boolean) {
        val source = compose.onNodeWithTag("queue-edit-$id").fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput {
            down(source - origin)
            if (hold) { advanceEventTime(750); moveTo(source - origin) }
            else {
                val direction = if (destination.x > source.x) 1 else -1
                moveTo(source + androidx.compose.ui.geometry.Offset(35f * direction, 0f) - origin, delayMillis = 16)
            }
            repeat(12) { step -> moveTo(source + (destination - source) * ((step + 1) / 12f) - origin, delayMillis = 16) }
            up()
        }
    }

    private fun swipeDelete(id: String) {
        compose.onNodeWithTag("queue-edit-$id").performScrollTo()
        val source = compose.onNodeWithTag("queue-edit-$id").fetchSemanticsNode().boundsInRoot.center
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pending = compose.runOnIdle { model.state.value.entries.single { it.id == id }.pendingOrder }
        dragCard(id, androidx.compose.ui.geometry.Offset(if (pending) root.right - 4 else root.left + 4, source.y), hold = false)
        compose.onNodeWithTag("queue-delete-pin").assertExists()
    }

    private fun dragToPending(id: String, hold: Boolean) {
        compose.onNodeWithTag("queue-edit-$id").performScrollTo()
        val source = compose.onNodeWithTag("queue-edit-$id").fetchSemanticsNode().boundsInRoot.center
        val orders = compose.onNodeWithTag("queue-pending-column").fetchSemanticsNode().boundsInRoot
        dragCard(id, androidx.compose.ui.geometry.Offset(orders.center.x, source.y), hold)
    }

    private fun reorderCard(id: String, target: String, after: Boolean) {
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-day-$today"))
        val bounds = compose.onNodeWithTag("queue-entry-$target").fetchSemanticsNode().boundsInRoot
        dragCard(id, androidx.compose.ui.geometry.Offset(bounds.center.x, bounds.center.y + if (after) 20 else -20), hold = true)
    }

    private fun openScheduleEditor(id: String, date: LocalDate) {
        compose.onNodeWithTag("queue-edit-$id").performScrollTo().performClick()
        compose.onNodeWithTag("queue-editor-production").performClick()
        compose.onNodeWithTag("queue-editor-date").performClick()
        chooseCalendarDay(date)
    }

    @Test fun editingAcceptsMinutePrecisionAndRejectsInvalidTimeWithoutMovingOrder() {
        openQueue(); compose.onNodeWithTag("production-queue_uht").performClick()
        compose.onNodeWithTag("queue-add").performClick()
        compose.onNodeWithText("Produkcja / produkt").performTextInput("UHT odbiorca")
        compose.onNodeWithText("Planowana ilość [l]").performTextInput("2000")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() }
        val id = compose.runOnIdle { model.state.value.entries.single().id }
        compose.onNodeWithContentDescription("Następny dzień").performClick()
        openScheduleEditor(id, today.plusDays(1))
        compose.onNodeWithTag("queue-editor-date").assert(hasText("Dzień produkcji: ${queueDateLabel(today.plusDays(1))}"))
        compose.onNodeWithTag("queue-editor-time").performTextClearance()
        compose.onNodeWithTag("queue-editor-time").performTextInput("24:00")
        compose.onNodeWithText("Zapisz").performClick()
        compose.onNodeWithText("Godzina musi mieścić się w zakresie 00:00–23:59.").assertExists()
        org.junit.Assert.assertTrue(compose.runOnIdle { model.state.value.entries.single().pendingOrder })
        compose.onNodeWithTag("queue-editor-time").performTextClearance()
        compose.onNodeWithTag("queue-editor-time").performTextInput("06:45")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("queue-editor-time").assert(hasText("06:45"))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() }
        val entry = compose.runOnIdle { model.state.value.entries.single() }
        org.junit.Assert.assertFalse(entry.pendingOrder)
        org.junit.Assert.assertEquals(today.plusDays(1), entry.productionDate)
        org.junit.Assert.assertEquals(java.time.LocalTime.of(6, 45), entry.scheduledTime)
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-entry-$id"))
        compose.onNodeWithTag("queue-remaining-$id", useUnmergedTree = true).assertTextEquals("Pozostało: 2\u00a0000 l")
    }

    @Test fun draggingAtPlanEdgeScrollsHoursHoveringArrowChangesDayAndDroppingOutsideCancels() {
        openQueue(); compose.onNodeWithTag("production-queue_powder").performClick()
        compose.runOnIdle {
            model.save("seed-order", "edge-order", ProductionLine.POWDER, today, "Proszek", "", java.math.BigDecimal("1000"), pendingOrder = true)
        }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.size == 1 } }
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        val source = compose.onNodeWithTag("queue-edit-edge-order").fetchSemanticsNode().boundsInRoot.center - origin
        val plan = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        val bottom = androidx.compose.ui.geometry.Offset(plan.center.x, plan.bottom - 12f) - origin
        val initialRange = compose.onNodeWithTag("queue-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onRoot().performTouchInput {
            down(source); advanceEventTime(750); moveTo(source); moveTo(bottom, delayMillis = 150)
        }
        compose.waitUntil(timeoutMillis = 10000) {
            val range = compose.onNodeWithTag("queue-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            range.value() > initialRange + 500f
        }
        val next = compose.onNodeWithContentDescription("Następny dzień").fetchSemanticsNode().boundsInRoot.center - origin
        compose.onRoot().performTouchInput { moveTo(next, delayMillis = 100) }
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("queue-date") and hasText(queueDateLabel(today.plusDays(1)))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().performTouchInput { moveTo(source, delayMillis = 30); up() }
        org.junit.Assert.assertTrue(compose.runOnIdle { model.state.value.entries.single().pendingOrder })
        org.junit.Assert.assertNull(compose.runOnIdle { model.state.value.entries.single().productionDate })
        compose.onNodeWithTag("queue-entry-edge-order").assert(hasAnyAncestor(hasTestTag("queue-orders")))
        screenshot("plan-przewijanie-przy-przenoszeniu")
    }

    private fun backToHome() {
        compose.onNodeWithContentDescription("Wróć do kolejki produkcji").performClick()
        compose.onNodeWithContentDescription("Wróć do produkcji").performClick()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
    }

    @Test fun longPressBubbleMovesPartialGoodsAndExcessIntoCompletedCatalogAndPreservesRemainder() {
        compose.onNodeWithTag("home-Production").assertIsDisplayed()
        compose.onNodeWithTag("home-Completed").assertIsDisplayed()
        screenshot("start-produkcja-wyprodukowano")
        compose.onNodeWithText("Zmiana 2").performClick()
        compose.onNodeWithTag("menu-Production").assertDoesNotExist()
        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        openQueue(); compose.onNodeWithTag("production-queue_butter").performClick()
        add("Masło 1000", "Zamówienie odbiorcy")
        val id = compose.runOnIdle { model.state.value.entries.single().id }
        compose.onNodeWithTag("queue-edit-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("production-completion-bubble").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("production-completion-bubble").assertDoesNotExist()
        compose.onNodeWithTag("production-queue-page").assertExists()
        org.junit.Assert.assertEquals(java.math.BigDecimal.ZERO, compose.runOnIdle { model.state.value.entries.single().producedAmount })
        holdAndProduce(id, "400")
        compose.onNodeWithTag("bubble-remaining").assertTextEquals("W kolejce pozostanie: 600 kg")
        screenshot("dymek-wyprodukowano")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Wyprodukowano teraz [kg]").assert(hasText("400"))
        compose.onNodeWithTag("bubble-save").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.state.value.entries.single().producedAmount == java.math.BigDecimal("400") } }
        compose.onNodeWithTag("queue-remaining-$id", useUnmergedTree = true).assertTextEquals("Pozostało: 600 kg")
        backToHome(); compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-amount-$id", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 400 kg")
        compose.onNodeWithTag("completed-entry-$id").performClick()
        compose.onNodeWithText("W kolejce pozostało: 600 kg").assertExists()
        screenshot("wyprodukowano-czesc")
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        openQueue(); compose.onNodeWithTag("production-queue_butter").performClick()
        holdAndProduce(id, "800")
        compose.onNodeWithTag("bubble-excess").assertTextEquals("Nadmiar: 200 kg")
        compose.onNodeWithTag("bubble-remaining").assertTextEquals("W kolejce pozostanie: 0 kg")
        compose.onNodeWithTag("bubble-save").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.state.value.entries.single().completed } }
        compose.onNodeWithTag("queue-entry-$id").assertDoesNotExist()
        backToHome(); compose.onNodeWithTag("home-Completed").performClick()
        compose.onNodeWithTag("completed-amount-$id", useUnmergedTree = true).assertTextEquals("Wyprodukowano: 1\u00a0200 kg")
        compose.onNodeWithTag("completed-order-$id", useUnmergedTree = true).assertTextEquals("Zamówiono: 1\u00a0000 kg + nadmiar: 200 kg")
        compose.onNodeWithTag("completed-entry-$id").performClick()
        compose.onNodeWithText("Zapisy wykonania").assertExists()
        screenshot("wyprodukowano-nadwyzka")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("completed-order-$id", useUnmergedTree = true).assert(hasText("nadmiar: 200 kg", substring = true))
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithTag("completed-filter-UHT").performScrollTo().performClick()
        compose.onNodeWithText("Brak wyprodukowanego towaru").assertExists()
        compose.onNodeWithText("Wszystkie").performScrollTo().performClick()
        compose.onNodeWithTag("completed-entry-$id").assertExists()
    }

    private fun holdAndProduce(id: String, amount: String) {
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-$id"))
        compose.onNodeWithTag("queue-edit-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("production-completion-bubble").assertExists()
        compose.onNodeWithText("Wyprodukowano teraz [kg]").performTextInput(amount)
    }

    @Test fun notesCanBeAddedInOrdersProductionAndCompletedGoodsAndStayTogetherAfterReopening() {
        openQueue(); compose.onNodeWithTag("production-queue_butter").performClick()
        compose.onNodeWithTag("queue-add").performClick()
        compose.onNodeWithText("Produkcja / produkt").performTextInput("Masło z notatkami")
        compose.onNodeWithText("Planowana ilość [kg]").performTextInput("1000")
        compose.onNodeWithText("Plan / uwagi").performTextInput("Plan odbiorcy")
        compose.onNode(hasText("Zapisz") and !hasTestTag("bubble-save")).performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() }
        val id = compose.runOnIdle { model.state.value.entries.single().id }
        appendNote(id, "Wymagania odbiorcy")
        openScheduleEditor(id, today)
        compose.onNode(hasText("Zapisz") and !hasTestTag("bubble-save")).performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Produkcja / produkt").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-$id"))
        appendNote(id, "Kontrola podczas produkcji")
        compose.onNodeWithTag("queue-edit-$id").performTouchInput { longClick() }
        compose.onNodeWithText("Wyprodukowano teraz [kg]").performTextInput("400")
        compose.onNodeWithTag("bubble-notes").performClick()
        compose.onNodeWithTag("product-note-input").performTextInput("Parametry przy odbiorze partii")
        compose.onNode(hasText("Zapisz") and !hasTestTag("bubble-save")).performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("product-notes-dialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Wyprodukowano teraz [kg]").assert(hasText("400"))
        compose.onNodeWithTag("bubble-save").performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { model.state.value.entries.single().producedAmount == java.math.BigDecimal("400") } }
        backToHome(); compose.onNodeWithTag("home-Completed").performClick()
        appendNote(id, "Notatka dla magazynu")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("product-notes-$id").performClick()
        listOf("Wymagania odbiorcy", "Kontrola podczas produkcji", "Parametry przy odbiorze partii", "Notatka dla magazynu", "Plan odbiorcy").forEach { value ->
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("product-notes-dialog"))).assertExists()
        }
        screenshot("notatki-produktu-etapy")
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        openQueue(); compose.onNodeWithTag("production-queue_butter").performClick()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-$id"))
        compose.onNodeWithTag("queue-remaining-$id", useUnmergedTree = true).assertTextEquals("Pozostało: 600 kg")
        org.junit.Assert.assertEquals(listOf(ProductNoteStage.COMPLETED, ProductNoteStage.PRODUCTION, ProductNoteStage.PRODUCTION, ProductNoteStage.ORDER),
            compose.runOnIdle { model.state.value.productNotes.map { it.stage } })
    }

    private fun appendNote(id: String, text: String) {
        compose.onNodeWithTag("product-notes-$id").performClick()
        compose.onNodeWithTag("product-note-input").performTextInput(text)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("product-note-input").assert(hasText(text))
        compose.onNode(hasText("Zapisz") and !hasTestTag("bubble-save")).performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithTag("product-notes-dialog").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun shortHorizontalDragAndVerticalScrollingKeepTheProductAndMinutePrecision() {
        compose.runOnIdle { model.save("short", "short", ProductionLine.UHT, today, "Krótki gest", "", java.math.BigDecimal("1000"),
            pendingOrder = false, scheduledTime = java.time.LocalTime.of(9, 15)) }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.size == 1 } }
        openQueue(); compose.onNodeWithTag("production-queue_uht").performClick()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-short"))
        val before = compose.runOnIdle { model.state.value.entries.single() }
        val source = compose.onNodeWithTag("queue-edit-short").fetchSemanticsNode().boundsInRoot.center
        dragCard("short", source + androidx.compose.ui.geometry.Offset(-40f, 0f), hold = false)
        compose.runOnIdle { assertEquals(before, model.state.value.entries.single()) }
        compose.onNodeWithTag("queue-delete-pin").assertDoesNotExist()
        compose.onNodeWithText("Produkcja / produkt").assertDoesNotExist()
        compose.onNodeWithTag("queue-entry-short").performTouchInput { swipeUp(durationMillis = 300) }
        compose.runOnIdle { assertEquals(before, model.state.value.entries.single()) }
        compose.onNodeWithTag("queue-delete-pin").assertDoesNotExist()
        compose.onNodeWithTag("production-completion-bubble").assertDoesNotExist()
    }

    @Test fun draggingPlannedProductContinuesAfterAutoScrollRecyclesSourceCard() {
        compose.runOnIdle { model.save("offscreen", "offscreen", ProductionLine.UHT, today, "Przewijana produkcja", "", java.math.BigDecimal("1000"),
            pendingOrder = false, scheduledTime = java.time.LocalTime.of(8, 0)) }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.size == 1 } }
        openQueue(); compose.onNodeWithTag("production-queue_uht").performClick()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-edit-offscreen"))
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        val source = compose.onNodeWithTag("queue-edit-offscreen").fetchSemanticsNode().boundsInRoot.center - origin
        val plan = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        val bottom = androidx.compose.ui.geometry.Offset(plan.center.x, plan.bottom - 12f) - origin
        compose.onRoot().performTouchInput { down(source); advanceEventTime(750); moveTo(source); moveTo(bottom, delayMillis = 100) }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("queue-entry-offscreen").fetchSemanticsNodes().isEmpty() }
        val destination = compose.onNodeWithTag("queue-pending-column").fetchSemanticsNode().boundsInRoot.center - origin
        compose.onRoot().performTouchInput { moveTo(destination, delayMillis = 100); up() }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.single().pendingOrder } }
        compose.onNodeWithTag("queue-entry-offscreen").assert(hasAnyAncestor(hasTestTag("queue-orders")))
    }

    @Test fun twoHourTimelineScrollsIntoFollowingDaysUpdatesCalendarAndKeepsExactHours() {
        openQueue(); compose.onNodeWithTag("production-queue_uht").performClick()
        compose.runOnIdle {
            model.save("seed-time", "exact-time", ProductionLine.UHT, today.plusDays(1), "Mleko na jutro", "", java.math.BigDecimal("1000"),
                pendingOrder = false, scheduledTime = java.time.LocalTime.of(9, 15))
        }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.entries.size == 1 } }
        compose.onNode(hasText("00:00–02:00") and hasAnyAncestor(hasTestTag("queue-hour-$today-0"))).assertExists()
        compose.onNodeWithTag("queue-hour-$today-1").assertDoesNotExist()
        // Actual vertical gestures cross midnight; the header follows the day at the top of the plan.
        repeat(6) {
            if (compose.onAllNodes(hasTestTag("queue-date") and hasText(queueDateLabel(today.plusDays(1)))).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("queue-list").performTouchInput { swipeUp(durationMillis = 1000) }
            }
        }
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onAllNodes(hasTestTag("queue-date") and hasText(queueDateLabel(today.plusDays(1)))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("queue-date").performClick()
        val currentLabel = today.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.US))
        compose.onNode(hasText(currentLabel, substring = true) and isSelected() and hasAnyAncestor(hasTestTag("queue-calendar"))).assertExists()
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-entry-exact-time"))
        compose.onNodeWithTag("queue-entry-exact-time").assert(hasAnyAncestor(hasTestTag("queue-hour-${today.plusDays(1)}-8")))
        compose.onNodeWithTag("queue-entry-exact-time").assert(hasText("Pozostało: 1\u00a0000 l", substring = true))
        compose.onNodeWithTag("queue-time-exact-time", useUnmergedTree = true).assertTextEquals("09:15")
        compose.activityRule.scenario.recreate()
        org.junit.Assert.assertEquals(java.time.LocalTime.of(9, 15), compose.runOnIdle { model.state.value.entries.single().scheduledTime })
        screenshot("kolejka-co-dwie-godziny")
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-day-${today.plusDays(2)}"))
        val dayTop = compose.onNodeWithTag("queue-day-${today.plusDays(2)}").fetchSemanticsNode().boundsInRoot.top
        val planTop = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("queue-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            it(0f, dayTop - planTop)
        }
        compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today.plusDays(2))))
        compose.onNodeWithTag("queue-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -160f) }
        screenshot("kolejka-granica-dni-delikatne-kolory")
        compose.onNodeWithText("Dzisiaj").performClick()
        compose.onNodeWithTag("queue-date").assert(hasText(queueDateLabel(today)))
        compose.onNodeWithTag("queue-entry-exact-time").assertDoesNotExist()
    }

}

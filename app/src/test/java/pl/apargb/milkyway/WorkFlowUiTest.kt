package pl.apargb.milkyway

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun emptyNotes() {
        val model = ViewModelProvider(compose.activity)[WorkNotesViewModel::class.java]
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
        WorkNotesDatabase(model.getApplication()).use { it.writableDatabase.delete("work_notes", null, null) }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(timeoutMillis = 10000) { compose.runOnIdle { !model.state.value.loading } }
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

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10000) {
        compose.onAllNodesWithTag("work-note-editor").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("notes-list"))).fetchSemanticsNodes().isNotEmpty()
    }

    private fun addEntry(body: String, button: String) {
        compose.onNodeWithText(button).performClick()
        compose.onNodeWithText("Tytuł").assertDoesNotExist()
        compose.onNodeWithText("Treść / notatka").performTextInput(body)
        compose.onNodeWithText("Zapisz").performClick()
        waitForText(body)
    }

    @Test fun choosingShiftOpensPanelAndNotesCanBeEditedCompletedScopedAndRestored() {
        compose.onNodeWithTag("menu-Tanks").assertDoesNotExist()
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").assertIsDisplayed()
        listOf("Tanks", "Softlab", "Reminders", "Controls").forEach {
            compose.onNodeWithTag("menu-$it").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Wybierz zmianę, na której pracujesz.").assertDoesNotExist()
        compose.onNodeWithTag("main-list").performScrollToIndex(0)
        screenshot("dashboard-zmiana-1")
        compose.onNodeWithTag("menu-Softlab").performClick()
        compose.onNodeWithText("Jak działa Softlab").assertExists()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()

        compose.onNodeWithTag("menu-Reminders").performClick()
        addEntry("Przekazanie zmiany — uwagi", "Dodaj przypomnienie")
        compose.onNodeWithText("Przekazanie zmiany — uwagi").performClick()
        compose.onNodeWithText("Treść / notatka").performTextClearance()
        compose.onNodeWithText("Treść / notatka").performTextInput("Uzupełniona notatka")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Uzupełniona notatka", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        waitForText("Uzupełniona notatka")
        val checkBox = hasAnyAncestor(hasText("Uzupełniona notatka")) and isToggleable()
        compose.onNode(checkBox).performClick()
        compose.waitUntil(timeoutMillis = 10000) { compose.onAllNodesWithText("Załatwione").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNode(checkBox).assertIsOn()
        screenshot("przypomnienia-edycja")
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()

        compose.onNodeWithTag("menu-Controls").performScrollTo().performClick()
        controlKinds.forEach { compose.onNodeWithTag("control-${it.name}").assertIsDisplayed() }
        screenshot("kontrola-parametrow")
        compose.onNodeWithTag("control-CURRENT_NOTES").performClick()
        addEntry("Bieżąca notatka do zachowania", "Dodaj notatkę")
        compose.onNodeWithContentDescription("Wróć do kontroli parametrów").performClick()
        compose.onNodeWithTag("control-AIR_CONDITIONING").performClick()
        compose.onNodeWithText("Brak wpisów").assertExists()
        addEntry("Wpis kontrolny", "Dodaj wpis")
        compose.onNodeWithContentDescription("Wróć do kontroli parametrów").performClick()
        compose.onNodeWithTag("control-WASHING").performClick()
        compose.onNodeWithText("Brak wpisów").assertExists()
        compose.onNodeWithContentDescription("Wróć do kontroli parametrów").performClick()
        compose.onNodeWithTag("control-PRODUCTION").performClick()
        compose.onNodeWithTag("production-queue").assertIsDisplayed()
        compose.onNodeWithTag("production-notes").assertDoesNotExist()
        compose.onNodeWithTag("production-tanks").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wróć do kontroli parametrów").performClick()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()

        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        compose.onNodeWithText("Zmiana 2").performClick()
        compose.onNodeWithTag("menu-Reminders").performClick()
        compose.onNodeWithText("Brak wpisów").assertExists()
        compose.onNodeWithText("Sprawdź wyparkę").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Controls").performScrollTo().performClick()
        compose.onNodeWithTag("control-CURRENT_NOTES").performClick()
        compose.onNodeWithText("Bieżąca notatka do zachowania").assertExists()
        compose.onNodeWithContentDescription("Usuń wpis: Bieżąca notatka do zachowania").performClick()
        compose.onNodeWithText("Usuń").performClick()
        waitForText("Brak wpisów")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Bieżąca notatka do zachowania").assertDoesNotExist()
    }

    @Test fun shiftPanelNotesCanBeSavedEditedAndOpenedFromBothShortcuts() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").assertIsDisplayed().performClick()
        compose.onNodeWithText("Wpisy dla zmiany 1").assertExists()
        addEntry("Informacja do przekazania", "Dodaj notatkę")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Informacja do przekazania").assertExists()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithTag("menu-Controls").performScrollTo().performClick()
        compose.onNodeWithTag("control-CURRENT_NOTES").performClick()
        compose.onNodeWithText("Informacja do przekazania").performClick()
        compose.onNodeWithText("Treść / notatka").performTextClearance()
        compose.onNodeWithText("Treść / notatka").performTextInput("Poprawiona informacja")
        compose.onNodeWithText("Zapisz").performClick()
        waitForText("Poprawiona informacja")
        compose.onNodeWithContentDescription("Wróć do kontroli parametrów").performClick()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithTag("menu-Notes").performScrollTo().performClick()
        compose.onNodeWithText("Poprawiona informacja").assertExists()
        screenshot("notatki-zmiany")
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        compose.onNodeWithText("Zmiana 2").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Wpisy dla zmiany 2").assertExists()
        compose.onNodeWithText("Brak wpisów").assertExists()
        compose.onNodeWithText("Poprawiona informacja").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wróć do panelu zmiany").performClick()
        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Poprawiona informacja").assertExists()
    }

    @Test fun importantShiftNotesKeepTheirRedTextAfterReopeningAndCanBeUnmarked() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Treść / notatka").performTextInput("Sprawdzić parametry przed produkcją")
        compose.onNodeWithTag("work-note-important").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("work-note-important").assertIsOn()
        screenshot("notatka-formularz")
        compose.onNodeWithText("Zapisz").performClick()
        waitForText("Sprawdzić parametry przed produkcją")
        compose.onNodeWithText("Sprawdzić parametry przed produkcją", useUnmergedTree = true).assertTextColor(ImportantNoteColor)
        screenshot("wazna-notatka-zmiany")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Sprawdzić parametry przed produkcją").performClick()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Sprawdzić parametry przed produkcją", substring = true))
        compose.onNodeWithTag("work-note-important").assertIsOn().performClick()
        compose.onNodeWithText("Zapisz").performClick()
        waitForText("Sprawdzić parametry przed produkcją")
        val model = ViewModelProvider(compose.activity)[WorkNotesViewModel::class.java]
        compose.runOnIdle {
            val note = model.state.value.notes.single()
            org.junit.Assert.assertFalse(note.important)
            org.junit.Assert.assertEquals("Sprawdzić parametry przed produkcją", note.body)
        }
    }
}

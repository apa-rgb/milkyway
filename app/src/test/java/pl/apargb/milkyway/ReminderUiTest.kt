package pl.apargb.milkyway

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[WorkNotesViewModel::class.java]
    @Before fun ready() {
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
        WorkNotesDatabase(model.getApplication()).use { it.writableDatabase.delete("work_notes", null, null) }
        ReminderScheduler.clear(compose.activity)
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
        assertTrue("A test must begin with an empty note list", compose.runOnIdle { model.state.value.notes.isEmpty() })
    }
    private fun due(kind: NoteKind = NoteKind.CURRENT_NOTES) {
        WorkNotesDatabase(model.getApplication()).use { db ->
            WorkNotesRepository(model.getApplication(), db).save("due", 1, kind, "Sprawdź pompę", withNoteReminder("Opis do przeczytania", NoteReminder(1L)), 0L, "Anna")
        }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
        assertEquals(1L, compose.runOnIdle { model.state.value.notes.single().reminder!!.at })
        compose.waitUntil(10000) { compose.onAllNodesWithTag("due-reminder").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val view = WindowInspector.getGlobalWindowViews().last { it.isShown }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/screenshots/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun dueNoteReturnsHomeUrgentTurnsRedAndOkPersistsAfterRestart() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        due()
        compose.onNodeWithTag("due-reminder-text").assertIsDisplayed()
        compose.onNodeWithText("Autor: Anna").assertExists()
        val done = compose.onNodeWithTag("reminder-done").getUnclippedBoundsInRoot()
        val read = compose.onNodeWithTag("reminder-read").getUnclippedBoundsInRoot()
        val urgent = compose.onNodeWithTag("reminder-urgent").getUnclippedBoundsInRoot()
        assertTrue(done.left < read.left && read.left < urgent.left)
        compose.onNodeWithTag("reminder-urgent").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { model.state.value.notes.single().important } }
        compose.onNodeWithTag("due-reminder-text").assertTextColor(ImportantNoteColor)
        screenshot("przypomnienie-pilne")
        compose.onNodeWithTag("reminder-read").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("due-reminder").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("home-Production").assertExists()
        assertEquals(setOf("local"), compose.runOnIdle { model.state.value.notes.single().reminder!!.readBy })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("due-reminder").assertDoesNotExist()
    }

    @Test fun doneCompletesReminderAndRemovesThePopup() {
        due(NoteKind.REMINDER)
        compose.onNodeWithTag("reminder-done").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("due-reminder").fetchSemanticsNodes().isEmpty() }
        assertTrue(compose.runOnIdle { model.state.value.notes.single().completed })
        assertTrue(compose.runOnIdle { model.state.value.notes.single().reminder!!.done })
    }

    @Test fun bottomReminderPickerKeepsDraftAndScheduleAcrossRotation() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Tytuł").performTextInput("Jutro sprawdzić")
        compose.onNodeWithText("Treść / notatka").performTextInput("Zachowany opis")
        compose.onNodeWithTag("note-reminder").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reminder-time").performTextReplacement("25:00")
        compose.onNodeWithTag("form-save-Kiedy przypomnieć?").performClick()
        compose.onNodeWithTag("reminder-picker-error").assertExists()
        compose.onNodeWithTag("reminder-time").performTextReplacement("23:59")
        screenshot("przypomnienie-termin")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reminder-time").assert(hasText("23:59", substring = true))
        compose.onNodeWithTag("form-save-Kiedy przypomnieć?").performClick()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Zachowany opis", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.notes.isNotEmpty() } }
        val note = compose.runOnIdle { model.state.value.notes.single() }
        assertNotNull(note.reminder); assertEquals("Zachowany opis", note.visibleBody)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Jutro sprawdzić").performClick()
        compose.onNodeWithTag("note-reminder").assertIsDisplayed()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Zachowany opis", substring = true))
    }

    @Test fun reminderInterruptedDraftRemainsAvailableAfterReadConfirmation() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Tytuł").performTextInput("Niezapisany wpis")
        compose.onNodeWithText("Treść / notatka").performTextInput("Wersja robocza do zachowania")
        due()
        compose.onNodeWithTag("reminder-read").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("due-reminder").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Tytuł").assert(hasText("Niezapisany wpis", substring = true))
        compose.onNodeWithText("Treść / notatka").assert(hasText("Wersja robocza do zachowania", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.notes.size == 2 } }
        assertEquals("Wersja robocza do zachowania", compose.runOnIdle { model.state.value.notes.single { it.title == "Niezapisany wpis" }.visibleBody })
    }

    @Test fun notificationReturnsHomeOnceAndDoesNotRedirectAgainOnRotation() {
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity,
            Intent(compose.activity.intent).putExtra(ReminderScheduler.OPEN_HOME, true)) }
        compose.onNodeWithTag("home-Production").assertExists()
        compose.onNodeWithText("Zmiana 1").performClick()
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Tytuł").performTextInput("Po powiadomieniu")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Tytuł").assert(hasText("Po powiadomieniu", substring = true))
        compose.onNodeWithTag("home-Production").assertDoesNotExist()
    }
}

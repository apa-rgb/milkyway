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
        compose.openShift(1)
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
        compose.openShift(1)
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Tytuł").assertDoesNotExist()
        compose.onNodeWithText("Zapisz").assertIsNotEnabled()
        compose.onNodeWithText("Treść / notatka").performTextInput("Zachowany opis")
        compose.onNodeWithTag("note-reminder").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reminder-date").performClick()
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        val dayLabel = tomorrow.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.US))
        compose.onNode(hasText(dayLabel, substring = true) and hasAnyAncestor(hasTestTag("reminder-calendar"))).performClick()
        compose.onNodeWithText("Wybierz").performClick()
        compose.onNodeWithTag("reminder-time").performClick()
        compose.onNodeWithTag("reminder-clock").assertIsDisplayed()
        screenshot("przypomnienie-zegarek")
        compose.onNode(hasContentDescription("23 hours") and hasAnyAncestor(hasTestTag("reminder-clock"))).performClick()
        compose.onNode(hasContentDescription("Select minutes", substring = true) and hasAnyAncestor(hasTestTag("reminder-clock"))).performClick()
        compose.onNode(hasContentDescription("55 minutes") and hasAnyAncestor(hasTestTag("reminder-clock"))).performClick()
        screenshot("przypomnienie-zegarek")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reminder-clock").assertIsDisplayed()
        compose.onNodeWithTag("reminder-clock-confirm").performClick()
        compose.onNodeWithTag("reminder-time").assert(hasText("23:55", substring = true))
        compose.onNodeWithTag("form-save-Kiedy przypomnieć?").performClick()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Zachowany opis", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.notes.isNotEmpty() } }
        val note = compose.runOnIdle { model.state.value.notes.single() }
        assertNotNull(note.reminder); assertEquals("Zachowany opis", note.visibleBody); assertEquals("", note.title)
        val scheduled = java.time.Instant.ofEpochMilli(note.reminder!!.at).atZone(java.time.ZoneId.systemDefault())
        assertEquals(tomorrow, scheduled.toLocalDate()); assertEquals(java.time.LocalTime.of(23, 55), scheduled.toLocalTime())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Zachowany opis").performClick()
        compose.onNodeWithTag("note-reminder").assertIsDisplayed()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Zachowany opis", substring = true))
    }

    @Test fun reminderInterruptedDraftRemainsAvailableAfterReadConfirmation() {
        compose.openShift(1)
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Treść / notatka").performTextInput("Wersja robocza do zachowania")
        due()
        compose.onNodeWithTag("reminder-read").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("due-reminder").fetchSemanticsNodes().isEmpty() }
        compose.openShift(1)
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Wersja robocza do zachowania", substring = true))
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.notes.size == 2 } }
        assertEquals("Wersja robocza do zachowania", compose.runOnIdle { model.state.value.notes.single { it.visibleBody == "Wersja robocza do zachowania" }.visibleBody })
    }

    @Test fun notificationReturnsHomeOnceAndDoesNotRedirectAgainOnRotation() {
        compose.openShift(1)
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.runOnIdle { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity,
            Intent(compose.activity.intent).putExtra(ReminderScheduler.OPEN_HOME, true)) }
        compose.onNodeWithTag("home-Production").assertExists()
        compose.openShift(1)
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Treść / notatka").performTextInput("Po powiadomieniu")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Treść / notatka").assert(hasText("Po powiadomieniu", substring = true))
        compose.onNodeWithTag("home-Production").assertDoesNotExist()
    }

    @Test fun threeBottomActionsKeepTheirStatesAfterRotationAndSaveWithoutAnAlarmWhenDone() {
        compose.openShift(1)
        compose.onNodeWithTag("menu-Notes").performClick()
        compose.onNodeWithText("Dodaj notatkę").performClick()
        compose.onNodeWithText("Treść / notatka").performTextInput("Trzy przyciski w dolnej belce")
        val important = compose.onNodeWithTag("work-note-important")
        val reminder = compose.onNodeWithTag("note-reminder")
        val completed = compose.onNodeWithTag("work-note-completed")
        listOf(important, reminder, completed).forEach { it.assertIsDisplayed().assertIsOff() }
        val first = important.getUnclippedBoundsInRoot(); val middle = reminder.getUnclippedBoundsInRoot(); val last = completed.getUnclippedBoundsInRoot()
        assertEquals(first.top, middle.top); assertEquals(middle.top, last.top)
        assertTrue(first.right <= middle.left && middle.right <= last.left)
        assertTrue(first.top >= compose.onNodeWithTag("work-note-input").getUnclippedBoundsInRoot().bottom)
        important.performClick().assertIsOn()
        reminder.performClick()
        compose.onNodeWithTag("form-save-Kiedy przypomnieć?").performClick()
        reminder.assertIsOn()
        completed.performClick().assertIsOn()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("work-note-important").assertIsOn()
        compose.onNodeWithTag("note-reminder").assertIsOn()
        compose.onNodeWithTag("work-note-completed").assertIsOn()
        screenshot("notatka-trzy-przyciski")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.notes.isNotEmpty() } }
        val note = compose.runOnIdle { model.state.value.notes.single() }
        assertTrue(note.important); assertTrue(note.completed); assertTrue(note.reminder!!.done)
        assertEquals("Trzy przyciski w dolnej belce", note.visibleBody)
        assertFalse(note.reminder!!.due("local", Long.MAX_VALUE))
        compose.onNodeWithText(note.visibleBody).performClick()
        compose.onNodeWithTag("work-note-completed").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && !model.state.value.notes.single().completed } }
        assertFalse(compose.runOnIdle { model.state.value.notes.single().reminder!!.done })
    }

}

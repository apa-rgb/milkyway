package pl.apargb.milkyway

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnouncementBoardUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: WorkNotesViewModel
    private val today get() = LocalDate.now()

    @Before fun seedSeparateDaysAndShiftReminder() {
        model = ViewModelProvider(compose.activity)[WorkNotesViewModel::class.java]
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
        WorkNotesDatabase(model.getApplication()).use { it.writableDatabase.delete("work_notes", null, null) }
        val repo = WorkNotesRepository(model.getApplication())
        try {
            val created = today.minusDays(1).atTime(10, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repo.save("yesterday", 0, NoteKind.REMINDER, "Wczorajsze ogłoszenie", "Oryginalna treść", created)
            repo.save("yesterday", 0, NoteKind.REMINDER, "Wczorajsze ogłoszenie", "Poprawiona treść", System.currentTimeMillis())
            repo.save("shift", 1, NoteKind.REMINDER, "Przypomnienie zmiany", "Oddzielny wpis", System.currentTimeMillis())
        } finally { repo.close() }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.loading } }
    }

    @Test fun announcementsKeepCreationTimeAndStatusAndFollowCalendarAndScrollingAcrossShifts() {
        compose.onNodeWithTag("home-Announcements").assertIsDisplayed().performClick()
        compose.onNodeWithTag("announcements-date").assert(hasText(queueDateLabel(today)))
        compose.onNodeWithText("Wczorajsze ogłoszenie").assertDoesNotExist()
        compose.onNodeWithText("Przypomnienie zmiany").assertDoesNotExist()
        compose.onNodeWithTag("announcements-add").performClick()
        compose.onNodeWithText("Tytuł").performTextInput("Informacja dla wszystkich")
        compose.onNodeWithText("Treść / notatka").performTextInput("Przegląd instalacji rano")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("announcement-status-yesterday").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("Tytuł").fetchSemanticsNodes().isEmpty() &&
            compose.runOnIdle { model.state.value.notes.any { it.title == "Informacja dla wszystkich" } } }
        val note = compose.runOnIdle { model.state.value.notes.single { it.title == "Informacja dla wszystkich" } }
        compose.onNodeWithTag("announcement-created-${note.id}", true).assertTextEquals("Dodano: ${announcementTimestamp(note.createdAt)}")
        compose.onNodeWithTag("announcement-done-${note.id}").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { !model.state.value.saving && model.state.value.notes.single { it.id == note.id }.completed } }
        compose.onNodeWithTag("announcement-status-${note.id}", true).assertTextEquals("Załatwione")
        compose.onNodeWithTag("announcement-${note.id}").performClick()
        compose.onNodeWithText("Treść / notatka").performTextClearance()
        compose.onNodeWithText("Treść / notatka").performTextInput("Zaktualizowane ogłoszenie")
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Tytuł").fetchSemanticsNodes().isEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("announcement-done-${note.id}").assertIsOn()
        compose.runOnIdle { assertEquals(note.createdAt, model.state.value.notes.single { it.id == note.id }.createdAt) }
        compose.onNodeWithTag("announcements-days").performTouchInput { swipeUp(durationMillis = 450) }
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("announcements-date") and hasText(queueDateLabel(today.plusDays(1)))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("announcements-date").performClick()
        val yesterday = today.minusDays(1)
        val label = yesterday.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.US))
        compose.onNode(hasText(label, substring = true) and hasAnyAncestor(hasTestTag("announcements-calendar"))).performClick()
        compose.onNodeWithText("Wybierz").performClick()
        compose.onNodeWithTag("announcements-date").assert(hasText(queueDateLabel(yesterday)))
        compose.onNodeWithText("Wczorajsze ogłoszenie").assertExists()
        compose.onNodeWithTag("announcement-created-yesterday", true).assertTextEquals("Dodano: ${queueDateLabel(yesterday)} 10:15")
        compose.onNodeWithTag("announcements-today").performClick()
        compose.onNodeWithText("Zaktualizowane ogłoszenie").assertExists()
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isShown }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/screenshots/tablica-ogloszen.png").apply {
                parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        compose.onNodeWithContentDescription("Wróć do ekranu głównego").performClick()
        compose.onNodeWithText("Zmiana 2").performClick()
        compose.onNodeWithContentDescription("Zmień zmianę").performClick()
        compose.onNodeWithTag("home-Announcements").performClick()
        compose.onNodeWithText("Zaktualizowane ogłoszenie").assertExists()
        compose.onNodeWithTag("announcement-done-${note.id}").assertIsOn()
    }
}

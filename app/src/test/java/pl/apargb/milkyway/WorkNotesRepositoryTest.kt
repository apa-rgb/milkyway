package pl.apargb.milkyway

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WorkNotesRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: WorkNotesRepository
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(WorkNotesDatabase.NAME)
        repository = WorkNotesRepository(context)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(WorkNotesDatabase.NAME) }

    @Test fun notesSurviveReopeningAndEditingPreservesCreationDateAndDoesNotDuplicate() {
        repository.save("note", 1, NoteKind.CURRENT_NOTES, "Przekazanie", "Zażółć 'gęślą'\nDruga linia", 100L)
        repository.close()
        repository = WorkNotesRepository(context)
        assertEquals("Zażółć 'gęślą'\nDruga linia", repository.load().single().body)
        repository.save("note", 1, NoteKind.CURRENT_NOTES, "Poprawione", "Nowa treść", 200L)
        val note = repository.load().single()
        assertEquals("Poprawione", note.title)
        assertEquals(100L, note.createdAt)
        assertEquals(200L, note.updatedAt)
        repository.save("note", 1, NoteKind.CURRENT_NOTES, "Poprawione", "Nowa treść", 200L)
        assertEquals(listOf(note), repository.load())
    }

    @Test fun entriesKeepTheirShiftAndCategoryAndCannotBeMovedByEditingTheirId() {
        repository.save("shift-one", 1, NoteKind.REMINDER, "A", "", 100L)
        repository.save("shift-two", 2, NoteKind.REMINDER, "B", "", 200L)
        repository.save("production", 1, NoteKind.PRODUCTION, "C", "", 300L)
        assertEquals(listOf("production", "shift-two", "shift-one"), repository.load().map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            repository.save("shift-one", 2, NoteKind.REMINDER, "Przeniesione", "", 400L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.save("shift-one", 1, NoteKind.WASHING, "Przeniesione", "", 400L)
        }
        assertEquals("A", repository.load().single { it.id == "shift-one" }.title)
    }

    @Test fun completedRemindersStayCompletedAfterEditAndReopenAndCanBeUnchecked() {
        repository.save("task", 3, NoteKind.REMINDER, "Kontrola", "Opis", 100L)
        repository.setCompleted(repository.load().single(), true, 200L)
        repository.save("task", 3, NoteKind.REMINDER, "Kontrola 2", "Opis 2", 300L)
        repository.close()
        repository = WorkNotesRepository(context)
        assertTrue(repository.load().single().completed)
        repository.setCompleted(repository.load().single(), false, 400L)
        assertFalse(repository.load().single().completed)
    }

    @Test fun deletingOneEntryKeepsOthersAndRejectsAnotherShiftOrKind() {
        repository.save("a", 1, NoteKind.CURRENT_NOTES, "A", "", 100L)
        repository.save("b", 2, NoteKind.CURRENT_NOTES, "B", "", 200L)
        val note = repository.load().single { it.id == "a" }
        assertThrows(IllegalArgumentException::class.java) { repository.delete(note.copy(scope = 2)) }
        assertThrows(IllegalArgumentException::class.java) { repository.delete(note.copy(kind = NoteKind.PRODUCTION)) }
        repository.delete(note)
        repository.close()
        repository = WorkNotesRepository(context)
        assertEquals(listOf("b"), repository.load().map { it.id })
    }

    @Test fun invalidEntriesDoNotOverwriteExistingDataAndOnlyRemindersCanBeCompleted() {
        repository.save("a", 1, NoteKind.AIR_CONDITIONING, "Pomiar", "4 °C", 100L)
        val before = repository.load()
        listOf(" ", "x".repeat(121)).forEach { title ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.save("a", 1, NoteKind.AIR_CONDITIONING, title, "", 200L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.save("b", 4, NoteKind.REMINDER, "B", "", 200L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.save("a", 1, NoteKind.AIR_CONDITIONING, "A", "x".repeat(10001), 200L)
        }
        assertThrows(IllegalArgumentException::class.java) { repository.setCompleted(before.single(), true, 200L) }
        assertEquals(before, repository.load())
    }

    @Test fun fullLengthImportantNoteKeepsItsOriginalAuthorAfterEditingAndLegacyNotesStayAnonymous() {
        val body = "x".repeat(10000)
        repository.save("new", 1, NoteKind.CURRENT_NOTES, "Długa notatka", withImportantText(body, true), 1L, author = "Anna")
        repository.save("legacy", 1, NoteKind.CURRENT_NOTES, "Stary wpis", "Treść", 2L)
        repository.close(); repository = WorkNotesRepository(context)
        val initial = repository.load().single { it.id == "new" }
        assertEquals(body, initial.visibleBody); assertEquals("Anna", initial.author); assertTrue(initial.important)
        repository.save("new", 1, NoteKind.CURRENT_NOTES, "Zmieniona", withImportantText("Nowa treść", false), 3L, author = "Piotr")
        repository.save("legacy", 1, NoteKind.CURRENT_NOTES, "Stary wpis", "Poprawiona", 4L, author = "Piotr")
        assertEquals("Anna", repository.load().single { it.id == "new" }.author)
        assertNull(repository.load().single { it.id == "legacy" }.author)
        assertEquals(1L, repository.load().single { it.id == "new" }.createdAt)
    }
}

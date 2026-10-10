package pl.apargb.milkyway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NoteRemindersTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T : android.database.sqlite.SQLiteOpenHelper, R> T.withDatabase(block: (T) -> R): R =
        try { block(this) } finally { close() }

    @Test fun markersDoNotLeakIntoBodyAndAuthorImportantAndReminderSurviveEdits() {
        val reminder = NoteReminder(500L, readBy = setOf("02", "01"))
        val text = withNoteReminder(withNoteAuthor(withImportantText("Treść\nDruga linia", true), "Anna"), reminder)
        assertEquals("Treść\nDruga linia", noteBody(text)); assertEquals("Anna", noteAuthor(text)); assertEquals(reminder, noteReminder(text))
        val edited = withImportantText(text, false)
        assertEquals(reminder, noteReminder(edited)); assertFalse(isImportantText(edited)); assertEquals("Anna", noteAuthor(edited))
        assertTrue(reminder.due("03", 500L)); assertFalse(reminder.due("02", 500L)); assertFalse(reminder.due("03", 499L))
        assertFalse(reminder.copy(done = true).due("03", 600L))
    }

    @Test fun staleEditorCannotUndoReadAcknowledgmentsAndReschedulingRejectsOldPopup() {
        WorkNotesDatabase(context, null).withDatabase { db ->
            val repo = WorkNotesRepository(context, db)
            repo.save("n", 1, NoteKind.CURRENT_NOTES, "Pomiar", withNoteReminder("Opis", NoteReminder(500L)), 1L, "Anna")
            val stale = repo.load().single()
            repo.actReminder(stale, stale.reminder!!.token, ReminderAction.READ, "01", 501L)
            repo.actReminder(stale, stale.reminder!!.token, ReminderAction.READ, "02", 502L)
            repo.save("n", 1, NoteKind.CURRENT_NOTES, "Poprawiony", stale.body, 503L, "Piotr")
            val edited = repo.load().single()
            assertEquals(setOf("01", "02"), edited.reminder!!.readBy); assertEquals("Anna", edited.author)
            val next = NoteReminder(800L)
            repo.scheduleReminder(stale, next, 504L)
            assertThrows(IllegalArgumentException::class.java) { repo.actReminder(stale, stale.reminder!!.token, ReminderAction.DONE, "01", 505L) }
            assertEquals(next, repo.load().single().reminder)
            assertThrows(IllegalArgumentException::class.java) { repo.scheduleReminder(stale, NoteReminder(503L), 504L) }
        }
    }

    @Test fun doneCompletesTaskAndNewDateReopensItWhileUrgentKeepsItUnread() {
        WorkNotesDatabase(context, null).withDatabase { db ->
            val repo = WorkNotesRepository(context, db)
            repo.save("n", 0, NoteKind.REMINDER, "Kontrola", withNoteReminder("Opis", NoteReminder(500L)), 1L, "Anna")
            val original = repo.load().single()
            repo.actReminder(original, original.reminder!!.token, ReminderAction.URGENT, "01", 501L)
            val urgent = repo.load().single()
            assertTrue(urgent.important); assertTrue(urgent.reminder!!.due("01", 502L)); assertEquals("Anna", urgent.author)
            repo.actReminder(original, original.reminder!!.token, ReminderAction.DONE, "01", 502L)
            assertTrue(repo.load().single().completed); assertTrue(repo.load().single().reminder!!.done)
            repo.scheduleReminder(original, NoteReminder(800L), 503L)
            assertFalse(repo.load().single().completed); assertFalse(repo.load().single().reminder!!.done)
            repo.setCompleted(repo.load().single(), true, 504L)
            assertTrue(repo.load().single().reminder!!.done)
            repo.setCompleted(repo.load().single(), false, 505L)
            assertFalse(repo.load().single().reminder!!.done)
        }
    }

    @Test fun sharedSnapshotKeepsBothOperatorsReadConfirmationsAndGlobalDone() {
        fun update(previous: Map<String, Any?>, reader: String, action: (WorkNotesRepository) -> Unit) = WorkNotesDatabase(context, null).withDatabase { db ->
            SharedRows.restore(db, SharedDomain.NOTES, previous)
            action(WorkNotesRepository(context, db))
            SharedRows.capture(db, SharedDomain.NOTES, previous, mapOf("uid" to reader, "account" to reader), 600L)
        }
        var snapshot = update(emptyMap(), "01") { it.save("n", 2, NoteKind.CURRENT_NOTES, "A", withNoteReminder("B", NoteReminder(500L)), 1L, "Anna") }
        snapshot = update(snapshot, "01") { val n = it.load().single(); it.actReminder(n, n.reminder!!.token, ReminderAction.READ, "01", 501L) }
        snapshot = update(snapshot, "02") { val n = it.load().single(); it.actReminder(n, n.reminder!!.token, ReminderAction.READ, "02", 502L) }
        update(snapshot, "03") {
            val n = it.load().single()
            assertEquals(setOf("01", "02"), n.reminder!!.readBy); assertTrue(n.reminder!!.due("03", 600L)); assertEquals("Anna", n.author)
            it.actReminder(n, n.reminder!!.token, ReminderAction.DONE, "03", 601L)
            assertFalse(workReminderItems(it.load()).single().reminder.due("04", 700L))
        }
    }

    @Test fun productReminderFollowsAllStagesKeepsAuthorAndDoesNotAttachToCodeHistory() {
        ProductionQueueDatabase(context, null).withDatabase { db ->
            val repo = ProductionQueueRepository(context, db)
            val day = LocalDate.now()
            repo.save("p", ProductionLine.POWDER, day, "Proszek", "", 1L, java.math.BigDecimal("100"), true)
            val entry = repo.load().single()
            repo.addProductNote("n", entry, ProductNoteStage.ORDER, withNoteReminder("Sprawdź opakowanie", NoteReminder(500L)), 2L, "Anna")
            val note = repo.snapshot().productNotes.single()
            repo.schedule(entry, day, java.time.LocalTime.NOON, 3L)
            repo.recordProduction("receipt", entry.id, entry.line, day, null, 4L)
            assertTrue(repo.load().single().completed)
            assertEquals(ProductNoteStage.ORDER, repo.snapshot().productNotes.single { it.id == "n" }.stage)
            val items = productReminderItems(repo.load(), repo.snapshot().productNotes)
            assertEquals(1, items.size); assertEquals("Anna", items.single().author)
            repo.actProductReminder(note, note.reminder!!.token, ReminderAction.READ, "01")
            repo.setProductNoteImportant(note, true)
            assertEquals(setOf("01"), repo.snapshot().productNotes.single { it.id == "n" }.reminder!!.readBy)
            val newer = NoteReminder(800L)
            repo.scheduleProductReminder(note, newer, 501L)
            assertThrows(IllegalArgumentException::class.java) { repo.actProductReminder(note, note.reminder!!.token, ReminderAction.DONE, "01") }
            val code = repo.snapshot().productNotes.first { it.isProductionCode() }
            assertThrows(IllegalArgumentException::class.java) { repo.scheduleProductReminder(code, NoteReminder(800L), 501L) }
            assertEquals(newer, repo.snapshot().productNotes.single { it.id == "n" }.reminder)
        }
    }

    @Test fun invalidPastClockAndDstGapAreRejectedAndYearBoundaryUsesDeviceZone() {
        val zone = ZoneId.of("Europe/Warsaw")
        assertThrows(IllegalArgumentException::class.java) { reminderMoment(LocalDate.of(2026, 3, 29), "02:30", zone, 0L) }
        assertThrows(IllegalArgumentException::class.java) { reminderMoment(LocalDate.of(2026, 10, 10), "25:00", zone, 0L) }
        val at = reminderMoment(LocalDate.of(2027, 1, 1), "00:15", zone, 0L)
        assertEquals("2026-12-31T23:15:00Z", java.time.Instant.ofEpochMilli(at).toString())
        assertThrows(IllegalArgumentException::class.java) { reminderMoment(LocalDate.of(2027, 1, 1), "00:15", zone, at) }
    }
}

package pl.apargb.milkyway

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSchedulerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private fun item(id: String = "n", at: Long = System.currentTimeMillis() + 3600000) =
        ReminderItem("notes", id, "Kontrola", "Sprawdź pompę", "Anna", false, NoteReminder(at), "Zmiana 1")

    @Before fun prepare() {
        ReminderScheduler.clear(context); ReminderScheduler.foreground = false
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @After fun clear() { ReminderScheduler.clear(context); ReminderScheduler.foreground = false }

    @Test fun futureAlarmsAreExactUniqueAndDoNotDuplicateAfterSynchronization() {
        val first = item(); val second = item("other")
        ReminderScheduler.sync(context, "01", listOf(first, second))
        assertEquals(2, alarms.scheduledAlarms.size)
        assertTrue(alarms.scheduledAlarms.all { it.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT && it.isAllowWhileIdle })
        assertEquals(setOf(first.reminder.at, second.reminder.at), alarms.scheduledAlarms.map { it.triggerAtMs }.toSet())
        ReminderScheduler.sync(context, "01", listOf(first.copy(text = "Zmieniona treść"), second))
        assertEquals(2, alarms.scheduledAlarms.size); assertEquals("Zmieniona treść", ReminderScheduler.cached(context).first().text)
        ReminderScheduler.deliver(context, first.key)
        assertEquals(0, notifications.size())
    }

    @Test fun disabledExactAccessFallsBackAndBootRestoresSavedSchedule() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val note = item()
        ReminderScheduler.sync(context, "01", listOf(note))
        assertTrue(alarms.scheduledAlarms.single().windowLengthMs != ShadowAlarmManager.WINDOW_EXACT)
        context.getSystemService(AlarmManager::class.java).cancelAll()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        NoteReminderReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(note.reminder.at, alarms.scheduledAlarms.single().triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarms.scheduledAlarms.single().windowLengthMs)
    }

    @Test fun backgroundNotificationUsesLatestTextOpensHomeAndReadCancelsAlarmAndNotification() {
        val note = item(at = System.currentTimeMillis() - 1000)
        ReminderScheduler.sync(context, "01", listOf(note))
        ReminderScheduler.foreground = true
        ReminderScheduler.deliver(context, note.key)
        assertEquals(0, notifications.size())
        ReminderScheduler.foreground = false
        ReminderScheduler.deliver(context, note.key)
        val notification = notifications.getNotification(note.key, 0)
        assertNotNull(notification)
        assertTrue(shadowOf(notification.contentIntent).savedIntent.getBooleanExtra(ReminderScheduler.OPEN_HOME, false))
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("Sprawdź pompę"))
        ReminderScheduler.sync(context, "01", listOf(note.copy(reminder = note.reminder.copy(readBy = setOf("01")))))
        assertEquals(0, notifications.size()); assertTrue(alarms.scheduledAlarms.isEmpty())
        ReminderScheduler.deliver(context, note.key)
        assertEquals(0, notifications.size())
        ReminderScheduler.sync(context, "02", listOf(note.copy(reminder = note.reminder.copy(readBy = setOf("01")))))
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun rescheduleDeleteDoneAndLogoutSuppressOldBroadcastsWithoutDiscardingOtherNotes() {
        val old = item(at = System.currentTimeMillis() - 1000); val other = item("other")
        ReminderScheduler.sync(context, "01", listOf(old, other))
        val next = old.copy(reminder = NoteReminder(System.currentTimeMillis() + 7200000))
        ReminderScheduler.sync(context, "01", listOf(next, other))
        ReminderScheduler.deliver(context, old.key)
        assertEquals(0, notifications.size()); assertEquals(2, alarms.scheduledAlarms.size)
        ReminderScheduler.sync(context, "01", listOf(next.copy(reminder = next.reminder.copy(done = true)), other))
        assertEquals(listOf(other), ReminderScheduler.cached(context))
        ReminderScheduler.sync(context, "01", emptyList())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        ReminderScheduler.sync(context, "01", listOf(old))
        ReminderScheduler.clear(context)
        ReminderScheduler.deliver(context, old.key)
        assertEquals(0, notifications.size()); assertTrue(ReminderScheduler.cached(context).isEmpty())
    }

    @Test fun deniedNotificationsKeepReminderForTheHomePopup() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val note = item(at = System.currentTimeMillis() - 1000)
        ReminderScheduler.sync(context, "01", listOf(note)); ReminderScheduler.deliver(context, note.key)
        assertEquals(0, notifications.size()); assertEquals(note.key, ReminderScheduler.cached(context).single().key)
    }

    @Test fun independentDomainReloadDoesNotCancelOtherAlarmsAndAccountChangeRemovesOldCache() {
        val work = item(); val product = item("product").copy(domain = "production")
        ReminderScheduler.syncDomain(context, "01", "notes", listOf(work))
        ReminderScheduler.syncDomain(context, "01", "production", listOf(product))
        assertEquals(2, alarms.scheduledAlarms.size)
        ReminderScheduler.syncDomain(context, "01", "notes", emptyList())
        assertEquals(listOf(product), ReminderScheduler.cached(context)); assertEquals(1, alarms.scheduledAlarms.size)
        ReminderScheduler.syncDomain(context, "02", "notes", listOf(work))
        assertEquals(listOf(work), ReminderScheduler.cached(context)); assertEquals(1, alarms.scheduledAlarms.size)
    }
}

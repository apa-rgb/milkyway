package pl.apargb.milkyway

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Private device cache: reminders are shared, delivery and read confirmation belong to an operator. */
internal object ReminderScheduler {
    const val OPEN_HOME = "reminder-open-home"
    private const val CHANNEL = "note-reminders"
    private const val ALARM = "pl.apargb.milkyway.NOTE_REMINDER"
    private fun preferences(context: Context) = context.getSharedPreferences("note-reminders", Context.MODE_PRIVATE)
    var foreground = false

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun exactAllowed(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized fun syncDomain(context: Context, reader: String, domain: String, items: List<ReminderItem>) {
        val retained = if (preferences(context).getString("reader", null) == reader) cached(context).filter { it.domain != domain } else emptyList()
        sync(context, reader, retained + items.filter { it.domain == domain })
    }

    @Synchronized fun sync(context: Context, reader: String, items: List<ReminderItem>, now: Long = System.currentTimeMillis()) {
        require(reader in reminderReaders)
        val prefs = preferences(context)
        val old = cached(context)
        val next = items.filter { !it.reminder.done && reader !in it.reminder.readBy }
        val sameReader = prefs.getString("reader", null) == reader
        old.filter { previous -> !sameReader || next.none { it.key == previous.key } }.forEach { cancel(context, it) }
        prefs.edit().putString("reader", reader).putString("items", JSONArray().apply {
            next.forEach { put(JSONObject().apply {
                put("domain", it.domain); put("id", it.noteId); put("title", it.title); put("text", it.text)
                put("author", it.author ?: ""); put("important", it.important); put("caption", it.caption)
                put("at", it.reminder.at); put("token", it.reminder.token)
            }) }
        }.toString()).apply()
        next.filter { item -> !sameReader || old.none { it.key == item.key } }.forEach { schedule(context, it, now) }
    }

    internal fun cached(context: Context): List<ReminderItem> = runCatching {
        val array = JSONArray(preferences(context).getString("items", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            ReminderItem(it.getString("domain"), it.getString("id"), it.getString("title"), it.getString("text"),
                it.optString("author").takeIf(String::isNotBlank), it.optBoolean("important"),
                NoteReminder(it.getLong("at"), it.getString("token")), it.getString("caption"))
        } }
    }.getOrDefault(emptyList())

    private fun pending(context: Context, item: ReminderItem): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, NoteReminderReceiver::class.java).setAction(ALARM).setData(Uri.Builder().scheme("milkyway")
            .authority("reminder").appendPath(item.domain).appendPath(item.noteId).appendPath(item.reminder.token).build())
            .putExtra("key", item.key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(context: Context, item: ReminderItem, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = maxOf(item.reminder.at, now + 1000)
        val intent = pending(context, item)
        // Special access may be revoked between checking and scheduling.
        try {
            if (exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent) }
    }

    private fun cancel(context: Context, item: ReminderItem) {
        val intent = pending(context, item)
        context.getSystemService(AlarmManager::class.java).cancel(intent)
        intent.cancel()
        context.getSystemService(NotificationManager::class.java).cancel(item.key, 0)
    }

    @Synchronized fun clear(context: Context) {
        cached(context).forEach { cancel(context, it) }
        preferences(context).edit().clear().apply()
    }

    @Synchronized fun restore(context: Context, now: Long = System.currentTimeMillis()) {
        cached(context).forEach { schedule(context, it, now) }
    }

    @Synchronized fun deliver(context: Context, key: String, now: Long = System.currentTimeMillis()) {
        val item = cached(context).firstOrNull { it.key == key && it.reminder.at <= now } ?: return
        if (foreground || !notificationsAllowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Przypomnienia notatek", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("milkyway://open-reminder/${Uri.encode(item.key)}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(OPEN_HOME, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = "${item.caption}\n${item.author?.let { "Autor: $it\n" }.orEmpty()}${item.text}"
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_note_reminder)
            .setContentTitle(if (item.important) "Pilne: ${item.title.ifBlank { "Przypomnienie" }}" else item.title.ifBlank { "Przypomnienie" }).setContentText(item.text)
            .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER).build()
        try { manager.notify(item.key, 0, notification) } catch (_: SecurityException) { /* Permission changed. Home still shows the note. */ }
    }

    fun deliverDue(context: Context) { cached(context).forEach { deliver(context, it.key) } }
}

class NoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "pl.apargb.milkyway.NOTE_REMINDER") {
            intent.getStringExtra("key")?.let { ReminderScheduler.deliver(context, it) }
        } else ReminderScheduler.restore(context)
    }
}

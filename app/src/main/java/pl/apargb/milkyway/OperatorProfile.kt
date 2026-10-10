package pl.apargb.milkyway

import android.content.Context

internal fun normalizeOperatorName(name: String): String = name
    .replace(Regex("[\\p{Cc}\\[\\]]"), " ").trim().replace(Regex("\\s+"), " ").take(60).trim()

/** Device-local name, bound to the verified user rather than a previous login attempt. */
internal class OperatorProfile(context: Context) {
    private val preferences = context.getSharedPreferences("operator_names", Context.MODE_PRIVATE)
    fun name(uid: String): String = preferences.getString(uid, "").orEmpty()
    fun save(uid: String, name: String) { preferences.edit().putString(uid, normalizeOperatorName(name)).apply() }
}

internal val CloudSessionState.noteAuthor: String?
    get() = if (uid == null) null else displayName?.takeIf { it.isNotBlank() } ?: number?.let { "Konto $it" }

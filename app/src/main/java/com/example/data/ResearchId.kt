package com.example.data

import android.content.Context
import androidx.core.content.edit

/**
 * A pseudonymous research id, e.g. "YD-K7PM-3QXA": random, made on first use, never derived from the
 * person, the phone or an account. It lets the analytics exports of several pilot participants be pooled
 * and told apart without anyone's name in the files, and it stays the same across exports so two exports
 * from one person are recognisably one person.
 *
 * Kept in the regular settings file, so the JSON backup and a device transfer carry it: a participant who
 * restores onto a new phone mid-pilot is still one participant. "Delete all data" clears it with the rest.
 */
object ResearchId {
    const val PREF_KEY = "research_participant_id"

    // Crockford-style: no 0/O, 1/I/L, U — nothing that reads ambiguously aloud or in a chat.
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val FORMAT = Regex("YD-[$ALPHABET]{4}-[$ALPHABET]{4}")

    fun isWellFormed(id: String?): Boolean = id != null && FORMAT.matches(id)

    fun generate(random: java.util.Random = java.security.SecureRandom()): String {
        fun block() = (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "YD-${block()}-${block()}"
    }

    /** The id, created on first call. A malformed stored value (hand-edited prefs) is replaced. */
    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        prefs.getString(PREF_KEY, null)?.takeIf { isWellFormed(it) }?.let { return it }
        val fresh = generate()
        // apply() is enough: SharedPreferences serves the new value from memory at once, so the export that
        // asked for the id and every later read agree even before the write reaches disk.
        prefs.edit { putString(PREF_KEY, fresh) }
        return fresh
    }
}

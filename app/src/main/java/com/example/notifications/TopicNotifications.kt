package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.data.local.entity.StudyUnitEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One notification per topic of today's share (the owner's decision, 2026-10-09), beside the reminder itself.
 *
 * The reminder ([NotificationScheduler.showReviewNotification]) stays one notification with its actions (Review now,
 * snooze, Not today; in alarm mode the ringer), and it is the only one that makes a sound or vibrates: once per chosen
 * reminder time. Under it a group lists the share's topics, at most [MAX_TOPICS], each its own notification. A tap
 * opens that topic to rate; once it is rated its notification goes and the next topic of the share takes its place,
 * silently. Swiping one away hides it until the next chosen reminder time ([hide]): not a review, not a deferral.
 *
 * All of them are silent: their channel ([CHANNEL_ID]) has no sound or vibration, and every post is marked silent.
 * Turning that channel off in the phone's settings turns them off and keeps the reminder.
 *
 * Android keeps about 50 notifications per app, and it limits how fast one is UPDATED (about five a second; a new one
 * is not limited that way). So a sync posts only the topics not yet showing and takes away the ones that left the
 * share; it never re-posts the ones already there.
 */
object TopicNotifications {
    const val CHANNEL_ID = "yadora_topics_v1"
    const val GROUP_KEY = "com.yadora.app.TODAY_SHARE"
    /** The group's summary. [NotificationScheduler.NOTIFICATION_ID] (1) is the reminder. */
    const val SUMMARY_ID = 2
    /** Every topic notification has this id and its own tag ([tagOf]). */
    const val TOPIC_ID = 3
    const val MAX_TOPICS = 40

    /** Swiping a topic notification away (its delete intent). */
    const val ACTION_HIDE = "com.example.notifications.ACTION_TOPIC_HIDDEN"
    const val EXTRA_UNIT_ID = "com.example.notifications.EXTRA_UNIT_ID"

    private const val TAG_PREFIX = "topic:"
    /** Device-local, in the transient prefs: which topics the learner put away until the next chosen reminder time. */
    private const val PREF_HIDDEN = "topic_notifications_hidden"
    private const val REQ_OPEN_TOPIC = 1101
    private const val REQ_HIDE_TOPIC = 1102
    private const val REQ_OPEN_TODAY = 1103

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun tagOf(unitId: Long) = "$TAG_PREFIX$unitId"

    fun unitIdOf(tag: String?): Long? = tag?.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.toLongOrNull()

    /** What a sync does: the topics to post, in the share's order, and the ones to take away. */
    data class Diff(val post: List<Long>, val cancel: Set<Long>)

    /**
     * The topics that should be showing are the share, in its order, without the ones the learner put away, at most
     * [max]. [showing] are the ones already up. Pure, so the rule is tested on its own.
     */
    fun diff(share: List<Long>, showing: Set<Long>, hidden: Set<Long>, max: Int = MAX_TOPICS): Diff {
        val wanted = share.asSequence().filter { it !in hidden }.distinct().take(max).toList()
        val wantedSet = wanted.toHashSet()
        return Diff(post = wanted.filter { it !in showing }, cancel = showing.filterTo(HashSet()) { it !in wantedSet })
    }

    fun createChannel(context: Context) {
        val name = when (language(context)) { "fa" -> "مباحث امروز"; "de" -> "Heutige Themen"; else -> "Today's topics" }
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = when (language(context)) {
                "fa" -> "هر مبحثِ سهم امروز یک اعلانِ بی‌صدا"
                "de" -> "Eine stille Benachrichtigung je Thema des heutigen Anteils"
                else -> "One silent notification for each topic of today's share"
            }
            // Silent by design: the reminder is the one notification that sounds, once per chosen reminder time.
            setSound(null, null)
            enableVibration(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    /** The topics whose notification is up now. */
    fun showingIds(context: Context): Set<Long> = active(context)
        .filter { it.id == TOPIC_ID }.mapNotNullTo(HashSet()) { unitIdOf(it.tag) }

    /** True when any of this group (a topic or its summary) is up. */
    fun anyShowing(context: Context): Boolean = active(context).any { (it.id == TOPIC_ID && unitIdOf(it.tag) != null) || (it.id == SUMMARY_ID && it.tag == null) }

    private fun active(context: Context): List<android.service.notification.StatusBarNotification> = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.toList()
    }.getOrDefault(emptyList())

    fun hidden(context: Context): Set<Long> =
        NotificationScheduler.transientPrefs(context).getStringSet(PREF_HIDDEN, null).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    /** The learner swiped this topic's notification away: it stays away until the next chosen reminder time. */
    fun hide(context: Context, unitId: Long) {
        val now = hidden(context) + unitId
        NotificationScheduler.transientPrefs(context).edit { putStringSet(PREF_HIDDEN, now.mapTo(HashSet()) { it.toString() }) }
    }

    fun clearHidden(context: Context) {
        NotificationScheduler.transientPrefs(context).edit { remove(PREF_HIDDEN) }
    }

    /**
     * A chosen reminder time (the set time, the second slot, a snooze ending, the boot catch-up, the safety net, a test):
     * every topic of today's share shows again, the ones put away included. Returns how many topic notifications are up.
     */
    suspend fun postAll(context: Context): Int {
        clearHidden(context)
        return update(context)
    }

    /**
     * After anything that can change today's share (a rating, an Undo, Not today, an edit, a restore), and at the ~3-hour
     * repeats: keeps what is showing in step with the share, silently. When nothing of the group is up (no reminder yet
     * today, or the learner put the whole list away), it posts nothing.
     */
    suspend fun sync(context: Context): Int = if (!anyShowing(context)) 0 else update(context)

    /**
     * [sync] and the reminder's own count ([NotificationScheduler.refreshIfShowing]) from any thread: a cheap check here,
     * the database work in the background, only when something is up.
     */
    fun syncSoon(context: Context) {
        val app = context.applicationContext
        if (!anyShowing(app) && !NotificationScheduler.reminderShowing(app)) return
        scope.launch {
            runCatching { sync(app) }
            runCatching { NotificationScheduler.refreshIfShowing(app) }
        }
    }

    /**
     * The interface language changed. The topic notifications keep the words they were posted with (re-posting forty at
     * once is throttled by Android), so they go, and come back in the new language at the next reminder time. Only the
     * reminder itself is re-worded now, if it is up. Nothing re-posts the group here: a sync right after the cancels
     * would race them and could leave half a list.
     */
    fun languageChanged(context: Context) {
        cancelAll(context)
        refreshReminderSoon(context)
    }

    /** The reminder's count and words kept true in the background, when it is up; the topic group is left alone. */
    fun refreshReminderSoon(context: Context) {
        val app = context.applicationContext
        if (!NotificationScheduler.reminderShowing(app)) return
        scope.launch { runCatching { NotificationScheduler.refreshIfShowing(app) } }
    }

    /** Takes the whole group away (a snooze, "Delete all data", reminders turned off). */
    fun cancelAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        showingIds(context).forEach { nm.cancel(tagOf(it), TOPIC_ID) }
        nm.cancel(SUMMARY_ID)
    }

    @SuppressLint("MissingPermission")
    private suspend fun update(context: Context): Int {
        val app = context.applicationContext as? com.example.MedReviewApplication ?: return 0
        if (!canPost(app)) return 0
        createChannel(app)
        val nm = NotificationManagerCompat.from(app)
        val share = app.todayPlan().queue
        val showing = showingIds(app)
        val d = diff(share.map { it.id }, showing, hidden(app))
        d.cancel.forEach { nm.cancel(tagOf(it), TOPIC_ID) }
        val up = (showing - d.cancel).size + d.post.size
        if (up == 0) {
            nm.cancel(SUMMARY_ID)
            return 0
        }
        val subjects = runCatching { app.database.categoryDao().getAllSubjectsOnce().associate { it.id to it.name } }.getOrDefault(emptyMap())
        val rank = share.withIndex().associate { (i, u) -> u.id to i }
        val byId = share.associateBy { it.id }
        // The summary first, so the topics land in their group at once.
        nm.notify(SUMMARY_ID, summary(app, share.size))
        for (id in d.post) {
            val unit = byId[id] ?: continue
            nm.notify(tagOf(id), TOPIC_ID, topic(app, unit, unit.subjectId?.let { subjects[it] }, rank.getValue(id)))
        }
        return up
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun language(context: Context): String =
        context.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE).getString("app_language", "en") ?: "en"

    private fun digits(context: Context, n: Int): String =
        if (language(context) == "fa") com.example.ui.i18n.PersianDate.faDigits(n) else n.toString()

    /** The group's own line: what the share holds, and a tap opens Today. */
    private fun summary(context: Context, shareSize: Int): Notification {
        val lang = language(context)
        val title = when (lang) { "fa" -> "سهم امروز"; "de" -> "Heutiger Anteil"; else -> "Today's share" }
        val text = when (lang) {
            "fa" -> "${digits(context, shareSize)} مبحث"
            "de" -> if (shareSize == 1) "1 Thema" else "$shareSize Themen"
            else -> if (shareSize == 1) "1 topic" else "$shareSize topics"
        }
        val open = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_review", true)
            putExtra(com.example.MainActivity.EXTRA_OPENED_FROM, "topics")
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_notification)
            .setColor(0xFF4E7A5A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(context, REQ_OPEN_TODAY, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, title))
            .build()
    }

    /** One topic: its title, its subject, and whether it waits for its first rating. A tap opens it to rate. */
    private fun topic(context: Context, unit: StudyUnitEntity, subject: String?, rank: Int): Notification {
        val lang = language(context)
        val kind = if (unit.reviewCount == 0) {
            when (lang) { "fa" -> "ثبت اولین ارزیابی"; "de" -> "Erste Bewertung"; else -> "First rating" }
        } else {
            when (lang) { "fa" -> "مرور"; "de" -> "Wiederholung"; else -> "Review" }
        }
        val open = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // The data makes each topic its own PendingIntent; the extras say which topic.
            data = "yadora://topic/${unit.id}".toUri()
            putExtra(com.example.MainActivity.EXTRA_OPEN_TOPIC, unit.id)
            putExtra(com.example.MainActivity.EXTRA_OPENED_FROM, "topic")
        }
        val hide = Intent(context, ReviewReminderReceiver::class.java).apply {
            action = ACTION_HIDE
            data = "yadora://topic/${unit.id}".toUri()
            putExtra(EXTRA_UNIT_ID, unit.id)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_notification)
            .setColor(0xFF4E7A5A.toInt())
            .setContentTitle(unit.title)
            .setContentText(listOfNotNull(subject?.takeIf { it.isNotBlank() }, kind).joinToString(" · "))
            .setGroup(GROUP_KEY)
            // The share's own order: first ratings, then the most urgent reviews.
            .setSortKey("%04d".format(java.util.Locale.ROOT, rank))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            // It stays until the topic is rated (or swiped away): a tap that does not end in a rating leaves it up.
            .setAutoCancel(false)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(PendingIntent.getActivity(context, REQ_OPEN_TOPIC, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(PendingIntent.getBroadcast(context, REQ_HIDE_TOPIC, hide, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            // What the learner studies is their business: the lock screen shows no title.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, when (lang) { "fa" -> "یک مبحث برای مرور"; "de" -> "Ein Thema zur Wiederholung"; else -> "A topic to review" }))
            .build()
    }

    private fun publicVersion(context: Context, title: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_notification)
            .setColor(0xFF4E7A5A.toInt())
            .setContentTitle(title)
            .setGroup(GROUP_KEY)
            .setSilent(true)
            .build()
}

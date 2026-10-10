package com.example.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * One silent notification per topic of today's share (the owner's decision, 2026-10-09): at most twenty (forty until
 * One UI's quota of 25 notifications per app dropped the rest, 2026-10-10), in the share's
 * order; a tap opens that topic; once it is rated the next topic of the share takes its place; a swiped one stays away
 * until the next chosen reminder time; and a ~3-hour repeat of the alarm chain makes no sound and posts nothing new.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TopicNotificationsTest {

    private val day = 86_400_000L
    private val app get() = ApplicationProvider.getApplicationContext<MedReviewApplication>()
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** [n] reviews overdue by different amounts, all in today's share at the default limit of 50. */
    private suspend fun seedShare(n: Int): List<Long> {
        val now = System.currentTimeMillis()
        return (1..n).map { i ->
            app.repository.insertUnit(
                StudyUnitEntity(
                    title = "Topic $i", studyType = "Topic", stability = 3.0, difficulty = 5.0, retrievability = 0.9,
                    state = "Building", studiedAt = now - 30 * day, lastReviewedAt = now - (4 + i % 7) * day,
                    nextReviewAt = now - (1 + i % 5) * day, modelDueAt = now - (1 + i % 5) * day, currentIntervalDays = 3.0,
                    reviewCount = 2, memoryModel = "FSRS-6",
                )
            )
        }
    }

    private fun topicsUp(): List<StatusBarNotification> = nm.activeNotifications.filter { it.id == TopicNotifications.TOPIC_ID }

    private fun idsUp(): Set<Long> = topicsUp().mapNotNullTo(HashSet()) { TopicNotifications.unitIdOf(it.tag) }

    private suspend fun rate(id: Long) {
        app.repository.rateUnit(id, System.currentTimeMillis(), MemoryRating.Good, UnderstandingRating.Clear,
            sessionKind = SessionKind.PLAN, reviewDurationMs = 1000)!!
    }

    /** The receiver works on its own thread: wait for it, a few seconds at most. */
    private suspend fun events(type: String, count: Int): List<com.example.data.local.entity.EventLogEntity> {
        repeat(100) {
            val found = app.database.eventLogDao().getAll().filter { it.type == type }
            if (found.size >= count) return found
            Thread.sleep(50)
        }
        return app.database.eventLogDao().getAll().filter { it.type == type }
    }

    @Test
    fun `the topics to show are the share in its order without the hidden ones, twenty at most`() {
        assertEquals("the reminder, the summary, the topics and an update in flight stay under One UI's 25",
            20, TopicNotifications.MAX_TOPICS)
        val d = TopicNotifications.diff((1L..45L).toList(), showing = setOf(1L, 2L, 99L), hidden = setOf(3L))
        assertEquals("3 left out, twenty in all, the two already up not posted again", (4L..21L).toList(), d.post)
        assertEquals("what left the share goes", setOf(99L), d.cancel)
        assertEquals(TopicNotifications.Diff(emptyList(), setOf(5L)), TopicNotifications.diff(emptyList(), setOf(5L), emptySet()))
    }

    @Test
    fun `a post the system dropped is sent again only while the share still wants it`() {
        // Posted 1..6; 1 and 2 came up, 3 to 6 did not. Meanwhile 4 was rated (it left the share) and 5 was swiped away.
        val again = TopicNotifications.repostTargets(tried = (1L..6L).toList(), share = listOf(1L, 2L, 3L, 5L, 6L, 7L),
            showing = setOf(1L, 2L), hidden = setOf(5L))
        assertEquals("3 and 6 again; 7 joined the share later and is another sync's to post", listOf(3L, 6L), again)
        assertEquals("nothing when everything came up", emptyList<Long>(),
            TopicNotifications.repostTargets(listOf(1L, 2L), listOf(1L, 2L), setOf(1L, 2L), emptySet()))
        assertEquals("never past the cap", (1L..20L).toList(),
            TopicNotifications.repostTargets((1L..30L).toList(), (1L..30L).toList(), emptySet(), emptySet()))
    }

    @Test
    fun `a chosen reminder time is the set time, the second slot, a snooze or a test, and a repeat is not`() {
        val zone = ZoneId.of("Asia/Tehran")
        fun at(h: Int, m: Int, s: Int = 0) = LocalDate.of(2026, 10, 9).atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()
        assertTrue(NotificationScheduler.isChosenSlot("primary", at(20, 0), 20, 0, zone))
        assertFalse("a repeat, armed three hours after a fire", NotificationScheduler.isChosenSlot("primary", at(13, 0, 7) + 123, 20, 0, zone))
        assertFalse(NotificationScheduler.isChosenSlot("primary", at(19, 0), 20, 0, zone))
        assertTrue(NotificationScheduler.isChosenSlot("secondary", at(10, 0), 20, 0, zone))
        assertTrue(NotificationScheduler.isChosenSlot("snooze", at(18, 0), 20, 0, zone))
        assertTrue(NotificationScheduler.isChosenSlot("test", at(9, 13, 2), 20, 0, zone))
        assertTrue("armed before the time was recorded", NotificationScheduler.isChosenSlot("primary", 0L, 20, 0, zone))
    }

    @Test
    fun `a chosen time posts the first twenty of the share, silently, in its order, each opening its topic`() = runBlocking {
        seedShare(45)
        val share = app.todayPlan().queue.map { it.id }
        assertEquals("the default limit holds all of them", 45, share.size)
        assertEquals(20, TopicNotifications.postAll(app))
        val up = topicsUp()
        assertEquals("the first twenty of the share", share.take(20).toSet(), idsUp())
        val summary = nm.activeNotifications.single { it.id == TopicNotifications.SUMMARY_ID }
        assertTrue("one group, with its own summary", summary.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        for (sbn in up) {
            val n = sbn.notification
            assertEquals(TopicNotifications.CHANNEL_ID, n.channelId)
            assertEquals(TopicNotifications.GROUP_KEY, n.group)
            assertEquals("never alerts", Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
            assertEquals("no title on the lock screen", Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals("it stays until the topic is rated", 0, n.flags and Notification.FLAG_AUTO_CANCEL)
            assertNotNull("a swipe is noticed", n.deleteIntent)
        }
        assertEquals("the summary never alerts either", Notification.GROUP_ALERT_CHILDREN, summary.notification.groupAlertBehavior)
        val channel = nm.getNotificationChannel(TopicNotifications.CHANNEL_ID)
        assertNull("the channel has no sound", channel.sound)
        assertFalse("and no vibration", channel.shouldVibrate())
        val keys = share.take(20).map { id -> up.single { TopicNotifications.unitIdOf(it.tag) == id }.notification.sortKey }
        assertEquals("in the share's order", keys.sorted(), keys)

        val tap = shadowOf(up.single { TopicNotifications.unitIdOf(it.tag) == share[0] }.notification.contentIntent).savedIntent
        assertEquals("a tap opens that topic", share[0], tap.getLongExtra(MainActivity.EXTRA_OPEN_TOPIC, -1L))
        assertEquals("topic", tap.getStringExtra(MainActivity.EXTRA_OPENED_FROM))
    }

    @Test
    fun `a rated topic goes and the next one of the share comes in`() = runBlocking {
        seedShare(45)
        TopicNotifications.postAll(app)
        val rated = app.todayPlan().queue.first().id
        rate(rated)
        assertEquals(20, TopicNotifications.sync(app))
        assertFalse(rated in idsUp())
        assertEquals("the share's first twenty as it is now", app.todayPlan().queue.map { it.id }.take(20).toSet(), idsUp())
    }

    @Test
    fun `a swiped topic stays away until the next chosen reminder time`() = runBlocking {
        seedShare(5)
        TopicNotifications.postAll(app)
        val swiped = app.todayPlan().queue[1].id
        // The learner swipes it: the system takes it away and sends its delete intent.
        val sbn = topicsUp().single { TopicNotifications.unitIdOf(it.tag) == swiped }
        nm.cancel(sbn.tag, sbn.id)
        app.sendBroadcast(shadowOf(sbn.notification.deleteIntent).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("logged, not a review and not a deferral", swiped, events("TOPIC_NOTIFICATION_HIDDEN", 1).single().unitId)
        assertTrue(swiped in TopicNotifications.hidden(app))
        assertEquals(0, app.database.reviewLogDao().getLogsForUnitOnce(swiped).size)
        assertNull(app.repository.getUnitById(swiped)!!.deferredUntil)

        TopicNotifications.sync(app)
        assertEquals("a sync leaves it away", 4, idsUp().size)
        assertFalse(swiped in idsUp())

        TopicNotifications.postAll(app)
        assertTrue("the next chosen time brings it back", swiped in idsUp())
        assertTrue(TopicNotifications.hidden(app).isEmpty())
    }

    @Test
    fun `with nothing up a change posts nothing, and an empty share takes the group away`() = runBlocking {
        val ids = seedShare(2)
        assertEquals(0, TopicNotifications.sync(app))
        assertTrue("before today's reminder, a rating posts nothing", nm.activeNotifications.isEmpty())

        TopicNotifications.postAll(app)
        ids.forEach { rate(it) }
        TopicNotifications.sync(app)
        assertTrue("the share is done", nm.activeNotifications.none { it.id == TopicNotifications.SUMMARY_ID || it.id == TopicNotifications.TOPIC_ID })
    }

    @Test
    fun `a restore takes the topic notifications away, since their ids may name other topics now`() = runBlocking {
        // First ratings waiting: a backup of rated topics without their history is refused, rightly.
        val now = System.currentTimeMillis()
        repeat(3) { i ->
            app.repository.insertUnit(
                StudyUnitEntity(title = "New $i", studyType = "Topic", studiedAt = now - 3_600_000L, createdAt = now - 3_600_000L,
                    nextReviewAt = now - 3_600_000L, modelDueAt = now - 3_600_000L, memoryModel = "FSRS-6")
            )
        }
        TopicNotifications.postAll(app)
        assertEquals(3, idsUp().size)
        val json = com.example.data.BackupManager.buildBackupJson(app)
        com.example.data.BackupManager.restoreFromJson(app, json)
        assertTrue("nothing of the group is left", nm.activeNotifications.none { it.id == TopicNotifications.TOPIC_ID || it.id == TopicNotifications.SUMMARY_ID })
    }

    @Test
    fun `a repeat of the alarm chain posts nothing new, and the set time posts the reminder and its topics`() = runBlocking {
        seedShare(3)
        app.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE).edit()
            .putInt("reminder_hour", 20).putInt("reminder_minute", 0).commit()
        fun fire(at: Long) = Intent(app, ReviewReminderReceiver::class.java).apply {
            action = NotificationScheduler.ACTION_FIRE
            putExtra(NotificationScheduler.EXTRA_SCHEDULED_AT, at)
            putExtra(NotificationScheduler.EXTRA_SLOT, "primary")
            putExtra(NotificationScheduler.EXTRA_EXACT, true)
        }
        app.sendBroadcast(fire(System.currentTimeMillis() - 1_234L))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events(ReminderTelemetry.EVENT, 1).single().detail!!.contains("outcome=repeat"))
        assertTrue("nothing was up, so a repeat posts nothing", nm.activeNotifications.isEmpty())

        val setTime = LocalDate.now().atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        app.sendBroadcast(fire(setTime))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events(ReminderTelemetry.EVENT, 2).last().detail!!.contains("outcome=posted"))
        assertTrue(NotificationScheduler.reminderShowing(app))
        assertEquals("and one silent notification per topic", 3, idsUp().size)
    }
}

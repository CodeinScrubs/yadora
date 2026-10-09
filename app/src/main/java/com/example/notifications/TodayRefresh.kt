package com.example.notifications

import android.content.Context

/**
 * After anything that can change today's share (a rating, an Undo, Not today, an edit, a merge, a restore, opening the
 * app): the home-screen widget's count, and whatever reminder and topic notifications are showing, kept in step
 * ([TopicNotifications.syncSoon]). Nothing is posted that is not already up.
 */
object TodayRefresh {
    /**
     * [topicsToo] false after a restore, which has already taken the topic group away (its ids may name other topics
     * now): a sync racing those cancels could leave half a list, so the group waits for the next reminder time.
     */
    fun afterChange(context: Context, topicsToo: Boolean = true) {
        com.example.widget.DueWidgetProvider.updateAll(context)
        runCatching { if (topicsToo) TopicNotifications.syncSoon(context) else TopicNotifications.refreshReminderSoon(context) }
    }
}

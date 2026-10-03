package com.example.ui.library

import com.example.data.local.entity.StudyUnitEntity
import com.example.data.text.TopicTitle

/**
 * The Library search, with each topic's texts folded ONCE ([TopicTitle.searchKey]) when the list changes, not on every
 * keystroke. The search used to fold the title, scope, key points, notes and source of every topic for each letter
 * typed: with a year of exam preparation (about 2,000 topics, many with long notes) that is megabytes of text per
 * keystroke on a phone, queued behind each other as the learner types. The matching rule is unchanged: a topic matches
 * when any of its own texts, its subject or its collection contains the query, all folded the same way.
 */
internal object LibrarySearch {

    /** One topic and the folded texts a search looks in. */
    class Entry(val unit: StudyUnitEntity, val keys: List<String>)

    fun index(units: List<StudyUnitEntity>): List<Entry> = units.map { Entry(it, keysOf(it)) }

    fun keysOf(u: StudyUnitEntity): List<String> =
        listOfNotNull(u.title, u.studyType, u.recallPrompt, u.keyPoints, u.notes, u.source)
            .map { TopicTitle.searchKey(it) }
            .filter { it.isNotEmpty() }

    /**
     * The topics matching [query], in the order given; every topic for a blank query. Subject and collection names are
     * folded per search: there are a few dozen of them, not thousands.
     */
    fun search(
        entries: List<Entry>,
        query: String,
        subjectNames: Map<Long, String>,
        systemNames: Map<Long, String>,
    ): List<StudyUnitEntity> {
        val key = TopicTitle.searchKey(query)
        if (key.isEmpty()) return entries.map { it.unit }
        val subjectHits = subjectNames.filterValues { TopicTitle.searchKey(it).contains(key) }.keys
        val systemHits = systemNames.filterValues { TopicTitle.searchKey(it).contains(key) }.keys
        return entries
            .filter { e -> e.keys.any { it.contains(key) } || e.unit.subjectId in subjectHits || e.unit.systemId in systemHits }
            .map { it.unit }
    }
}

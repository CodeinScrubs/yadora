package com.example.ui.library

import com.example.data.local.entity.StudyUnitEntity
import com.example.data.text.TopicTitle

/**
 * The Library search, with each topic's texts folded ONCE ([TopicTitle.searchKey]) when the list changes, not on every
 * keystroke. The search used to fold the title, scope, key points, notes and source of every topic for each letter
 * typed: with a year of exam preparation (about 2,000 topics, many with long notes) that is megabytes of text per
 * keystroke on a phone, queued behind each other as the learner types. A topic matches when every word of the query is
 * in one of its own texts, its subject or its collection, all folded the same way ([search]).
 */
internal object LibrarySearch {

    /** One topic and the folded texts a search looks in. */
    class Entry(val unit: StudyUnitEntity, val keys: List<String>)

    fun index(units: List<StudyUnitEntity>): List<Entry> = units.map { Entry(it, keysOf(it)) }

    /**
     * The texts the learner can see on a topic. Not its study type: that column is dormant, "Topic" on every topic the Add
     * screen saves and shown nowhere, so "opi" (opioids) or "top" matched the whole library (a production review,
     * 2026-10-10).
     */
    fun keysOf(u: StudyUnitEntity): List<String> =
        listOfNotNull(u.title, u.recallPrompt, u.keyPoints, u.notes, u.source)
            .map { TopicTitle.searchKey(it) }
            .filter { it.isNotEmpty() }

    /**
     * The topics matching [query], in the order given; every topic for a blank query. Each WORD of the query must be
     * found on its own, in any of the topic's texts, its subject or its collection (2026-10-04): "قلب نارسایی" finds
     * "نارسایی قلب", and "neuro 303" a Neurology topic titled "Topic 303". The whole query used to have to appear as one
     * run of text, so a phrase typed in another word order, or a subject plus a word of the title, found nothing. A
     * one-word query is the rule it always was, and more words only ever add matches: words found together are also
     * found one by one. Subject and collection names are folded per search: there are a few dozen, not thousands.
     */
    fun search(
        entries: List<Entry>,
        query: String,
        subjectNames: Map<Long, String>,
        systemNames: Map<Long, String>,
    ): List<StudyUnitEntity> {
        // normalize() folds the words exactly as searchKey() folds the texts; split on the single spaces it leaves.
        val words = TopicTitle.normalize(query).split(' ').filter { it.isNotEmpty() }.distinct()
        if (words.isEmpty()) return entries.map { it.unit }
        val subjectHits = words.map { w -> subjectNames.filterValues { TopicTitle.searchKey(it).contains(w) }.keys }
        val systemHits = words.map { w -> systemNames.filterValues { TopicTitle.searchKey(it).contains(w) }.keys }
        return entries
            .filter { e ->
                words.indices.all { i ->
                    e.keys.any { it.contains(words[i]) } || e.unit.subjectId in subjectHits[i] || e.unit.systemId in systemHits[i]
                }
            }
            .map { it.unit }
    }
}

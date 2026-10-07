package com.example.ui.add

import com.example.data.local.dao.RelatedTopicTitle
import com.example.data.text.TopicTitle

/** Advisory title matches only. Similar titles never imply shared history or permission to merge. */
internal object RelatedTopics {
    data class Entry(val topic: RelatedTopicTitle, val key: String)

    fun index(topics: List<RelatedTopicTitle>): List<Entry> =
        topics.map { Entry(it, TopicTitle.searchKey(it.title)) }

    fun search(entries: List<Entry>, query: String, subjectId: Long?): List<RelatedTopicTitle> {
        val words = TopicTitle.normalize(query).split(' ').filter { it.isNotBlank() }.distinct()
        if (words.isEmpty()) return emptyList()
        val exact = TopicTitle.searchKey(query)
        return entries.asSequence()
            .filter { e -> words.all { e.key.contains(it) } }
            .sortedWith(compareBy<Entry> { if (it.key == exact) 0 else 1 }
                .thenBy { if (subjectId != null && it.topic.subjectId == subjectId) 0 else 1 }
                .thenBy { it.topic.archived }
                .thenBy { if (it.key.startsWith(exact)) 0 else 1 }
                .thenBy { it.key }.thenBy { it.topic.id })
            .map { it.topic }.toList()
    }
}

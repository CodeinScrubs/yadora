package com.example.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Streaming JSON for the backup and the research export.
 *
 * Both used to build the whole document as one org.json tree and then one String. Measured on a
 * multi-year history (3,300 topics, ~20,000 reviews): a 24 MB backup cost ~160 MB of heap to build, and a
 * restore held the file's text, its parsed tree AND a full pre-restore safety backup at once. On a phone
 * with a 192-256 MB heap that fails exactly when the data matters most. Now records are written one at a
 * time, and read one at a time into their entities, so memory is the size of the entities, not of the text.
 */
internal object JsonStreams {

    fun JsonWriter.field(name: String, value: String?): JsonWriter {
        name(name); if (value == null) nullValue() else value(value); return this
    }

    fun JsonWriter.field(name: String, value: Long?): JsonWriter {
        name(name); if (value == null) nullValue() else value(value); return this
    }

    fun JsonWriter.field(name: String, value: Int): JsonWriter {
        name(name); value(value.toLong()); return this
    }

    /** Non-finite values throw, exactly as JSONObject.put did: a NaN in the data is a bug to surface, not to hide. */
    fun JsonWriter.field(name: String, value: Double): JsonWriter {
        name(name); value(value); return this
    }

    fun JsonWriter.field(name: String, value: Boolean): JsonWriter {
        name(name); value(value); return this
    }

    /** Writes an org.json value (small nested objects such as the settings block) through the stream. */
    fun JsonWriter.jsonValue(value: Any?) {
        when (value) {
            null, JSONObject.NULL -> nullValue()
            is JSONObject -> {
                beginObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    name(k); jsonValue(value.opt(k))
                }
                endObject()
            }
            is JSONArray -> {
                beginArray()
                for (i in 0 until value.length()) jsonValue(value.opt(i))
                endArray()
            }
            is Boolean -> value(value)
            is Int, is Long, is Short, is Byte -> value((value as Number).toLong())
            is Number -> value(value.toDouble())
            else -> value(value.toString())
        }
    }

    /**
     * One record, read into a small JSONObject so the per-record parsing keeps org.json's forgiving
     * accessors (optString, optLong, isNull ...) exactly as before. Only one record is alive at a time.
     */
    fun JsonReader.readRecord(): JSONObject {
        val o = JSONObject()
        beginObject()
        while (hasNext()) {
            val name = nextName()
            o.put(name, readValue())
        }
        endObject()
        return o
    }

    fun JsonReader.readValue(): Any = when (peek()) {
        JsonToken.BEGIN_OBJECT -> readRecord()
        JsonToken.BEGIN_ARRAY -> JSONArray().also { a ->
            beginArray()
            while (hasNext()) a.put(readValue())
            endArray()
        }
        JsonToken.STRING -> nextString()
        // The literal as written: whole numbers stay exact Longs (ids and epoch millis must not pass
        // through a Double), everything else is a Double.
        JsonToken.NUMBER -> nextString().let { s -> s.toLongOrNull() ?: s.toDouble() }
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); JSONObject.NULL }
        else -> throw IllegalStateException("Unexpected JSON token ${peek()}")
    }

    /** Calls [onRecord] for each element of the array the reader is positioned on. */
    inline fun JsonReader.forEachRecord(onRecord: (index: Int, record: JSONObject) -> Unit) {
        if (peek() == JsonToken.NULL) { nextNull(); return }
        beginArray()
        var i = 0
        while (hasNext()) onRecord(i++, readRecord())
        endArray()
    }

    /**
     * An InputStream that refuses to read past [limit] bytes: a file picked by mistake (a video, a disk
     * image) fails fast instead of being parsed for minutes. The limit is far above anything the app can
     * write, so a backup Yadora made is always importable again.
     */
    class Bounded(private val inner: java.io.InputStream, private val limit: Long) : java.io.FilterInputStream(inner) {
        private var count = 0L

        private fun add(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > limit) throw java.io.IOException("File too large to be a Yadora backup")
            }
            return n
        }

        override fun read(): Int = inner.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(inner.read(b, off, len))
    }
}

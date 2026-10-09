package com.example.data

import android.util.JsonReader
import android.util.JsonToken
import com.example.data.JsonStreams.readRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Validate multi-year fixtures without rebuilding a file-sized org.json tree or serialized copy. */
internal object SoakJsonFiles {
    fun assertConsistentExport(file: File) {
        JsonReader(file.reader(Charsets.UTF_8)).use { reader ->
            var found = false
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "consistency") {
                    assertTrue("only one consistency block", !found)
                    found = true
                    val block = reader.readRecord()
                    assertEquals("export self-check: $block", 0, block.getInt("issueCount"))
                } else reader.skipValue()
            }
            reader.endObject()
            assertTrue("export must carry its self-check", found)
            assertEquals("no trailing export data", JsonToken.END_DOCUMENT, reader.peek())
        }
    }

    /** Same check as the former complete JSON comparison: every field/row/value, except root exportedAt. */
    fun assertSameBackup(before: File, after: File) {
        JsonReader(before.reader(Charsets.UTF_8)).use { a ->
            JsonReader(after.reader(Charsets.UTF_8)).use { b ->
                sameValue(a, b, "$", root = true)
                assertEquals(JsonToken.END_DOCUMENT, a.peek())
                assertEquals(JsonToken.END_DOCUMENT, b.peek())
            }
        }
    }

    private fun sameValue(a: JsonReader, b: JsonReader, path: String, root: Boolean = false) {
        assertEquals("$path token", a.peek(), b.peek())
        when (a.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                a.beginObject(); b.beginObject()
                while (a.hasNext()) {
                    assertTrue("$path missing field", b.hasNext())
                    val name = a.nextName()
                    assertEquals("$path field", name, b.nextName())
                    if (root && name == "exportedAt") { a.skipValue(); b.skipValue() }
                    else sameValue(a, b, "$path.$name")
                }
                assertTrue("$path extra field", !b.hasNext())
                a.endObject(); b.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                a.beginArray(); b.beginArray()
                var index = 0
                while (a.hasNext()) {
                    assertTrue("$path missing row $index", b.hasNext())
                    sameValue(a, b, "$path[${index++}]")
                }
                assertTrue("$path extra row", !b.hasNext())
                a.endArray(); b.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> assertEquals(path, a.nextString(), b.nextString())
            JsonToken.BOOLEAN -> assertEquals(path, a.nextBoolean(), b.nextBoolean())
            JsonToken.NULL -> { a.nextNull(); b.nextNull() }
            else -> throw AssertionError("$path unexpected token ${a.peek()}")
        }
    }
}

package com.example.data

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SoakJsonFilesTest {
    private fun withFile(json: String, check: (File) -> Unit) {
        val file = File.createTempFile("yadora-soak-json", ".json")
        try { file.writeText(json); check(file) } finally { file.delete() }
    }

    private fun compare(a: String, b: String) = withFile(a) { before ->
        withFile(b) { after -> SoakJsonFiles.assertSameBackup(before, after) }
    }

    @Test fun `root export time may change but every large id and snapshot survives`() {
        compare("""{"exportedAt":1,"events":[{"id":9007199254740993,"detail":"p=0.8 memory=Good"}],"empty":[],"null":null}""",
                """{"exportedAt":2,"events":[{"id":9007199254740993,"detail":"p=0.8 memory=Good"}],"empty":[],"null":null}""")
        withFile("""{"events":[{"id":1}],"consistency":{"issueCount":0,"issues":[]}}""") {
            SoakJsonFiles.assertConsistentExport(it)
        }
    }

    @Test(expected = AssertionError::class) fun `changed forecast is not a successful backup identity`() {
        compare("""{"events":[{"detail":"p=0.8 memory=Good"}]}""", """{"events":[{"detail":"p=0.8 memory=Forgot"}]}""")
    }

    @Test(expected = AssertionError::class) fun `a missing row is not a successful backup identity`() {
        compare("""{"events":[1,2]}""", """{"events":[1]}""")
    }

    @Test(expected = AssertionError::class) fun `only the root exportedAt is ignored`() {
        compare("""{"exportedAt":1,"events":[{"exportedAt":1}]}""", """{"exportedAt":2,"events":[{"exportedAt":2}]}""")
    }

    @Test(expected = AssertionError::class) fun `nonzero consistency remains a failure`() {
        withFile("""{"consistency":{"issueCount":1,"issues":[{"kind":"ORPHAN_LOG"}]}}""") {
            SoakJsonFiles.assertConsistentExport(it)
        }
    }

    @Test(expected = AssertionError::class) fun `missing consistency remains a failure`() {
        withFile("""{"events":[]}""") { SoakJsonFiles.assertConsistentExport(it) }
    }
}

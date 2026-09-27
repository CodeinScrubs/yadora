package com.example.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.time.LocalDateTime

/** The automatic backup's rules ([AutoBackup]): what is written, what is kept, what is never touched. */
class AutoBackupTest {

    /** A folder on the JVM file system, standing in for the learner's chosen folder. */
    private class DirFolder(val dir: java.io.File) : AutoBackup.Folder {
        override fun list() = dir.listFiles().orEmpty().map { AutoBackup.Folder.Entry(it.name, it) }
        override fun create(name: String): AutoBackup.Folder.Entry? =
            java.io.File(dir, name).takeIf { it.createNewFile() }?.let { AutoBackup.Folder.Entry(name, it) }
        override fun openOutput(entry: AutoBackup.Folder.Entry): OutputStream = (entry.handle as java.io.File).outputStream()
        override fun delete(entry: AutoBackup.Folder.Entry) = (entry.handle as java.io.File).delete()
        fun names() = dir.list().orEmpty().sorted()
    }

    private fun folder() = DirFolder(kotlin.io.path.createTempDirectory("yadora-autobackup").toFile())
    private val t0: LocalDateTime = LocalDateTime.of(2026, 9, 24, 21, 30)

    @Test fun `a backup is a new dated file with the full contents`() = runBlocking {
        val f = folder()
        val out = AutoBackup.writeInto(f, t0, topicCount = 12) { it.write("{\"backupVersion\":9}".toByteArray()) }
        assertEquals(AutoBackup.Outcome.Written("yadora_backup_2026-09-24_213000.json"), out)
        assertEquals("{\"backupVersion\":9}", java.io.File(f.dir, "yadora_backup_2026-09-24_213000.json").readText())
    }

    @Test fun `a write that fails part-way leaves no file behind and deletes nothing`() = runBlocking {
        val f = folder()
        AutoBackup.writeInto(f, t0.minusDays(1), 12) { it.write(1) }
        val out = AutoBackup.writeInto(f, t0, 12) { it.write("{\"half".toByteArray()); throw java.io.IOException("disk full") }
        assertTrue(out is AutoBackup.Outcome.Failed)
        assertEquals("only yesterday's good backup remains", listOf("yadora_backup_2026-09-23_213000.json"), f.names())
    }

    @Test fun `an empty library is never backed up, so old backups are never rotated out by empty ones`() = runBlocking {
        val f = folder()
        AutoBackup.writeInto(f, t0, 12) { it.write(1) }
        for (d in 1..40) {
            assertTrue(AutoBackup.writeInto(f, t0.plusDays(d.toLong()), topicCount = 0) { it.write(1) } is AutoBackup.Outcome.Skipped)
        }
        assertEquals(listOf("yadora_backup_2026-09-24_213000.json"), f.names())
    }

    @Test fun `rotation keeps a week of days and six months, and never touches other files`() = runBlocking {
        val f = folder()
        java.io.File(f.dir, "notes.txt").writeText("mine")
        java.io.File(f.dir, "yadora_backup.json").writeText("a manual export")
        // A year of daily backups, two on some days.
        for (d in 0 until 365) {
            val at = t0.minusDays(364L - d)
            AutoBackup.writeInto(f, at, 12) { it.write(1) }
            if (d % 10 == 0) AutoBackup.writeInto(f, at.plusMinutes(30), 12) { it.write(2) }
        }
        val backups = f.names().filter { it.startsWith("yadora_backup_2") }
        assertTrue("foreign files untouched", "notes.txt" in f.names() && "yadora_backup.json" in f.names())
        // The last 7 days (one file each, the newest of the day) plus one per month for 6 months (the
        // current month's newest is already among the 7 days).
        val days = backups.map { it.substring(14, 24) }.toSet()
        assertEquals(backups.size, days.size)
        (0L until 7L).forEach { assertTrue("day -$it kept", t0.minusDays(it).toLocalDate().toString() in days) }
        val months = backups.map { it.substring(14, 21) }.toSet()
        assertEquals("six monthly checkpoints", 6, months.size)
        assertTrue("and nothing older than six months", backups.none { it.substring(14, 21) < "2026-04" })
        assertTrue("bounded", backups.size <= AutoBackup.KEEP_DAYS + AutoBackup.KEEP_MONTHS)
    }

    @Test fun `the rotation rule alone`() {
        val names = listOf(
            "yadora_backup_2026-09-24_2130.json", "yadora_backup_2026-09-24_0800.json",
            "yadora_backup_2026-09-23_2130.json", "yadora_backup_2026-08-31_2130.json",
            "yadora_backup_2026-08-02_2130.json", "photo.jpg", "yadora_backup_2026-13-01_0000.json",
        )
        val doomed = AutoBackup.toDelete(names)
        assertEquals(listOf("yadora_backup_2026-09-24_0800.json"), doomed)
    }

    @Test fun `older minute-only names and a provider's renamed copy are backups too, and are pruned`() {
        val names = listOf(
            "yadora_backup_2026-09-27_190312.json",      // current: to the second
            "yadora_backup_2026-09-27_1903.json",        // an older build's minute-only name, same day
            "yadora_backup_2026-09-27_1903 (1).json",    // the clash copy a storage provider renamed
            "yadora_backup_2026-09-26_2100.json",
            "yadora_backup (1).json", "notes (1).json",  // not backups: never touched
        )
        val doomed = AutoBackup.toDelete(names).toSet()
        assertEquals(setOf("yadora_backup_2026-09-27_1903.json", "yadora_backup_2026-09-27_1903 (1).json"), doomed)
    }

    /** Two backups seconds apart (the daily job and "Back up now") leave ONE file: the newer, holding the most. */
    @Test fun `two backups moments apart leave one file, the newer`() = runBlocking {
        val f = folder()
        AutoBackup.writeInto(f, t0, 12) { it.write("{\"older\":1}".toByteArray()) }
        val out = AutoBackup.writeInto(f, t0.plusSeconds(40), 12) { it.write("{\"newer\":1}".toByteArray()) }
        assertEquals(AutoBackup.Outcome.Written("yadora_backup_2026-09-24_213040.json"), out)
        assertEquals(listOf("yadora_backup_2026-09-24_213040.json"), f.names())
        assertEquals("{\"newer\":1}", java.io.File(f.dir, "yadora_backup_2026-09-24_213040.json").readText())
    }

    @Test fun `Back up now always writes, the daily job once a day`() {
        val now = 1_800_000_000_000L
        val min = 60_000L
        assertTrue("nothing yet: write", AutoBackup.shouldWrite(false, now, 0L))
        assertTrue("Back up now a minute after another: write, it must hold everything up to now",
            AutoBackup.shouldWrite(true, now, now - min))
        assertFalse("the daily job the same day: skip", AutoBackup.shouldWrite(false, now, now - min))
        assertTrue("the daily job a day later: write", AutoBackup.shouldWrite(false, now, now - 25 * 60 * min))
        assertTrue("a clock set back: write rather than trust a future stamp", AutoBackup.shouldWrite(false, now, now + 60 * min))
    }

    @Test fun `Today suggests automatic backup only when there is something to lose and at most monthly`() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        assertFalse("too few topics", AutoBackup.shouldNudge(19, false, now, 0, 0))
        assertTrue(AutoBackup.shouldNudge(20, false, now, 0, 0))
        assertFalse("already on", AutoBackup.shouldNudge(500, true, now, 0, 0))
        assertFalse("a manual backup last week", AutoBackup.shouldNudge(500, false, now, now - 7 * day, 0))
        assertFalse("dismissed last week", AutoBackup.shouldNudge(500, false, now, 0, now - 7 * day))
        assertTrue("a month later it asks again", AutoBackup.shouldNudge(500, false, now, now - 31 * day, now - 31 * day))
    }
}

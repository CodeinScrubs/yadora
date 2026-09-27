package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.MedReviewApplication
import com.example.data.local.entity.StudyUnitEntity
import com.example.domain.model.MemoryRating
import com.example.domain.model.ReviewMethod
import com.example.domain.model.SessionKind
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import com.example.ui.today.DayBounds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * A realistic study history, ending NOW, written as a normal backup file for testing on a phone.
 *
 * Settings → Import backup restores it on a device, and every screen then has something real to show: reviews
 * due today and overdue (more than a daily limit of 10 allows), two topics studied today and waiting for their
 * first rating, one planned for tomorrow, Important topics, a deferred one, an archived one and one in Recently
 * deleted, Persian and English titles, scope lines, notes with web links, and two legacy topics with key points.
 * The history is made the way the app makes it: every rating goes through [MedReviewRepository.rateUnit], the
 * review screen's own commit path, with outcomes drawn from the model's prediction. The file carries no settings
 * block, so a restore keeps the phone's own language, reminders and limit.
 *
 * Output: app/build/device-seed/yadora_seed_backup.json (times in Asia/Tehran, like the phones it is for). Push it
 * to the phone's Download folder and import it. Its dates are relative to when the test ran, so regenerate it on
 * the day of a device session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceSeedBackupTest {

    private val day = 86_400_000L

    private val titles = listOf(
        "Heart failure — drugs" to "Classes, mortality benefit, contraindications",
        "Atrial fibrillation" to "Rate vs rhythm control, anticoagulation (CHA2DS2-VASc)",
        "سندرم نفروتیک" to "علل، تتراد، عوارض، درمان",
        "Acute kidney injury" to "Prerenal, intrinsic, postrenal; urine indices",
        "Beta-lactams" to "Spectrum, resistance, allergy cross-reactivity",
        "آپاندیسیت" to "تظاهرات، نمره Alvarado، تصویربرداری، درمان",
        "Aminoglycosides" to null,
        "Hyponatraemia" to "Approach by volume status; correction limits",
        "Infective endocarditis" to "Duke criteria, organisms, empirical therapy",
        "دیابت نوع ۲ — درمان دارویی" to null,
        "Acid–base disorders" to "Anion gap, Winter's formula, delta ratio",
        "Pneumonia — CAP" to "CURB-65, organisms, empirical therapy",
        "Macrolides" to null,
        "Glomerulonephritis" to "Nephritic vs nephrotic, complement levels",
        "Myocardial infarction" to "STEMI/NSTEMI, reperfusion, secondary prevention",
        "کوله‌سیستیت حاد" to null,
        "Hyperkalaemia" to "ECG changes, emergency treatment order",
        "Warfarin & DOACs" to "Monitoring, reversal, interactions",
        "Hypertension — first line" to null,
        "Nephrolithiasis" to "Stone types, imaging, prevention",
        "Fluoroquinolones" to null,
        "Valvular heart disease" to "Murmurs, severity, when to operate",
        "Pancreatitis" to "Causes, Ranson/BISAP, fluids",
        "Thyroid storm" to null,
        "Sepsis bundle" to "Hour-1 bundle, lactate, vasopressors",
        "Hernias" to "Inguinal vs femoral, incarceration",
        "Anaemia — microcytic" to "Iron, thalassaemia, sideroblastic; iron studies",
        "COPD exacerbation" to null,
        "Asthma — stepwise therapy" to "GINA steps, acute severe asthma",
        "Upper GI bleeding" to "Glasgow-Blatchford, PPI, endoscopy timing",
        "Diuretics" to "Site of action, side effects",
        "Heparin & HIT" to null,
        "Cirrhosis complications" to "Ascites, SBP, varices, encephalopathy",
        "Pulmonary embolism" to "Wells, D-dimer, CTPA, thrombolysis",
        "Stroke — acute management" to "Thrombolysis window, thrombectomy",
        "Meningitis" to "CSF patterns, empirical antibiotics",
        "Addison's disease" to null,
        "Cushing's syndrome" to "Screening tests, causes",
        "Burns" to "Parkland formula, rule of nines",
        "Trauma — primary survey" to "ABCDE, shock classes",
    )

    @Test
    fun `a two-month study history ending now restores as a backup and checks out`() {
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tehran"))
        try {
            seed()
        } finally {
            java.util.TimeZone.setDefault(saved)
            MedScheduler.calibrationScale = 1.0
        }
    }

    private fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MedReviewApplication>()
        val repo = app.repository
        val zone = ZoneId.systemDefault()
        val rnd = kotlin.random.Random(20260927)
        MedScheduler.calibrationScale = 1.0
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        fun at(daysAgo: Int, hour: Int, minute: Int = 0): Long =
            today.minusDays(daysAgo.toLong()).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

        val subjects = listOf("Cardiology", "Nephrology", "Pharmacology", "داخلی", "Surgery").map { repo.insertSubject(it) }
        fun subjectOf(i: Int) = subjects[i % subjects.size]

        // Study: two topics most days over the last month, logged and rated at once ("Save and rate now").
        val ids = ArrayList<Long>()
        var t = 0
        for (daysAgo in 32 downTo 6) {
            if (t >= titles.size) break
            if (daysAgo % 7 == 5) continue
            val howMany = 2
            repeat(howMany) { k ->
                if (t >= titles.size) return@repeat
                val (title, scope) = titles[t]
                val studiedAt = at(daysAgo, 17, 15 * k)
                val notes = when (t % 4) {
                    0 -> "Summary written after the lecture.\nGuideline: https://www.escardio.org/Guidelines\nQuestion block: UWorld 12, 18, 23."
                    1 -> "High-yield: first-line therapy and the one contraindication examiners love.\nVideo: www.youtube.com/watch?v=dQw4w9WgXcQ"
                    2 -> null
                    else -> "نکات مهم کلاس، صفحه ۲۳۴ هریسون."
                }
                val id = repo.insertUnit(
                    StudyUnitEntity(
                        title = title, studyType = "Topic", subjectId = subjectOf(t),
                        recallPrompt = scope, notes = notes,
                        source = if (t % 5 == 1) "Harrison's 21e, ch. ${200 + t}" else if (t % 5 == 3) "https://www.uptodate.com" else null,
                        highYield = t % 6 == 0,
                        // Two LEGACY topics carry key points (reference text since the rating cap was retired).
                        keyPoints = if (t == 2 || t == 14) "First point\nSecond point\nThird point" else null,
                        stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                        studiedAt = studiedAt, nextReviewAt = studiedAt, modelDueAt = studiedAt,
                        currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = studiedAt,
                    )
                )
                ids += id
                val first = listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Good, MemoryRating.Easy)[rnd.nextInt(4)]
                repo.rateUnit(
                    unitId = id, now = studiedAt + 20 * 60_000L, memoryRating = first,
                    understandingRating = listOf(UnderstandingRating.Clear, UnderstandingRating.Clear, UnderstandingRating.Partial)[rnd.nextInt(3)],
                    sessionKind = SessionKind.TOPIC, reviewDurationMs = 60_000,
                )!!
                t++
            }
        }

        // Reviews: every evening, today's plan at a limit of 50, outcomes drawn from the model's prediction. The last
        // five evenings are skipped, so today opens with a backlog.
        for (daysAgo in 31 downTo 6) {
            if (daysAgo % 11 == 7) continue
            val evening = at(daysAgo, 21)
            repo.refreshMemoryModel()
            MedScheduler.calibrationScale = repo.recallCalibrationScale()
            val plan = repo.todayPlan(50, evening)
            for ((i, u) in plan.reviews.withIndex()) {
                val at = evening + i * 90_000L
                val elapsed = MedScheduler.modelElapsedDays(u.lastReviewedAt ?: u.studiedAt, at, MedScheduler.CURRENT_MODEL)
                val r = MedScheduler.retrievability(elapsed, u.stability, MedScheduler.CURRENT_MODEL, u.parameterSetId)
                val recalled = rnd.nextDouble() < r
                val memory = if (!recalled) MemoryRating.Forgot
                    else listOf(MemoryRating.Hard, MemoryRating.Good, MemoryRating.Good, MemoryRating.Good, MemoryRating.Easy)[rnd.nextInt(5)]
                val methods = when (rnd.nextInt(4)) {
                    0 -> setOf(ReviewMethod.Questions)
                    1 -> setOf(ReviewMethod.Reading)
                    2 -> setOf(ReviewMethod.Questions, ReviewMethod.Reading)
                    else -> emptySet()
                }
                val total = 10 + rnd.nextInt(11)
                val score = if (ReviewMethod.Questions in methods)
                    (total * (if (recalled) 0.6 + 0.35 * rnd.nextDouble() else 0.2 + 0.3 * rnd.nextDouble())).toInt() to total
                else null to null
                repo.rateUnit(
                    unitId = u.id, now = at, memoryRating = memory,
                    understandingRating = if (recalled) listOf(UnderstandingRating.Clear, UnderstandingRating.Clear, UnderstandingRating.Partial)[rnd.nextInt(3)]
                        else UnderstandingRating.Partial,
                    understandingAsked = recalled, methods = methods,
                    questionsCorrect = score.first, questionsTotal = score.second,
                    sessionKind = SessionKind.PLAN, reviewDurationMs = 120_000,
                )!!
            }
        }

        // Today: two topics studied this morning, not rated yet; one planned for tomorrow.
        val morning = at(0, 9)
        listOf("Hyperthyroidism" to "Graves, toxic nodule, thyroiditis", "سندرم کوشینگ — تشخیص" to null).forEachIndexed { i, (title, scope) ->
            ids += repo.insertUnit(
                StudyUnitEntity(
                    title = title, studyType = "Topic", subjectId = subjectOf(i), recallPrompt = scope,
                    stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                    studiedAt = morning + i * 60_000L, nextReviewAt = morning + i * 60_000L, modelDueAt = morning + i * 60_000L,
                    currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = morning + i * 60_000L,
                )
            )
        }
        val tomorrow = today.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        repo.insertUnit(
            StudyUnitEntity(
                title = "Lung cancer — staging", studyType = "Topic", subjectId = subjects[0],
                stability = 1.0, difficulty = 5.0, retrievability = 1.0, state = "New",
                studiedAt = tomorrow, nextReviewAt = tomorrow, modelDueAt = tomorrow,
                currentIntervalDays = 0.0, reviewCount = 0, lapseCount = 0, createdAt = now,
            )
        )

        // A deferral, an archived topic and a recently deleted one.
        val due = app.database.studyUnitDao().getDueUnitsList(DayBounds.endOf(now)).filter { it.reviewCount > 0 }
        repo.procrastinateUnit(due.last().id, today.plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli())
        val archivedId = ids[ids.size - 5]
        repo.getUnitById(archivedId)!!.let { app.database.studyUnitDao().updateUnit(it.copy(archived = true)) }
        val deletedId = ids[ids.size - 6]
        repo.getUnitById(deletedId)!!.let { app.database.studyUnitDao().updateUnit(it.copy(archived = true, deletedAt = now - 2 * day)) }

        // What today looks like, and what the test promises about it.
        val plan50 = repo.todayPlan(50, now)
        val plan10 = repo.todayPlan(10, now)
        val upcoming = app.database.studyUnitDao().getAllActiveOnce().count { it.nextReviewAt > DayBounds.endOf(now) }
        println("SEED: ${ids.size + 1} topics; due now: ${plan50.firstRatings.size} first ratings + ${plan50.reviews.size} reviews " +
            "(held back at 50: ${plan50.heldBack}, at 10: ${plan10.heldBack}); upcoming: $upcoming")
        assertEquals("two first ratings wait for today", 2, plan50.firstRatings.size)
        assertTrue("more reviews are due than a limit of 10 allows: ${plan50.reviews.size}", plan50.reviews.size > 10)
        assertTrue("there are topics ahead to review ahead: $upcoming", upcoming >= 10)

        // Written as a normal backup, without the settings block, and checked by restoring it.
        val backup = JSONObject(BackupManager.buildBackupJson(app)).apply { remove("settings") }
        val restored = BackupManager.restoreFromJson(app, backup.toString())
        assertEquals(ids.size + 1, restored)
        val export = JSONObject(AnalyticsExporter.buildJson(app))
        assertEquals("the seed breaks no invariant: " + export.getJSONObject("consistency"), 0,
            export.getJSONObject("consistency").getInt("issueCount"))

        val out = java.io.File(System.getProperty("user.dir"), "build/device-seed/yadora_seed_backup.json")
        out.parentFile!!.mkdirs()
        out.writeText(backup.toString())
    }
}

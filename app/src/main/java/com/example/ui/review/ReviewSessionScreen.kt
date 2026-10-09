package com.example.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.example.ui.i18n.autoDirection
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.StudyUnitEntity

import com.example.data.repository.MedReviewRepository
import com.example.domain.model.MemoryRating
import com.example.domain.model.UnderstandingRating
import com.example.domain.srs.MedScheduler
import com.example.ui.i18n.stateLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.unit.sp

import kotlinx.coroutines.flow.first
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** English ordinal: 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 11 -> "11th", 21 -> "21st"... */
private fun ordinalEn(n: Int): String {
    if (n % 100 in 11..13) return "${n}th"
    return when (n % 10) {
        1 -> "${n}st"
        2 -> "${n}nd"
        3 -> "${n}rd"
        else -> "${n}th"
    }
}

/**
 * The interval printed on an understanding button, e.g. "30.8d". Rounded to the nearest tenth. It used to
 * truncate, which printed a 4.98-day first check-in as "4.9d" while the message for the same review said
 * "in 5 days", and made every label under-report.
 */
internal fun intervalButtonLabel(days: Double): String {
    if (days < 1.0) {
        val hours = (days * 24).toInt()
        return if (hours < 1) "<1h" else "${hours}h"
    }
    val tenths = Math.round(days * 10) / 10.0
    return if (tenths % 1.0 == 0.0) "${tenths.toLong()}d" else "${tenths}d"
}

/**
 * [intervalButtonLabel] in the user's language. Persian gets Persian digits and the unit as a word:
 * "۳٫۲d" mixed two scripts on one button while every other number on the screen was Persian.
 */
internal fun localizedIntervalLabel(days: Double, languageCode: String): String {
    val latin = intervalButtonLabel(days)
    if (languageCode == "de") return latin.replace('.', ',') // German writes a decimal comma: "3,2d"
    if (languageCode != "fa") return latin
    return when {
        latin == "<1h" -> "کمتر از ۱ ساعت"
        latin.endsWith("h") -> "${com.example.ui.i18n.PersianDate.faDigits(latin.dropLast(1))} ساعت"
        else -> "${com.example.ui.i18n.PersianDate.faDigits(latin.dropLast(1).replace('.', '٫'))} روز"
    }
}

/**
 * When the topic on screen comes back after [memory] and [understanding], in days from [now]: the same
 * [MedScheduler.review], fuzz and two clocks as the commit ([com.example.data.repository.MedReviewRepository.rateUnit]),
 * so a button can never promise one date and the schedule write another. `ButtonEstimateTest` pins it against the
 * commit for every combination. The understanding buttons show it exactly; each memory button shows it for Clear
 * understanding as an estimate, because Partial or Confused can only bring a topic back SOONER (the repair clock),
 * never later, and a Forgot is always tomorrow.
 */
internal fun previewReturnDays(
    unit: StudyUnitEntity,
    now: Long,
    memory: MemoryRating,
    understanding: UnderstandingRating,
    unrepairedStreak: Int,
): Double {
    val reviewNumber = MedScheduler.effectiveReviewNumber(unit.reviewCount)
    // Measured as the commit measures it (whole local calendar days on FSRS-6).
    val elapsedDays = MedScheduler.modelElapsedDays(unit.lastReviewedAt ?: unit.studiedAt, now, MedScheduler.CURRENT_MODEL)
    val outcome = MedScheduler.review(
        stability = unit.stability,
        difficulty = unit.difficulty,
        elapsedDays = elapsedDays,
        memoryRating = memory,
        understanding = understanding,
        highYield = unit.highYield,
        reviewNumber = reviewNumber,
        model = MedScheduler.CURRENT_MODEL,
        unrepairedStreak = unrepairedStreak,
        parameterSetId = unit.parameterSetId,
    )
    val memoryInterval = MedScheduler.fuzzedInterval(
        outcome.intervalDays, outcome.baseIntervalDays, unit.id, unit.reviewCount, isFirstStudy = reviewNumber == 0,
    )
    // The date that actually applies: the earlier of the memory prediction and the understanding repair deadline, kept by
    // the same rule the commit applies (MedScheduler.repairDays).
    return minOf(memoryInterval, MedScheduler.repairDays(outcome, memory, memoryInterval, MedScheduler.POLICY_VERSION) ?: Double.MAX_VALUE)
}

/**
 * The figure printed on a memory button. An ESTIMATE, so whole days and a sign that says so ("~128d",
 * "حدود ۱۲۸ روز"): a tenth of a day on a guess claims precision it does not have, and "~" is not how Persian says
 * "about". A Forgot is exact (tomorrow) and is printed like the understanding buttons' exact figures.
 */
internal fun estimateLabel(days: Double, languageCode: String, exact: Boolean): String {
    if (exact) return localizedIntervalLabel(days, languageCode)
    val whole = Math.round(days).coerceAtLeast(1L)
    return if (languageCode == "fa") "حدود ${com.example.ui.i18n.PersianDate.faDigits(whole)} روز" else "~${whole}d"
}

/** True if [earlier] falls on an earlier local calendar day than [later]. */
private fun isEarlierLocalDay(earlier: Long, later: Long): Boolean {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = earlier
    val ey = c.get(java.util.Calendar.YEAR); val ed = c.get(java.util.Calendar.DAY_OF_YEAR)
    c.timeInMillis = later
    val ly = c.get(java.util.Calendar.YEAR); val ld = c.get(java.util.Calendar.DAY_OF_YEAR)
    return ey < ly || (ey == ly && ed < ld)
}

@Suppress("UNCHECKED_CAST")
class ReviewViewModelFactory(
    private val application: android.app.Application,
    private val repository: MedReviewRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReviewViewModel(application, repository) as T
    }
}

class ReviewViewModel(
    application: android.app.Application,
    private val repository: MedReviewRepository
) : androidx.lifecycle.AndroidViewModel(application) {
    data class ReviewHistoryItem(
        val review: MedReviewRepository.RatedReview,
        val ratingGiven: MemoryRating,
        /** A first rating logs a study; it is counted apart from reviews in the session summary. */
        val wasFirstRating: Boolean,
    )
    private val ratedStack = mutableListOf<ReviewHistoryItem>()

    private val dueUnits = mutableListOf<StudyUnitEntity>()

    /**
     * A finished session looks exactly like a not-yet-loaded one (empty queue, null current unit), so
     * re-running the load effect after a configuration change re-fetched the topic the user had just
     * reviewed and let them rate it a second time. The ViewModel is scoped to the nav back-stack entry
     * and therefore survives rotation, dark-mode switches and split-screen resizes — so the "already
     * started" flag belongs here, not in composition.
     */
    private var sessionStarted = false

    /**
     * Which kind of session this is, stamped on every log it writes (research data: an early review is a
     * different measurement from an on-time one). Set once, when the session starts.
     */
    private var sessionKind = com.example.domain.model.SessionKind.PLAN

    /**
     * @param unitId one topic to review now (from a Today card or the Library), or -1 for today's plan.
     * @param ignoreLimit the learner chose "review more anyway" after today's limit was used up.
     * @param ahead "review ahead": topics not yet due, weakest first ([com.example.ui.today.ReviewAhead]).
     */
    fun startSessionOnce(unitId: Long = -1L, ignoreLimit: Boolean = false, ahead: Boolean = false, kind: String? = null) {
        if (sessionStarted) return
        sessionStarted = true
        singleTopic = unitId != -1L
        sessionKind = when {
            // One topic opened from Today keeps where it was in Today's list (Screen.ReviewSession.kind).
            unitId != -1L -> com.example.domain.model.SessionKind.entries.firstOrNull { it.name == kind }
                ?: com.example.domain.model.SessionKind.TOPIC
            ahead -> com.example.domain.model.SessionKind.AHEAD
            ignoreLimit -> com.example.domain.model.SessionKind.EXTRA
            else -> com.example.domain.model.SessionKind.PLAN
        }
        loadNext(unitId, ignoreLimit, ahead)
    }
    
    private val _currentUnit = MutableStateFlow<StudyUnitEntity?>(null)
    val currentUnit: StateFlow<StudyUnitEntity?> = _currentUnit
    
    val subjects: StateFlow<List<com.example.data.local.entity.SubjectEntity>> = repository.allSubjects
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
        
    var sessionCount by androidx.compose.runtime.mutableStateOf(0)
        private set
    var sessionHard by androidx.compose.runtime.mutableStateOf(0)
        private set
    var sessionGood by androidx.compose.runtime.mutableStateOf(0)
        private set
    var sessionForgot by androidx.compose.runtime.mutableStateOf(0)
        private set
    /** First ratings (studies logged) this session. Their Easy/Medium/Hard is a difficulty, not recall. */
    var sessionNew by androidx.compose.runtime.mutableStateOf(0)
        private set
    var canUndo by androidx.compose.runtime.mutableStateOf(false)
        private set
    var isLoading by androidx.compose.runtime.mutableStateOf(true)
        private set

    /**
     * Topics left out of this session because their history could not be replayed onto the current
     * memory model. Surfaced rather than swallowed: a silently shorter queue looks like "nothing
     * due" and the user would never learn a topic had become unreadable.
     */
    var skippedUnprojectable by androidx.compose.runtime.mutableStateOf(0)
        private set

    // Guards against a fast double-tap rating/procrastinating the same card twice (double-log + skip).
    var isProcessing by androidx.compose.runtime.mutableStateOf(false)
        private set

    // "Why scheduled?" — a localized one-liner after each rating (shown as a snackbar) so the user
    // sees cause → effect and learns to trust the scheduler instead of guessing at it.
    var lastReason by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    fun consumeReason() { lastReason = null }

    /**
     * ONE topic, opened on its own (from Today's list, the Library, a reminder): what every review is since Today stopped
     * offering sessions (the owner's decision, 2026-10-09). Its end screen says "Saved" and when the topic comes back,
     * not a session summary of one.
     */
    var singleTopic by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** The last rating's reason, kept for a single topic's end screen (the snackbar's copy is consumed). */
    var savedReason by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    // When the current card appeared — the review's duration (shown → rated) is a research signal
    // (optimal-retention computation needs per-review time). Capped so a phone left open overnight
    // doesn't record a 9-hour "review".
    private var unitShownAt = System.currentTimeMillis()

    // Split-topic hint: true when the CURRENT topic's recall history whiplashes (strong↔Forgot
    // reversals), which usually means it bundles parts developing at different rates. Advisory only —
    // Yadora never splits automatically, and a dismissal suppresses it for 5 more reviews (via prefs).
    var splitSuggestion by androidx.compose.runtime.mutableStateOf(false)

    /**
     * How many answers in a row before this one left the CURRENT topic's understanding unrepaired
     * (Partial/Confused on a successful recall). The repair clock doubles per such answer, so the
     * understanding buttons must preview with the same value the commit will read from the logs.
     * Refreshed whenever the displayed topic changes, including after an undo.
     */
    var currentUnrepairedStreak by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun dismissSplitSuggestion() {
        val unit = _currentUnit.value ?: return
        getApplication<android.app.Application>()
            .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
            .edit { putInt("split_dismiss_${unit.id}", unit.reviewCount) }
        splitSuggestion = false
    }

    private fun checkSplitSuggestion(unit: StudyUnitEntity) {
        splitSuggestion = false
        viewModelScope.launch {
            runCatching {
                val dismissedAt = getApplication<android.app.Application>()
                    .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    .getInt("split_dismiss_${unit.id}", -1)
                if (dismissedAt >= 0 && unit.reviewCount < dismissedAt + 5) return@launch
                val recalls = repository.getLogsForUnit(unit.id).first()
                    .filter { it.logType == "RECALL" }
                    .sortedWith(com.example.data.local.entity.REVIEW_HISTORY_ORDER)
                if (recalls.size < 4) return@launch
                // A "reversal" = adjacent ratings jumping between strong (Good/Easy) and Forgot.
                fun strong(r: String) = r == "Good" || r == "Easy"
                val reversals = recalls.zipWithNext().count { (a, b) ->
                    (strong(a.memoryRating) && b.memoryRating == "Forgot") ||
                        (a.memoryRating == "Forgot" && strong(b.memoryRating))
                }
                if (reversals >= 2 && _currentUnit.value?.id == unit.id) splitSuggestion = true
            }
        }
    }

    fun loadNext(unitId: Long = -1L, ignoreLimit: Boolean = false, ahead: Boolean = false) {
        viewModelScope.launch {
            if (dueUnits.isEmpty() && _currentUnit.value == null) {
                // The per-user interval correction, refreshed once per session from the logs. Read
                // here and not per review so a value cannot move between a button's preview and its
                // commit; both read MedScheduler.calibrationScale as it stands for the session.
                // The weight set first: the calibration pools evidence within the active set only.
                runCatching { repository.refreshMemoryModel() }
                runCatching { MedScheduler.calibrationScale = repository.recallCalibrationScale() }
                if (unitId != -1L) {
                    val unit = repository.getUnitById(unitId)
                    if (unit != null) {
                        dueUnits.clear()
                        dueUnits.add(unit)
                        advanceUnit()
                    }
                } else if (ahead) {
                    // Not yet due, weakest predicted recall first. Ordinary reviews in every other respect:
                    // each one is scored by FSRS at its real elapsed time and rescheduled from there.
                    dueUnits.clear()
                    dueUnits.addAll(repository.reviewAheadQueue())
                    advanceUnit()
                } else {
                    val sharedPrefs = getApplication<android.app.Application>().getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    val limit = MedScheduler.safeDailyLimit(sharedPrefs.getFloat("daily_review_limit", 50f))
                    // Today's plan: every first rating, then the most urgent reviews that fit in what is
                    // left of the DAILY limit (DailyPlan). The same plan Today and the reminders count.
                    val plan = repository.todayPlan(limit, ignoreLimit = ignoreLimit)
                    dueUnits.clear()
                    dueUnits.addAll(plan.queue)
                    advanceUnit()
                }
            }
            isLoading = false
        }
    }

    /**
     * Show the next due topic, PROJECTED onto the current memory model first.
     *
     * The projection has to happen here and not only at commit time: the rating buttons preview the
     * interval from the displayed unit's state, so showing an un-projected FSRS-5 stability while the
     * commit projects to FSRS-6 first would preview one number and then schedule another — breaking
     * the preview == commit invariant for every topic that has not yet crossed over. Projection is
     * idempotent, so the commit path re-running it is a no-op.
     */
    private suspend fun advanceUnit() {
        var shown: StudyUnitEntity? = null
        while (dueUnits.isNotEmpty()) {
            val queued = dueUnits.removeAt(0)
            // The queue was read when the session started; the row may have changed since (an edit, a
            // correction, a merge, a delete), and the buttons must preview from the row the commit will read.
            val next = runCatching { repository.getUnitById(queued.id) }.getOrNull()
                ?.takeIf { it.deletedAt == null && !it.archived } ?: continue
            // FAIL CLOSED. The old fallback here was getOrDefault(next), which on a projection
            // failure showed the RAW row -- an FSRS-5 stability behind a preview that computes
            // FSRS-6 intervals, which is precisely the preview-vs-commit mismatch this projection
            // exists to prevent. A topic whose history cannot be replayed has unreadable data; the
            // honest response is to leave it out of the session, not to schedule it on a curve its
            // state was never measured under.
            val projected = runCatching { repository.projectOntoCurrentModel(next) }.getOrElse {
                android.util.Log.w("Yadora", "skipping topic ${next.id}: projection failed", it)
                skippedUnprojectable++
                // Recorded in the event log so it reaches the analytics export. A projection failure
                // in the field is otherwise invisible: the topic just stops appearing, and neither
                // the user nor a later log review would ever learn why.
                runCatching {
                    repository.logEvent(
                        "PROJECTION_FAILED",
                        unitId = next.id,
                        detail = "model=${next.memoryModel} ${it::class.java.simpleName}: ${it.message?.take(120)}",
                    )
                }
                null
            }
            if (projected != null) { shown = projected; break }
        }
        // Read from the logs, as the commit reads it. An unreadable history degrades to "no streak" for
        // the preview instead of crashing the session; the commit reads it again and fails loudly there.
        currentUnrepairedStreak = shown?.let { runCatching { repository.unrepairedStreak(it.id) }.getOrDefault(0) } ?: 0
        _currentUnit.value = shown
        unitShownAt = System.currentTimeMillis()
        _currentUnit.value?.let { checkSplitSuggestion(it) } ?: run { splitSuggestion = false }
    }

    /**
     * Re-read the topic on screen, for coming back to it: the pencil opens the Edit screen from here, where the
     * learner can correct a past rating, switch Important on or change the study date. The card used to keep
     * the row it had loaded, so the buttons previewed from a state the database no longer held while the
     * commit read the new one (an outside audit, 2026-09-27). The answers already chosen stay: they are kept
     * per topic, not per copy of its row. A topic deleted, archived or merged away meanwhile gives way to the next.
     */
    fun refreshCurrentUnit() {
        val shownId = _currentUnit.value?.id ?: return
        if (isProcessing) return
        viewModelScope.launch {
            val fresh = runCatching { repository.getUnitById(shownId) }.getOrNull()
            if (_currentUnit.value?.id != shownId || isProcessing) return@launch // moved on meanwhile
            if (fresh == null || fresh.deletedAt != null || fresh.archived) {
                advanceUnit()
                return@launch
            }
            val projected = runCatching { repository.projectOntoCurrentModel(fresh) }.getOrNull()
            if (projected == null) {
                advanceUnit()
                return@launch
            }
            currentUnrepairedStreak = runCatching { repository.unrepairedStreak(shownId) }.getOrDefault(0)
            if (projected != _currentUnit.value) _currentUnit.value = projected
        }
    }

    /**
     * The plain-language "why this date" for the rating just committed.
     *
     * Takes BOTH clocks. It used to receive only the memory interval, so a topic rated Good with
     * Partial understanding announced "next in 100 days" and then came back in three — the headline
     * number was the one the user did NOT get. Under a two-clock model the honest headline is always
     * the date that actually applies, with the memory estimate named alongside it when they differ.
     */
    private fun buildReasonText(
        memory: MemoryRating,
        understanding: UnderstandingRating,
        highYield: Boolean,
        intervalDays: Double,
        effectiveIntervalDays: Double,
        firstStudy: Boolean,
        // Whether a repair deadline was actually written. With the backoff, Partial or Confused can
        // leave the memory date standing alone; the sentence must then not claim anything moved.
        repairPending: Boolean,
    ): String {
        val lang = getApplication<android.app.Application>()
            .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
            .getString("app_language", "en") ?: "en"
        val fa = lang == "fa"
        val de = lang == "de"
        // The headline is the date the topic ACTUALLY returns — the earlier of the two clocks.
        val d = Math.round(effectiveIntervalDays).toInt().coerceAtLeast(1)
        val memoryDays = Math.round(intervalDays).toInt().coerceAtLeast(1)
        val repairWon = memoryDays > d
        // A review saved for an earlier day can already be due again (counted from today, as the caller passes it).
        val dueNow = effectiveIntervalDays < 0.5
        val days = when {
            dueNow -> if (fa) "امروز" else if (de) "heute" else "today"
            fa -> "${com.example.ui.i18n.PersianDate.faDigits(d)} روز دیگر"
            de -> if (d <= 1) "in 1 Tag" else "in $d Tagen"
            else -> if (d <= 1) "in 1 day" else "in $d days"
        }
        val core = when {
            firstStudy -> if (fa) "ثبت شد — اولین مرور $days." else if (de) "Gespeichert — erster Check-in $days." else "Logged — first check-in $days."
            memory == MemoryRating.Forgot && dueNow -> if (fa) "فراموش شده بود — از امروز دوباره مرورش می‌کنی." else if (de) "Vergessen — ab heute wieder zum Neulernen dran." else "Forgot — it's due again from today to relearn."
            memory == MemoryRating.Forgot -> if (fa) "فراموش شده بود — فردا دوباره مرورش می‌کنی." else if (de) "Vergessen — morgen kommt es zum Neulernen zurück." else "Forgot — it's back tomorrow to relearn."
            memory == MemoryRating.Hard -> if (fa) "جاهای خالی داشت، پس فاصله کوتاه ماند — مرور بعدی $days." else if (de) "Es gab Lücken, also blieb der Abstand kurz — nächste $days." else "There were gaps, so it comes back soon — next $days."
            memory == MemoryRating.Easy -> if (fa) "آسان بود — مرور بعدی $days." else if (de) "Leicht — weiter hinausgeschoben, nächste $days." else "Easy — pushed out, next $days."
            else -> if (fa) "خوب یادت مانده بود — مرور بعدی $days." else if (de) "Gut behalten — nächste $days." else "Remembered well — next $days."
        }
        // When the understanding clock wins, name the memory estimate too. "A bit sooner" alone hid
        // how far apart the two can be — a 100-day memory prediction with a 3-day repair is not
        // "a bit", and the user is entitled to see that their memory is fine and comprehension isn't.
        val memoryEstimate = if (fa) "${com.example.ui.i18n.PersianDate.faDigits(memoryDays)} روز"
            else if (de) "$memoryDays Tage" else "$memoryDays days"
        // Only a repair deadline that actually won the date gets a sentence. A deadline that was
        // backed off past the memory date, or that rounds to the same day, changed nothing the user
        // can see, and saying "sooner" about it would be false.
        val note = if (memory != MemoryRating.Forgot && !firstStudy && repairPending && repairWon) when (understanding) {
            UnderstandingRating.Confused ->
                if (fa) " حافظه‌ات $memoryEstimate دوام می‌آورد، اما چون گیج‌کننده بود زودتر برمی‌گردد."
                else if (de) " Dein Gedächtnis hält $memoryEstimate, aber es kommt früher zurück, weil es verwirrend war."
                else " Your memory should hold for $memoryEstimate, but it returns sooner because it was confusing."
            UnderstandingRating.Partial ->
                if (fa) " حافظه‌ات $memoryEstimate دوام می‌آورد، اما فهم ناقص زودتر برش می‌گرداند."
                else if (de) " Dein Gedächtnis hält $memoryEstimate, aber teilweises Verständnis holt es früher zurück."
                else " Your memory should hold for $memoryEstimate, but partial understanding brings it back sooner."
            else -> ""
        } else ""
        val yield = if (highYield && memory != MemoryRating.Forgot) (if (fa) " (فشرده‌تر چون مهم است.)" else if (de) " (Enger getaktet — es ist wichtig.)" else " (Kept tighter — it's important.)") else ""
        return core + note + yield
    }

    fun undoLastRating() {
        if (isProcessing) return
        val historyItem = ratedStack.removeLastOrNull() ?: return
        isProcessing = true

        viewModelScope.launch {
            try {
                // One transaction: restore the unit AND delete its log together (see undoReview).
                if (!repository.undoReview(historyItem.review)) {
                    // A later review, correction, merge, restore or deferral won. Drop this stale undo
                    // entry without decrementing the session's counts or overwriting the newer work.
                    canUndo = ratedStack.isNotEmpty()
                    lastReason = when (getApplication<android.app.Application>()
                        .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                        .getString("app_language", "en")) {
                        "fa" -> "سابقه یا برنامهٔ این مبحث تغییر کرده است؛ واگرد انجام نشد. اطلاعات جدید حفظ شد."
                        "de" -> "Verlauf oder Termin dieses Themas wurden geändert. Nichts wurde zurückgenommen; die neueren Daten bleiben erhalten."
                        else -> "This topic's history or schedule has changed. Nothing was undone; your newer work is preserved."
                    }
                    return@launch
                }
                com.example.widget.DueWidgetProvider.updateAll(getApplication()) // undo changes the due count

                // Counters adjust only AFTER the undo transaction succeeds (mirror of rateCurrentUnit).
                canUndo = ratedStack.isNotEmpty()
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (historyItem.wasFirstRating) {
                    sessionNew = (sessionNew - 1).coerceAtLeast(0)
                } else when (historyItem.ratingGiven) {
                    MemoryRating.Forgot -> sessionForgot = (sessionForgot - 1).coerceAtLeast(0)
                    MemoryRating.Hard -> sessionHard = (sessionHard - 1).coerceAtLeast(0)
                    MemoryRating.Good, MemoryRating.Easy -> sessionGood = (sessionGood - 1).coerceAtLeast(0)
                }

                val current = _currentUnit.value
                if (current != null) {
                    dueUnits.add(0, current)
                }
                savedReason = null
                // The undone log is gone, so the streak the buttons preview with must be re-read.
                currentUnrepairedStreak = repository.unrepairedStreak(historyItem.review.before.id)
                // The row as undo left it: its schedule from before the rating, its content as it is now, projected
                // for the buttons exactly as advanceUnit does. The snapshot is the STORED row, possibly on an older
                // model, so it is never shown in place of that: a topic that cannot be re-read and projected gives
                // way to the card that was on screen (fail closed, as advanceUnit does).
                val restored = repository.getUnitById(historyItem.review.before.id)
                    ?.takeIf { it.deletedAt == null && !it.archived }
                    ?.let { runCatching { repository.projectOntoCurrentModel(it) }.getOrNull() }
                if (restored != null) _currentUnit.value = restored else advanceUnit()
            } catch (t: Throwable) {
                ratedStack.add(historyItem) // undo failed: keep the history item so Undo stays possible
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Commit a rating for the topic on screen. A review can be anything the learner chose — questions,
     * rereading, a lecture, a video — so the memory rating is their own judgement of how much they still
     * had when they came back to it, never capped by anything the app scored.
     */
    fun rateCurrentUnit(
        memoryRating: MemoryRating,
        understandingRating: UnderstandingRating,
        understandingAsked: Boolean = true,
        // Pilot research data, optional and never scheduled from: how the learner reviewed, and a question
        // score if they entered one. Anything that is not a real count is stored as "not recorded".
        methods: Set<com.example.domain.model.ReviewMethod> = emptySet(),
        questionsCorrect: Int? = null,
        questionsTotal: Int? = null,
        // The learner's rough minutes (StudyMinutes) and the day the review happened (an epoch day; null = today).
        studyMinutes: Int = com.example.domain.model.StudyMinutes.NOT_GIVEN,
        reviewedOnEpochDay: Long? = null,
    ) {
        if (isProcessing) return
        val currentId = _currentUnit.value?.id ?: return
        val lastReviewedAt = _currentUnit.value?.lastReviewedAt
        isProcessing = true

        viewModelScope.launch {
          try {
            val wallClock = System.currentTimeMillis()
            // When the review happened: now, or the chosen earlier day at this hour (after the topic's last review). The
            // schedule counts from it; the wall clock goes in as the time it was saved.
            val now = reviewedOnEpochDay?.let {
                com.example.domain.model.ReviewDay.timeForRating(
                    java.time.LocalDate.ofEpochDay(it), wallClock, lastReviewedAt, java.time.ZoneId.systemDefault(),
                )
            } ?: wallClock
            // The one commit path (MedReviewRepository.rateUnit): reload, project onto the current model,
            // schedule with the same MedScheduler.review() the buttons previewed, and write the row and its
            // log in one transaction. The tests that pin what a rating writes call the same function.
            val rated = repository.rateUnit(
                unitId = currentId,
                now = now,
                memoryRating = memoryRating,
                understandingRating = understandingRating,
                understandingAsked = understandingAsked,
                methods = methods,
                questionsCorrect = questionsCorrect,
                questionsTotal = questionsTotal,
                sessionKind = sessionKind,
                reviewDurationMs = (wallClock - unitShownAt).coerceIn(0L, 30 * 60 * 1000L),
                studyMinutes = studyMinutes,
                loggedAt = wallClock,
            )
            if (rated == null) {
                // The topic was deleted (from the Edit screen, say) while it sat on screen: there is nothing to
                // rate, so move on instead of leaving the session stuck on a card every button ignores.
                advanceUnit()
                return@launch
            }
            val reviewNumber = rated.reviewNumber
            // Session counters update only AFTER the commit succeeds — a failed write must never be
            // counted as a completed review in the session summary.
            sessionCount++
            if (reviewNumber == 0) {
                sessionNew++
            } else when (memoryRating) {
                MemoryRating.Forgot -> sessionForgot++
                MemoryRating.Hard -> sessionHard++
                MemoryRating.Good, MemoryRating.Easy -> sessionGood++
            }
            ratedStack.add(ReviewHistoryItem(rated, memoryRating, wasFirstRating = reviewNumber == 0))
            canUndo = ratedStack.isNotEmpty()
            // (The growth event is inserted inside commitReview's transaction, keyed to the log id,
            // so a committed review and its growth can never disagree — and undo removes both.)
            com.example.widget.DueWidgetProvider.updateAll(getApplication())
            // Both clocks: the memory prediction AND the date actually written to the row, so the
            // message can never announce an interval the schedule did not use. Counted from today, so a review saved for
            // an earlier day says when the topic comes back from now.
            val effectiveIntervalDays = (rated.effectiveDueAt - wallClock) / 86400000.0
            lastReason = buildReasonText(
                memoryRating, understandingRating, rated.before.highYield,
                intervalDays = rated.memoryIntervalDays - (wallClock - now) / 86400000.0,
                effectiveIntervalDays = effectiveIntervalDays,
                firstStudy = reviewNumber == 0,
                repairPending = rated.repairPending,
            )
            savedReason = lastReason

            advanceUnit()
          } catch (t: Throwable) {
            // Persistence failed: nothing was counted, the card stays current, and the user sees why
            // instead of the app silently losing (or worse, crashing over) a review.
            lastReason = if (getApplication<android.app.Application>()
                    .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    .getString("app_language", "en") == "fa"
            ) "ذخیرهٔ این مرور ناموفق بود — مبحث تغییری نکرد. دوباره تلاش کن." else if (getApplication<android.app.Application>()
                    .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    .getString("app_language", "en") == "de"
            ) "Diese Wiederholung konnte nicht gespeichert werden — das Thema ist unverändert. Bitte versuche es erneut."
            else "This review couldn't be saved — the topic is unchanged. Please try again."
          } finally {
            isProcessing = false
          }
        }
    }

    /**
     * "Not today" for the current topic: move it to tomorrow morning WITHOUT logging a review, so the
     * FSRS memory state is untouched. It leaves today's queue and returns tomorrow — or on its own date
     * if that is later: a topic opened early from the Library is never pulled forward.
     */
    fun procrastinateCurrentUnit() {
        if (isProcessing) return
        val currentId = _currentUnit.value?.id ?: return
        isProcessing = true
        viewModelScope.launch {
            try {
                val tomorrow = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 8)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                // One transactional deferral (v5 semantics: deferredUntil set, modelDueAt untouched,
                // audit event atomic with the schedule change).
                repository.procrastinateUnit(currentId, tomorrow)
                com.example.notifications.NotificationScheduler.scheduleDailyReminder(getApplication())
                com.example.widget.DueWidgetProvider.updateAll(getApplication())
                advanceUnit()
            } catch (t: Throwable) {
                // Same contract as rating: a failed write leaves the card in place and tells the user.
                lastReason = if (getApplication<android.app.Application>()
                        .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                        .getString("app_language", "en") == "fa"
                ) "انجام نشد — مبحث تغییری نکرد. دوباره تلاش کن." else if (getApplication<android.app.Application>()
                        .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                        .getString("app_language", "en") == "de"
                ) "Das wurde nicht gespeichert — das Thema ist unverändert. Bitte versuche es erneut."
                else "That didn't save — the topic is unchanged. Please try again."
            } finally {
                isProcessing = false
            }
        }
    }
}

@Composable
fun ReviewSessionScreen(
    repository: MedReviewRepository,
    unitId: Long = -1L,
    /** Today's limit was used up and the learner chose "review more anyway". */
    ignoreLimit: Boolean = false,
    /** "Review ahead": topics not yet due, weakest first. */
    ahead: Boolean = false,
    /** One topic opened from Today: where it was in Today's list (a SessionKind name); null = TOPIC. */
    kind: String? = null,
    onNavigateToEdit: (Long) -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: ReviewViewModel = viewModel(factory = ReviewViewModelFactory(application, repository))

    LaunchedEffect(unitId, ignoreLimit, ahead) {
        viewModel.startSessionOnce(unitId = unitId, ignoreLimit = ignoreLimit, ahead = ahead, kind = kind)
    }
    // Coming back to the card (from the Edit screen, or the app from the background): show the row as it is now.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCurrentUnit()
    }

    val currentUnitState by viewModel.currentUnit.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val strings = com.example.ui.i18n.LocalStrings.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Localized numerals: Persian digits in fa, Latin otherwise.
    val num: (Any) -> String = { if (strings.languageCode == "fa") com.example.ui.i18n.PersianDate.faDigits(it.toString()) else it.toString() }

    // rememberSaveable, not remember: a rotation, a dark-mode toggle, a split-screen resize or a
    // font-size change destroys composition, and with plain remember the learner was thrown back to
    // the first rating step having already answered it. Keyed on the topic's id, so moving to the next topic
    // clears it while a refresh of the same topic (coming back from the Edit screen) keeps it.
    val currentTopicKey = currentUnitState?.id
    var selectedMemory by rememberSaveable(
        currentTopicKey,
        // Saved as the enum NAME (MemoryRating is not Parcelable); an unknown name restores as null
        // rather than throwing, so a bundle written by another build cannot crash the review screen.
        stateSaver = androidx.compose.runtime.saveable.Saver<MemoryRating?, String>(
            save = { it?.name },
            restore = { name -> runCatching { MemoryRating.valueOf(name) }.getOrNull() },
        ),
    ) { mutableStateOf<MemoryRating?>(null) }

    // Optional pilot research data for THIS topic: how the learner reviewed, and a question score. Reset for
    // every topic (a remembered choice would record a method nobody picked), saved across rotation.
    var reviewMethods by rememberSaveable(
        currentTopicKey,
        stateSaver = androidx.compose.runtime.saveable.Saver<Set<com.example.domain.model.ReviewMethod>, String>(
            save = { com.example.domain.model.ReviewMethod.encode(it).orEmpty() },
            restore = { com.example.domain.model.ReviewMethod.decode(it) },
        ),
    ) { mutableStateOf(emptySet<com.example.domain.model.ReviewMethod>()) }
    var questionsRight by rememberSaveable(currentTopicKey) { mutableStateOf("") }
    var questionsTotal by rememberSaveable(currentTopicKey) { mutableStateOf("") }
    // The learner's rough minutes for THIS topic (StudyMinutes.NOT_GIVEN = none chosen) and the day it was reviewed on
    // (null = today, saved as "finished just now"; otherwise an epoch day, ReviewDay). Reset for every topic, like the
    // method row: a remembered choice would record an estimate nobody gave. Saved across rotation.
    var studyMinutes by rememberSaveable(currentTopicKey) { androidx.compose.runtime.mutableIntStateOf(com.example.domain.model.StudyMinutes.NOT_GIVEN) }
    var reviewedOnEpochDay by rememberSaveable(currentTopicKey) { mutableStateOf<Long?>(null) }
    // Only a Questions review carries a score; unticking Questions drops what was typed.
    fun scoreOrNull(field: String): Int? =
        if (com.example.domain.model.ReviewMethod.Questions in reviewMethods) field.trim().toIntOrNull() else null

    // Back steps BACKWARDS through the rating flow and cancels — nothing is committed to the DB until
    // the understanding rating is tapped. So leaving mid-rating (memory chosen, understanding not)
    // never counts as a review. At the first step, back exits the session.
    androidx.activity.compose.BackHandler {
        when {
            selectedMemory != null -> selectedMemory = null   // understanding step -> back to the memory step
            else -> onFinish()                                // memory step -> exit session
        }
    }

    // "Why scheduled?" — the reason line appears briefly after each rating (calm, dismissible),
    // teaching cause → effect so the schedule feels explainable instead of arbitrary.
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(viewModel.lastReason) {
        viewModel.lastReason?.let { reason ->
            // A single topic's end screen says this itself; a warning (a failed save, a refused Undo) still shows here.
            if (viewModel.singleTopic && reason == viewModel.savedReason) {
                viewModel.consumeReason()
                return@LaunchedEffect
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            // consumeReason() MUST come after showSnackbar, not before: lastReason is this effect's
            // key, so clearing it first changed the key, disposed the effect, and cancelled the
            // suspended showSnackbar one frame in — the message never appeared. That silently hid
            // the "this review couldn't be saved" warning, which travels on the same channel.
            snackbarHostState.showSnackbar(reason, duration = androidx.compose.material3.SnackbarDuration.Short)
            viewModel.consumeReason()
        }
    }

    Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }) { padding ->
        val currentUnit = currentUnitState
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // The keyboard (a question score) takes its height off the page instead of covering it; the
                // system-bar padding above is consumed first so the navigation bar is not counted twice.
                .consumeWindowInsets(padding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentUnit == null && viewModel.isLoading) {
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.weight(1f))
            } else if (currentUnit == null) {
                Spacer(modifier = Modifier.weight(1f))
                // One topic: "Saved" and when it comes back. Nothing logged (Not today, or a topic that could not be
                // opened) says so instead of a session summary of none.
                val headline = when {
                    !viewModel.singleTopic -> strings.sessionComplete
                    viewModel.sessionCount > 0 -> when (strings.languageCode) { "fa" -> "ثبت شد"; "de" -> "Gespeichert"; else -> "Saved" }
                    else -> when (strings.languageCode) { "fa" -> "چیزی ثبت نشد"; "de" -> "Nichts gespeichert"; else -> "Nothing logged" }
                }
                Text(headline, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                // Topics whose history could not be replayed were left out rather than scheduled on
                // a model their state was never measured under. Say so: a silently shorter queue is
                // indistinguishable from "nothing was due", and the user would never find out.
                if (viewModel.skippedUnprojectable > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when (strings.languageCode) {
                            "fa" -> "${num(viewModel.skippedUnprojectable)} مبحث به‌خاطر تاریخچهٔ ناخوانا کنار گذاشته شد. یک پشتیبان بگیر و از تنظیمات بازیابی کن."
                            "de" -> "${viewModel.skippedUnprojectable} Thema/Themen wegen unlesbarer Historie übersprungen. Sichere deine Daten und stelle sie in den Einstellungen wieder her."
                            else -> "${viewModel.skippedUnprojectable} topic(s) skipped — their review history could not be read. Export a backup and restore it from Settings."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))

                val reason = viewModel.savedReason
                if (viewModel.singleTopic && reason != null && viewModel.sessionCount > 0) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                } else if (!viewModel.singleTopic) {
                // Expose session stats as an elegant summary card:
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when (strings.languageCode) { "fa" -> "خلاصه جلسه مرور"; "de" -> "Zusammenfassung"; else -> "Session summary" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Reviews only: a first rating's Easy/Medium/Hard says how difficult a fresh study
                            // felt, not how much was remembered, so it is counted on its own. A column with nothing in
                            // it is left out: logging one new topic used to end on "0 · 1 · 0 · 0 · 0".
                            val reviewed = viewModel.sessionCount - viewModel.sessionNew
                            @Composable
                            fun stat(value: Int, label: String, color: androidx.compose.ui.graphics.Color) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = num(value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = color)
                                    Text(text = label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (reviewed > 0 || viewModel.sessionNew == 0) {
                                stat(reviewed, when (strings.languageCode) { "fa" -> "مرور"; "de" -> "Wiederholt"; else -> "Reviewed" }, MaterialTheme.colorScheme.primary)
                            }
                            if (viewModel.sessionNew > 0) {
                                stat(viewModel.sessionNew, when (strings.languageCode) { "fa" -> "مطالعهٔ ثبت‌شده"; "de" -> "Neu erfasst"; else -> "Studies logged" }, MaterialTheme.colorScheme.tertiary)
                            }
                            if (viewModel.sessionGood > 0) {
                                stat(viewModel.sessionGood, when (strings.languageCode) { "fa" -> "آسان/خوب"; "de" -> "Gut/Leicht"; else -> "Good/Easy" }, MaterialTheme.colorScheme.secondary)
                            }
                            if (viewModel.sessionHard > 0) {
                                stat(viewModel.sessionHard, when (strings.languageCode) { "fa" -> "سخت"; "de" -> "Schwer"; else -> "Hard" }, com.example.ui.theme.ratingTone(com.example.domain.model.MemoryRating.Hard).solid)
                            }
                            if (viewModel.sessionForgot > 0) {
                                stat(viewModel.sessionForgot, when (strings.languageCode) { "fa" -> "فراموش شده"; "de" -> "Vergessen"; else -> "Forgot" }, com.example.ui.theme.ratingTone(com.example.domain.model.MemoryRating.Forgot).solid)
                            }
                        }
                    }
                }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onFinish, 
                    modifier = Modifier.fillMaxWidth(0.6f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(percent = 50)
                ) {
                    Text(strings.done, style = MaterialTheme.typography.titleMedium)
                }
                // The last rating of a session can be a slip too. Undo lived only in the card's header, which this
                // summary replaces, so a wrong tap on the last topic could only be fixed from the Edit screen.
                if (viewModel.canUndo) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(enabled = !viewModel.isProcessing, onClick = { viewModel.undoLastRating() }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(when (strings.languageCode) { "fa" -> "واگرد آخرین ارزیابی"; "de" -> "Letzte Bewertung zurücknehmen"; else -> "Undo last rating" })
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            } else {
                val formattedState = strings.stateLabel(currentUnit.state)
                val subject = subjects.find { it.id == currentUnit.subjectId }
                // First rating (logging a study) vs a later review.
                val previewReviewNumber = MedScheduler.effectiveReviewNumber(currentUnit.reviewCount)
                val isFreshFirstStudy = previewReviewNumber == 0
                // One clock for every preview on this screen (the estimates and the understanding buttons).
                val now = System.currentTimeMillis()
                // Reference only: shown with the notes, never scored (KeyPoints).
                val keyPointList = remember(currentUnit.keyPoints) { com.example.domain.srs.KeyPoints.parse(currentUnit.keyPoints) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val isFarsi = strings.languageCode == "fa"
                    TextButton(onClick = onFinish) {
                        Text(strings.done)
                    }
                    
                    // Takes only what Done, Undo and Edit leave: a long subject at a large font size pushed the
                    // pencil off the screen (an outside emulator audit, German at 200%, 2026-09-30).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    ) {
                        if (subject?.colorHex != null) {
                            Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(runCatching { androidx.compose.ui.graphics.Color(subject.colorHex.toColorInt()) }.getOrNull() ?: MaterialTheme.colorScheme.primary))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            // No subject: just the state. studyType is a dormant column ("Topic" for every
                            // topic added since it was retired) and printed English into every language.
                            text = listOfNotNull(subject?.name, formattedState).joinToString(" • ").uppercase(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            // Tracking helps ALL-CAPS Latin labels but breaks Arabic-script letter joining.
                            letterSpacing = if (strings.languageCode == "fa") 0.sp else 1.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewModel.canUndo) {
                            IconButton(enabled = !viewModel.isProcessing, onClick = {
                                viewModel.undoLastRating()
                                selectedMemory = null
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = when (strings.languageCode) { "fa" -> "واگرد آخرین ارزیابی"; "de" -> "Letzte Bewertung zurücknehmen"; else -> "Undo last rating" },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { onNavigateToEdit(currentUnit.id) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = when (strings.languageCode) { "fa" -> "ویرایش مبحث"; "de" -> "Thema bearbeiten"; else -> "Edit topic" }, // not a flashcard app
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                // The topic card and the rating controls share what is left of the screen: the card fills it on a
                // normal phone, and on a small one (or with the keyboard open) the page scrolls instead of
                // squeezing the card to nothing (ReviewCardLayout).
                ReviewCardLayout(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    resetKey = currentUnit.id,
                    card = {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Study-count badge: count the original study too if this topic was back-dated
                            // (studied before it was added), so a back-dated topic's first review reads "2nd study".
                            val studiedBeforeAdded = isEarlierLocalDay(currentUnit.studiedAt, currentUnit.createdAt)
                            val repNum = currentUnit.reviewCount + 1 + (if (studiedBeforeAdded) 1 else 0)
                        
                            val lastDateStr = currentUnit.lastReviewedAt?.let {
                                val diffMs = System.currentTimeMillis() - it
                                val hrs = (diffMs / 3600000).toInt()
                                if (hrs < 1) {
                                    val mins = (diffMs / 60000).toInt()
                                    if (mins < 1) {
                                        when (strings.languageCode) { "fa" -> "همین الان"; "de" -> "gerade eben"; else -> "just now" }
                                    } else {
                                        when (strings.languageCode) { "fa" -> "${num(mins)} دقیقه پیش"; "de" -> "vor ${mins} Min."; else -> "${mins}m ago" }
                                    }
                                } else if (hrs < 24) {
                                    when (strings.languageCode) { "fa" -> "${num(hrs)} ساعت پیش"; "de" -> "vor ${hrs} Std."; else -> "${hrs}h ago" }
                                 } else {
                                    val days = hrs / 24
                                    when (strings.languageCode) { "fa" -> "${num(days)} روز پیش"; "de" -> "vor ${days} Tagen"; else -> "${days}d ago" }
                                }
                            } ?: (when (strings.languageCode) { "fa" -> "هرگز"; "de" -> "Nie"; else -> "Never" })

                            val intervalStr = if (currentUnit.currentIntervalDays <= 0.0) {
                                when (strings.languageCode) { "fa" -> "۰ روز"; "de" -> "0 Tage"; else -> "0 days" }
                            } else {
                                // Rounded like the buttons' figures (it used to truncate: a 77.79-day interval the
                                // button had shown as 77.8 read 77.7 here), without a trailing ".0".
                                val tenths = Math.round(currentUnit.currentIntervalDays * 10) / 10.0
                                val rounded = if (tenths % 1.0 == 0.0) tenths.toLong().toString() else tenths.toString()
                                when (strings.languageCode) { "fa" -> "${num(rounded)} روز"; "de" -> "${rounded.replace('.', ',')} Tage"; else -> "$rounded days" }
                            }

                            // ONE line: which study this is, and the gap and time since the last one. The stage is already in the
                            // header above; two rows here cost the space the scope and notes need on a phone.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isFreshFirstStudy && strings.languageCode == "fa" -> "مطالعه‌ی اول"
                                            isFreshFirstStudy && strings.languageCode == "de" -> "Erstes Lernen"
                                            isFreshFirstStudy -> "First study"
                                            strings.languageCode == "fa" -> "مطالعه‌ی ${num(repNum)}‌اُم"
                                            // German ordinals are just "N." — no irregular suffixes to get wrong.
                                            strings.languageCode == "de" -> "$repNum. Lerneinheit"
                                            else -> "${ordinalEn(repNum)} study"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                if (!isFreshFirstStudy) {
                                    Text(
                                        text = "${when (strings.languageCode) { "fa" -> "فاصله"; "de" -> "Abstand"; else -> "Interval" }}: $intervalStr · " +
                                            "${when (strings.languageCode) { "fa" -> "آخرین"; "de" -> "Zuletzt"; else -> "Last" }}: $lastDateStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = currentUnit.title,
                                style = MaterialTheme.typography.headlineMedium.autoDirection(),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        
                            if (!currentUnit.recallPrompt.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = currentUnit.recallPrompt!!,
                                    style = MaterialTheme.typography.titleMedium.autoDirection(),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        
                            Spacer(modifier = Modifier.height(32.dp))

                            // Split-topic hint: shown only when this topic's recall history whiplashes
                            // (see checkSplitSuggestion). Advisory and dismissible — never automatic.
                            if (viewModel.splitSuggestion) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = when (strings.languageCode) {
                                                "fa" -> "این مبحث شاید بخش‌هایی با سرعت‌های متفاوت داشته باشد. یکجا نگه داشتنش کاملاً درست است، ولی تقسیمش می‌تواند مرورها را کارآمدتر کند."
                                                "de" -> "Dieses Thema enthält womöglich Teile, die sich unterschiedlich schnell festigen. Zusammenlassen ist völlig in Ordnung — Aufteilen kann künftige Wiederholungen effizienter machen."
                                                else -> "This topic may contain parts developing at different rates. Keeping it together is completely fine, but splitting it may make future reviews more efficient."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = { viewModel.dismissSplitSuggestion() }) {
                                                Text(when (strings.languageCode) { "fa" -> "یکجا می‌ماند"; "de" -> "Zusammenlassen"; else -> "Keep as one" })
                                            }
                                            TextButton(onClick = {
                                                viewModel.dismissSplitSuggestion()
                                                onNavigateToEdit(currentUnit.id)
                                            }) {
                                                Text(when (strings.languageCode) { "fa" -> "تقسیم مبحث"; "de" -> "Thema aufteilen"; else -> "Split topic" })
                                            }
                                        }
                                    }
                                }
                            }

                            // The topic's own material, always visible. A review is whatever the learner
                            // chooses — questions, rereading, a lecture, a video — so nothing is hidden
                            // behind a reveal step: this is reference, and the rating below is their own
                            // judgement of how much they still had.
                            if (keyPointList.isNotEmpty()) {
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(12.dp))
                                keyPointList.forEach { point ->
                                    Text(
                                        text = "• $point",
                                        style = MaterialTheme.typography.bodyLarge.autoDirection(),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                            if (!currentUnit.notes.isNullOrBlank()) {
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(16.dp))
                                // Web addresses in the notes open when tapped (a Notion page, a video, a question block).
                                val linkColor = MaterialTheme.colorScheme.primary
                                val notesText = currentUnit.notes!!
                                Text(
                                    text = remember(notesText, linkColor) { com.example.ui.components.NoteLinks.annotate(notesText, linkColor) },
                                    style = MaterialTheme.typography.bodyLarge.autoDirection(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (!currentUnit.source.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                val src = currentUnit.source!!.trim()
                                // Bare domains ("wikipedia.org/...") open too — normalized to https.
                                // A source with spaces is prose (book/page), not a link.
                                val openUrl = when {
                                    src.startsWith("http://") || src.startsWith("https://") -> src
                                    "." in src && " " !in src -> "https://$src"
                                    else -> null
                                }
                                Text(
                                    text = src,
                                    style = MaterialTheme.typography.bodyMedium.autoDirection(),
                                    color = if (openUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = if (openUrl != null) {
                                        Modifier.fillMaxWidth().clickable { runCatching { uriHandler.openUri(openUrl) } }
                                    } else {
                                        Modifier.fillMaxWidth()
                                    }
                                )
                            }
                        }
                    }
                    },
                    controls = {
                    if (isFreshFirstStudy && selectedMemory == null) {
                        // FIRST RATING: the learner just studied this — rate how difficult the topic was.
                        Text(when (strings.languageCode) { "fa" -> "این مبحث چقدر سخت بود؟"; "de" -> "Wie schwer war dieses Thema?"; else -> "How difficult was this topic?" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            // The schedule counts from the moment of this rating, so rating right after
                            // studying keeps the first review honest; say so.
                            when (strings.languageCode) {
                                "fa" -> "اولین مرورت از همین لحظه زمان‌بندی می‌شود؛ پس بهتر است همان روزی که خواندی ثبتش کنی."
                                "de" -> "Die erste Wiederholung wird ab jetzt geplant — am besten also am Lerntag selbst bewerten."
                                else -> "Your first review is scheduled from this moment, so rate it on the day you studied it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Difficulty maps to the FSRS initial grade: an easy topic gets a longer first gap.
                            // Same warm→cool ramp as recall: Easy = steel (cool), Medium = sage, Hard = ochre.
                            listOf(MemoryRating.Easy, MemoryRating.Good, MemoryRating.Hard).forEach { rating ->
                                val tone = com.example.ui.theme.ratingTone(rating)
                                Button(
                                    onClick = { selectedMemory = rating },
                                    enabled = !viewModel.isProcessing,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = tone.container,
                                        contentColor = tone.onContainer,
                                    ),
                                    modifier = Modifier.weight(1f).padding(4.dp).heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    // The first check-in this answer leads to (with Clear understanding): the learner sees
                                    // what each answer means for the schedule before choosing it.
                                    val estimate = previewReturnDays(currentUnit, now, rating, UnderstandingRating.Clear, viewModel.currentUnrepairedStreak)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            when (rating) {
                                                MemoryRating.Easy -> when (strings.languageCode) { "fa" -> "آسان"; "de" -> "Leicht"; else -> "Easy" }
                                                MemoryRating.Good -> when (strings.languageCode) { "fa" -> "متوسط"; "de" -> "Mittel"; else -> "Medium" }
                                                else -> when (strings.languageCode) { "fa" -> "سخت"; "de" -> "Schwer"; else -> "Hard" }
                                            },
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Text(
                                            estimateLabel(estimate, strings.languageCode, exact = false),
                                            style = MaterialTheme.typography.labelSmall,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    } else if (selectedMemory == null) {
                        // A REVIEW. The learner reviews however they like, in or out of the app; what the
                        // memory model needs is how much of the topic they still had when they came back to
                        // it — FSRS's recall outcome — not how the review session felt afterwards.
                        ReviewMethodPicker(
                            languageCode = strings.languageCode,
                            selected = reviewMethods,
                            onToggle = { m -> reviewMethods = if (m in reviewMethods) reviewMethods - m else reviewMethods + m },
                            right = questionsRight,
                            onRight = { questionsRight = it.filter(Char::isDigit).take(3) },
                            total = questionsTotal,
                            onTotal = { questionsTotal = it.filter(Char::isDigit).take(3) },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(strings.memoryQuestion, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text(
                            strings.memoryQuestionHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // One full-width button per rating, each saying what it means, so the choice is made
                        // against a description rather than a single word.
                        MemoryRating.entries.reversed().forEach { rating ->
                            // Warm→cool rating ramp; "Forgot" is calm sienna, never alarm-red.
                            val tone = com.example.ui.theme.ratingTone(rating)
                            Button(
                                // Every answer, Forgot included, goes on to the understanding question (the owner's
                                // decision, 2026-10-09). After Forgot the date is tomorrow whatever the answer, but the
                                // log then tells "forgot, clear now" from "forgot and still confused".
                                onClick = { selectedMemory = rating },
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tone.container,
                                    contentColor = tone.onContainer,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // The rating, and under it roughly when this answer brings the topic back.
                                    val estimate = previewReturnDays(
                                        currentUnit, now, rating,
                                        if (rating == MemoryRating.Forgot) UnderstandingRating.Partial else UnderstandingRating.Clear,
                                        viewModel.currentUnrepairedStreak,
                                    )
                                    Column(modifier = Modifier.width(88.dp)) {
                                        Text(
                                            when (rating) {
                                                MemoryRating.Forgot -> strings.ratingFail
                                                MemoryRating.Hard -> strings.ratingHard
                                                MemoryRating.Good -> strings.ratingGood
                                                MemoryRating.Easy -> strings.ratingEasy
                                            },
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            estimateLabel(estimate, strings.languageCode, exact = rating == MemoryRating.Forgot),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Text(
                                        when (rating) {
                                            MemoryRating.Forgot -> strings.ratingFailMeaning
                                            MemoryRating.Hard -> strings.ratingHardMeaning
                                            MemoryRating.Good -> strings.ratingGoodMeaning
                                            MemoryRating.Easy -> strings.ratingEasyMeaning
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.procrastinateCurrentUnit()
                                selectedMemory = null
                            },
                            enabled = !viewModel.isProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.notToday)
                        }
                    } else {
                        // Before the last answer, two optional things about the review itself, for a first study and a
                        // review alike: the day it happened (today unless the learner says otherwise) and about how long
                        // it took. Neither is ever required (the owner's decisions, 2026-10-09).
                        val zone = java.time.ZoneId.systemDefault()
                        val reviewTime = reviewedOnEpochDay?.let {
                            com.example.domain.model.ReviewDay.timeForRating(java.time.LocalDate.ofEpochDay(it), now, currentUnit.lastReviewedAt, zone)
                        } ?: now
                        ReviewDayChip(
                            languageCode = strings.languageCode,
                            firstStudy = isFreshFirstStudy,
                            chosenEpochDay = reviewedOnEpochDay,
                            earliest = com.example.domain.model.ReviewDay.earliestForRating(currentUnit.lastReviewedAt, currentUnit.studiedAt, now, zone),
                            today = com.example.domain.model.ReviewDay.day(now, zone),
                            useJalali = com.example.ui.i18n.LocalUseJalali.current,
                            onChoose = { reviewedOnEpochDay = it },
                        )
                        StudyMinutesPicker(
                            languageCode = strings.languageCode,
                            selected = studyMinutes,
                            onSelect = { studyMinutes = it },
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            // Asked after a first study and after a review alike, Forgot included: understanding is about now.
                            strings.understandingNowQuestion,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val chosenMemory = selectedMemory ?: MemoryRating.Good
                            UnderstandingRating.entries.forEach { rating ->
                                // Both answers are known here, so this is exactly when the topic comes back: the
                                // earlier of the memory date and the repair deadline, with the commit's own fuzz, from
                                // the time the review is saved with. Shown counted from today: a review saved for an
                                // earlier day comes back that much sooner, or is due now.
                                val effectiveInterval = previewReturnDays(currentUnit, reviewTime, chosenMemory, rating, viewModel.currentUnrepairedStreak)
                                val fromToday = effectiveInterval - (now - reviewTime) / 86_400_000.0
                                val intervalStr = if (fromToday < 0.5) {
                                    when (strings.languageCode) { "fa" -> "امروز"; "de" -> "heute"; else -> "today" }
                                } else localizedIntervalLabel(fromToday, strings.languageCode)
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        // Read at tap time, not composition time: a Back gesture in the same
                                        // frame clears the choice before this button is gone, and `!!` crashed.
                                        selectedMemory?.let { chosen ->
                                            viewModel.rateCurrentUnit(
                                                chosen, rating,
                                                methods = reviewMethods,
                                                questionsCorrect = scoreOrNull(questionsRight),
                                                questionsTotal = scoreOrNull(questionsTotal),
                                                studyMinutes = studyMinutes,
                                                reviewedOnEpochDay = reviewedOnEpochDay,
                                            )
                                        }
                                    },
                                    enabled = !viewModel.isProcessing,
                                    modifier = Modifier.weight(1f).padding(4.dp).heightIn(min = 56.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        "${
                                            when(rating) {
                                                UnderstandingRating.Confused -> strings.urConfused
                                                UnderstandingRating.Partial -> strings.urPartial
                                                UnderstandingRating.Clear -> strings.urClear
                                            }
                                        }\n$intervalStr",
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                    },
                )
            }
        }
    }
}


/**
 * The topic card above the rating controls, sharing the space below the header.
 *
 * On a normal phone the controls sit at the bottom and the card fills everything above them, exactly as a
 * weighted card would. The controls grew when a review became "any method" (the optional method row, a
 * question score, a hint, one full-width button per rating), and with the card simply weighted, a small
 * phone, Persian's larger type or the keyboard open for a score squeezed the card to nothing: the learner
 * could not see which topic they were rating. So the card never gets less than [minCardHeight]; when that
 * does not fit, the page scrolls instead, and every control stays reachable.
 */
@Composable
private fun ReviewCardLayout(
    modifier: Modifier,
    /** The topic on screen: a new one starts at the top of the page. */
    resetKey: Any?,
    card: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    minCardHeight: Dp = 200.dp,
    gap: Dp = 16.dp,
) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        val scroll = remember(resetKey) { ScrollState(0) }
        Layout(
            contents = listOf(
                card,
                { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = controls) },
            ),
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
        ) { (cardParts, controlParts), c ->
            val width = c.maxWidth
            val gapPx = gap.roundToPx()
            val controlsPlaced = controlParts.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val controlsHeight = controlsPlaced.sumOf { it.height }
            val room = if (viewport == Constraints.Infinity) 0 else viewport - controlsHeight - gapPx
            val cardHeight = maxOf(minCardHeight.roundToPx(), room)
            val cardPlaced = cardParts.map { it.measure(Constraints.fixed(width, cardHeight)) }
            layout(width, cardHeight + gapPx + controlsHeight) {
                cardPlaced.forEach { it.place(0, 0) }
                var y = cardHeight + gapPx
                controlsPlaced.forEach { it.place(0, y); y += it.height }
            }
        }
    }
}

/**
 * "Reviewed: today ▾": the day the review (or the first study) happened (the owner's decision, 2026-10-09). The rating is
 * saved as finished just now unless the learner says it was yesterday or an earlier day, back to the topic's previous
 * review ([com.example.domain.model.ReviewDay]). A week at most here; an older slip is corrected from the topic's history.
 */
@Composable
private fun ReviewDayChip(
    languageCode: String,
    firstStudy: Boolean,
    chosenEpochDay: Long?,
    earliest: java.time.LocalDate,
    today: java.time.LocalDate,
    useJalali: Boolean,
    onChoose: (Long?) -> Unit,
) {
    val fa = languageCode == "fa"
    val de = languageCode == "de"
    var open by remember { mutableStateOf(false) }
    fun label(day: java.time.LocalDate): String = when (java.time.temporal.ChronoUnit.DAYS.between(day, today)) {
        0L -> if (fa) "امروز" else if (de) "heute" else "today"
        1L -> if (fa) "دیروز" else if (de) "gestern" else "yesterday"
        else -> com.example.ui.i18n.AppDate.weekdayDate(
            useJalali, day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), fa,
        )
    }
    val chosen = chosenEpochDay?.let { java.time.LocalDate.ofEpochDay(it) } ?: today
    val prefix = when {
        firstStudy -> if (fa) "مطالعه شده:" else if (de) "Gelernt:" else "Studied:"
        else -> if (fa) "مرور شده:" else if (de) "Wiederholt:" else "Reviewed:"
    }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.AssistChip(
            onClick = { open = true },
            label = { Text("$prefix ${label(chosen)}") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var day = today
            var shown = 0
            while (day >= earliest && shown < 7) {
                val option = day
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { onChoose(if (option == today) null else option.toEpochDay()); open = false },
                )
                day = day.minusDays(1)
                shown++
            }
        }
    }
}

/**
 * "About how long? (optional)": the learner's rough minutes for this review or first study
 * ([com.example.domain.model.StudyMinutes]). None chosen means not known, never zero; a second tap clears a choice. Nothing
 * schedules from it.
 */
@Composable
private fun StudyMinutesPicker(
    languageCode: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val fa = languageCode == "fa"
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (languageCode) { "fa" -> "حدوداً چقدر طول کشید؟ (اختیاری)"; "de" -> "Ungefähr wie lange? (optional)"; else -> "About how long? (optional)" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            com.example.domain.model.StudyMinutes.CHOICES.forEach { m ->
                val number = if (m == com.example.domain.model.StudyMinutes.CHOICES.last()) "$m+" else "$m"
                val shown = if (fa) com.example.ui.i18n.PersianDate.faDigits(number) else number
                FilterChip(
                    selected = selected == m,
                    onClick = { onSelect(if (selected == m) com.example.domain.model.StudyMinutes.NOT_GIVEN else m) },
                    label = { Text(when (languageCode) { "fa" -> "$shown دقیقه"; "de" -> "$shown Min."; else -> "$shown min" }) },
                )
            }
        }
    }
}

/**
 * "How did you review? (optional)": how the learner reviewed this topic, and a question score if they did
 * questions. Pilot research data only. The rating does not depend on it and nothing schedules from it; it
 * is what lets the logs later say whether one way of reviewing holds up better than another.
 */
@Composable
private fun ReviewMethodPicker(
    languageCode: String,
    selected: Set<com.example.domain.model.ReviewMethod>,
    onToggle: (com.example.domain.model.ReviewMethod) -> Unit,
    right: String,
    onRight: (String) -> Unit,
    total: String,
    onTotal: (String) -> Unit,
) {
    fun label(m: com.example.domain.model.ReviewMethod): String = when (m) {
        com.example.domain.model.ReviewMethod.Questions -> when (languageCode) { "fa" -> "تست و سؤال"; "de" -> "Fragen"; else -> "Questions" }
        com.example.domain.model.ReviewMethod.Reading -> when (languageCode) { "fa" -> "خواندن"; "de" -> "Lesen"; else -> "Reading" }
        // Short enough that all four fit one row on a phone; a lecture here includes a recorded one or a video.
        com.example.domain.model.ReviewMethod.Lecture -> when (languageCode) { "fa" -> "کلاس/ویدیو"; "de" -> "Vorlesung"; else -> "Lecture" }
        com.example.domain.model.ReviewMethod.Other -> when (languageCode) { "fa" -> "سایر"; "de" -> "Anders"; else -> "Other" }
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (languageCode) { "fa" -> "چطور مرور کردی؟ (اختیاری)"; "de" -> "Wie hast du wiederholt? (optional)"; else -> "How did you review? (optional)" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        // Wraps instead of scrolling: on a phone the fourth chip was cut off at the edge, and an option
        // that looks clipped reads as not being there.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            com.example.domain.model.ReviewMethod.entries.forEach { m ->
                FilterChip(
                    selected = m in selected,
                    onClick = { onToggle(m) },
                    label = { Text(label(m)) },
                )
            }
        }
        if (com.example.domain.model.ReviewMethod.Questions in selected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                // The keyboard's action key moves from "right" to "total", then closes the keyboard: without it the
                // key did nothing on a phone, and the learner had to reach for the second field and then press Back.
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                OutlinedTextField(
                    value = right, onValueChange = onRight, singleLine = true,
                    label = { Text(when (languageCode) { "fa" -> "درست"; "de" -> "richtig"; else -> "right" }) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onNext = { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) },
                    ),
                    modifier = Modifier.width(96.dp),
                )
                Text(when (languageCode) { "fa" -> "از"; "de" -> "von"; else -> "out of" })
                OutlinedTextField(
                    value = total, onValueChange = onTotal, singleLine = true,
                    label = { Text(when (languageCode) { "fa" -> "کل"; "de" -> "gesamt"; else -> "total" }) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier.width(96.dp),
                )
            }
            // A score that is not a real count is not stored, and the rating still goes ahead. Say so here: it used to
            // vanish without a word ("9 out of 5" logged nothing; an outside emulator audit, 2026-09-30). Persian digits
            // are fine — toIntOrNull reads any Unicode decimal digit.
            val problem = com.example.domain.model.QuestionScore.problem(right.trim().toIntOrNull(), total.trim().toIntOrNull())
            if (problem != null) {
                Text(
                    text = when (problem) {
                        com.example.domain.model.QuestionScore.Problem.INCOMPLETE -> when (languageCode) {
                            "fa" -> "برای ثبت نمره، هر دو عدد را بنویس."
                            "de" -> "Gib beide Zahlen ein, damit die Punktzahl gespeichert wird."
                            else -> "Enter both numbers to keep this score."
                        }
                        com.example.domain.model.QuestionScore.Problem.BAD_TOTAL -> when (languageCode) {
                            "fa" -> "کل سؤال‌ها باید بین ۱ و ۹۹۹ باشد؛ این نمره ثبت نمی‌شود."
                            "de" -> "Die Gesamtzahl muss zwischen 1 und 999 liegen – diese Punktzahl wird nicht gespeichert."
                            else -> "The total must be between 1 and 999, so this score won't be kept."
                        }
                        com.example.domain.model.QuestionScore.Problem.MORE_RIGHT_THAN_ASKED -> when (languageCode) {
                            "fa" -> "تعداد درست‌ها از کل بیشتر است؛ این نمره ثبت نمی‌شود."
                            "de" -> "Mehr richtig als gesamt – diese Punktzahl wird nicht gespeichert."
                            else -> "More right than the total, so this score won't be kept."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = com.example.ui.theme.overdueTone().main, // a calm caution: red is for destructive actions
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

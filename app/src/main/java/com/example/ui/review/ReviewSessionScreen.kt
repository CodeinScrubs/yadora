package com.example.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.example.ui.i18n.autoDirection
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Undo
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
    data class ReviewHistoryItem(val unitBeforeRating: StudyUnitEntity, val logId: Long, val ratingGiven: MemoryRating)
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

    fun startSessionOnce(cutoffTime: Long, unitId: Long = -1L) {
        if (sessionStarted) return
        sessionStarted = true
        loadNext(cutoffTime, unitId)
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

    // When the current card appeared — the review's duration (shown → rated) is a research signal
    // (optimal-retention computation needs per-review time). Capped so a phone left open overnight
    // doesn't record a 9-hour "review".
    private var unitShownAt = System.currentTimeMillis()

    // Split-topic hint: true when the CURRENT topic's recall history whiplashes (strong↔Forgot
    // reversals), which usually means it bundles parts developing at different rates. Advisory only —
    // Yadora never splits automatically, and a dismissal suppresses it for 5 more reviews (via prefs).
    var splitSuggestion by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun dismissSplitSuggestion() {
        val unit = _currentUnit.value ?: return
        getApplication<android.app.Application>()
            .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
            .edit().putInt("split_dismiss_${unit.id}", unit.reviewCount).apply()
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
                    .sortedWith(compareBy({ it.reviewedAt }, { it.id }))
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

    fun loadNext(cutoffTime: Long, unitId: Long = -1L) {
        viewModelScope.launch {
            if (dueUnits.isEmpty() && _currentUnit.value == null) {
                if (unitId != -1L) {
                    val unit = repository.getUnitById(unitId)
                    if (unit != null) {
                        dueUnits.clear()
                        dueUnits.add(unit)
                        advanceUnit()
                    }
                } else {
                    val sharedPrefs = getApplication<android.app.Application>().getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    val limit = MedScheduler.safeDailyLimit(sharedPrefs.getFloat("daily_review_limit", 50f).toInt())
                    val now = System.currentTimeMillis()
                    val units = repository.getDueUnits(cutoffTime).first()
                    dueUnits.clear()
                    // Priority order so the most important items survive the daily cap, not just the
                    // earliest-due ones: high-yield, weak/relearn, lapses, and how overdue they are.
                    val prioritized = units.sortedByDescending { u ->
                        com.example.domain.srs.MedScheduler.priorityScore(u.highYield, u.state, u.lapseCount, u.nextReviewAt, now)
                    }
                    dueUnits.addAll(prioritized.take(limit))
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
            val next = dueUnits.removeAt(0)
            // FAIL CLOSED. The old fallback here was getOrDefault(next), which on a projection
            // failure showed the RAW row -- an FSRS-5 stability behind a preview that computes
            // FSRS-6 intervals, which is precisely the preview-vs-commit mismatch this projection
            // exists to prevent. A topic whose history cannot be replayed has unreadable data; the
            // honest response is to leave it out of the session, not to schedule it on a curve its
            // state was never measured under.
            val projected = runCatching { repository.projectOntoCurrentModel(next) }.getOrElse {
                android.util.Log.w("Yadora", "skipping topic ${next.id}: projection failed", it)
                skippedUnprojectable++
                null
            }
            if (projected != null) { shown = projected; break }
        }
        _currentUnit.value = shown
        unitShownAt = System.currentTimeMillis()
        _currentUnit.value?.let { checkSplitSuggestion(it) } ?: run { splitSuggestion = false }
    }

    /** Localized cause → effect line for the just-committed rating ("why is it scheduled there?"). */
    /**
     * The plain-language "why this date".
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
        val days = if (fa) "${com.example.ui.i18n.PersianDate.faDigits(d)} روز دیگر" else if (de) (if (d <= 1) "in 1 Tag" else "in $d Tagen") else if (d <= 1) "in 1 day" else "in $d days"
        val core = when {
            firstStudy -> if (fa) "ثبت شد — اولین مرور $days." else if (de) "Gespeichert — erster Check-in $days." else "Logged — first check-in $days."
            memory == MemoryRating.Forgot -> if (fa) "فراموش شده بود — فردا دوباره مرورش می‌کنی." else if (de) "Vergessen — morgen kommt es zum Neulernen zurück." else "Forgot — it's back tomorrow to relearn."
            memory == MemoryRating.Hard -> if (fa) "سخت بود، پس فاصله کوتاه ماند — مرور بعدی $days." else if (de) "Es war schwer, also blieb der Abstand kurz — nächste $days." else "It felt hard, so the gap stayed short — next $days."
            memory == MemoryRating.Easy -> if (fa) "آسان بود — مرور بعدی $days." else if (de) "Leicht — weiter hinausgeschoben, nächste $days." else "Easy — pushed out, next $days."
            else -> if (fa) "خوب به یاد آوردی — مرور بعدی $days." else if (de) "Gut erinnert — nächste $days." else "Recalled well — next $days."
        }
        // When the understanding clock wins, name the memory estimate too. "A bit sooner" alone hid
        // how far apart the two can be — a 100-day memory prediction with a 3-day repair is not
        // "a bit", and the user is entitled to see that their memory is fine and comprehension isn't.
        val memoryEstimate = if (fa) "${com.example.ui.i18n.PersianDate.faDigits(memoryDays)} روز"
            else if (de) "$memoryDays Tage" else "$memoryDays days"
        val note = if (memory != MemoryRating.Forgot && !firstStudy) when {
            understanding == UnderstandingRating.Confused && repairWon ->
                if (fa) " حافظه‌ات $memoryEstimate دوام می‌آورد، اما چون گیج‌کننده بود زودتر برمی‌گردد."
                else if (de) " Dein Gedächtnis hält $memoryEstimate, aber es kommt früher zurück, weil es verwirrend war."
                else " Your memory should hold for $memoryEstimate, but it returns sooner because it was confusing."
            understanding == UnderstandingRating.Partial && repairWon ->
                if (fa) " حافظه‌ات $memoryEstimate دوام می‌آورد، اما فهم ناقص زودتر برش می‌گرداند."
                else if (de) " Dein Gedächtnis hält $memoryEstimate, aber teilweises Verständnis holt es früher zurück."
                else " Your memory should hold for $memoryEstimate, but partial understanding brings it back sooner."
            understanding == UnderstandingRating.Confused ->
                if (fa) " چون گیج‌کننده بود، کمی زودتر." else if (de) " Etwas früher, weil es verwirrend war." else " A bit sooner because it was confusing."
            understanding == UnderstandingRating.Partial ->
                if (fa) " چون فهم ناقص بود، کمی زودتر." else if (de) " Etwas früher wegen teilweisem Verständnis." else " Slightly sooner for partial understanding."
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
                repository.undoReview(historyItem.unitBeforeRating, historyItem.logId)
                com.example.widget.DueWidgetProvider.updateAll(getApplication()) // undo changes the due count

                // Counters adjust only AFTER the undo transaction succeeds (mirror of rateCurrentUnit).
                canUndo = ratedStack.isNotEmpty()
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                when (historyItem.ratingGiven) {
                    MemoryRating.Forgot -> sessionForgot = (sessionForgot - 1).coerceAtLeast(0)
                    MemoryRating.Hard -> sessionHard = (sessionHard - 1).coerceAtLeast(0)
                    MemoryRating.Good, MemoryRating.Easy -> sessionGood = (sessionGood - 1).coerceAtLeast(0)
                }

                val current = _currentUnit.value
                if (current != null) {
                    dueUnits.add(0, current)
                }
                _currentUnit.value = historyItem.unitBeforeRating
            } catch (t: Throwable) {
                ratedStack.add(historyItem) // undo failed: keep the history item so Undo stays possible
            } finally {
                isProcessing = false
            }
        }
    }

    fun rateCurrentUnit(memoryRating: MemoryRating, understandingRating: UnderstandingRating, understandingAsked: Boolean = true) {
        if (isProcessing) return
        val currentId = _currentUnit.value?.id ?: return
        isProcessing = true

        viewModelScope.launch {
          try {
            // Reload from the DB so edits made on the Edit screen aren't clobbered by a stale copy.
            val loaded = repository.getUnitById(currentId) ?: return@launch
            // Carry the topic onto the current memory model BEFORE anything schedules from it. An
            // FSRS-5 stability is not an FSRS-6 stability, so the state is rebuilt by replaying this
            // topic's real rating history through FSRS-6. No-op once it is already on the new model,
            // and it never touches the dates — only the latent state moves.
            val unit = repository.projectOntoCurrentModel(loaded)

            val now = System.currentTimeMillis()
            // Clamped: a future-dated topic reviewed early would otherwise log NEGATIVE elapsed days
            // (the FSRS math clamps internally, but the log/export data must stay clean too).
            // Measured as the CURRENT model counts time (whole local calendar days for FSRS-6), so
            // a topic offered by today's queue is credited with the day the learner actually waited
            // rather than with the clock difference from whatever hour they last reviewed at.
            val elapsedDays = MedScheduler.modelElapsedDays(
                unit.lastReviewedAt ?: unit.studiedAt, now, MedScheduler.CURRENT_MODEL,
            )

            // The first graded rating is always review #0 (seeded from the rating, capped by the
            // first-study window) no matter how late it happens. Same rule as the replay path.
            val reviewNumber = MedScheduler.effectiveReviewNumber(unit.reviewCount)

            // Single source of truth: the same MedScheduler.review() that powers the button preview.
            val outcome = MedScheduler.review(
                stability = unit.stability,
                difficulty = unit.difficulty,
                elapsedDays = elapsedDays,
                memoryRating = memoryRating,
                understanding = understandingRating,
                highYield = unit.highYield,
                reviewNumber = reviewNumber,
                model = MedScheduler.CURRENT_MODEL,
            )

            // Deterministic ±5% fuzz (seeded by unit + prior review count) de-clumps cohorts; same
            // value the preview buttons showed, and the same value the history replay will recompute.
            val nextInterval = MedScheduler.fuzzedInterval(
                outcome.intervalDays, outcome.baseIntervalDays, unit.id, unit.reviewCount,
                isFirstStudy = reviewNumber == 0,
            )
            val newReviewCount = unit.reviewCount + 1
            val nextState = MedScheduler.masteryState(
                stability = outcome.state.stability,
                justForgot = memoryRating == MemoryRating.Forgot,
            )

            // TWO CLOCKS (DB v6). The memory model's date is preserved exactly in modelDueAt; a weak
            // understanding adds a SHORT repair deadline instead of scaling that prediction down. The
            // topic surfaces on whichever comes first, so a 250-day memory prediction with Partial
            // understanding is still a 250-day prediction — the user just sees it again in 4 days.
            val memoryDueAt = now + (nextInterval * 86400000).toLong()
            val understandingDueAt = outcome.remediationDays?.let { now + (it * 86400000).toLong() }
            val effectiveDueAt = listOfNotNull(memoryDueAt, understandingDueAt).min()

            val updatedUnit = unit.copy(
                lastReviewedAt = now,
                nextReviewAt = effectiveDueAt,
                // A real review resets the honest-scheduling pair (DB v5): the model's date IS the
                // effective date again, and any earlier user deferral is spent.
                modelDueAt = memoryDueAt,
                understandingDueAt = understandingDueAt,
                memoryModel = MedScheduler.CURRENT_MODEL.id,
                deferredUntil = null,
                currentIntervalDays = nextInterval,
                reviewCount = newReviewCount,
                lapseCount = if (memoryRating == MemoryRating.Forgot) unit.lapseCount + 1 else unit.lapseCount,
                state = nextState.name,
                difficulty = outcome.state.difficulty,
                stability = outcome.state.stability,
                retrievability = outcome.retrievabilityAtReview,
                updatedAt = now
            )

            val log = com.example.data.local.entity.ReviewLogEntity(
                studyUnitId = unit.id,
                reviewedAt = now,
                memoryRating = memoryRating.name,
                // Data honesty: when the understanding question was skipped (the Forgot fast-commit),
                // record that it was never asked instead of fabricating an answer the user never gave.
                understandingRating = if (understandingAsked) understandingRating.name else "NotAsked",
                previousIntervalDays = unit.currentIntervalDays,
                nextIntervalDays = nextInterval,
                previousState = unit.state,
                nextState = nextState.name,
                retrievabilityAtReview = outcome.retrievabilityAtReview,
                elapsedDays = elapsedDays,
                // FIRST_STUDY rows carry a difficulty answer, not a recall grade — tagged so exports
                // and calibration never mix the two signals.
                logType = if (reviewNumber == 0) "FIRST_STUDY" else "RECALL",
                // Per-review context (v4): what the user actually chose + the settings in force —
                // the data future weight-tuning can't backfill.
                initialDifficulty = if (reviewNumber == 0) MedScheduler.difficultyLabelFor(memoryRating) else null,
                reviewDurationMs = (now - unitShownAt).coerceIn(0L, 30 * 60 * 1000L),
                wasImportantAtReview = if (unit.highYield) 1 else 0,
                desiredRetentionAtReview = MedScheduler.effectiveRetention(unit.highYield),
                schedulerVersion = MedScheduler.SCHEDULER_VERSION,
                // v5 policy snapshot: which Yadora policy bundle + which understanding factor actually
                // shaped this interval — so future policy changes replay history faithfully.
                schedulerPolicyVersion = MedScheduler.POLICY_VERSION,
                // -1.0 is the entity's "not recorded" sentinel. Storing Partial's 0.9 here because
                // the fast Forgot path passes Partial as a placeholder would put a factor in the
                // research export that the user never actually selected.
                understandingFactorAtReview =
                    if (understandingAsked) MedScheduler.understandingFactor(understandingRating) else -1.0,
            )
            // Update the unit's schedule AND insert its log atomically (one Room transaction), then
            // remember the exact log id so Undo deletes precisely this log.
            val logId = repository.commitReview(updatedUnit, log)
            // Session counters update only AFTER the commit succeeds — a failed write must never be
            // counted as a completed review in the session summary.
            sessionCount++
            when (memoryRating) {
                MemoryRating.Forgot -> sessionForgot++
                MemoryRating.Hard -> sessionHard++
                MemoryRating.Good, MemoryRating.Easy -> sessionGood++
            }
            ratedStack.add(ReviewHistoryItem(unit.copy(), logId, memoryRating))
            canUndo = ratedStack.isNotEmpty()
            // (The growth event is inserted inside commitReview's transaction, keyed to the log id,
            // so a committed review and its growth can never disagree — and undo removes both.)
            com.example.widget.DueWidgetProvider.updateAll(getApplication())
            // Both clocks: the memory prediction AND the date actually written to the row, so the
            // message can never announce an interval the schedule did not use.
            val effectiveIntervalDays = (effectiveDueAt - now) / 86400000.0
            lastReason = buildReasonText(
                memoryRating, understandingRating, unit.highYield,
                intervalDays = nextInterval,
                effectiveIntervalDays = effectiveIntervalDays,
                firstStudy = reviewNumber == 0,
            )

            advanceUnit()
          } catch (t: Throwable) {
            // Persistence failed: nothing was counted, the card stays current, and the user sees why
            // instead of the app silently losing (or worse, crashing over) a review.
            lastReason = if (getApplication<android.app.Application>()
                    .getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    .getString("app_language", "en") == "fa"
            ) "ذخیرهٔ این مرور ناموفق بود — مبحث تغییری نکرد. دوباره تلاش کن." else "This review couldn't be saved — the topic is unchanged. Please try again."
          } finally {
            isProcessing = false
          }
        }
    }

    /**
     * "Not today" for the current topic: move it to tomorrow morning WITHOUT logging a review, so the
     * FSRS memory state is untouched. It leaves today's queue and returns tomorrow.
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
                ) "انجام نشد — مبحث تغییری نکرد. دوباره تلاش کن." else "That didn't save — the topic is unchanged. Please try again."
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
    onNavigateToEdit: (Long) -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: ReviewViewModel = viewModel(factory = ReviewViewModelFactory(application, repository))
    
    LaunchedEffect(unitId) {
        val endOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
        }.timeInMillis
        viewModel.startSessionOnce(endOfDay, unitId = unitId)
    }
    
    val currentUnitState by viewModel.currentUnit.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val strings = com.example.ui.i18n.LocalStrings.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Localized numerals: Persian digits in fa, Latin otherwise.
    val num: (Any) -> String = { if (strings.languageCode == "fa") com.example.ui.i18n.PersianDate.faDigits(it.toString()) else it.toString() }
    
    // Survives configuration changes for the same reason: having revealed the source, the learner
    // should not be silently returned to the pre-reveal screen.
    var showNotes by rememberSaveable(currentUnitState) { mutableStateOf(false) }
    // rememberSaveable, not remember: a rotation, a dark-mode toggle, a split-screen resize or a
    // font-size change destroys composition, and with plain remember the learner was thrown back to
    // the recall step having already answered it. Stored as the enum NAME because MemoryRating is
    // not Parcelable; keyed on the unit so moving to the next topic still clears it.
    var selectedMemory by rememberSaveable(
        currentUnitState,
        // Saved as the enum NAME (MemoryRating is not Parcelable); an unknown name restores as null
        // rather than throwing, so a bundle written by another build cannot crash the review screen.
        stateSaver = androidx.compose.runtime.saveable.Saver<MemoryRating?, String>(
            save = { it?.name },
            restore = { name -> runCatching { MemoryRating.valueOf(name) }.getOrNull() },
        ),
    ) { mutableStateOf<MemoryRating?>(null) }

    // Back steps BACKWARDS through the rating flow and cancels — nothing is committed to the DB until
    // the understanding rating is tapped. So leaving mid-rating (difficulty chosen, understanding not)
    // never counts as a review. At the first step, back exits the session.
    androidx.activity.compose.BackHandler {
        when {
            selectedMemory != null -> selectedMemory = null   // understanding step -> back to difficulty
            showNotes -> showNotes = false                    // difficulty step -> back to recall prompt
            else -> onFinish()                                // recall step -> exit session
        }
    }

    // "Why scheduled?" — the reason line appears briefly after each rating (calm, dismissible),
    // teaching cause → effect so the schedule feels explainable instead of arbitrary.
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(viewModel.lastReason) {
        viewModel.lastReason?.let { reason ->
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentUnit == null && viewModel.isLoading) {
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.weight(1f))
            } else if (currentUnit == null) {
                Spacer(modifier = Modifier.weight(1f))
                Text(strings.sessionComplete, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = num(viewModel.sessionCount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                Text(text = when (strings.languageCode) { "fa" -> "کل مرورها"; "de" -> "Wiederholt"; else -> "Reviewed" }, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = num(viewModel.sessionGood), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
                                Text(text = when (strings.languageCode) { "fa" -> "آسان/خوب"; "de" -> "Gut/Leicht"; else -> "Good/Easy" }, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = num(viewModel.sessionHard), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = com.example.ui.theme.ratingTone(com.example.domain.model.MemoryRating.Hard).solid)
                                Text(text = when (strings.languageCode) { "fa" -> "سخت"; "de" -> "Schwer"; else -> "Hard" }, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = num(viewModel.sessionForgot), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = com.example.ui.theme.ratingTone(com.example.domain.model.MemoryRating.Forgot).solid)
                                Text(text = when (strings.languageCode) { "fa" -> "فراموش شده"; "de" -> "Vergessen"; else -> "Forgot" }, style = MaterialTheme.typography.labelSmall)
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
                Spacer(modifier = Modifier.weight(1f))
            } else {
                val formattedState = strings.stateLabel(currentUnit.state)
                val subject = subjects.find { it.id == currentUnit.subjectId }
                // First study (studied today, never reviewed) vs a recall review (back-dated or later).
                val previewReviewNumber = MedScheduler.effectiveReviewNumber(currentUnit.reviewCount)
                val isFreshFirstStudy = previewReviewNumber == 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val isFarsi = strings.languageCode == "fa"
                    TextButton(onClick = onFinish) {
                        Text(strings.done)
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (subject?.colorHex != null) {
                            Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(subject.colorHex)) }.getOrNull() ?: MaterialTheme.colorScheme.primary))
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = "${(subject?.name ?: currentUnit.studyType).uppercase()} • ${formattedState.uppercase()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            // Tracking helps ALL-CAPS Latin labels but breaks Arabic-script letter joining.
                            letterSpacing = if (strings.languageCode == "fa") 0.sp else 1.sp
                        )
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewModel.canUndo) {
                            IconButton(enabled = !viewModel.isProcessing, onClick = {
                                viewModel.undoLastRating()
                                showNotes = false
                                selectedMemory = null
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Undo,
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
                
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                        val formattedStage = strings.stateLabel(currentUnit.state)
                        
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
                            val rounded = (currentUnit.currentIntervalDays * 10).toInt() / 10.0
                            when (strings.languageCode) { "fa" -> "${num(rounded)} روز"; "de" -> "$rounded Tage"; else -> "$rounded days" }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
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
                            
                            Text(
                                text = "${when (strings.languageCode) { "fa" -> "مرحله"; "de" -> "Stufe"; else -> "Stage" }}: $formattedStage",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${when (strings.languageCode) { "fa" -> "فاصله"; "de" -> "Abstand"; else -> "Interval" }}: $intervalStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${when (strings.languageCode) { "fa" -> "آخرین"; "de" -> "Zuletzt"; else -> "Last" }}: $lastDateStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

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

                        if (!showNotes && !isFreshFirstStudy) {
                            Text(
                                strings.recallFirstPrompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        } else {
                            if (!currentUnit.notes.isNullOrBlank()) {
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = currentUnit.notes!!,
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
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isFreshFirstStudy && selectedMemory == null) {
                    // FIRST STUDY: you just studied this today — rate how hard the topic was (not recall).
                    Text(when (strings.languageCode) { "fa" -> "این مبحث چقدر سخت بود؟"; "de" -> "Wie schwer war dieses Thema?"; else -> "How difficult was this topic?" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    // Retrieval nudge: a rating that follows a real recall attempt is far more diagnostic
                    // than a "felt fluent while reading" judgment. Costs nothing, reinforces active recall.
                    Text(
                        // Semantically honest: minutes after studying, "recall without looking" is a
                        // fluency check, not delayed retrieval. Frame it as the check-in it really is.
                        if (strings.languageCode == "fa") "این ثبت اولیه، اولین مرور را تنظیم می‌کند — سنجش واقعی حافظه از مرور بعدی و پس از گذشت زمان شروع می‌شود." else if (strings.languageCode == "de") "Dieser Check-in legt die erste Wiederholung fest — das echte Gedächtnistesten beginnt beim nächsten Mal." else "This check-in sets your first review — real memory testing starts next time, after time has passed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tone.container,
                                    contentColor = tone.onContainer,
                                ),
                                modifier = Modifier.weight(1f).padding(4.dp).heightIn(min = 56.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    when (rating) {
                                        MemoryRating.Easy -> when (strings.languageCode) { "fa" -> "آسان"; "de" -> "Leicht"; else -> "Easy" }
                                        MemoryRating.Good -> when (strings.languageCode) { "fa" -> "متوسط"; "de" -> "Mittel"; else -> "Medium" }
                                        else -> when (strings.languageCode) { "fa" -> "سخت"; "de" -> "Schwer"; else -> "Hard" }
                                    },
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else if (!isFreshFirstStudy && !showNotes) {
                    Button(
                        onClick = { showNotes = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = RoundedCornerShape(percent = 50)
                    ) {
                        Text(strings.showNotes, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = {
                            viewModel.procrastinateCurrentUnit()
                            showNotes = false
                            selectedMemory = null
                        },
                        enabled = !viewModel.isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(strings.notToday)
                    }
                } else if (selectedMemory == null) {
                    Text(strings.memoryRating, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MemoryRating.entries.forEach { rating ->
                            // No interval on the memory buttons: the final interval also depends on
                            // the understanding rating (chosen next), so it's shown on those buttons.
                            // Warm→cool rating ramp; "Forgot" is calm sienna, never alarm-red.
                            val tone = com.example.ui.theme.ratingTone(rating)
                            Button(
                                onClick = {
                                    if (rating == MemoryRating.Forgot) {
                                        // Forgot → relearn tomorrow regardless of understanding, so commit
                                        // now and skip that moot second question (less friction on a miss).
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        viewModel.rateCurrentUnit(MemoryRating.Forgot, UnderstandingRating.Partial, understandingAsked = false)
                                    } else {
                                        selectedMemory = rating
                                    }
                                },
                                enabled = !viewModel.isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tone.container,
                                    contentColor = tone.onContainer,
                                ),
                                modifier = Modifier.weight(1f).padding(4.dp).heightIn(min = 56.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    when(rating) {
                                        MemoryRating.Forgot -> strings.ratingFail
                                        MemoryRating.Hard -> strings.ratingHard
                                        MemoryRating.Good -> strings.ratingGood
                                        MemoryRating.Easy -> strings.ratingEasy
                                    },
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    Text(strings.understandingRating, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        UnderstandingRating.entries.forEach { rating ->
                            val now = System.currentTimeMillis()
                            // Same measure the commit uses, or the buttons would preview an
                            // interval the commit then disagrees with.
                            val elapsedDays = MedScheduler.modelElapsedDays(
                                currentUnit.lastReviewedAt ?: currentUnit.studiedAt, now,
                                MedScheduler.CURRENT_MODEL,
                            )
                            // Both choices are known here, so this is the actual interval that commits
                            // (including the same deterministic fuzz the commit path applies).
                            val previewOutcome = MedScheduler.review(
                                stability = currentUnit.stability,
                                difficulty = currentUnit.difficulty,
                                elapsedDays = elapsedDays,
                                memoryRating = selectedMemory!!,
                                understanding = rating,
                                highYield = currentUnit.highYield,
                                reviewNumber = previewReviewNumber,
                                model = MedScheduler.CURRENT_MODEL,
                            )
                            val finalInterval = MedScheduler.fuzzedInterval(
                                previewOutcome.intervalDays,
                                previewOutcome.baseIntervalDays,
                                currentUnit.id,
                                currentUnit.reviewCount,
                                isFirstStudy = previewReviewNumber == 0,
                            )
                            // Show when the topic will actually COME BACK, which under the two-clock
                            // model is the earlier of the memory prediction and the understanding
                            // repair deadline. Showing the raw memory interval here would print the
                            // same number on all three buttons (understanding no longer scales it)
                            // and then contradict itself by resurfacing the topic days earlier.
                            val effectiveInterval =
                                minOf(finalInterval, previewOutcome.remediationDays ?: Double.MAX_VALUE)
                            val intervalStr = if (effectiveInterval < 1.0) {
                                val hrs = (effectiveInterval * 24).toInt()
                                if (hrs < 1) "<1h" else "${hrs}h"
                            } else {
                                "${(effectiveInterval * 10).toInt() / 10.0}d".replace(".0d", "d")
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    viewModel.rateCurrentUnit(selectedMemory!!, rating)
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
                                            else -> rating.name
                                        }
                                    }\n$intervalStr",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

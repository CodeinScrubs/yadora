package com.example.ui.add

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.core.graphics.toColorInt
import com.example.ui.i18n.autoDirection
import com.example.ui.i18n.stateLabel
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import com.example.data.local.entity.ReviewLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Suppress("UNCHECKED_CAST")
class AddUnitViewModelFactory(private val repository: MedReviewRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AddUnitViewModel(repository) as T
    }
}

class AddUnitViewModel(val repository: MedReviewRepository) : ViewModel() {
    var existingUnit by mutableStateOf<StudyUnitEntity?>(null)
    
    private val _reviewLogs = MutableStateFlow<List<ReviewLogEntity>>(emptyList())
    val reviewLogs: StateFlow<List<ReviewLogEntity>> = _reviewLogs.asStateFlow()
    
    val subjects: StateFlow<List<com.example.data.local.entity.SubjectEntity>> = repository.allSubjects
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    // Fold titles when the library changes, on a worker dispatcher; searches reuse that small index.
    internal val relatedTopicIndex = repository.topicTitles.map(RelatedTopics::index)
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private var logsJob: kotlinx.coroutines.Job? = null

    /**
     * The topic this screen is EDITING, known synchronously from the navigation argument.
     *
     * [existingUnit] arrives asynchronously, so it must never be what decides insert-vs-update: the
     * form's own fields are rememberSaveable and come back instantly after process death, which means
     * Save can be pressed while the row is still loading. Branching on a null [existingUnit] then
     * silently INSERTED a second copy of a topic the user was only editing (and, before the insert,
     * ran the new-topic duplicate check against their own unchanged title).
     */
    var editingUnitId: Long? = null
        private set

    fun loadUnit(id: Long) {
        editingUnitId = id
        viewModelScope.launch {
            // The forgetting curve is drawn on the topic's own weight set, so the sets are loaded first.
            repository.ensureMemoryModelLoaded()
            existingUnit = repository.getUnitById(id)
        }
        // Cancel any previous log collector so repeated Edit visits don't pile up infinite collectors.
        logsJob?.cancel()
        logsJob = viewModelScope.launch {
            repository.getLogsForUnit(id).collect { logs ->
                _reviewLogs.value = logs
            }
        }
    }
    
    /** Restore an archived topic (with its whole history) instead of creating a duplicate. */
    fun restoreArchived(id: Long, onRestored: () -> Unit) {
        viewModelScope.launch {
            repository.unarchiveUnit(id)
            onRestored()
        }
    }

    fun saveSubject(name: String, colorHex: String?, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            // Reuse an existing subject with the same (normalized) name instead of creating a duplicate.
            val existing = subjects.value.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
            val id = existing?.id ?: repository.insertSubject(name.trim(), colorHex)
            onSaved(id)
        }
    }

    /** [onSaved] receives the new topic's id when one was inserted, null after an edit. */
    /**
     * [loadedStudiedAt] and [loadedNextReviewAt]: the dates the form was filled with (TopicEdit compares the form with
     * them). [existingUnit] cannot stand in for them: the screen re-reads the row each time it comes back (from a review
     * opened on top of it, or after process death), and against that fresher row an untouched date looked edited, so Save
     * wrote the date a review had just replaced back as a deferral (a production review, 2026-10-10).
     */
    fun saveUnit(title: String, subjectId: Long?, systemId: Long?, studyType: String, prompt: String, keyPoints: String?, notes: String, source: String, highYield: Boolean, studiedAt: Long?, nextReviewAt: Long?, onSaved: (Long?) -> Unit = {}, onError: () -> Unit = {}, onDuplicate: () -> Unit = {}, onArchivedDuplicate: (StudyUnitEntity) -> Unit = {}, skipArchivedDuplicateCheck: Boolean = false, loadedStudiedAt: Long? = null, loadedNextReviewAt: Long? = null) {
        viewModelScope.launch {
            // "Am I editing?" comes from the nav argument, NOT from whether the row has finished
            // loading — see editingUnitId.
            val isEditing = editingUnitId != null
            // Block true duplicates on NEW topics only (editing an existing one is never a dup of itself).
            if (!isEditing && repository.isDuplicate(title, subjectId, notes, source)) {
                onDuplicate()
                return@launch
            }
            // An ARCHIVED topic with this title: OFFER to restore it (with its whole history). Only an
            // offer — the dialog's "Create new" re-invokes save with the check skipped, so a same-title
            // topic that's genuinely different (e.g. another subject) is never blocked.
            if (!isEditing && !skipArchivedDuplicateCheck) {
                repository.findArchivedDuplicate(title)?.let { archived ->
                    onArchivedDuplicate(archived)
                    return@launch
                }
            }
            // The DB write runs NonCancellable: navigating back clears this ViewModel and cancels its
            // scope, and without this guard the insert/update could be aborted mid-flight, silently
            // losing the topic. onSaved() fires only AFTER the write commits; on failure onError() fires
            // so the Save button never stays stuck disabled.
            var insertedId: Long? = null
            val ok = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val editingId = editingUnitId
                if (editingId != null) {
                    // Read-modify-write in ONE transaction against the row as it is NOW (TopicEdit): the
                    // form was filled when this screen opened, and a review, a "Not today" or a rating
                    // correction may have written the row since. Saving the loaded copy undid them.
                    val found = repository.inTransaction {
                        val fresh = repository.getUnitById(editingId) ?: return@inTransaction false
                        // The row the form was filled from. If Save beat the load (process death restored
                        // the form before the row arrived), the fresh row is the only baseline there is. Its dates are
                        // the ones the form was filled with, when the screen knows them.
                        val loaded = (existingUnit?.takeIf { it.id == editingId } ?: fresh).let { row ->
                            if (loadedStudiedAt != null && loadedNextReviewAt != null) row.copy(studiedAt = loadedStudiedAt, nextReviewAt = loadedNextReviewAt)
                            else row
                        }
                        val plan = TopicEdit.plan(
                            loaded = loaded,
                            fresh = fresh,
                            form = TopicEdit.Form(
                                // The form shows neither the collection nor the study type any more, so it keeps
                                // the row's: passing the new-topic defaults here wiped a restored or older topic's
                                // collection on every save.
                                title = title, subjectId = subjectId, systemId = fresh.systemId, studyType = fresh.studyType,
                                recallPrompt = prompt.ifBlank { null }, keyPoints = keyPoints, notes = notes, source = source,
                                highYield = highYield, studiedAt = studiedAt, nextReviewAt = nextReviewAt,
                            ),
                            now = System.currentTimeMillis(),
                        )
                        // Turning IMPORTANT ON responds immediately (#15): recompute the current interval
                        // under the tighter retention target from the stored memory state, never later than
                        // what was already scheduled. A subsequent history edit recomputes from the logs
                        // (which store per-review importance), so this is a one-time convenience reschedule.
                        suspend fun tightenedForImportant(row: StudyUnitEntity): StudyUnitEntity {
                            val lastReviewedAt = row.lastReviewedAt ?: return row
                            // The per-user interval correction, refreshed from the logs right before it is
                            // used — the review session and a rating correction do the same.
                            runCatching { repository.refreshMemoryModel() }
                            runCatching { MedScheduler.calibrationScale = repository.recallCalibrationScale() }
                            // Through the topic's OWN model: a stored stability only means something
                            // together with the model that produced it, and re-deriving the interval on
                            // the wrong curve would set a date the next real review then disagrees with.
                            val tighter = MedScheduler.scheduledIntervalDays(
                                row.stability,
                                MedScheduler.effectiveRetention(true),
                                MedScheduler.MemoryModel.of(row.memoryModel),
                                // ...and its own weight set, for the same reason.
                                row.parameterSetId,
                            ).coerceIn(MedScheduler.MIN_INTERVAL_DAYS, MedScheduler.MAX_INTERVAL_DAYS)
                            val tighterNext = lastReviewedAt + (tighter * 86400000).toLong()
                            return if (tighterNext < row.nextReviewAt) row.copy(
                                nextReviewAt = tighterNext,
                                modelDueAt = tighterNext,
                                // The model reclaimed the schedule — a stale deferral marker would
                                // make this honest-scheduling data lie about who chose the date.
                                deferredUntil = null,
                                currentIntervalDays = tighter,
                            ) else row
                        }
                        // The study date is the replay origin: when it moves, the schedule is recomputed
                        // from it atomically with the edit — the whole history of a rated topic, the due
                        // date of an unrated one. Unrated topics used to skip this, so moving their study
                        // date in this form left them due on the old day.
                        if (plan.studyDateChanged) {
                            repository.updateUnitReplayingHistory(plan.updated)
                            // A next-review date picked in the same save is the learner's deferral, and the replay
                            // gives a rated topic the model's date (it supersedes a deferral made BEFORE it): the
                            // picked date was dropped without a word (a production review, 2026-10-10). An unrated
                            // topic keeps it already (updateUnitReplayingHistory).
                            if (plan.nextDateChanged && plan.updated.deferredUntil != null) {
                                repository.getUnitById(editingId)?.let { replayed ->
                                    if (replayed.deferredUntil != plan.updated.deferredUntil) repository.updateUnit(
                                        replayed.copy(nextReviewAt = plan.updated.nextReviewAt, deferredUntil = plan.updated.deferredUntil)
                                    )
                                }
                            }
                            // Important switched on in the same save: tighten the REPLAYED row. It used to be
                            // tightened first and then overwritten by the replay, which recomputes from the logs
                            // (they record the importance each review had), so the switch did nothing until the
                            // next review (found by an outside review, 2026-09-27).
                            if (plan.tightenForImportant) {
                                repository.getUnitById(editingId)?.let { replayed ->
                                    val tightened = tightenedForImportant(replayed)
                                    if (tightened != replayed) repository.updateUnit(tightened)
                                }
                            }
                        } else {
                            repository.updateUnit(
                                if (plan.tightenForImportant) tightenedForImportant(plan.updated) else plan.updated
                            )
                        }
                        true
                    }
                    // Never fall through to an insert for a topic this screen was editing: that would fork
                    // a copy of a topic that has since been deleted or replaced by a restore.
                    check(found) { "topic $editingId no longer exists" }
                } else {
                    // First study: seed the FSRS memory state from the student's self-rated confidence.
                    // Seed a neutral starting state; the first rating in the review screen re-seeds the
                    // real memory state (initialState), so this only matters before that first rating.
                    val seed = MedScheduler.firstStudy(understanding = UnderstandingRating.Partial, highYield = highYield)
                    val now = System.currentTimeMillis()
                    val baseTime = studiedAt ?: now
                    // The first review is due ON the study date itself, so the user rates difficulty +
                    // understanding the same day they log it (that first rating IS the first review):
                    //  - studied TODAY          -> due today (appears in Today now, as a "New" item)
                    //  - studied in the PAST    -> already overdue (appears in Today, "From earlier")
                    //  - planned for the FUTURE -> surfaces on that day as "study this".
                    val computedNext = baseTime
                    insertedId = repository.insertUnit(
                        StudyUnitEntity(
                            title = title,
                            subjectId = subjectId,
                            systemId = systemId,
                            studyType = studyType,
                            recallPrompt = prompt.ifBlank { null },
                            keyPoints = keyPoints,
                            notes = notes,
                            source = source,
                            highYield = highYield,
                            studiedAt = baseTime,
                            lastReviewedAt = null,
                            nextReviewAt = nextReviewAt ?: computedNext,
                            // v5 honest-scheduling pair: the model's date is the study date itself; a
                            // custom first date chosen by the user is recorded as a deferral.
                            modelDueAt = computedNext,
                            deferredUntil = nextReviewAt?.takeIf { it != computedNext },
                            // 0 = no interval has elapsed yet: the topic is due ON its study date and
                            // hasn't been rated. Storing the seed's ~1.1d here made the first log's
                            // previousIntervalDays claim an interval that never existed (and disagree
                            // with the history replay, which correctly starts from 0).
                            currentIntervalDays = 0.0,
                            stability = seed.state.stability,
                            difficulty = seed.state.difficulty
                        )
                    )
                }
            }
                true
            } catch (e: Exception) {
                // Surface the failure in Logcat at least; user-facing behavior unchanged (still onError()).
                android.util.Log.e("AddUnitViewModel", "Topic save failed", e)
                false
            }
            if (ok) onSaved(insertedId) else onError()
        }
    }

    /**
     * Correct a past review's ratings, and its day when [newReviewedAt] is given (between its neighbouring reviews, never
     * in the future: com.example.domain.model.ReviewDay); the repository replays history to recompute the schedule.
     */
    fun editReviewRating(
        logId: Long,
        mem: com.example.domain.model.MemoryRating,
        und: UnderstandingRating?,
        newReviewedAt: Long? = null,
        onComplete: (Boolean) -> Unit,
    ) {
        val unitId = existingUnit?.id ?: return onComplete(false)
        viewModelScope.launch {
            // If replay aborts (a corrupt log, or a day outside its neighbours), the topic is left untouched — never
            // half-replayed.
            val result = runCatching { repository.editReviewRating(unitId, logId, mem, und, newReviewedAt) }
            if (result.isSuccess) existingUnit = repository.getUnitById(unitId)
            onComplete(result.isSuccess)
        }
    }
}



/** A small forgetting-curve sparkline: recall probability decaying over time for this topic's stability. */
@androidx.compose.runtime.Composable
private fun ForgettingCurve(stability: Double, model: com.example.domain.srs.MedScheduler.MemoryModel, parameterSetId: Long, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val mutedLine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxT = (stability * 2.5).coerceAtLeast(1.0)
        val y90 = h * (1f - 0.9f)
        drawLine(mutedLine, androidx.compose.ui.geometry.Offset(0f, y90), androidx.compose.ui.geometry.Offset(w, y90), strokeWidth = 1.5f)
        val path = androidx.compose.ui.graphics.Path()
        val steps = 60
        for (i in 0..steps) {
            val t = maxT * i / steps
            val r = com.example.domain.srs.MedScheduler.retrievability(t, stability, model, parameterSetId).toFloat()
            val x = w * i / steps
            val y = h * (1f - r)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
    }
}

/**
 * A study day picked as [picked] (09:00 on that day, as the pickers give it): today's 09:00 is in the future before 09:00,
 * which hid "Save and rate now" for a topic studied this morning (a production review, 2026-10-10), so a study today is
 * now at the latest. Another day keeps its 09:00.
 */
internal fun studiedOn(picked: Long, now: Long = System.currentTimeMillis()): Long {
    val zone = java.time.ZoneId.systemDefault()
    return if (com.example.domain.model.ReviewDay.day(picked, zone) == com.example.domain.model.ReviewDay.day(now, zone)) minOf(picked, now) else picked
}

/**
 * [picked] unless it is the day [current] is already on: the pickers give 09:00 of the day chosen, and OK on the day
 * already shown turned a due date of 16:05 into a deferral to 09:00 nobody chose, or a study time into a replayed history
 * (a production review, 2026-10-10).
 */
internal fun keepIfSameDay(picked: Long, current: Long?): Long {
    val zone = java.time.ZoneId.systemDefault()
    return if (current != null && com.example.domain.model.ReviewDay.day(picked, zone) == com.example.domain.model.ReviewDay.day(current, zone)) current else picked
}

/** DatePicker returns UTC-midnight of the chosen day; convert to ~9am local on that same calendar date. */
private fun datePickerUtcToLocalDay(utcMillis: Long): Long {
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.YEAR, utc.get(java.util.Calendar.YEAR))
        set(java.util.Calendar.MONTH, utc.get(java.util.Calendar.MONTH))
        set(java.util.Calendar.DAY_OF_MONTH, utc.get(java.util.Calendar.DAY_OF_MONTH))
        set(java.util.Calendar.HOUR_OF_DAY, 9)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUnitScreen(
    repository: MedReviewRepository,
    unitId: Long?,
    onBack: () -> Unit,
    /** "Save and rate now" on a new topic: open its first rating straight away (the id just saved). */
    onRateNow: (Long) -> Unit = {},
    /**
     * "Save and review now" on an existing topic: the learner decides it needs a review, due or not (the owner,
     * 2026-10-03: "sometimes I may need to review a topic even if the app has not asked for it"). The one-topic session
     * the Library's long-press opens, reachable from the topic's own page.
     */
    onReviewNow: (Long) -> Unit = {},
    /** False when this page was opened from a review in progress, whose card is this topic already. */
    showReviewNow: Boolean = true,
    /** Existing suggestions open without saving or discarding this draft. Archived topics open their details. */
    onOpenRelatedTopic: (id: Long, archived: Boolean) -> Unit = { _, _ -> },
) {
    val viewModel: AddUnitViewModel = viewModel(factory = AddUnitViewModelFactory(repository))
    
    LaunchedEffect(unitId) {
        if (unitId != null) {
            viewModel.loadUnit(unitId)
        }
    }
    
    // rememberSaveable: a half-typed topic must survive rotation / process death — losing the user's
    // notes to a screen rotation is unacceptable data loss for a capture flow.
    var title by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var notes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var recallPrompt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var keyPointsText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // Key points are reference text now (KeyPoints). The field is offered only for a topic that already
    // has some, so they can still be read, edited or cleared; new topics use the notes.
    var showKeyPointsField by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var highYield by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selectedSubjectId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var studiedAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var nextReviewAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Set by "Save and rate now": after a new topic is saved, open its first rating instead of going back.
    var rateNowAfterSave by remember { mutableStateOf(false) }
    // Set by "Save and review now": after an edit is saved, open this topic's review instead of going back.
    var reviewNowAfterSave by remember { mutableStateOf(false) }
    var archivedDuplicate by remember { mutableStateOf<StudyUnitEntity?>(null) }
    // The review whose rating is being corrected, by id and saveable: a rotation used to close the dialog and drop what
    // was chosen in it (the rule UI-01 set for this screen's other dialogs; a production review, 2026-10-10).
    var editingLogId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    val editingLogs by viewModel.reviewLogs.collectAsStateWithLifecycle()
    val editingLog = editingLogId?.let { id -> editingLogs.firstOrNull { it.id == id } }
    var editingLogSaving by remember { mutableStateOf(false) }
    var editingLogError by remember { mutableStateOf(false) }
    // The correction itself failed (the repository refused it): said under the fields, not left without a word.
    var editingLogFailed by remember { mutableStateOf(false) }

    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    var showSubjectDropdown by remember { mutableStateOf(false) }
    // Saveable: turning the phone closed the "new subject" dialog and dropped the name being typed (an outside
    // emulator audit, 2026-09-30), while the topic form around it was kept.
    var showAddSubjectDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var newSubjectName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var newSubjectColor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var sourceLink by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    // Populate ONCE per edit session: without the guard, rotation re-runs this effect and clobbers
    // the user's in-progress (rememberSaveable-restored) edits with the stored DB values.
    var loadedFromUnit by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // The dates the form was filled with: what Save compares the form's dates with (AddUnitViewModel.saveUnit).
    var loadedStudiedAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var loadedNextReviewAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(viewModel.existingUnit) {
        if (loadedFromUnit) return@LaunchedEffect
        viewModel.existingUnit?.let {
            title = it.title
            notes = it.notes ?: ""
            recallPrompt = it.recallPrompt ?: ""
            keyPointsText = it.keyPoints ?: ""
            showKeyPointsField = !it.keyPoints.isNullOrBlank()
            sourceLink = it.source ?: ""
            highYield = it.highYield
            selectedSubjectId = it.subjectId
            studiedAt = it.studiedAt
            nextReviewAt = it.nextReviewAt
            loadedStudiedAt = it.studiedAt
            loadedNextReviewAt = it.nextReviewAt
            loadedFromUnit = true
        }
    }
    
    // Saveable: a rotation used to close an open date picker (an outside audit, 2026-10-03); the date itself was kept.
    var showStudiedAtPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showNextReviewAtPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    
    val strings = com.example.ui.i18n.LocalStrings.current
    val titleRequiredHint = when (strings.languageCode) {
        "fa" -> "برای ذخیره، عنوان لازم است"
        "de" -> "Zum Speichern wird ein Titel benötigt"
        else -> "A title is required to save"
    }
    val reminderContext = androidx.compose.ui.platform.LocalContext.current
    // Dates honor the user's calendar preference (Jalali/Gregorian), independent of UI language.
    val useJalali = com.example.ui.i18n.LocalUseJalali.current
    val fmtDate: (Long) -> String = { m -> com.example.ui.i18n.AppDate.date(useJalali, m, strings.languageCode == "fa") }

    // One save path for every button: the top bar's Save, "Save and rate now" for a new topic, and "Save and review
    // now" for an existing one. Saving first means a review never leaves an edit behind unsaved.
    fun save(rateNow: Boolean, reviewNow: Boolean = false) {
        if (saving) return
        saving = true
        rateNowAfterSave = rateNow
        reviewNowAfterSave = reviewNow
        // System and study type were removed as v1 bloat (columns kept, dormant).
        // The recall prompt was too, until it returned as an optional field.
        viewModel.saveUnit(title, selectedSubjectId, null, "Topic", recallPrompt.trim(), com.example.domain.srs.KeyPoints.normalize(keyPointsText), notes, sourceLink, highYield, studiedAt, nextReviewAt,
            loadedStudiedAt = loadedStudiedAt, loadedNextReviewAt = loadedNextReviewAt,
            onSaved = { newId ->
                com.example.notifications.NotificationScheduler.scheduleDailyReminder(reminderContext)
                com.example.notifications.TodayRefresh.afterChange(reminderContext) // new topic changes today's count
                when {
                    rateNowAfterSave && newId != null -> onRateNow(newId)
                    reviewNowAfterSave && unitId != null -> onReviewNow(unitId)
                    else -> onBack()
                }
            },
            onError = { saving = false },
            onDuplicate = {
                saving = false
                android.widget.Toast.makeText(
                    reminderContext,
                    if (strings.languageCode == "fa") "این مبحث از قبل وجود دارد. برای تفکیک، درس یا یادداشت متفاوتی اضافه کن." else if (strings.languageCode == "de") "Dieses Thema gibt es schon. Gib einem der beiden ein anderes Fach oder eine andere Notiz." else "This topic already exists. To keep both, give one a different subject or note.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            },
            onArchivedDuplicate = { archived ->
                saving = false
                archivedDuplicate = archived
            })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (unitId == null) strings.addNewTopic else strings.addEditTopic, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { 
                        Icon(Icons.Default.Close, strings.cancel) 
                    }
                },
                actions = {
                    TextButton(
                        onClick = { save(rateNow = false) },
                        enabled = title.isNotBlank() && !saving,
                        // A greyed-out Save with no stated reason is a dead end — invisible to a
                        // screen reader and a guessing game for everyone else. Name the one thing
                        // that is missing. ('saving' is transient and self-explanatory, so it is
                        // deliberately not narrated.)
                        modifier = Modifier.semantics {
                            if (title.isBlank()) stateDescription = titleRequiredHint
                        }
                    ) {
                        Text(strings.save)
                    }
                }
            )
        }
    ) { padding ->
        // Re-adding a topic that lives in the ARCHIVE: restoring it keeps its whole review history —
        // almost always what the user wants over a fresh duplicate that starts from zero.
        archivedDuplicate?.let { arch ->
            AlertDialog(
                onDismissRequest = { archivedDuplicate = null },
                title = { Text(when (strings.languageCode) { "fa" -> "در بایگانی موجود است"; "de" -> "Im Archiv vorhanden"; else -> "Already in your archive" }) },
                text = { Text(when (strings.languageCode) {
                    "fa" -> "«${arch.title}» در بایگانی‌ات هست. بازگرداندنش تمام تاریخچهٔ مرور را حفظ می‌کند."
                    "de" -> "„${arch.title}“ liegt in deinem Archiv. Wiederherstellen behält den gesamten Wiederholungsverlauf."
                    else -> "\"${arch.title}\" is in your archive. Restoring it keeps its whole review history."
                }) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.restoreArchived(arch.id) {
                            com.example.notifications.TodayRefresh.afterChange(reminderContext)
                            archivedDuplicate = null
                            onBack()
                        }
                    }) { Text(when (strings.languageCode) { "fa" -> "بازگردانی"; "de" -> "Wiederherstellen"; else -> "Restore" }) }
                },
                dismissButton = {
                    Row {
                        // Same title but genuinely different topic (e.g. another subject): the archived
                        // match is only an OFFER — creating a new topic must never be blocked by it.
                        TextButton(onClick = {
                            archivedDuplicate = null
                            saving = true
                            viewModel.saveUnit(title, selectedSubjectId, null, "Topic", recallPrompt.trim(), com.example.domain.srs.KeyPoints.normalize(keyPointsText), notes, sourceLink, highYield, studiedAt, nextReviewAt,
                                onSaved = { newId ->
                                    com.example.notifications.NotificationScheduler.scheduleDailyReminder(reminderContext)
                                    com.example.notifications.TodayRefresh.afterChange(reminderContext)
                                    if (rateNowAfterSave && newId != null) onRateNow(newId) else onBack()
                                },
                                onError = { saving = false },
                                skipArchivedDuplicateCheck = true)
                        }) { Text(when (strings.languageCode) { "fa" -> "ایجاد مبحث جدید"; "de" -> "Neu anlegen"; else -> "Create new" }) }
                        TextButton(onClick = { archivedDuplicate = null }) { Text(strings.cancel) }
                    }
                }
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                // The app is edge-to-edge, so the keyboard covers the window instead of shrinking it. Taking its
                // height off the scroll area lets a focused field (the notes, the source) scroll into view above
                // it. The system-bar padding is consumed first so the navigation bar is not counted twice.
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(strings.topicTitleLabel) },
                modifier = Modifier.fillMaxWidth(),
                // The title is the ONLY required field; say so under the box rather than letting
                // Save sit greyed out with no explanation.
                supportingText = { if (title.isBlank()) Text(titleRequiredHint) },
                // A topic title can be in any language; lay it out by its own first strong character.
                textStyle = LocalTextStyle.current.autoDirection(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            if (unitId == null) {
                val relatedIndex by viewModel.relatedTopicIndex.collectAsStateWithLifecycle()
                val related = remember(relatedIndex, title, selectedSubjectId) {
                    RelatedTopics.search(relatedIndex, title, selectedSubjectId)
                }
                if (related.isNotEmpty()) {
                    val fa = strings.languageCode == "fa"
                    val de = strings.languageCode == "de"
                    Text(
                        when { fa -> "مباحث ثبت‌شدهٔ مشابه"; de -> "Ähnliche vorhandene Themen"; else -> "Related existing topics" },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    related.take(5).forEach { existing ->
                        OutlinedCard(
                            onClick = { onOpenRelatedTopic(existing.id, existing.archived) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(existing.title, style = MaterialTheme.typography.bodyMedium.autoDirection())
                                val subject = subjects.firstOrNull { it.id == existing.subjectId }?.name
                                val action = when {
                                    existing.archived -> when { fa -> "بایگانی‌شده · مشاهده"; de -> "Archiviert · öffnen"; else -> "Archived · open" }
                                    fa -> "ثبت مرور"; de -> "Wiederholung eintragen"; else -> "Log a review"
                                }
                                Text(listOfNotNull(subject, action).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall.autoDirection(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val remaining = related.size - 5
                    if (remaining > 0) Text(
                        when {
                            fa -> "${com.example.ui.i18n.PersianDate.faDigits(remaining)} مورد دیگر؛ برای محدودکردن نتایج، عنوان را دقیق‌تر بنویس."
                            de -> "$remaining weitere Treffer. Genauer tippen, um die Suche einzugrenzen."
                            else -> "$remaining more matches. Keep typing to narrow the results."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // OPTIONAL scope (stored as recallPrompt) — what this topic covers. A bare title such as
            // "Appendicitis" leaves open what "I still remembered it" means (the presentation? the whole
            // framework?), and that ambiguity sits under every rating the scheduler learns from. Worded as
            // scope, not as a quiz question: a review is whatever the learner chooses (questions, notes,
            // a lecture, a video), so this names what to cover, not what to recite. Shown under the title
            // at every review.
            OutlinedTextField(
                value = recallPrompt,
                onValueChange = { recallPrompt = it },
                label = {
                    Text(
                        when (strings.languageCode) {
                            "fa" -> "این مبحث شامل چه چیزهایی است؟ (اختیاری)"
                            "de" -> "Was umfasst dieses Thema? (optional)"
                            else -> "What does this topic cover? (optional)"
                        }
                    )
                },
                placeholder = {
                    Text(
                        when (strings.languageCode) {
                            "fa" -> "مثلاً: علت‌ها، نشانه‌های کلیدی، درمان خط اول"
                            "de" -> "z. B.: Ursachen, Leitsymptome, Erstlinientherapie"
                            else -> "e.g. main causes, key signs, first-line treatment"
                        }
                    )
                },
                supportingText = {
                    Text(
                        when (strings.languageCode) {
                            "fa" -> "در هر مرور زیر عنوان نشان داده می‌شود تا بدانی مرور شامل چه چیزهایی است."
                            "de" -> "Steht bei jeder Wiederholung unter dem Titel, damit klar ist, was dazugehört."
                            else -> "Shown under the title at every review, so you know what the review covers."
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.autoDirection(),
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )
            // KEY POINTS, legacy: only for a topic that already has some (see KeyPoints). They are shown with
            // the notes at review time and no longer score or cap anything. The editor still stops at
            // MAX_POINTS rather than truncating what was typed.
            if (showKeyPointsField) {
                Spacer(modifier = Modifier.height(12.dp))
                val keyPointCount = com.example.domain.srs.KeyPoints.parse(keyPointsText).size
                val faNum: (Int) -> String = { if (strings.languageCode == "fa") com.example.ui.i18n.PersianDate.faDigits(it.toString()) else it.toString() }
                OutlinedTextField(
                    value = keyPointsText,
                    onValueChange = { typed -> if (com.example.domain.srs.KeyPoints.withinLimit(typed)) keyPointsText = typed },
                    label = {
                        Text(
                            when (strings.languageCode) {
                                "fa" -> "نکات کلیدی (اختیاری)"
                                "de" -> "Kernpunkte (optional)"
                                else -> "Key points (optional)"
                            }
                        )
                    },
                    placeholder = {
                        Text(
                            when (strings.languageCode) {
                                "fa" -> "هر نکته در یک خط"
                                "de" -> "Ein Punkt pro Zeile"
                                else -> "One point per line"
                            }
                        )
                    },
                    supportingText = {
                        val counter = "${faNum(keyPointCount)}/${faNum(com.example.domain.srs.KeyPoints.MAX_POINTS)}"
                        Text(
                            when (strings.languageCode) {
                                "fa" -> "هنگام مرور همراه یادداشت‌هایت نشان داده می‌شود. $counter"
                                "de" -> "Wird beim Wiederholen mit deinen Notizen angezeigt. $counter"
                                else -> "Shown with your notes when you review. $counter"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.autoDirection(),
                    minLines = 2,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(strings.subjectFolder, style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (strings.languageCode) { "fa" -> "مثلاً: شیمی، آناتومی"; "de" -> "z. B.: Chemie, Anatomie"; else -> "e.g. Chemistry, Anatomy" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = showSubjectDropdown,
                onExpandedChange = { showSubjectDropdown = it }
            ) {
                OutlinedTextField(
                    value = subjects.find { it.id == selectedSubjectId }?.name ?: strings.noSubject,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showSubjectDropdown) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = showSubjectDropdown,
                    onDismissRequest = { showSubjectDropdown = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(strings.noSubject) },
                        onClick = { 
                            selectedSubjectId = null
                            showSubjectDropdown = false 
                        }
                    )
                    subjects.forEach { subject ->
                        DropdownMenuItem(
                            text = { Text(subject.name) },
                            onClick = { 
                                selectedSubjectId = subject.id
                                showSubjectDropdown = false 
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(strings.addNewSubject, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                        onClick = { 
                            showSubjectDropdown = false
                            showAddSubjectDialog = true
                        }
                    )
                }
            }
            
            if (showAddSubjectDialog) {
                AlertDialog(
                    onDismissRequest = { showAddSubjectDialog = false },
                    title = { Text(strings.newSubjectTitle) },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = newSubjectName,
                                onValueChange = { newSubjectName = it },
                                label = { Text(strings.subjectName) },
                                textStyle = LocalTextStyle.current.autoDirection(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Simple color presets
                            val presets = listOf("#E57373", "#81C784", "#64B5F6", "#FFD54F", "#BA68C8", "#4DB6AC")
                            // Named for a screen reader: the swatches were nameless buttons, their choice shown only by a border.
                            val colourNames = when (strings.languageCode) {
                                "fa" -> listOf("قرمز", "سبز", "آبی", "زرد", "بنفش", "فیروزه‌ای")
                                "de" -> listOf("Rot", "Grün", "Blau", "Gelb", "Lila", "Türkis")
                                else -> listOf("Red", "Green", "Blue", "Yellow", "Purple", "Teal")
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(presets) { colorHex ->
                                    val parsedColor = androidx.compose.ui.graphics.Color(colorHex.toColorInt())
                                    Card(
                                        onClick = { newSubjectColor = colorHex },
                                        colors = CardDefaults.cardColors(containerColor = parsedColor),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        modifier = Modifier.size(32.dp).padding(2.dp).semantics {
                                            contentDescription = colourNames[presets.indexOf(colorHex)]
                                            selected = newSubjectColor == colorHex
                                        },
                                        border = if (newSubjectColor == colorHex) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null
                                    ) {}
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            // Disabled until there is a name: it used to look ready and do nothing on an empty one.
                            enabled = newSubjectName.isNotBlank(),
                            onClick = {
                                if (newSubjectName.isNotBlank()) {
                                    viewModel.saveSubject(newSubjectName, if (newSubjectColor.isBlank()) null else newSubjectColor) { newId ->
                                        selectedSubjectId = newId
                                    }
                                    newSubjectName = ""
                                    newSubjectColor = ""
                                    showAddSubjectDialog = false
                                }
                            }
                        ) { Text(strings.add) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddSubjectDialog = false }) { Text(strings.cancel) }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = when (strings.languageCode) { "fa" -> "یادداشت / توضیح (اختیاری)"; "de" -> "Notizen / Erklärung (optional)"; else -> "Notes / Explanation (optional)" },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = when (strings.languageCode) {
                    // Not "key points": those have their own field now, and this hint used to promise them here.
                    "fa" -> "یک خلاصه، نکته‌های مهم، اشتباه‌هایی که باید حواست باشد، یا هر چیزی که موقع مرور به کارت می‌آید."
                    "de" -> "Eine kurze Zusammenfassung, Merkpunkte, typische Fehler — alles, was dir beim Wiederholen hilft."
                    else -> "A short summary, high-yield points, mistakes to watch for — anything useful when you review."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(strings.notesExplanation) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.autoDirection(),
                minLines = 3,
                maxLines = 8,
                // Unit-size coaching: scheduling precision depends on topic granularity more than on
                // any algorithm detail — one memory rating can't describe a mega-topic honestly.
                supportingText = if (notes.length > 1500) {
                    {
                        Text(
                            if (strings.languageCode == "fa") "مباحث کوچک‌تر دقیق‌تر زمان‌بندی می‌شوند — اگر می‌شود، این را به چند مبحث بشکن."
                            else if (strings.languageCode == "de") "Kleinere Themen lassen sich genauer planen — teile dieses lieber in mehrere auf."
                            else "Smaller topics schedule more precisely — consider splitting this into a few."
                        )
                    }
                } else null,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = when (strings.languageCode) { "fa" -> "منبع (اختیاری)"; "de" -> "Quelle (optional)"; else -> "Source (optional)" },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = when (strings.languageCode) {
                    "fa" -> "از کجا خواندی؟ مثلاً جزوه، فلش‌کارت، اسلاید، ویدیو."
                    "de" -> "Womit hast du gelernt? z. B. Notizen, Karteikarten, Vorlesung, Video."
                    else -> "What did you study from? e.g. notes, flashcards, lectures, videos."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = sourceLink,
                onValueChange = { sourceLink = it },
                label = { Text(when (strings.languageCode) { "fa" -> "منبع"; "de" -> "Quelle"; else -> "Source" }) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.autoDirection(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(strings.scheduling, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            if (unitId == null) {
                Text(
                    text = if (strings.languageCode == "fa") "بعد از ذخیره، سختی و میزان درکت را ثبت می‌کنی — همین حالا یا بعداً در تب «امروز». برنامهٔ مرور از لحظهٔ همین ثبت شروع می‌شود." else if (strings.languageCode == "de") "Nach dem Speichern bewertest du Schwierigkeit & Verständnis — jetzt gleich oder später unter Heute. Der Wiederholungsplan zählt ab dieser Bewertung." else "After saving, you rate difficulty & understanding — right away, or later in Today. The review schedule counts from that rating.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedCard(onClick = { showStudiedAtPicker = true }) {
                ListItem(
                    headlineContent = { Text(strings.lastStudiedAdded) },
                    supportingContent = {
                        Column {
                            Text(studiedAt?.let { fmtDate(it) } ?: strings.today)
                            Text(
                                text = when (strings.languageCode) { "fa" -> "تاریخی که این را خواندی انتخاب کن"; "de" -> "Wähle den Tag, an dem du es gelernt hast"; else -> "Choose the day you studied this" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // The study date is the replay origin: changing it after real reviews exist
                            // rebuilds this topic's whole schedule history — warn, don't surprise.
                            if ((viewModel.existingUnit?.reviewCount ?: 0) > 0) {
                                Text(
                                    if (strings.languageCode == "fa") "تغییر این تاریخ، تاریخچهٔ مرور این مبحث را بازمحاسبه می‌کند." else if (strings.languageCode == "de") "Wenn du dieses Datum änderst, wird der Wiederholungsverlauf neu berechnet." else "Changing this date recalculates this topic's review history.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    },
                    trailingContent = { Icon(Icons.Default.DateRange, contentDescription = null) }
                )
            }
            
            // Manual next-review override is an advanced control for EXISTING topics only. For a NEW
            // topic the schedule comes from the study date + the first rating in Today, so exposing this
            // here would let someone set a future date and never get asked difficulty/understanding.
            if (unitId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedCard(onClick = { showNextReviewAtPicker = true }) {
                    ListItem(
                        headlineContent = { Text(strings.nextReviewDate) },
                        supportingContent = { Text(nextReviewAt?.let { fmtDate(it) } ?: (if (strings.languageCode == "fa") "پیش‌فرض: روز مطالعه" else if (strings.languageCode == "de") "Standard: am Lerntag" else "Default: on study date")) },
                        trailingContent = { Icon(Icons.Default.DateRange, contentDescription = null) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(strings.highYieldTopic, style = MaterialTheme.typography.titleMedium)
                Switch(checked = highYield, onCheckedChange = { highYield = it })
            }
            
            viewModel.existingUnit?.let { unit ->
                if (unit.reviewCount > 0) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(if (strings.languageCode == "fa") "منحنی فراموشی" else if (strings.languageCode == "de") "Vergessenskurve" else "Forgetting curve", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    ForgettingCurve(
                        stability = unit.stability,
                        model = com.example.domain.srs.MedScheduler.MemoryModel.of(unit.memoryModel),
                        parameterSetId = unit.parameterSetId,
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                    )
                }
            }

            // A NEW topic studied today or earlier can be rated straight away: the study just happened, and
            // the schedule counts from the rating, so rating now keeps the first review on time. A topic
            // planned for a future day is rated on that day, from Today.
            if (unitId == null && (studiedAt ?: 0L) <= System.currentTimeMillis()) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { save(rateNow = true) },
                    enabled = title.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text(
                        when (strings.languageCode) {
                            "fa" -> "ذخیره و ثبت همین حالا"
                            "de" -> "Speichern und jetzt bewerten"
                            else -> "Save and rate now"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // An EXISTING topic can be reviewed right now, due or not: a self-check before a test, a chapter gone over in
            // class today. It opens the same one-topic session as the Library's long-press, so an early review is
            // ordinary FSRS (recall predicted high, a small gain, a lapse is a lapse). The form is saved first, so nothing
            // typed here is lost; an unrated topic gets its first rating.
            val reviewable = viewModel.existingUnit?.takeIf { unitId != null && showReviewNow && !it.archived && it.deletedAt == null }
            if (reviewable != null) {
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { save(rateNow = false, reviewNow = true) },
                    enabled = title.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text(
                        if (reviewable.reviewCount == 0) when (strings.languageCode) {
                            "fa" -> "ذخیره و ثبت همین حالا"
                            "de" -> "Speichern und jetzt bewerten"
                            else -> "Save and rate now"
                        } else when (strings.languageCode) {
                            "fa" -> "ذخیره و مرور همین حالا"
                            "de" -> "Speichern und jetzt wiederholen"
                            else -> "Save and review now"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            val logs by viewModel.reviewLogs.collectAsStateWithLifecycle()
            if (logs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(strings.reviewLogs, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (strings.languageCode == "fa") "برای اصلاح ارزیابی، روی یک مورد بزن." else if (strings.languageCode == "de") "Tippe auf einen Eintrag, um die Bewertung zu korrigieren." else "Tap an entry to correct its rating.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                logs.forEach { log ->
                    Card(
                        onClick = { editingLogId = log.id; editingLogFailed = false },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = com.example.ui.i18n.AppDate.dateTime(useJalali, log.reviewedAt, strings.languageCode == "fa"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val logRating = when (log.memoryRating) {
                                    "Easy" -> com.example.domain.model.MemoryRating.Easy
                                    "Good" -> com.example.domain.model.MemoryRating.Good
                                    "Hard" -> com.example.domain.model.MemoryRating.Hard
                                    else -> com.example.domain.model.MemoryRating.Forgot
                                }
                                val logTone = com.example.ui.theme.ratingTone(logRating)
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = logTone.container),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (log.logType == "FIRST_STUDY") {
                                            val d = when (log.initialDifficulty ?: com.example.domain.srs.MedScheduler.difficultyLabelFor(logRating)) {
                                                // Stored in English (Easy/Medium/Hard); shown in the user's language.
                                                "Easy" -> strings.ratingEasy
                                                "Hard" -> strings.ratingHard
                                                else -> when (strings.languageCode) { "fa" -> "متوسط"; "de" -> "Mittel"; else -> "Medium" }
                                            }
                                            if (strings.languageCode == "fa") "مطالعهٔ اول · $d" else if (strings.languageCode == "de") "Erstes Lernen · $d" else "First study · $d"
                                        } else when (logRating) {
                                            com.example.domain.model.MemoryRating.Easy -> strings.ratingEasy
                                            com.example.domain.model.MemoryRating.Good -> strings.ratingGood
                                            com.example.domain.model.MemoryRating.Hard -> strings.ratingHard
                                            com.example.domain.model.MemoryRating.Forgot -> strings.ratingFail
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = logTone.onContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Built from localized parts: this line used to be hardcoded English AND
                            // to print the raw DB enum ("NeedsRelearn") straight through, bypassing
                            // stateLabel(), so a Persian or German user read internal identifiers.
                            Text(
                                text = run {
                                    val fa = strings.languageCode == "fa"
                                    // Each language's own decimal sign ("10.5d", "10,5 T", "۱۰٫۵ روز").
                                    fun num(v: Double): String {
                                        val s = String.format(java.util.Locale.US, "%.1f", v)
                                        return if (fa) com.example.ui.i18n.PersianDate.faDigits(s)
                                            else if (strings.languageCode == "de") s.replace('.', ',') else s
                                    }
                                    val dayUnit = when (strings.languageCode) {
                                        "fa" -> " روز"; "de" -> " T"; else -> "d"
                                    }
                                    val intervalLabel = when (strings.languageCode) {
                                        "fa" -> "بازه"; "de" -> "Intervall"; else -> "Interval"
                                    }
                                    val stateLabelText = when (strings.languageCode) {
                                        "fa" -> "وضعیت"; "de" -> "Status"; else -> "State"
                                    }
                                    "$intervalLabel: ${num(log.previousIntervalDays)}$dayUnit → " +
                                        "${num(log.nextIntervalDays)}$dayUnit  |  " +
                                        "$stateLabelText: ${strings.stateLabel(log.previousState)} → " +
                                        strings.stateLabel(log.nextState)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            // How this review was done, when the learner said (pilot research data).
                            val methods = com.example.domain.model.ReviewMethod.decode(log.reviewMethods)
                            val hasScore = com.example.domain.model.QuestionScore.isValid(log.questionsCorrect, log.questionsTotal)
                            if (methods.isNotEmpty() || hasScore) {
                                val fa = strings.languageCode == "fa"
                                val methodText = methods.joinToString(" · ") { m ->
                                    when (m) {
                                        com.example.domain.model.ReviewMethod.Questions -> when (strings.languageCode) { "fa" -> "تست و سؤال"; "de" -> "Fragen"; else -> "Questions" }
                                        com.example.domain.model.ReviewMethod.Reading -> when (strings.languageCode) { "fa" -> "خواندن"; "de" -> "Lesen"; else -> "Reading" }
                                        com.example.domain.model.ReviewMethod.Lecture -> when (strings.languageCode) { "fa" -> "کلاس یا ویدیو"; "de" -> "Vorlesung / Video"; else -> "Lecture / video" }
                                        com.example.domain.model.ReviewMethod.Other -> when (strings.languageCode) { "fa" -> "روش دیگر"; "de" -> "Anders"; else -> "Other" }
                                    }
                                }
                                val scoreText = if (hasScore) {
                                    val raw = "${log.questionsCorrect}/${log.questionsTotal}"
                                    if (fa) com.example.ui.i18n.PersianDate.faDigits(raw) else raw
                                } else null
                                Text(
                                    text = listOfNotNull(methodText.ifBlank { null }, scoreText).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    editingLog?.let { log ->
        val fa = strings.languageCode == "fa"
        val isFirstStudy = log.logType == "FIRST_STUDY"
        var mem by androidx.compose.runtime.saveable.rememberSaveable(log.id) { mutableStateOf(runCatching { MemoryRating.valueOf(log.memoryRating) }.getOrDefault(MemoryRating.Good)) }
        var und by androidx.compose.runtime.saveable.rememberSaveable(log.id) {
            mutableStateOf(
                if (log.understandingRating == "NotAsked") null
                else runCatching { UnderstandingRating.valueOf(log.understandingRating) }.getOrDefault(UnderstandingRating.Clear)
            )
        }
        val ratingOptions = if (isFirstStudy) listOf(MemoryRating.Easy, MemoryRating.Good, MemoryRating.Hard) else MemoryRating.entries
        val understandingRequired = mem != MemoryRating.Forgot
        // The day this review happened, correctable between the reviews saved before and after it (saved order) and
        // never in the future (com.example.domain.model.ReviewDay, the owner's decision 2026-10-09).
        val zone = java.time.ZoneId.systemDefault()
        val history = viewModel.reviewLogs.collectAsStateWithLifecycle().value
        val ordered = remember(log.id, history) { history.sortedWith(com.example.data.local.entity.REVIEW_HISTORY_ORDER) }
        val position = ordered.indexOfFirst { it.id == log.id }
        val previousAt = if (position > 0) ordered[position - 1].reviewedAt else null
        val nextAt = if (position >= 0) ordered.getOrNull(position + 1)?.reviewedAt else null
        val dayRange = remember(log.id, previousAt, nextAt) {
            com.example.domain.model.ReviewDay.correctionRange(previousAt, nextAt, System.currentTimeMillis(), zone)
        }
        val originalDay = remember(log.id) { com.example.domain.model.ReviewDay.day(log.reviewedAt, zone) }
        var newDay by androidx.compose.runtime.saveable.rememberSaveable(log.id) { mutableStateOf<java.time.LocalDate?>(null) }
        var showDayPicker by androidx.compose.runtime.saveable.rememberSaveable(log.id) { mutableStateOf(false) }
        var dayError by remember(log.id) { mutableStateOf(false) }
        fun noonOf(day: java.time.LocalDate): Long = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val shownDay = newDay ?: originalDay
        AlertDialog(
            onDismissRequest = { editingLogId = null },
            title = { Text(if (fa) "اصلاح ارزیابی" else if (strings.languageCode == "de") (if (isFirstStudy) "Erste Bewertung korrigieren" else "Bewertung korrigieren") else if (isFirstStudy) "Correct first-study rating" else "Correct this rating") },
            text = {
                Column {
                    Text(if (isFirstStudy) (if (fa) "سختی اولیه" else if (strings.languageCode == "de") "Anfängliche Schwierigkeit" else "Initial difficulty") else strings.memoryRating, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ratingOptions.forEach { r ->
                            FilterChip(
                                selected = mem == r,
                                onClick = { mem = r; editingLogFailed = false },
                                label = { Text(when (r) {
                                    MemoryRating.Forgot -> strings.ratingFail
                                    MemoryRating.Hard -> strings.ratingHard
                                    // A first study asked "how difficult was this topic?", where this grade
                                    // is called Medium, so correcting it must use the same word.
                                    MemoryRating.Good -> if (isFirstStudy) {
                                        when (strings.languageCode) { "fa" -> "متوسط"; "de" -> "Mittel"; else -> "Medium" }
                                    } else strings.ratingGood
                                    MemoryRating.Easy -> strings.ratingEasy
                                }) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(strings.understandingRating, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UnderstandingRating.entries.forEach { r ->
                            FilterChip(
                                selected = und == r,
                                onClick = { und = r; editingLogError = false; editingLogFailed = false },
                                label = { Text(when (r) {
                                    UnderstandingRating.Confused -> strings.urConfused
                                    UnderstandingRating.Partial -> strings.urPartial
                                    UnderstandingRating.Clear -> strings.urClear
                                }) }
                            )
                        }
                    }
                    if (und == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (fa) "در ارزیابی اصلی، درک پرسیده نشد. اگر امتیاز دیگر فراموشی نیست، یک گزینه انتخاب کن." else if (strings.languageCode == "de") "Das Verständnis wurde ursprünglich nicht abgefragt. Wähle eine Option, wenn die Bewertung nicht mehr „Vergessen“ ist." else "Understanding was not asked originally. Choose one if the rating is no longer Forgot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (editingLogError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (isFirstStudy) (if (fa) "روز مطالعه" else if (strings.languageCode == "de") "Lerntag" else "Day studied")
                        else (if (fa) "روز مرور" else if (strings.languageCode == "de") "Tag der Wiederholung" else "Day reviewed"),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(onClick = { showDayPicker = true }, enabled = dayRange != null) {
                        Text(fmtDate(noonOf(shownDay)))
                    }
                    if (dayError && dayRange != null) {
                        Text(
                            text = run {
                                val from = fmtDate(noonOf(dayRange.start))
                                val to = fmtDate(noonOf(dayRange.endInclusive))
                                if (fa) "روزی بین $from و $to انتخاب کن: ترتیب مرورهای این مبحث عوض نمی‌شود."
                                else if (strings.languageCode == "de") "Wähle einen Tag zwischen $from und $to: die Reihenfolge der Wiederholungen bleibt."
                                else "Choose a day between $from and $to: the order of this topic's reviews stays as it is."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (editingLogFailed) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (fa) "این اصلاح ذخیره نشد و چیزی تغییر نکرد." else if (strings.languageCode == "de") "Diese Korrektur wurde nicht gespeichert; nichts wurde geändert." else "This correction could not be saved, and nothing was changed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (fa) "کل زمان‌بندی این مبحث بر اساس ارزیابی اصلاح‌شده بازمحاسبه می‌شود." else if (strings.languageCode == "de") "Der gesamte Zeitplan dieses Themas wird aus der korrigierten Bewertung neu berechnet." else "This topic's whole schedule is recalculated from the corrected rating.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(
                enabled = !editingLogSaving,
                onClick = {
                    if (understandingRequired && und == null) {
                        editingLogError = true
                        return@TextButton
                    }
                    editingLogSaving = true
                    editingLogError = false
                    editingLogFailed = false
                    // A moved day keeps the review's own hour, inside its neighbours (ReviewDay.timeForCorrection).
                    val movedTo = newDay?.takeIf { it != originalDay }?.let { day ->
                        com.example.domain.model.ReviewDay.timeForCorrection(day, log.reviewedAt, previousAt, nextAt, System.currentTimeMillis(), zone)
                    }
                    viewModel.editReviewRating(log.id, mem, und, movedTo) { success ->
                        editingLogSaving = false
                        if (success) {
                            // The replay just recomputed this topic's schedule, but the FORM still holds
                            // the pre-correction dates (the populate effect is one-shot, guarded by
                            // loadedFromUnit). Without this re-sync, pressing Save afterwards wrote the
                            // stale date back — reverting the replayed due date AND recording it as a
                            // manual deferral the user never made (deferredUntil), which is exactly what
                            // the v5 honest-scheduling model forbids.
                            // A date the learner changed in the form and has not saved yet stays as they set it (it used
                            // to be replaced without a word; a production review, 2026-10-10); an untouched one follows
                            // the replay. Either way the replayed dates are what the form now compares with.
                            viewModel.existingUnit?.let { replayed ->
                                if (studiedAt == loadedStudiedAt) studiedAt = replayed.studiedAt
                                if (nextReviewAt == loadedNextReviewAt) nextReviewAt = replayed.nextReviewAt
                                loadedStudiedAt = replayed.studiedAt
                                loadedNextReviewAt = replayed.nextReviewAt
                            }
                            // Replay can move the due date → keep the reminder + widget in sync only
                            // after the database transaction has completed.
                            com.example.notifications.NotificationScheduler.scheduleDailyReminder(reminderContext)
                            com.example.notifications.TodayRefresh.afterChange(reminderContext)
                            editingLogId = null
                        } else {
                            editingLogFailed = true
                        }
                    }
                }
            ) { Text(if (editingLogSaving) "…" else strings.save) } },
            dismissButton = { TextButton(onClick = { editingLogId = null }) { Text(strings.cancel) } }
        )

        if (showDayPicker && dayRange != null) {
            // A day outside the neighbours would reorder the history: refused here, and again by the repository.
            fun accept(day: java.time.LocalDate) {
                if (day in dayRange) { newDay = day; dayError = false; editingLogFailed = false } else dayError = true
                showDayPicker = false
            }
            if (useJalali) {
                com.example.ui.components.JalaliDatePickerDialog(
                    initialMillis = noonOf(shownDay),
                    onDismiss = { showDayPicker = false },
                    onConfirm = { millis -> accept(com.example.domain.model.ReviewDay.day(millis, zone)) },
                )
            } else {
                val dayState = rememberDatePickerState(
                    initialSelectedDateMillis = com.example.ui.i18n.AppDate.pickerSelection(noonOf(shownDay)),
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate() in dayRange
                    },
                )
                DatePickerDialog(
                    onDismissRequest = { showDayPicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            val picked = dayState.selectedDateMillis
                            if (picked != null) accept(java.time.Instant.ofEpochMilli(picked).atZone(java.time.ZoneOffset.UTC).toLocalDate())
                            else showDayPicker = false
                        }) { Text(strings.okBtn) }
                    },
                    dismissButton = { TextButton(onClick = { showDayPicker = false }) { Text(strings.cancel) } },
                ) {
                    DatePicker(state = dayState)
                }
            }
        }
    }

    if (showStudiedAtPicker) {
        if (useJalali) {
            // Jalali-calendar users pick on a real Jalali (شمسی) grid — leap years exact.
            com.example.ui.components.JalaliDatePickerDialog(
                initialMillis = studiedAt ?: System.currentTimeMillis(),
                onDismiss = { showStudiedAtPicker = false },
                onConfirm = { millis -> studiedAt = keepIfSameDay(studiedOn(millis), studiedAt); showStudiedAtPicker = false },
            )
        } else {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = com.example.ui.i18n.AppDate.pickerSelection(studiedAt ?: System.currentTimeMillis()))
            DatePickerDialog(
                onDismissRequest = { showStudiedAtPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        // A date typed in and then cleared selects nothing: the date stays as it was. It used to become
                        // null, which the card shows as "Today" while Save keeps the stored date (2026-10-10).
                        datePickerState.selectedDateMillis?.let { studiedAt = keepIfSameDay(studiedOn(datePickerUtcToLocalDay(it)), studiedAt) }
                        showStudiedAtPicker = false
                    }) { Text(strings.okBtn) }
                },
                dismissButton = {
                    TextButton(onClick = { showStudiedAtPicker = false }) { Text(strings.cancel) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }

    if (showNextReviewAtPicker) {
        if (useJalali) {
            com.example.ui.components.JalaliDatePickerDialog(
                initialMillis = nextReviewAt ?: (System.currentTimeMillis() + 86400000),
                onDismiss = { showNextReviewAtPicker = false },
                onConfirm = { millis -> nextReviewAt = keepIfSameDay(millis, nextReviewAt); showNextReviewAtPicker = false },
            )
        } else {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = com.example.ui.i18n.AppDate.pickerSelection(nextReviewAt ?: (System.currentTimeMillis() + 86400000)))
            DatePickerDialog(
                onDismissRequest = { showNextReviewAtPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        // As above: nothing selected leaves the date as it was.
                        datePickerState.selectedDateMillis?.let { nextReviewAt = keepIfSameDay(datePickerUtcToLocalDay(it), nextReviewAt) }
                        showNextReviewAtPicker = false
                    }) { Text(strings.okBtn) }
                },
                dismissButton = {
                    TextButton(onClick = { showNextReviewAtPicker = false }) { Text(strings.cancel) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

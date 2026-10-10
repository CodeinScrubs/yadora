package com.example.ui.today

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.core.graphics.toColorInt
import com.example.ui.i18n.autoDirection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.data.local.entity.StudyUnitEntity
import com.example.data.repository.MedReviewRepository
import com.example.ui.i18n.stateLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** "Next up": how many topics Today lists at first, and at most after "more". */
private const val NEXT_UP_SHOWN = 10
private const val NEXT_UP_MAX = 30

class TodayViewModel(private val repository: MedReviewRepository) : ViewModel() {
    // Day boundaries are computed fresh on each emission (not once at construction), so the Today
    // categories stay correct even if the app is left open into a new day.
    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59)
        set(java.util.Calendar.MILLISECOND, 999)
    }.timeInMillis

    /**
     * The minute, for what the screen reads from the clock (the greeting, "due now", the exam countdown): the screen
     * recomposes only when a value it reads changes, so a Today left open kept the morning's greeting in the afternoon
     * (a production review, 2026-10-10).
     */
    val minuteTick: StateFlow<Long> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Room flows only emit on DATABASE changes — if the app sits open across midnight untouched,
    // yesterday's classification would stick. This ticker re-emits when the local DATE changes, checked each minute, so
    // Due/Overdue/Upcoming reclassify from time passing alone. Checked by the minute, not timed to the next midnight: a
    // time-zone change (a trip with the app open) moves midnight, and the old zone's midnight could be most of a day away
    // (a production review, 2026-10-10).
    private val dayTick = kotlinx.coroutines.flow.flow {
        var shown: java.time.LocalDate? = null
        while (true) {
            val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
            if (today != shown) {
                shown = today
                emit(Unit)
            }
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    /** One midnight timer for the whole screen, shared by everything that depends on the date. */
    private val sharedDayTick = dayTick.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    /**
     * The active topics, read ONCE per database change (or midnight) and shared by every list below. Each list
     * used to collect repository.activeUnits on its own, so every write ran the full-library query five times,
     * with five midnight timers beside it (an outside audit counted the queries, 2026-09-27). The day bounds are
     * still read at each emission, so the lists reclassify at midnight exactly as before.
     */
    private val activeNow = repository.activeUnits
        // The order of today's share and of Next up reads each topic on its own weight set, so they are loaded first.
        .onStart { repository.ensureMemoryModelLoaded() }
        .combine(sharedDayTick) { units, _ -> units }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val dueUnits: StateFlow<List<StudyUnitEntity>> = activeNow.map { units ->
        val end = endOfToday()
        units.filter { TodayBuckets.isDueByEndOfToday(it.nextReviewAt, end) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val overdueUnits = activeNow.map { units ->
        val start = startOfToday()
        units.filter { TodayBuckets.isOverdue(it.nextReviewAt, start) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dueTodayUnits = activeNow.map { units ->
        val start = startOfToday()
        val end = endOfToday()
        units.filter { TodayBuckets.isDueToday(it.nextReviewAt, start, end) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // The full upcoming list (not capped at 5), for the "what's coming" calendar dialog.
    val allUpcoming: StateFlow<List<StudyUnitEntity>> = activeNow.map { units ->
        val end = endOfToday()
        units.filter { TodayBuckets.isUpcoming(it.nextReviewAt, end) }.sortedBy { it.nextReviewAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingUnits: StateFlow<List<StudyUnitEntity>> = allUpcoming.map { it.take(5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * "Next up, weakest first": rated topics not due today, lowest predicted recall first (ReviewAhead), each a tap away
     * from an early review. It replaced the Review ahead session (the owner's decision, 2026-10-09: the learner picks
     * topics, there is no session). Computed off the main thread whenever the library changes.
     */
    val nextUp: StateFlow<List<StudyUnitEntity>> = activeNow
        .map { units -> repository.reviewAheadOf(units, System.currentTimeMillis(), NEXT_UP_MAX) }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val subjects: StateFlow<List<com.example.data.local.entity.SubjectEntity>> = repository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -1 = not loaded yet: the first-run welcome must never flash for an existing user while the DB
    // is still emitting, so the UI only treats a REAL 0 as "library is empty".
    val totalActive: StateFlow<Int> = repository.totalActiveCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    /**
     * Every topic, archived ones included (-1 = not read yet): the first-run welcome is for a library that is truly
     * empty. It counted active topics only, so a learner who had archived everything was welcomed as new (a production
     * review, 2026-10-10).
     */
    val libraryCount: StateFlow<Int> = repository.libraryCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    /** The library has been read once: until then every list above is empty because nothing is known yet. */
    val libraryLoaded: StateFlow<Boolean> = activeNow.map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Reviews done today (first ratings excluded): what the daily limit counts. Resets at midnight. -1 = not read yet. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val reviewsDoneToday: StateFlow<Int> = sharedDayTick
        .flatMapLatest { repository.observeReviewsBetween(startOfToday(), endOfToday()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    // "Spread out" (OverdueRedistributor.deferrals) is no longer offered here (the owner's decision, 2026-10-09): the line
    // after today's share says what to do today, and a backlog stays visible, most urgent first, instead of being moved
    // onto later days. Deferrals it wrote earlier are honoured as before.
}

@Suppress("UNCHECKED_CAST")
class TodayViewModelFactory(private val repository: MedReviewRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TodayViewModel(repository) as T
    }
}

/**
 * "What's coming" — upcoming reviews grouped by due date, so the user gets a feel for the days ahead.
 * Read-only; tapping a topic isn't needed here (that's what Library is for).
 */
@Composable
private fun UpcomingScheduleDialog(
    upcoming: List<StudyUnitEntity>,
    useJalali: Boolean,
    languageCode: String,
    onDismiss: () -> Unit,
) {
    val byDay = remember(upcoming) {
        upcoming.groupBy {
            java.util.Calendar.getInstance().apply {
                timeInMillis = it.nextReviewAt
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.toSortedMap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(when (languageCode) { "fa" -> "بستن"; "de" -> "Schließen"; else -> "Close" }) } },
        title = { Text(when (languageCode) { "fa" -> "روزهای پیش رو"; "de" -> "Kommende Tage"; else -> "The days ahead" }) },
        text = {
            if (byDay.isEmpty()) {
                Text(when (languageCode) { "fa" -> "فعلاً چیزی در برنامه نیست."; "de" -> "Noch nichts geplant."; else -> "Nothing scheduled yet." })
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    byDay.forEach { (day, topics) ->
                        item {
                            Text(
                                text = com.example.ui.i18n.AppDate.weekdayDate(useJalali, day, languageCode == "fa"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                            )
                        }
                        items(topics) { t ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (t.highYield) {
                                    Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(t.title, style = MaterialTheme.typography.bodyMedium.autoDirection(), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    repository: MedReviewRepository,
    onNavigateToAdd: () -> Unit,
    onNavigateToReview: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    /**
     * One topic tapped in Today's list, and where it was (a SessionKind name): PLAN in today's share, EXTRA below its
     * line, AHEAD in "next up". There is no session to start (the owner's decision, 2026-10-09): the learner picks.
     */
    onReviewFromToday: (unitId: Long, kind: String) -> Unit = { id, _ -> onNavigateToReview(id) },
) {
    val viewModel: TodayViewModel = viewModel(factory = TodayViewModelFactory(repository))
    
    // Notification permission is requested once in MainActivity; not duplicated here.

    val due by viewModel.dueUnits.collectAsStateWithLifecycle()
    val overdue by viewModel.overdueUnits.collectAsStateWithLifecycle()
    val dueToday by viewModel.dueTodayUnits.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcomingUnits.collectAsStateWithLifecycle()
    val allUpcoming by viewModel.allUpcoming.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val totalActive by viewModel.totalActive.collectAsStateWithLifecycle()
    val libraryCount by viewModel.libraryCount.collectAsStateWithLifecycle()
    // Read again at every minute (TodayViewModel.minuteTick): the greeting, "due now" and the countdown follow the clock.
    val minute by viewModel.minuteTick.collectAsStateWithLifecycle()
    val now = remember(minute) { System.currentTimeMillis() }
    var showUpcomingSchedule by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) } // kept through a rotation (UI-01)
        val strings = com.example.ui.i18n.LocalStrings.current
    val useJalali = com.example.ui.i18n.LocalUseJalali.current

    // Disambiguation: if two active topics share a title, surface whatever differs (a note/source
    // snippet, else subject) so the user can tell them apart on the card.
    // Titles are compared through TopicTitle, like the duplicate warning on save: a plain lowercase()
    // cannot see that the same Persian word was typed on two different keyboards.
    val nextUp by viewModel.nextUp.collectAsStateWithLifecycle()
    val dupTitles = remember(overdue, dueToday, nextUp) {
        (overdue + dueToday + nextUp).groupingBy { com.example.data.text.TopicTitle.normalize(it.title) }.eachCount()
            .filterValues { it > 1 }.keys
    }
    val disambOf: (StudyUnitEntity) -> String? = { u ->
        if (com.example.data.text.TopicTitle.normalize(u.title) !in dupTitles) null
        else u.notes?.trim()?.take(40)?.takeIf { it.isNotBlank() }
            ?: u.source?.trim()?.take(40)?.takeIf { it.isNotBlank() }
            ?: subjects.find { it.id == u.subjectId }?.name
    }
        
        val totalDue = overdue.size + dueToday.size
    // The review queue is capped at the daily limit, so show the count that will actually load (no lie).
    val ctxForLimit = androidx.compose.ui.platform.LocalContext.current
    // Live-read the limit so changing it in Settings reflects here without needing an app restart.
    val dailyLimit by androidx.compose.runtime.produceState(
        initialValue = com.example.domain.srs.MedScheduler.safeDailyLimit(ctxForLimit.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE).getFloat("daily_review_limit", 50f))
    ) {
        val sp = ctxForLimit.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "daily_review_limit") value = com.example.domain.srs.MedScheduler.safeDailyLimit(prefs.getFloat("daily_review_limit", 50f))
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Today's plan (DailyPlan): every first rating plus the reviews that fit in what is left of the DAILY limit. Since
    // 2026-10-09 it is "today's share": the top of one list the learner picks from (no session), and what the reminders
    // and the widget count. The line after it keeps the rest of the day for new material, the largest lever in the
    // one-year simulation (RESEARCH.md §2.8); everything below it stays listed, most urgent first, never hidden.
    //
    // No time estimate: a review is done however the learner likes, mostly outside the app (questions,
    // a lecture, a video), so the seconds a card sits open measure nothing and "about N min" would be a
    // made-up number.
    val doneToday by viewModel.reviewsDoneToday.collectAsStateWithLifecycle()
    // Nothing is drawn from the lists until the library and today's count have been read: on a cold start the empty
    // first values drew "Finished for today" for a moment, and a share that ignored the reviews already done (a
    // production review, 2026-10-10).
    val libraryLoaded by viewModel.libraryLoaded.collectAsStateWithLifecycle()
    val ready = libraryLoaded && doneToday >= 0
    val plan = remember(due, doneToday, dailyLimit) {
        DailyPlan.plan(due, doneToday, dailyLimit, System.currentTimeMillis())
    }
    val displayDue = plan.size
    // Below the line: every other due review, in the same urgency order (DailyPlan.byPriority).
    val belowLine = remember(due, plan) {
        val offered = plan.reviews.mapTo(HashSet()) { it.id }
        DailyPlan.byPriority(due.filterNot { DailyPlan.isFirstRating(it) }, System.currentTimeMillis()).filter { it.id !in offered }
    }
    var nextUpExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    if (showUpcomingSchedule) {
        UpcomingScheduleDialog(
            upcoming = allUpcoming,
            useJalali = useJalali,
            languageCode = strings.languageCode,
            onDismiss = { showUpcomingSchedule = false }
        )
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(strings.todayDateTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(percent = 50))
                    ) {
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Settings, contentDescription = strings.settings, tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = strings.addNewTopic)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Exam countdown (top-left), shown only when an exam name + future date are set.
            // Shared logic (ExamCountdown) — exam day itself is not counted; same text on all screens.
            val examSp = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
            val examText = com.example.ui.i18n.ExamCountdown.text(
                examSp.getString("exam_name", "") ?: "", examSp.getLong("exam_date", 0L), strings.languageCode, now,
            )
            if (examText != null) {
                Text(
                    text = examText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                )
            }
            // One calm hero card: greeting, what's on the plate, and the single primary action.
            run {
                // When nothing is planned, the calm cards below cover it — don't double up here.
                if (displayDue == 0 || !ready) return@run
                val planned = plan.queue
                val highYieldCount = planned.count { it.highYield }
                val weakCount = planned.count { it.state == "NeedsRelearn" || it.state == "Learning" }
                val newCount = plan.firstRatings.size
                val isFa = strings.languageCode == "fa"
                fun n(v: Int) = if (isFa) com.example.ui.i18n.PersianDate.faDigits(v) else v.toString()
                val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
                val greeting = when {
                    hour < 12 -> if (isFa) "صبح بخیر" else if (strings.languageCode == "de") "Guten Morgen" else "Good morning"
                    hour < 18 -> if (isFa) "بعدازظهر بخیر" else if (strings.languageCode == "de") "Guten Tag" else "Good afternoon"
                    else -> if (isFa) "عصر بخیر" else if (strings.languageCode == "de") "Guten Abend" else "Good evening"
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(greeting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isFa) "${n(displayDue)} مورد برای امروز" else if (strings.languageCode == "de") "$displayDue ${if (displayDue == 1) "Thema" else "Themen"} für heute" else "$displayDue ${if (displayDue == 1) "topic" else "topics"} for today",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // What the count is made of: new studies waiting for their first rating are
                        // quick and never held back; reviews are the real work.
                        val parts = buildList {
                            if (newCount > 0) add(if (isFa) "${n(newCount)} مطالعهٔ جدید برای ثبت" else if (strings.languageCode == "de") "$newCount neu zu erfassen" else "$newCount new to log")
                            if (plan.reviews.isNotEmpty()) add(if (isFa) "${n(plan.reviews.size)} مرور" else if (strings.languageCode == "de") "${plan.reviews.size} ${if (plan.reviews.size == 1) "Wiederholung" else "Wiederholungen"}" else "${plan.reviews.size} ${if (plan.reviews.size == 1) "review" else "reviews"}")
                            if (highYieldCount > 0) add(if (isFa) "${n(highYieldCount)} مهم" else if (strings.languageCode == "de") "$highYieldCount wichtig" else "$highYieldCount important")
                            if (weakCount > 0) add(if (isFa) "${n(weakCount)} ضعیف" else if (strings.languageCode == "de") "$weakCount schwach" else "$weakCount weak")
                        }
                        if (parts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = parts.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (plan.heldBack > 0) {
                            // Transparency: the daily limit is managing the load, not hiding it — the most urgent
                            // reviews are today's share, and the rest stay listed below its line.
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFa) "${n(plan.heldBack)} مرور دیگر زیر خط سهم امروز، فوری‌ترین اول؛ برای وقتی که وقت داری. سقف روزانه بقیهٔ روز را برای مطالب جدید نگه می‌دارد."
                                    else if (strings.languageCode == "de") "${plan.heldBack} weitere unter der Linie des heutigen Anteils, die dringendsten zuerst: für freie Zeit. Dein Tageslimit hält den Rest des Tages für neuen Stoff frei."
                                    else "${plan.heldBack} more below the line of today's share, most urgent first: for when you have time. Your daily limit keeps the rest of the day for new material.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // No "Start review" button (the owner's decision, 2026-10-09): the learner picks a topic below,
                        // studies it however they like and rates it; Yadora is not a flashcard session.
                    }
                }
            }

            // Today's limit is used up while reviews are still due. Said plainly; the rest stay listed below, a tap away:
            // the limit protects the learner's day, it must never stand between them and a review they want.
            if (plan.limitReached) {
                val isFa = strings.languageCode == "fa"
                fun n(v: Int) = if (isFa) com.example.ui.i18n.PersianDate.faDigits(v) else v.toString()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(
                            text = if (isFa) "سهم امروز انجام شد" else if (strings.languageCode == "de") "Tagesziel erreicht" else "Today's reviews are done",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isFa) "امروز ${n(plan.doneToday)} مرور انجام دادی و به سقف روزانه‌ات رسیدی. ${n(plan.heldBack)} مرور دیگر پایین‌تر است، فوری‌ترین اول: تا فردا صبر می‌کنند، یا اگر وقت داری سراغشان برو."
                                else if (strings.languageCode == "de") "Du hast heute ${if (plan.doneToday == 1) "1 Wiederholung" else "${plan.doneToday} Wiederholungen"} gemacht und dein Tageslimit erreicht. ${plan.heldBack} ${if (plan.heldBack == 1) "weitere steht" else "weitere stehen"} unten, die dringendsten zuerst: Sie können bis morgen warten, oder du nimmst sie dir vor, wenn du Zeit hast."
                                else "You did ${if (plan.doneToday == 1) "1 review" else "${plan.doneToday} reviews"} today and reached your daily limit. ${plan.heldBack} more ${if (plan.heldBack == 1) "is" else "are"} below, most urgent first: they can wait until tomorrow, or take them if you have time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Automatic backup, suggested once there is a real history to lose (AutoBackup.shouldNudge).
            val nudgeContext = androidx.compose.ui.platform.LocalContext.current
            var nudgeTick by remember { mutableIntStateOf(0) }
            val showBackupNudge = remember(totalActive, nudgeTick) {
                val tp = com.example.notifications.NotificationScheduler.transientPrefs(nudgeContext)
                com.example.data.AutoBackup.shouldNudge(
                    topics = totalActive,
                    autoOn = com.example.data.AutoBackup.isOn(nudgeContext),
                    now = System.currentTimeMillis(),
                    lastManualAt = tp.getLong(com.example.data.AutoBackup.PREF_LAST_MANUAL_AT, 0L),
                    dismissedAt = tp.getLong(com.example.data.AutoBackup.PREF_NUDGE_DISMISSED_AT, 0L),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room under the last card for the floating + button, which otherwise covered its end (on a phone it
                // sat over the Upcoming section's expand arrow).
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!ready) return@LazyColumn
                if (showBackupNudge) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    when (strings.languageCode) {
                                        "fa" -> "از تاریخچهٔ مطالعه‌ات محافظت کن"
                                        "de" -> "Schütze deinen Lernverlauf"
                                        else -> "Protect your study history"
                                    },
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    when (strings.languageCode) {
                                        "fa" -> "برنامهٔ مرورهایت از روی همین تاریخچه ساخته می‌شود و فعلاً فقط روی این گوشی است. پشتیبان‌گیری خودکار هر روز یک نسخه در پوشه‌ای که انتخاب می‌کنی ذخیره می‌کند."
                                        "de" -> "Dein Wiederholungsplan wird aus diesem Verlauf berechnet, und er liegt bisher nur auf diesem Handy. Die automatische Sicherung legt jeden Tag eine Kopie in einem Ordner deiner Wahl ab."
                                        else -> "Your review schedule is computed from this history, and right now it lives only on this phone. Automatic backup saves a copy every day in a folder you choose."
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onNavigateToSettings) {
                                        Text(when (strings.languageCode) { "fa" -> "تنظیم پشتیبان‌گیری"; "de" -> "Einrichten"; else -> "Set it up" })
                                    }
                                    TextButton(onClick = {
                                        com.example.notifications.NotificationScheduler.transientPrefs(nudgeContext).edit {
                                            putLong(com.example.data.AutoBackup.PREF_NUDGE_DISMISSED_AT, System.currentTimeMillis())
                                        }
                                        nudgeTick++
                                    }) {
                                        Text(when (strings.languageCode) { "fa" -> "بعداً"; "de" -> "Später"; else -> "Not now" })
                                    }
                                }
                            }
                        }
                    }
                }
                if (totalDue == 0) {
                    item {
                        val isFarsi = strings.languageCode == "fa"
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // First run (library truly empty, count loaded): "caught up" would be
                                // confusing before anything was ever added — greet and point at "+".
                                if (libraryCount == 0) {
                                    Text(
                                        text = when (strings.languageCode) { "fa" -> "به یادورا خوش آمدی"; "de" -> "Willkommen bei Yadora"; else -> "Welcome to Yadora" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    // The whole loop, once, before anything exists. Users of apps like this keep
                                    // asking for exactly this ("no tutorial, I didn't understand what to do").
                                    Text(
                                        text = when (strings.languageCode) {
                                            "fa" -> "۱. هر طور که دوست داری بخوان: یک فصل، یک کلاس، یک بلوک تست.\n" +
                                                "۲. با دکمهٔ + ثبتش کن و بگو چطور پیش رفت. اولین مرور از همان لحظه برنامه‌ریزی می‌شود.\n" +
                                                "۳. هر مبحث را وقتی یادورا یادآوری کرد، به هر روشی که دوست داری مرور کن و بگو چقدر از آن یادت مانده بود. تاریخ بعدی از روی جوابت تعیین می‌شود."
                                            "de" -> "1. Lerne, wie du willst: ein Kapitel, eine Vorlesung, einen Fragenblock.\n" +
                                                "2. Trag es mit + ein und bewerte, wie es lief. Die erste Wiederholung wird ab diesem Moment geplant.\n" +
                                                "3. Wiederhole jedes Thema, wenn Yadora dich erinnert, auf beliebige Weise, und sag, wie viel du noch wusstest. Der nächste Termin ergibt sich aus deiner Antwort."
                                            else -> "1. Study however you like: a chapter, a lecture, a block of questions.\n" +
                                                "2. Tap + to log it and rate how it went. The first review is planned from that moment.\n" +
                                                "3. Review each topic when Yadora reminds you, any way you like, then say how much you still remembered. The next date follows from your answer."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = when (strings.languageCode) { "fa" -> "برای امروز تمام شد"; "de" -> "Für heute fertig"; else -> "Finished for today" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        // Peek at what's coming: opens a by-day list of upcoming reviews.
                                        IconButton(onClick = { showUpcomingSchedule = true }, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                imageVector = Icons.Default.DateRange,
                                                contentDescription = when (strings.languageCode) { "fa" -> "برنامهٔ روزهای آینده"; "de" -> "Kommende Tage"; else -> "Upcoming schedule" },
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    // Honest next-review line: the real date of the next upcoming item,
                                    // not a hardcoded "tomorrow" (which was simply wrong for longer gaps).
                                    val nextDue = upcoming.firstOrNull()
                                    Text(
                                        text = if (nextDue == null) (when (strings.languageCode) { "fa" -> "فعلاً چیزی در برنامه نیست."; "de" -> "Noch nichts geplant."; else -> "Nothing scheduled yet." })
                                               else (when (strings.languageCode) { "fa" -> "مرور بعدی: "; "de" -> "Nächste: "; else -> "Next: " }) + com.example.ui.i18n.AppDate.weekdayDate(useJalali, nextDue.nextReviewAt, strings.languageCode == "fa"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Today's share: first ratings, then the most urgent reviews, up to the daily limit. A tap opens that
                    // topic to rate; the learner studies it however they like, and there is no session to start.
                    if (plan.size > 0) {
                        item {
                            Text(
                                when (strings.languageCode) { "fa" -> "سهم امروز"; "de" -> "HEUTIGER ANTEIL"; else -> "TODAY'S SHARE" },
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(plan.queue, key = { "share-${it.id}" }) { unit ->
                            StudyUnitCard(unit, subjects, onClick = { onReviewFromToday(unit.id, "PLAN") }, disambiguator = disambOf(unit), now = now)
                        }
                    }
                    // The line: the share ends where the daily limit does, and the rest of the day belongs to new material.
                    // Everything else that is due stays listed below it, most urgent first, never hidden or moved.
                    if (belowLine.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    when (strings.languageCode) {
                                        "fa" -> "سهم امروز تا اینجاست؛ بقیهٔ وقتت برای مطالب جدید. پایین‌تر: بقیهٔ موعدرسیده‌ها، فوری‌ترین اول."
                                        "de" -> "Hier endet der heutige Anteil; der Rest deiner Zeit gehört neuem Stoff. Darunter: alles Weitere, was fällig ist, das Dringendste zuerst."
                                        else -> "Today's share ends here; the rest of your time is for new material. Below: the rest of what is due, most urgent first."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(belowLine, key = { "below-${it.id}" }) { unit ->
                            StudyUnitCard(unit, subjects, onClick = { onReviewFromToday(unit.id, "EXTRA") }, disambiguator = disambOf(unit), now = now)
                        }
                    }
                }
                // Next up, weakest first (ReviewAhead): rated topics not due today, lowest predicted recall first, a tap from
                // an early review. It replaced the Review ahead session (the owner's decision, 2026-10-09).
                if (nextUp.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    when (strings.languageCode) { "fa" -> "بعدی‌ها، ضعیف‌ترین اول"; "de" -> "ALS NÄCHSTES · DIE SCHWÄCHSTEN ZUERST"; else -> "NEXT UP · WEAKEST FIRST" },
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                // Peek at what's coming: opens a by-day list of upcoming reviews.
                                IconButton(onClick = { showUpcomingSchedule = true }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.DateRange,
                                        contentDescription = when (strings.languageCode) { "fa" -> "برنامهٔ روزهای آینده"; "de" -> "Kommende Tage"; else -> "Upcoming schedule" },
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                text = when (strings.languageCode) {
                                    // A good use of ANY spare time, not only the last weeks: with a fixed daily budget,
                                    // spare evenings spent here left more on exam day than a higher target did
                                    // (tools/pilot/one_exam.py, docs/RESEARCH.md 2.8).
                                    "fa" -> "مباحثی که هنوز موعدشان نرسیده، از ضعیف‌ترین: استفادهٔ خوبی از وقت اضافه، مخصوصاً در هفته‌های پیش از امتحان. مرورِ زودتر از موعد، حافظه را کمتر از مرورِ به‌موقع تقویت می‌کند."
                                    "de" -> "Noch nicht fällige Themen, die schwächsten zuerst: gut für freie Zeit, vor allem in den Wochen vor einer Prüfung. Eine frühe Wiederholung stärkt das Gedächtnis weniger als eine pünktliche."
                                    else -> "Topics not due yet, weakest first: a good use of spare time, above all in the weeks before an exam. An early review strengthens memory less than one on time."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(if (nextUpExpanded) nextUp else nextUp.take(NEXT_UP_SHOWN), key = { "ahead-${it.id}" }) { unit ->
                        StudyUnitCard(unit, subjects, onClick = { onReviewFromToday(unit.id, "AHEAD") }, disambiguator = disambOf(unit), now = now)
                    }
                    if (!nextUpExpanded && nextUp.size > NEXT_UP_SHOWN) {
                        item {
                            TextButton(onClick = { nextUpExpanded = true }) {
                                val more = nextUp.size - NEXT_UP_SHOWN
                                Text(
                                    when (strings.languageCode) {
                                        "fa" -> "بیشتر (${com.example.ui.i18n.PersianDate.faDigits(more)})"
                                        "de" -> "Mehr ($more)"
                                        else -> "More ($more)"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StudyUnitCard(
    unit: StudyUnitEntity,
    subjects: List<com.example.data.local.entity.SubjectEntity> = emptyList(),
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    selectable: Boolean = false,
    // Shown under the title ONLY when another active topic shares this exact title, so the user can
    // tell two same-named topics apart by whatever actually differs (a note or source snippet).
    disambiguator: String? = null,
    /** The clock "due now" is read against; Today passes its minute so the label follows time passing. */
    now: Long = System.currentTimeMillis(),
) {
    val strings = com.example.ui.i18n.LocalStrings.current
    val haptic = LocalHapticFeedback.current
    val selectedStateLabel = when (strings.languageCode) { "fa" -> "انتخاب شده"; "de" -> "Ausgewählt"; else -> "Selected" }
    val unselectedStateLabel = when (strings.languageCode) { "fa" -> "انتخاب نشده"; "de" -> "Nicht ausgewählt"; else -> "Not selected" }

    // A topic added retroactively (studied well before it was logged): flagged so back-dated items are
    // visually distinct in Today. Future-planned items (studiedAt in the future) are NOT flagged.
    val isBackdated = unit.reviewCount == 0 && run {
        // Studied on an earlier local calendar day than it was added (e.g. "I studied it yesterday").
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = unit.studiedAt
        val sy = cal.get(java.util.Calendar.YEAR); val sd = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = unit.createdAt
        val cy = cal.get(java.util.Calendar.YEAR); val cd = cal.get(java.util.Calendar.DAY_OF_YEAR)
        sy < cy || (sy == cy && sd < cd)
    }

    // Smoothly animated selection (no hard border/shadow snap): a gentle tint + border tween.
    val targetBg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
    val backgroundColor by androidx.compose.animation.animateColorAsState(targetBg, label = "cardBg")
    val borderColor by androidx.compose.animation.animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary
            isBackdated -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outline
        },
        label = "cardBorder"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(if (selected) 2.dp else 1.dp, label = "cardBorderW")
    val elevation by androidx.compose.animation.core.animateDpAsState(if (selected) 4.dp else 2.dp, label = "cardElev")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // Gentle tick, not the heavy long-press buzz — this is a light "selected", not an alert.
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onLongClick?.invoke()
                }
            )
            // In selection mode this card IS a checkbox. Without saying so, "selected" exists only
            // as a tint and a slightly heavier border — invisible to a screen reader and easy to
            // miss for a colour-blind user. Announced as an explicit state, not just a colour.
            .semantics {
                if (selectable) {
                    this.selected = selected
                    role = androidx.compose.ui.semantics.Role.Checkbox
                    stateDescription = if (selected) selectedStateLabel else unselectedStateLabel
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
        shadowElevation = elevation
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Selection indicator animates in/out (expand + fade) instead of popping and
                    // shoving the title sideways — the source of the old "weird" feel.
                    androidx.compose.animation.AnimatedVisibility(visible = selectable) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 8.dp).size(22.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (unit.state == "Strong") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(percent = 50)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = unit.title,
                            // User content: direction follows the TITLE's own script, not the screen's.
                            style = MaterialTheme.typography.titleMedium.autoDirection(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!disambiguator.isNullOrBlank()) {
                            Text(
                                text = disambiguator,
                                style = MaterialTheme.typography.bodySmall.autoDirection(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isBackdated) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(
                                text = when (strings.languageCode) { "fa" -> "از قبل"; "de" -> "Von früher"; else -> "From earlier" },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (unit.reviewCount == 0) {
                        // Never reviewed and studied ~now: the first-time-through item, due today to rate.
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(
                                text = when (strings.languageCode) { "fa" -> "جدید"; "de" -> "Neu"; else -> "New" },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (unit.highYield) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(
                                text = strings.highYield,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val formattedState = strings.stateLabel(unit.state)
                val subject = subjects.find { it.id == unit.subjectId }
                // The subject and state give way to the date, never the reverse: unweighted, a long subject (or German
                // at a large font) ran into the date with no gap ("GefestigtNächste"; an outside audit, 2026-09-30).
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    if (subject?.colorHex != null) {
                        Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(runCatching { Color(subject.colorHex.toColorInt()) }.getOrNull() ?: MaterialTheme.colorScheme.primary))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        // No subject: just the state. studyType is a dormant column ("Topic" for every
                        // topic added since it was retired) and printed English into every language.
                        text = listOfNotNull(subject?.name, formattedState).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                
                // Date only — the schedule is day-granularity, so a clock time would claim a precision
                // the scheduler doesn't have. Calendar (Jalali/Gregorian) follows the user preference.
                val nextReviewText = if (unit.nextReviewAt < now) strings.dueNow
                    else com.example.ui.i18n.AppDate.date(com.example.ui.i18n.LocalUseJalali.current, unit.nextReviewAt, strings.languageCode == "fa")
                
                Text(
                    text = strings.nextReview.format(nextReviewText),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            com.example.ui.components.StrengthBar(
                progress = com.example.ui.components.strengthOf(unit.state),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

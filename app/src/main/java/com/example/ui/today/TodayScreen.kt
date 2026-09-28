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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

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

    // Room flows only emit on DATABASE changes — if the app sits open across midnight untouched,
    // yesterday's classification would stick. This ticker re-emits just after each local midnight so
    // Due/Overdue/Upcoming reclassify from time passing alone.
    private val dayTick = kotlinx.coroutines.flow.flow {
        emit(Unit)
        while (true) {
            val now = System.currentTimeMillis()
            val nextMidnight = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            kotlinx.coroutines.delay((nextMidnight - now).coerceAtLeast(1000L) + 1000L)
            emit(Unit)
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
    private val activeNow = repository.activeUnits.combine(sharedDayTick) { units, _ -> units }
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
    
    val subjects: StateFlow<List<com.example.data.local.entity.SubjectEntity>> = repository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // -1 = not loaded yet: the first-run welcome must never flash for an existing user while the DB
    // is still emitting, so the UI only treats a REAL 0 as "library is empty".
    val totalActive: StateFlow<Int> = repository.totalActiveCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    /** Reviews done today (first ratings excluded): what the daily limit counts. Resets at midnight. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val reviewsDoneToday: StateFlow<Int> = sharedDayTick
        .flatMapLatest { repository.observeReviewsBetween(startOfToday(), endOfToday()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun redistributeOverdueUnits(context: android.content.Context) {
        viewModelScope.launch {
            val overdueList = overdueUnits.value
            if (overdueList.isEmpty()) return@launch
            
            val now = System.currentTimeMillis()
            // Highest-priority first (same score the review queue uses) so DAY 1 gets the Important topics and
            // then the longest-overdue ones, instead of a blind round-robin.
            val prioritized = overdueList.sortedByDescending { u ->
                com.example.domain.srs.MedScheduler.priorityScore(
                    u.highYield, u.modelDueAt, now, u.nextReviewAt, u.understandingDueAt,
                )
            }
            val total = prioritized.size
            // The plan is built around what the user actually said they can do in a day. A fixed
            // window turned a 100-topic backlog into 34 a day for someone whose limit is 10 -- a
            // schedule they cannot execute, which teaches them the dates are not to be trusted.
            val capacity = com.example.domain.srs.MedScheduler.safeDailyLimit(
                context.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
                    .getFloat("daily_review_limit", 50f)
            )
            val updated = prioritized.mapIndexed { index, unit ->
                val target = OverdueRedistributor.targetMillis(
                    now, OverdueRedistributor.dayOffset(index, total, capacity),
                )
                // Recorded as a DEFERRAL (v5): the model's own due date stays in modelDueAt untouched.
                unit.copy(nextReviewAt = target, deferredUntil = target, updatedAt = now)
            }
            // One transaction: a crash mid-redistribution must not leave a half-applied plan.
            repository.updateUnitsAtomic(updated)
            repository.logEvent("REDISTRIBUTE", detail = prioritized.size.toString())
            // The schedule just changed: re-arm the reminder and refresh the home-screen widget so
            // neither keeps acting on the pre-redistribution due list.
            runCatching { com.example.notifications.NotificationScheduler.scheduleDailyReminder(context) }
            com.example.widget.DueWidgetProvider.updateAll(context)
        }
    }
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
    /** Today's limit is used up and the learner wants to keep going: a session without the limit. */
    onReviewMoreAnyway: () -> Unit = {},
    /** Nothing is due and the learner wants to study anyway: not-yet-due topics, weakest first (ReviewAhead). */
    onReviewAhead: () -> Unit = {},
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
    var showUpcomingSchedule by remember { mutableStateOf(false) }
        val strings = com.example.ui.i18n.LocalStrings.current
    val useJalali = com.example.ui.i18n.LocalUseJalali.current

    // Disambiguation: if two active topics share a title, surface whatever differs (a note/source
    // snippet, else subject) so the user can tell them apart on the card.
    // Titles are compared through TopicTitle, like the duplicate warning on save: a plain lowercase()
    // cannot see that the same Persian word was typed on two different keyboards.
    val dupTitles = remember(overdue, dueToday, upcoming) {
        (overdue + dueToday + upcoming).groupingBy { com.example.data.text.TopicTitle.normalize(it.title) }.eachCount()
            .filterValues { it > 1 }.keys
    }
    val disambOf: (StudyUnitEntity) -> String? = { u ->
        if (com.example.data.text.TopicTitle.normalize(u.title) !in dupTitles) null
        else u.notes?.trim()?.take(40)?.takeIf { it.isNotBlank() }
            ?: u.source?.trim()?.take(40)?.takeIf { it.isNotBlank() }
            ?: subjects.find { it.id == u.subjectId }?.name
    }
        var upcomingExpanded by remember { mutableStateOf(false) }
        
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
    // Today's plan (DailyPlan): every first rating plus the reviews that fit in what is left of the DAILY
    // limit — exactly what "Start review" loads, and what the reminders and the widget count.
    //
    // No time estimate: a review is done however the learner likes, mostly outside the app (questions,
    // a lecture, a video), so the seconds a card sits open measure nothing and "about N min" would be a
    // made-up number.
    val doneToday by viewModel.reviewsDoneToday.collectAsStateWithLifecycle()
    val plan = remember(due, doneToday, dailyLimit) {
        DailyPlan.plan(due, doneToday, dailyLimit, System.currentTimeMillis())
    }
    val displayDue = plan.size

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
                examSp.getString("exam_name", "") ?: "", examSp.getLong("exam_date", 0L), strings.languageCode
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
                if (displayDue == 0) return@run
                val planned = plan.queue
                val highYieldCount = planned.count { it.highYield }
                val weakCount = planned.count { it.state == "NeedsRelearn" || it.state == "Learning" }
                val newCount = plan.firstRatings.size
                val isFa = strings.languageCode == "fa"
                fun n(v: Int) = if (isFa) com.example.ui.i18n.PersianDate.faDigits(v) else v.toString()
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
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
                            // Transparency: the daily limit is managing the load, not hiding it — the
                            // most urgent reviews got today's slots, the rest wait for tomorrow.
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFa) "${n(plan.heldBack)} مرور طبق سقف روزانه‌ات برای بعد نگه داشته شد" else if (strings.languageCode == "de") "${plan.heldBack} durch dein Tageslimit für später aufgehoben" else "${plan.heldBack} held for later by your daily limit",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onNavigateToReview(-1L) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(
                                "${strings.startReview} · ${n(displayDue)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Today's limit is used up while reviews are still due. Said plainly, with a way to keep going:
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
                            text = if (isFa) "امروز ${n(plan.doneToday)} مرور انجام دادی و به سقف روزانه‌ات رسیدی. ${n(plan.heldBack)} مرور دیگر می‌تواند تا فردا صبر کند."
                                else if (strings.languageCode == "de") "Du hast heute ${if (plan.doneToday == 1) "1 Wiederholung" else "${plan.doneToday} Wiederholungen"} gemacht und dein Tageslimit erreicht. ${plan.heldBack} ${if (plan.heldBack == 1) "weitere kann" else "weitere können"} bis morgen warten."
                                else "You did ${if (plan.doneToday == 1) "1 review" else "${plan.doneToday} reviews"} today and reached your daily limit. ${plan.heldBack} more can wait until tomorrow.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onReviewMoreAnyway) {
                            Text(if (isFa) "باز هم مرور می‌کنم" else if (strings.languageCode == "de") "Trotzdem weiter wiederholen" else "Review more anyway")
                        }
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
                                if (totalActive == 0) {
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
                                    val nextUp = upcoming.firstOrNull()
                                    Text(
                                        text = if (nextUp == null) (when (strings.languageCode) { "fa" -> "فعلاً چیزی در برنامه نیست."; "de" -> "Noch nichts geplant."; else -> "Nothing scheduled yet." })
                                               else (when (strings.languageCode) { "fa" -> "مرور بعدی: "; "de" -> "Nächste: "; else -> "Next: " }) + com.example.ui.i18n.AppDate.weekdayDate(useJalali, nextUp.nextReviewAt, strings.languageCode == "fa"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    // Review ahead (ReviewAhead): for spare time and the weeks before an exam.
                                    // Offered only when a rated topic is waiting; honest about the cost.
                                    val endOfDay = remember(allUpcoming) { DayBounds.endOf(System.currentTimeMillis()) }
                                    if (allUpcoming.any { ReviewAhead.isCandidate(it, endOfDay) }) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        OutlinedButton(onClick = onReviewAhead, shape = RoundedCornerShape(percent = 50)) {
                                            Text(when (strings.languageCode) { "fa" -> "مرور جلوتر از برنامه"; "de" -> "Vorausarbeiten"; else -> "Review ahead" })
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = when (strings.languageCode) {
                                                "fa" -> "مباحثی که هنوز موعدشان نرسیده، از ضعیف‌ترین. پیش از امتحان مفید است؛ مرورِ زودتر از موعد، حافظه را کمتر از مرورِ به‌موقع تقویت می‌کند."
                                                "de" -> "Noch nicht fällige Themen, die schwächsten zuerst. Nützlich vor einer Prüfung; eine frühe Wiederholung stärkt das Gedächtnis weniger als eine pünktliche."
                                                else -> "Topics not due yet, weakest first. Useful before an exam; an early review strengthens memory less than one on time."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        // Today is clear; show a collapsed "Upcoming" the user can open on demand.
                        // (When upcoming is also empty, the caught-up card above already says everything —
                        // no second "nothing due" line, and no celebratory tone.)
                        item {
                            // The header counts everything ahead; the list previews the next five.
                            UpcomingHeader(strings.upcoming, allUpcoming.size, upcomingExpanded) { upcomingExpanded = !upcomingExpanded }
                        }
                        if (upcomingExpanded) {
                            items(upcoming) { unit ->
                                StudyUnitCard(unit, subjects, onClick = { onNavigateToEdit(unit.id) }, disambiguator = disambOf(unit))
                            }
                            if (allUpcoming.size > upcoming.size) {
                                item {
                                    TextButton(onClick = { showUpcomingSchedule = true }) {
                                        Text(
                                            when (strings.languageCode) {
                                                "fa" -> "دیدن همه (${com.example.ui.i18n.PersianDate.faDigits(allUpcoming.size)})"
                                                "de" -> "Alle ansehen (${allUpcoming.size})"
                                                else -> "See all (${allUpcoming.size})"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (overdue.isNotEmpty()) {
                        // The recovery plan is offered only for a backlog bigger than one day's limit
                        // (OverdueRedistributor.offersRecovery); a smaller one the daily plan clears by itself.
                        if (OverdueRedistributor.offersRecovery(overdue.size, dailyLimit)) item {
                            val isFarsi = strings.languageCode == "fa"
                            val over = com.example.ui.theme.overdueTone()
                            val nOver = if (isFarsi) com.example.ui.i18n.PersianDate.faDigits(overdue.size) else overdue.size.toString()
                            // The same plan the button below builds: sized from the daily limit, 3–14 days.
                            // This card used to promise "3 days" whatever the backlog was.
                            val recoveryDays = OverdueRedistributor.recoveryDays(overdue.size, dailyLimit)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = over.container
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, over.main.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isFarsi) "مدتی دور بودی" else if (strings.languageCode == "de") "Du warst eine Weile weg" else "You were away",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = over.main
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isFarsi) {
                                            "$nOver مرور منتظر است. بیا اول مهم‌ترین‌ها را جبران کنیم — می‌توانی آن‌ها را روی ${com.example.ui.i18n.PersianDate.faDigits(recoveryDays)} روز پخش کنی."
                                        } else if (strings.languageCode == "de") {
                                            (if (overdue.size == 1) "1 Wiederholung wartet." else "${overdue.size} Wiederholungen warten.") +
                                                " Holen wir zuerst die wichtigsten nach — du kannst sie auf $recoveryDays Tage verteilen."
                                        } else {
                                            (if (overdue.size == 1) "1 review is waiting." else "${overdue.size} reviews are waiting.") +
                                                " Let's recover the important ones first — you can spread them over $recoveryDays days."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { viewModel.redistributeOverdueUnits(ctxForLimit.applicationContext) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = over.main,
                                            contentColor = over.onSolid
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isFarsi) "توزیع مجدد و پخش مباحث عقب‌افتاده" else if (strings.languageCode == "de") "Überfällige Themen verteilen" else "Spread Out Overdue Topics",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Text(strings.overdue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = com.example.ui.theme.overdueTone().main)
                        }
                        items(overdue) { unit ->
                            StudyUnitCard(unit, subjects, onClick = { onNavigateToReview(unit.id) }, disambiguator = disambOf(unit))
                        }
                    }
                    
                    if (dueToday.isNotEmpty()) {
                        item {
                            Text(strings.priorityFocus, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(dueToday) { unit ->
                            StudyUnitCard(unit, subjects, onClick = { onNavigateToReview(unit.id) }, disambiguator = disambOf(unit))
                        }
                    }
                    
                    if (upcoming.isNotEmpty()) {
                        item {
                            // The header counts everything ahead; the list previews the next five.
                            UpcomingHeader(strings.upcoming, allUpcoming.size, upcomingExpanded) { upcomingExpanded = !upcomingExpanded }
                        }
                        if (upcomingExpanded) {
                            items(upcoming) { unit ->
                                StudyUnitCard(unit, subjects, onClick = { onNavigateToEdit(unit.id) }, disambiguator = disambOf(unit))
                            }
                            if (allUpcoming.size > upcoming.size) {
                                item {
                                    TextButton(onClick = { showUpcomingSchedule = true }) {
                                        Text(
                                            when (strings.languageCode) {
                                                "fa" -> "دیدن همه (${com.example.ui.i18n.PersianDate.faDigits(allUpcoming.size)})"
                                                "de" -> "Alle ansehen (${allUpcoming.size})"
                                                else -> "See all (${allUpcoming.size})"
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
    }
}

@Composable
private fun UpcomingHeader(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val strings = com.example.ui.i18n.LocalStrings.current
        Text(
            text = "${label.uppercase()} (${if (strings.languageCode == "fa") com.example.ui.i18n.PersianDate.faDigits(count) else count.toString()})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = when (com.example.ui.i18n.LocalStrings.current.languageCode) {
                "fa" -> if (expanded) "بستن" else "باز کردن"
                "de" -> if (expanded) "Einklappen" else "Ausklappen"
                else -> if (expanded) "Collapse" else "Expand"
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    disambiguator: String? = null
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subject?.colorHex != null) {
                        Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(runCatching { Color(subject.colorHex.toColorInt()) }.getOrNull() ?: MaterialTheme.colorScheme.primary))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        // No subject: just the state. studyType is a dormant column ("Topic" for every
                        // topic added since it was retired) and printed English into every language.
                        text = listOfNotNull(subject?.name, formattedState).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Date only — the schedule is day-granularity, so a clock time would claim a precision
                // the scheduler doesn't have. Calendar (Jalali/Gregorian) follows the user preference.
                val nextReviewText = if (unit.nextReviewAt < System.currentTimeMillis()) strings.dueNow
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

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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.map
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

    val dueUnits: StateFlow<List<StudyUnitEntity>> = repository.activeUnits.combine(dayTick) { units, _ ->
        val end = endOfToday()
        units.filter { it.nextReviewAt <= end }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val overdueUnits = repository.activeUnits.combine(dayTick) { units, _ ->
        val start = startOfToday()
        units.filter { it.nextReviewAt < start }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dueTodayUnits = repository.activeUnits.combine(dayTick) { units, _ ->
        val start = startOfToday()
        val end = endOfToday()
        units.filter { it.nextReviewAt in start..end }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingUnits: StateFlow<List<StudyUnitEntity>> = repository.activeUnits.combine(dayTick) { units, _ ->
        val end = endOfToday()
        units.filter { it.nextReviewAt > end }.sortedBy { it.nextReviewAt }.take(5)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val subjects: StateFlow<List<com.example.data.local.entity.SubjectEntity>> = repository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun redistributeOverdueUnits(context: android.content.Context) {
        viewModelScope.launch {
            val overdueList = overdueUnits.value
            if (overdueList.isEmpty()) return@launch
            
            val now = System.currentTimeMillis()
            // Highest-priority first (same score the review queue uses) so DAY 1 gets the important ones
            // (high-yield, needs-relearn, recent forgot, most overdue) instead of a blind round-robin.
            val prioritized = overdueList.sortedByDescending { u ->
                com.example.domain.srs.MedScheduler.priorityScore(u.highYield, u.state, u.lapseCount, u.nextReviewAt, now)
            }
            val perDay = Math.ceil(prioritized.size / 3.0).toInt().coerceAtLeast(1)
            val updated = prioritized.mapIndexed { index, unit ->
                val dayOffset = (index / perDay).coerceAtMost(2) + 1 // fill day 1, then 2, then 3
                val target = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                    set(java.util.Calendar.HOUR_OF_DAY, 8)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                unit.copy(nextReviewAt = target, updatedAt = now)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    repository: MedReviewRepository,
    onNavigateToAdd: () -> Unit,
    onNavigateToReview: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val viewModel: TodayViewModel = viewModel(factory = TodayViewModelFactory(repository))
    
    // Notification permission is requested once in MainActivity; not duplicated here.

    val due by viewModel.dueUnits.collectAsStateWithLifecycle()
    val overdue by viewModel.overdueUnits.collectAsStateWithLifecycle()
    val dueToday by viewModel.dueTodayUnits.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcomingUnits.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
        val strings = com.example.ui.i18n.LocalStrings.current
    val useJalali = com.example.ui.i18n.LocalUseJalali.current

    // Disambiguation: if two active topics share a title, surface whatever differs (a note/source
    // snippet, else subject) so the user can tell them apart on the card.
    val dupTitles = remember(overdue, dueToday, upcoming) {
        (overdue + dueToday + upcoming).groupingBy { it.title.trim().lowercase() }.eachCount()
            .filterValues { it > 1 }.keys
    }
    val disambOf: (StudyUnitEntity) -> String? = { u ->
        if (u.title.trim().lowercase() !in dupTitles) null
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
        initialValue = ctxForLimit.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE).getFloat("daily_review_limit", 50f).toInt()
    ) {
        val sp = ctxForLimit.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "daily_review_limit") value = prefs.getFloat("daily_review_limit", 50f).toInt()
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val displayDue = minOf(totalDue, dailyLimit)
    val estimatedTimeMin = displayDue * 2

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
                Icon(Icons.Default.Add, contentDescription = "Add Study Unit")
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
            val examSp = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE)
            val examNamePref = examSp.getString("exam_name", "") ?: ""
            val examDatePref = examSp.getLong("exam_date", 0L)
            if (examNamePref.isNotBlank() && examDatePref > 0L) {
                val daysLeft = Math.ceil((examDatePref - System.currentTimeMillis()) / 86400000.0).toLong()
                if (daysLeft >= 0) {
                    Text(
                        text = if (strings.languageCode == "fa") "${com.example.ui.i18n.PersianDate.faDigits(daysLeft)} روز تا $examNamePref" else "$daysLeft days until $examNamePref",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                    )
                }
            }
            // One calm hero card: greeting, what's on the plate, and the single primary action.
            run {
                // When caught up, the calm "All caught up" card below covers it — don't double up here.
                if (totalDue == 0) return@run
                val dueList = overdue + dueToday
                val highYieldCount = dueList.count { it.highYield }
                val weakCount = dueList.count { it.state == "NeedsRelearn" || it.state == "Learning" }
                val isFa = strings.languageCode == "fa"
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val greeting = when {
                    hour < 12 -> if (isFa) "صبح بخیر" else "Good morning"
                    hour < 18 -> if (isFa) "بعدازظهر بخیر" else "Good afternoon"
                    else -> if (isFa) "عصر بخیر" else "Good evening"
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
                            // The run block above returns early when totalDue == 0, so this hero card
                            // only ever renders the "N reviews" case (the caught-up card lives below).
                            text = if (isFa) "${com.example.ui.i18n.PersianDate.faDigits(displayDue)} مرور · حدود ${com.example.ui.i18n.PersianDate.faDigits(estimatedTimeMin)} دقیقه" else "$displayDue ${if (displayDue == 1) "review" else "reviews"} · about $estimatedTimeMin min",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (highYieldCount > 0 || weakCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFa) "${com.example.ui.i18n.PersianDate.faDigits(highYieldCount)} مهم · ${com.example.ui.i18n.PersianDate.faDigits(weakCount)} ضعیف" else "$highYieldCount important · $weakCount weak",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (totalDue > displayDue) {
                            // Transparency: the daily cap is managing the load, not hiding it — the
                            // highest-priority items got today's slots, the rest wait.
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isFa) "${com.example.ui.i18n.PersianDate.faDigits(totalDue - displayDue)} مورد طبق سقف روزانه‌ات برای بعد نگه داشته شد" else "${totalDue - displayDue} held for later by your daily limit",
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
                                "${strings.startReview} · ${if (strings.languageCode == "fa") com.example.ui.i18n.PersianDate.faDigits(displayDue) else displayDue.toString()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                                Text(
                                    text = if (isFarsi) "برای امروز تمام شد" else "All caught up for today",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                // Honest next-review line: the real date of the next upcoming item,
                                // not a hardcoded "tomorrow" (which was simply wrong for longer gaps).
                                val nextUp = upcoming.firstOrNull()
                                Text(
                                    text = if (nextUp == null) (if (isFarsi) "فعلاً چیزی در برنامه نیست." else "Nothing scheduled yet.")
                                           else (if (isFarsi) "مرور بعدی: " else "Next review: ") + com.example.ui.i18n.AppDate.weekdayDate(useJalali, nextUp.nextReviewAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        // Today is clear; show a collapsed "Upcoming" the user can open on demand.
                        // (When upcoming is also empty, the caught-up card above already says everything —
                        // no second "nothing due" line, and no celebratory tone.)
                        item {
                            UpcomingHeader(strings.upcoming, upcoming.size, upcomingExpanded) { upcomingExpanded = !upcomingExpanded }
                        }
                        if (upcomingExpanded) {
                            items(upcoming) { unit ->
                                StudyUnitCard(unit, subjects, onClick = { onNavigateToEdit(unit.id) }, disambiguator = disambOf(unit))
                            }
                        }
                    }
                } else {
                    if (overdue.isNotEmpty()) {
                        item {
                            val isFarsi = strings.languageCode == "fa"
                            val over = com.example.ui.theme.overdueTone()
                            val nOver = if (isFarsi) com.example.ui.i18n.PersianDate.faDigits(overdue.size) else overdue.size.toString()
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
                                            text = if (isFarsi) "مدتی دور بودی" else "You were away",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = over.main
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isFarsi) {
                                            "$nOver مرور منتظر است. بیا اول مهم‌ترین‌ها را جبران کنیم — می‌توانی آن‌ها را روی ۳ روز پخش کنی."
                                        } else {
                                            "${overdue.size} reviews are waiting. Let's recover the important ones first — you can spread them over 3 days."
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
                                            text = if (isFarsi) "توزیع مجدد و پخش مباحث عقب‌افتاده" else "Spread Out Overdue Topics",
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
                            UpcomingHeader(strings.upcoming, upcoming.size, upcomingExpanded) { upcomingExpanded = !upcomingExpanded }
                        }
                        if (upcomingExpanded) {
                            items(upcoming) { unit ->
                                StudyUnitCard(unit, subjects, onClick = { onNavigateToEdit(unit.id) }, disambiguator = disambOf(unit))
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
        Text(
            text = "${label.uppercase()} ($count)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
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

    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    val borderStroke = when {
        selected -> androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        isBackdated -> androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
        else -> androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick?.invoke()
                }
            ),
        shape = RoundedCornerShape(24.dp),
        color = backgroundColor,
        border = borderStroke,
        shadowElevation = if (selected) 4.dp else 2.dp
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
                    if (selectable) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            modifier = Modifier.padding(end = 8.dp)
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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!disambiguator.isNullOrBlank()) {
                            Text(
                                text = disambiguator,
                                style = MaterialTheme.typography.bodySmall,
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
                                text = if (strings.languageCode == "fa") "از قبل" else "From earlier",
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
                                text = if (strings.languageCode == "fa") "جدید" else "New",
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
                        Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(runCatching { Color(android.graphics.Color.parseColor(subject.colorHex)) }.getOrNull() ?: MaterialTheme.colorScheme.primary))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "${subject?.name ?: unit.studyType} • $formattedState",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Date only — the schedule is day-granularity, so a clock time would claim a precision
                // the scheduler doesn't have. Calendar (Jalali/Gregorian) follows the user preference.
                val nextReviewText = if (unit.nextReviewAt < System.currentTimeMillis()) strings.dueNow
                    else com.example.ui.i18n.AppDate.date(com.example.ui.i18n.LocalUseJalali.current, unit.nextReviewAt)
                
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

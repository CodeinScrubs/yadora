package com.example.ui.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.core.graphics.toColorInt
import com.example.ui.i18n.autoDirection
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

import com.example.data.repository.MedReviewRepository
import com.example.ui.theme.HighYieldOrange
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis

data class SubjectDifficulty(val name: String, val strong: Int, val weak: Int)

@Suppress("UNCHECKED_CAST")
class ProgressViewModelFactory(private val repository: MedReviewRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProgressViewModel(repository) as T
    }
}

class ProgressViewModel(repository: MedReviewRepository) : ViewModel() {
    val totalCount: StateFlow<Int> = repository.totalActiveCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    val strongCount = repository.getCountByState("Strong")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    val buildingCount = repository.getCountByState("Building")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    val newCount = repository.getCountByState("New")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    val learningCount = repository.getCountByState("Learning")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    val relearnCount = repository.getCountByState("NeedsRelearn")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    // Growth visual (0..1): one permanent sprout fed by committed study actions in event_logs, so
    // earned growth survives topic deletion. Per local day: 1 unit for the first action + 0.25 for
    // each of the next four (max 2/day — more study still counts in stats, just not in the visual).
    // Target 180 units → full plant in 90 active days (5+ actions/day) to 180 (1/day). Never resets.
    val growthProgress: StateFlow<Float> = repository.studyActionTimes()
        .map { times ->
            fun localDay(ms: Long): Int = ((ms + java.util.TimeZone.getDefault().getOffset(ms)) / (1000L * 60 * 60 * 24)).toInt()
            val units = times.groupingBy { localDay(it) }.eachCount().values.sumOf { actions ->
                1.0 + 0.25 * (actions - 1).coerceIn(0, 4)
            }
            (units / 180.0).coerceIn(0.0, 1.0).toFloat()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // ONE query feeds every log-derived card below. Each used to run its own getLogsSince(0L), so
    // opening this screen read the whole review history four times, and four times again per write.
    private val allLogs = repository.getLogsSince(0L)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    // Counted from all logs with a FRESH 7-day window each emission, so it can't go stale overnight.
    // FIRST_STUDY rows are difficulty check-ins, not recall reviews — excluded so the count means
    // "reviews done", consistent with the retention chart (which also excludes them).
    val reviewsLast7Days = allLogs
        .map { logs ->
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            logs.count { it.reviewedAt >= cutoff && it.logType != "FIRST_STUDY" }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    // Null = not enough data yet (the UI shows an honest "no data" state instead of a fake-perfect line).
    val retentionChartData = allLogs
        .map { list ->
            // DST-aware local day index (not raw UTC), so reviews land on the user's actual calendar day.
            fun localDay(ms: Long): Int = ((ms + java.util.TimeZone.getDefault().getOffset(ms)) / (1000L * 60 * 60 * 24)).toInt()
            val dayStats = mutableMapOf<Int, Pair<Int, Int>>()
            for (log in list) {
                // Retention = RECALL accuracy only. A FIRST_STUDY row's "memoryRating" is a difficulty
                // answer, not a recall outcome — counting it would inflate/distort the retention rate.
                if (log.logType == "FIRST_STUDY") continue
                val day = localDay(log.reviewedAt)
                val stat = dayStats.getOrDefault(day, Pair(0, 0))
                // FSRS semantics: Again(Forgot) is the only failed recall — Hard IS a successful
                // (effortful) recall. Counting Hard as forgotten contradicted the calibration card.
                val isRetained = log.memoryRating != "Forgot"
                dayStats[day] = Pair(stat.first + 1, stat.second + (if (isRetained) 1 else 0))
            }
            if (dayStats.isEmpty()) return@map null // no recall data yet — never plot a fake 100%
            val today = localDay(System.currentTimeMillis())
            // Only plot from the first day retention was actually measured. Carry the last REAL rate
            // across later no-review days (flat line, not a dip), but do NOT backfill the days BEFORE
            // any data existed — inventing a rate for days the user hadn't studied yet is a fake line.
            val firstDataDay = dayStats.minByOrNull { it.key }!!.key
            var lastRate = -1f
            val entries = (0..13).mapNotNull { i ->
                val day = today - 13 + i
                if (day < firstDataDay) return@mapNotNull null // pre-observation: leave a gap, don't invent
                val stat = dayStats.getOrDefault(day, Pair(0, 0))
                if (stat.first > 0) lastRate = stat.second.toFloat() / stat.first.toFloat() * 100f
                if (lastRate < 0f) return@mapNotNull null // no data on/after firstDataDay yet in this cell
                FloatEntry(i.toFloat(), lastRate)
            }
            if (entries.isEmpty()) return@map null
            entryModelOf(entries)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val consistencyChartData = allLogs
        .map { list ->
            fun localDay(ms: Long): Int = ((ms + java.util.TimeZone.getDefault().getOffset(ms)) / (1000L * 60 * 60 * 24)).toInt()
            val counts = mutableMapOf<Int, Int>()
            for (log in list) {
                // Review consistency, not activity — exclude first-study check-ins so this chart
                // agrees with the 7-day review count and the retention chart beside it.
                if (log.logType == "FIRST_STUDY") continue
                val day = localDay(log.reviewedAt)
                counts[day] = counts.getOrDefault(day, 0) + 1
            }
            val today = localDay(System.currentTimeMillis())
            val entries = (0..13).map { i ->
                val day = today - 13 + i
                FloatEntry(i.toFloat(), counts.getOrDefault(day, 0).toFloat())
            }
            entryModelOf(entries)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), entryModelOf(listOf(FloatEntry(0f, 0f))))
        
    val subjectDifficultyData = kotlinx.coroutines.flow.combine(repository.activeUnits, repository.allSubjects) { units, subjects ->
        units.groupBy { it.subjectId }.mapNotNull { (subjectId, list) ->
            if (subjectId == null) return@mapNotNull null
            val subName = subjects.find { it.id == subjectId }?.name ?: "Unknown"
            val strong = list.count { it.state == "Strong" }
            val weak = list.count { it.state == "New" || it.state == "Learning" || it.state == "NeedsRelearn" || it.state == "Building"}
            SubjectDifficulty(subName, strong, weak)
        }.sortedByDescending { it.strong + it.weak }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeUnits = repository.activeUnits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Scheduler calibration: mean predicted recall (FSRS R at review time) vs the actual recall rate. */
    data class CalibrationStats(
        val n: Int,
        val predictedPct: Int,
        val actualPct: Int,
        val model: String,
        /** The interval correction learned from these reviews (RecallCalibration); 1.0 = none. */
        val scale: Double,
    )

    // The calibration card. calibrationStatsOf explains why its numbers come from the calibration's
    // own evidence rows and compare the DEFAULT model's predictions with what happened. Right after a
    // model change the card goes quiet until ten new reviews exist: nothing is yet known about how
    // well the current model predicts THIS user.
    // Which weight set the cards describe comes from the database rows (activeSetOf explains why).
    private val parameterSets = repository.observeParameterSets()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    val calibrationStats = kotlinx.coroutines.flow.combine(allLogs, parameterSets) { logs, sets ->
        calibrationStatsOf(logs, activeSetOf(sets))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The memory model card: the weight set in use, the last fit attempt, and the evidence so far. */
    val memoryModelStatus = kotlinx.coroutines.flow.combine(allLogs, parameterSets) { logs, sets ->
        memoryModelStatusOf(logs, sets)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(repository: MedReviewRepository, onNavigateToSettings: () -> Unit = {}) {
    val viewModel: ProgressViewModel = viewModel(factory = ProgressViewModelFactory(repository))
    val total by viewModel.totalCount.collectAsStateWithLifecycle()
    val strong by viewModel.strongCount.collectAsStateWithLifecycle()
    val building by viewModel.buildingCount.collectAsStateWithLifecycle()
    val newTopics by viewModel.newCount.collectAsStateWithLifecycle()
    val learning by viewModel.learningCount.collectAsStateWithLifecycle()
    val relearn by viewModel.relearnCount.collectAsStateWithLifecycle()
    val reviews7d by viewModel.reviewsLast7Days.collectAsStateWithLifecycle()
    
    val growthProgress by viewModel.growthProgress.collectAsStateWithLifecycle()
    val retentionData by viewModel.retentionChartData.collectAsStateWithLifecycle()
    val consistencyData by viewModel.consistencyChartData.collectAsStateWithLifecycle()
    val subjectDifficultyData by viewModel.subjectDifficultyData.collectAsStateWithLifecycle()
    val calibration by viewModel.calibrationStats.collectAsStateWithLifecycle()
    val memoryModel by viewModel.memoryModelStatus.collectAsStateWithLifecycle()
    
    val strings = com.example.ui.i18n.LocalStrings.current
    
    var selectedTab by remember { mutableStateOf(0) }
    val isFarsiLanguage = strings.languageCode == "fa"
    val useJalali = com.example.ui.i18n.LocalUseJalali.current
    val tabTitles = if (isFarsiLanguage) listOf("نمای کلی", "تقویم مرور") else if (strings.languageCode == "de") listOf("Überblick", "Kalenderplan") else listOf("Overview", "Calendar Plan")

    // Exam countdown text computed in composable scope (LocalContext can't be read inside LazyColumn items).
    val examCtx = LocalContext.current
    val examCountdownText = run {
        val sp = examCtx.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE)
        com.example.ui.i18n.ExamCountdown.text(sp.getString("exam_name", "") ?: "", sp.getLong("exam_date", 0L), strings.languageCode)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(strings.progress, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = strings.settings)
                        }
                    }
                )
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(strings.overview, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                // Exam countdown, consistent with Today/Library (exam day itself not counted).
                if (examCountdownText != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Text(
                                text = examCountdownText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The rest of this screen converts its numerals; these two headline cards
                        // were the odd ones out, mixing 0-9 with ۰-۹ in a single view.
                        val faDigits = strings.languageCode == "fa"
                        fun num(v: Int) = if (faDigits) com.example.ui.i18n.PersianDate.faDigits(v) else v.toString()
                        StatCard(title = strings.totalTopics, value = num(total), modifier = Modifier.weight(1f))
                        StatCard(title = strings.past7days, value = num(reviews7d), modifier = Modifier.weight(1f))
                    }
                }

                // One permanent sprout, grown by real study days (max 2 units/day; full in 90–180
                // active days; never resets — see growthProgress). Quiet by design: no numbers, no
                // levels, no rewards — just a plant that is visibly further along than last month.
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GrowthSprout(progress = growthProgress, modifier = Modifier.size(96.dp))
                            Spacer(modifier = Modifier.width(20.dp))
                            Column {
                                Text(
                                    when (strings.languageCode) { "fa" -> "رشد"; "de" -> "Wachstum"; else -> "Growth" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    when (strings.languageCode) {
                                        "fa" -> "با هر روزِ مطالعه کمی رشد می‌کند — و هیچ‌وقت صفر نمی‌شود."
                                        "de" -> "Wächst mit jedem Lerntag ein Stück — und wird nie zurückgesetzt."
                                        else -> "Grows a little for every day you study — and never resets."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Text(strings.knowledgeState, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // "Needs relearn" is a memory state, not an error → warm sienna, not alarm-red.
                            val relearnColor = com.example.ui.theme.ratingTone(com.example.domain.model.MemoryRating.Forgot).solid
                            DonutChart(
                                data = listOf(strong.toFloat(), learning.toFloat(), building.toFloat(), newTopics.toFloat(), relearn.toFloat()),
                                colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary, Color.Gray, relearnColor),
                                modifier = Modifier.size(100.dp)
                            )
                            Spacer(modifier = Modifier.width(24.dp))
                            Column {
                                StateRow(strings.strong, strong, MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                StateRow(strings.learning, learning, MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(8.dp))
                                StateRow(if (isFarsiLanguage) "در حال ساخت" else if (strings.languageCode == "de") "Im Aufbau" else "Building", building, MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.height(8.dp))
                                StateRow(if (isFarsiLanguage) "جدید" else if (strings.languageCode == "de") "Neu" else "New", newTopics, Color.Gray)
                                Spacer(modifier = Modifier.height(8.dp))
                                StateRow(strings.needsRelearn, relearn, relearnColor)
                            }
                        }
                    }
                }
                
                item {
                    Text(if (isFarsiLanguage) "نرخ به‌خاطرسپاری (۱۴ روز اخیر)" else if (strings.languageCode == "de") "Behaltensquote (letzte 14 Tage)" else "Retention Rate (Last 14 Days)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            val model = retentionData
                            if (model != null) {
                                FourteenDayChart(model = model, bars = false, percent = true)
                            } else {
                                // Honest empty state: never plot a fake-perfect 100% before real data.
                                Text(
                                    if (isFarsiLanguage) "هنوز داده‌ای نیست — بعد از چند مرور واقعی نمایش داده می‌شود." else if (strings.languageCode == "de") "Noch keine Daten — erscheint nach deinen ersten echten Wiederholungen." else "No data yet — appears after your first real reviews.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Scheduler calibration — predicted recall vs what actually happened. Appears only once
                // there's enough post-v2 data (>=10 real recall events) to say something honest.
                calibration?.let { cal ->
                    item {
                        Text(if (isFarsiLanguage) "دقت زمان‌بندی" else if (strings.languageCode == "de") "Genauigkeit der Planung" else "Scheduler Calibration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                val fmt: (Int) -> String = { if (isFarsiLanguage) com.example.ui.i18n.PersianDate.faDigits(it) else it.toString() }
                                Text(
                                    text = if (isFarsiLanguage)
                                        "پیش‌بینی مدل: ٪${fmt(cal.predictedPct)} · عملکرد واقعی: ٪${fmt(cal.actualPct)}"
                                    else if (strings.languageCode == "de")
                                        "Modell sagte ${cal.predictedPct}% voraus · tatsächlich erinnert: ${cal.actualPct}%"
                                    else
                                        "Model predicted ${cal.predictedPct}% recall · you actually recalled ${cal.actualPct}%",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val gap = cal.actualPct - cal.predictedPct
                                // DESCRIPTIVE, never prescriptive. This used to tell the user to
                                // raise or lower their retention target off a >5-point gap measured
                                // on as few as ten reviews — a sample far too small and far too
                                // correlated (one learner, overlapping topics, self-rated, and only
                                // the reviews they chose to do) to support changing a scheduler
                                // setting. Reporting the number is honest; acting on it is not.
                                val enoughToJudge = cal.n >= 50
                                Text(
                                    text = when {
                                        !enoughToJudge -> if (isFarsiLanguage) "هنوز برای نتیجه‌گیری خیلی زود است — این فقط چیزی است که تا الان دیده شده." else if (strings.languageCode == "de") "Noch zu wenige Daten für eine Aussage — das ist nur, was bisher beobachtet wurde." else "Still too few reviews to conclude anything — this is simply what has been observed so far."
                                        Math.abs(gap) <= 5 -> if (isFarsiLanguage) "تا اینجا پیش‌بینی و عملکرد نزدیک بوده‌اند." else if (strings.languageCode == "de") "Bisher liegen Vorhersage und Ergebnis nah beieinander." else "So far, prediction and outcome have stayed close."
                                        gap > 5 -> if (isFarsiLanguage) "تا اینجا بهتر از پیش‌بینی به یاد آورده‌ای." else if (strings.languageCode == "de") "Bisher erinnerst du dich besser als vorhergesagt." else "So far you have recalled more than the model predicted."
                                        else -> if (isFarsiLanguage) "تا اینجا کمی بیشتر از پیش‌بینی فراموش کرده‌ای." else if (strings.languageCode == "de") "Bisher vergisst du etwas mehr als vorhergesagt." else "So far you have forgotten a little more than the model predicted."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // The correction in force, stated as a plain factor on the intervals.
                                val scaleText = String.format(java.util.Locale.US, "%.2f", cal.scale)
                                    .let { if (isFarsiLanguage) com.example.ui.i18n.PersianDate.faDigits(it) else if (strings.languageCode == "de") it.replace('.', ',') else it }
                                Text(
                                    text = if (isFarsiLanguage) "بر اساس ${fmt(cal.n)} مرور واقعی · ${cal.model} · ضریب فاصله‌ها ×$scaleText" else if (strings.languageCode == "de") "Basierend auf ${cal.n} echten Wiederholungen · ${cal.model} · Intervallfaktor ×$scaleText" else "Based on ${cal.n} real recall reviews · ${cal.model} · interval factor ×$scaleText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // The memory model: fitted to this learner or the published defaults, and on what evidence.
                // Descriptive only, like the calibration card: nothing here asks the learner to act.
                memoryModel?.let { status ->
                    item {
                        Text(
                            when (strings.languageCode) { "fa" -> "مدل حافظه"; "de" -> "Gedächtnismodell"; else -> "Memory model" },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                val digits: (String) -> String = { if (isFarsiLanguage) com.example.ui.i18n.PersianDate.faDigits(it) else it }
                                val n: (Int) -> String = { digits(it.toString()) }
                                val ll: (Double) -> String = { digits(String.format(java.util.Locale.US, "%.3f", it)) }
                                val pct: (Double) -> String = { digits(String.format(java.util.Locale.US, "%.1f", it * 100)) }
                                val date: (Long) -> String = { com.example.ui.i18n.AppDate.date(useJalali, it, isFarsiLanguage) }
                                val active = status.active
                                val latest = status.latestAttempt
                                val standard = when (strings.languageCode) { "fa" -> "وزن‌های استاندارد FSRS-6"; "de" -> "Standardgewichte von FSRS-6"; else -> "Standard FSRS-6 weights" }
                                val headline = if (active != null) {
                                    when (strings.languageCode) { "fa" -> "متناسب با مرورهای خودت"; "de" -> "An deine eigenen Wiederholungen angepasst"; else -> "Fitted to your own reviews" }
                                } else standard
                                val since = active?.let { date(it.activatedAt ?: it.createdAt) }
                                val min = n(com.example.domain.srs.Fsrs6Optimizer.MIN_REVIEWS_FOR_A_FIT)
                                val detail = when {
                                    active != null -> when (strings.languageCode) {
                                        "fa" -> "از $since، بر پایهٔ ${n(active.availableReviews)} مرور. روی مرورهای بعدی‌ات بهتر از مدل قبلی پیش‌بینی کرد: خطای لگاریتمی ${ll(active.currentLogLoss)} ← ${ll(active.candidateLogLoss)}، خطای گروه‌بندی‌شده ٪${pct(active.currentRmseBins)} ← ٪${pct(active.candidateRmseBins)}."
                                        "de" -> "Seit $since, aus ${active.availableReviews} Wiederholungen. Bei deinen späteren Wiederholungen sagte es besser voraus als das ersetzte Modell: Log-Loss ${ll(active.currentLogLoss)} → ${ll(active.candidateLogLoss)}, gruppierter Fehler ${pct(active.currentRmseBins)} % → ${pct(active.candidateRmseBins)} %."
                                        else -> "Since $since, from ${active.availableReviews} reviews. On your later reviews it predicted better than the model it replaced: log loss ${ll(active.currentLogLoss)} → ${ll(active.candidateLogLoss)}, binned error ${pct(active.currentRmseBins)}% → ${pct(active.candidateRmseBins)}%."
                                    }
                                    latest?.status == com.example.data.local.entity.MemoryParameterSetEntity.REJECTED -> when (strings.languageCode) {
                                        "fa" -> "آخرین بررسی ${date(latest.createdAt)} با ${n(latest.availableReviews)} مرور: مدلی که بر تو برازش شد مرورهای بعدی‌ات را به‌طور قابل‌اعتمادی بهتر پیش‌بینی نکرد، پس چیزی تغییر نکرد."
                                        "de" -> "Zuletzt geprüft am ${date(latest.createdAt)} mit ${latest.availableReviews} Wiederholungen: Ein an dich angepasstes Modell sagte deine späteren Wiederholungen nicht verlässlich besser voraus, daher bleibt alles, wie es ist."
                                        else -> "Last checked ${date(latest.createdAt)} on ${latest.availableReviews} reviews: a model fitted to you did not predict your later reviews reliably better, so nothing changed."
                                    }
                                    latest != null -> when (strings.languageCode) {
                                        "fa" -> "مدلی که بر تو برازش شده بود خاموش شد."
                                        "de" -> "Das an dich angepasste Modell wurde ausgeschaltet."
                                        else -> "The model fitted to you was switched off."
                                    }
                                    else -> when (strings.languageCode) {
                                        "fa" -> "مدلی متناسب با مرورهای خودت وقتی حدود $min مرورِ یادآوری جمع شود امتحان می‌شود (تا الان ${n(status.recallReviews)}) و فقط اگر مرورهای بعدی‌ات را بهتر پیش‌بینی کند به کار می‌رود."
                                        "de" -> "Ein an deine Wiederholungen angepasstes Modell wird erprobt, sobald etwa $min Abruf-Wiederholungen vorliegen (bisher ${status.recallReviews}), und nur verwendet, wenn es deine späteren Wiederholungen besser vorhersagt."
                                        else -> "A model fitted to your own reviews is tried once about $min recall reviews exist (${status.recallReviews} so far), and used only if it predicts your later reviews better."
                                    }
                                }
                                Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                item {
                    Text(if (isFarsiLanguage) "ثبات مرور (۱۴ روز اخیر)" else if (strings.languageCode == "de") "Regelmäßigkeit (letzte 14 Tage)" else "Review Consistency (Last 14 Days)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            FourteenDayChart(model = consistencyData, bars = true, percent = false)
                        }
                    }
                }
                
                item {
                    Text(if (isFarsiLanguage) "دشواری بر اساس موضوع" else if (strings.languageCode == "de") "Schwierigkeit nach Fach" else "Difficulty by Subject", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                
                items(subjectDifficultyData.size) { i ->
                    val data = subjectDifficultyData[i]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(data.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                val totalVal = Math.max(1, data.strong + data.weak).toFloat()
                                val weakWeight = data.weak / totalVal
                                val strongWeight = data.strong / totalVal
                                if (weakWeight > 0) {
                                    Surface(modifier = Modifier.height(8.dp).weight(weakWeight), color = com.example.ui.theme.Overdue, shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50, topEndPercent = if (strongWeight == 0f) 50 else 0, bottomEndPercent = if (strongWeight == 0f) 50 else 0)) {}
                                }
                                if (strongWeight > 0) {
                                    Surface(modifier = Modifier.height(8.dp).weight(strongWeight), color = MaterialTheme.colorScheme.secondary, shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50, topStartPercent = if (weakWeight == 0f) 50 else 0, bottomStartPercent = if (weakWeight == 0f) 50 else 0)) {}
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                val nWeak = if (isFarsiLanguage) com.example.ui.i18n.PersianDate.faDigits(data.weak) else data.weak.toString()
                                val nStrong = if (isFarsiLanguage) com.example.ui.i18n.PersianDate.faDigits(data.strong) else data.strong.toString()
                                Text(if (isFarsiLanguage) "$nWeak ضعیف" else if (strings.languageCode == "de") "$nWeak schwach" else "$nWeak Weak", style = MaterialTheme.typography.bodySmall, color = com.example.ui.theme.Overdue)
                                Text(if (isFarsiLanguage) "$nStrong قوی" else if (strings.languageCode == "de") "$nStrong stark" else "$nStrong Strong", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        } else {
            val activeList by viewModel.activeUnits.collectAsStateWithLifecycle()
            val subjectsList by repository.allSubjects.collectAsStateWithLifecycle(emptyList())
            
            val limitContext = LocalContext.current
            val sharedPrefs = remember { limitContext.getSharedPreferences("medreview_settings", Context.MODE_PRIVATE) }
            val limitValue = com.example.domain.srs.MedScheduler.safeDailyLimit(sharedPrefs.getFloat("daily_review_limit", 50f))
            
            val todayCalendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayStart = todayCalendar.timeInMillis
            val oneDayMs = 24L * 60 * 60 * 1000
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = if (isFarsiLanguage) "پیش‌بینی حجم دروس" else if (strings.languageCode == "de") "Vorschau (nächste 10 Tage)" else "Review Forecast (Next 10 Days)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                val daysToForecast = 10
                items(daysToForecast) { i ->
                    val dayStart = Calendar.getInstance().apply { timeInMillis = todayStart; add(Calendar.DAY_OF_YEAR, i) }.timeInMillis
                    val dayEnd = Calendar.getInstance().apply { timeInMillis = todayStart; add(Calendar.DAY_OF_YEAR, i + 1) }.timeInMillis
                    
                    val dueInDay = activeList.filter { unit ->
                        if (i == 0) {
                            unit.nextReviewAt <= dayEnd
                        } else {
                            unit.nextReviewAt > dayStart && unit.nextReviewAt <= dayEnd
                        }
                    }
                    
                    val dayNameCalendar = Calendar.getInstance().apply {
                        timeInMillis = dayStart
                    }
                    // Calendar follows the user's preference; Today/Tomorrow labels follow language.
                    val dayHeader = when (i) {
                        0 -> if (isFarsiLanguage) "امروز" else if (strings.languageCode == "de") "Heute" else "Today"
                        1 -> if (isFarsiLanguage) "فردا" else if (strings.languageCode == "de") "Morgen" else "Tomorrow"
                        else -> com.example.ui.i18n.AppDate.weekdayDate(useJalali, dayNameCalendar.timeInMillis, isFarsiLanguage)
                    }
                    
                    val reviewCount = dueInDay.size
                    var isExpanded by remember { mutableStateOf(i == 0) }
                    // An over-limit day is a calm caution (warm terracotta), never an alarm-red error.
                    val over = com.example.ui.theme.overdueTone()

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                reviewCount == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                reviewCount > limitValue -> over.main.copy(alpha = 0.10f)
                                reviewCount > limitValue / 2 -> HighYieldOrange.copy(alpha = 0.08f)
                                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                            }
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when {
                                reviewCount > limitValue -> over.main.copy(alpha = 0.5f)
                                reviewCount > limitValue / 2 -> HighYieldOrange.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        onClick = { isExpanded = !isExpanded }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dayHeader,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            reviewCount > limitValue -> over.main
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isFarsiLanguage) "${com.example.ui.i18n.PersianDate.faDigits(reviewCount)} مبحث برای مرور" else if (strings.languageCode == "de") (if (reviewCount == 1) "1 Thema zur Wiederholung" else "$reviewCount Themen zur Wiederholung") else (if (reviewCount == 1) "1 topic to review" else "$reviewCount topics to review"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            reviewCount == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                            reviewCount > limitValue -> over.main
                                            reviewCount > limitValue / 2 -> HighYieldOrange
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = when {
                                            reviewCount == 0 -> if (isFarsiLanguage) "آزاد" else if (strings.languageCode == "de") "Frei" else "Relax"
                                            reviewCount > limitValue -> if (isFarsiLanguage) "پُر" else if (strings.languageCode == "de") "Voll" else "Full"
                                            reviewCount > limitValue / 2 -> if (isFarsiLanguage) "متوسط" else if (strings.languageCode == "de") "Mittel" else "Moderate"
                                            else -> if (isFarsiLanguage) "سبک" else if (strings.languageCode == "de") "Leicht" else "Light"
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            reviewCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                            reviewCount > limitValue -> over.onSolid
                                            else -> MaterialTheme.colorScheme.onPrimary
                                        }
                                    )
                                }
                            }
                            
                            if (reviewCount > limitValue) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (isFarsiLanguage) {
                                        "این روز از سقف روزانه‌ات (${com.example.ui.i18n.PersianDate.faDigits(limitValue)} مرور) بیشتر است. برای سبک‌تر شدنش، چند مبحث را زودتر یا دیرتر بگذار."
                                    } else if (strings.languageCode == "de") {
                                        "Dieser Tag liegt über deinem Limit von $limitValue Wiederholungen. Verschiebe ein paar Themen nach vorn oder hinten, um ihn zu entlasten."
                                    } else {
                                        "This day is above your set limit of $limitValue reviews. To lighten it, move a few topics earlier or later."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = over.main,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            
                            if (isExpanded && reviewCount > 0) {
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                dueInDay.forEach { unit ->
                                    val unitSubject = subjectsList.find { it.id == unit.subjectId }
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                text = unit.title,
                                                style = MaterialTheme.typography.bodyMedium.autoDirection(),
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            if (unit.highYield) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = strings.highYield, // "Important" / "مهم"
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = HighYieldOrange,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        
                                        if (unitSubject != null) {
                                            val badgeColor = unitSubject.colorHex?.let {
                                                try {
                                                    Color(it.toColorInt())
                                                } catch (e: Exception) {
                                                    MaterialTheme.colorScheme.secondary
                                                }
                                            } ?: MaterialTheme.colorScheme.secondary
                                            
                                            Surface(
                                                color = badgeColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                                            ) {
                                                Text(
                                                    text = unitSubject.name.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor
                                                )
                                            }
                                        }
                                        // No subject: no badge. studyType is a dormant column that printed
                                        // "TOPIC" in English on every subject-less row, in every language.
                                    }
                                }
                            }
                        }
                    }
                }
                
                val laterUnits = activeList.filter { it.nextReviewAt > todayStart + daysToForecast * oneDayMs }
                if (laterUnits.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = if (isFarsiLanguage) "مرورهای دورتر" else if (strings.languageCode == "de") "Spätere Wiederholungen" else "Later Reviews",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isFarsiLanguage) "برنامه‌ریزی برای بیش از ۱۰ روز آینده" else if (strings.languageCode == "de") "Geplant in mehr als 10 Tagen" else "Scheduled beyond 10 days",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "+${if (isFarsiLanguage) com.example.ui.i18n.PersianDate.faDigits(laterUnits.size) else laterUnits.size.toString()}",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
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

/**
 * One of Progress's 14-day charts: x = 0..13 is thirteen days ago .. today (the view model's indexing).
 *
 * Every day is on screen at once, and the last point is TODAY. The charts used to scroll sideways and open on the
 * OLDEST ten days, so the four most recent (today included) were hidden unless the learner thought to swipe the
 * chart itself. Days are labelled by their day of the month in the learner's calendar and digits, every other day,
 * instead of the raw index 0..9. Retention sits on a fixed 0–100% scale in 25% steps; review counts are bars on
 * whole-number steps (ticks read "9.17" and "1.83" reviews before), and the retention line is straight, since a smoothed
 * curve overshoots and suggests values between days that were never measured.
 */
@Composable
private fun FourteenDayChart(model: ChartEntryModel, bars: Boolean, percent: Boolean) {
    val strings = com.example.ui.i18n.LocalStrings.current
    val useJalali = com.example.ui.i18n.LocalUseJalali.current
    val fa = strings.languageCode == "fa"
    fun digits(s: String) = if (fa) com.example.ui.i18n.PersianDate.faDigits(s) else s
    val color = MaterialTheme.colorScheme.primary

    // The y axis: 0-100% in quarters, or counts on a "nice" whole-number step with at most five ticks.
    val (top, steps) = if (percent) 100f to 4 else {
        val max = maxOf(model.maxY, 1f)
        val step = listOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 500, 1000).first { it >= max / 4f }
        val top = kotlin.math.ceil(max / step).toInt().coerceAtLeast(1) * step
        top.toFloat() to top / step
    }
    val overrider = remember(top) {
        com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider.fixed(minX = 0f, maxX = 13f, minY = 0f, maxY = top)
    }
    val chart = if (bars) {
        com.patrykandpatrick.vico.compose.chart.column.columnChart(
            columns = listOf(
                com.patrykandpatrick.vico.compose.component.lineComponent(
                    color = color,
                    thickness = 8.dp,
                    shape = com.patrykandpatrick.vico.core.component.shape.Shapes.roundedCornerShape(allPercent = 40),
                )
            ),
            axisValuesOverrider = overrider,
        )
    } else {
        lineChart(
            lines = listOf(
                com.patrykandpatrick.vico.compose.chart.line.lineSpec(
                    lineColor = color,
                    pointConnector = com.patrykandpatrick.vico.core.chart.DefaultPointConnector(cubicStrength = 0f),
                )
            ),
            axisValuesOverrider = overrider,
        )
    }
    val yLabels = remember(fa, percent) {
        object : com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter<com.patrykandpatrick.vico.core.axis.AxisPosition.Vertical.Start> {
            override fun formatValue(value: Float, chartValues: com.patrykandpatrick.vico.core.chart.values.ChartValues): CharSequence {
                val n = kotlin.math.round(value).toInt()
                return if (!percent) digits(n.toString()) else if (fa) "٪" + digits(n.toString()) else "$n%"
            }
        }
    }
    val xLabels = remember(fa, useJalali) {
        object : com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter<com.patrykandpatrick.vico.core.axis.AxisPosition.Horizontal.Bottom> {
            override fun formatValue(value: Float, chartValues: com.patrykandpatrick.vico.core.chart.values.ChartValues): CharSequence {
                val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, kotlin.math.round(value).toInt() - 13) }
                val day = if (useJalali) {
                    com.example.ui.i18n.PersianDate.gregorianToJalali(
                        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                    ).third
                } else c.get(java.util.Calendar.DAY_OF_MONTH)
                return digits(day.toString())
            }
        }
    }
    Chart(
        chart = chart,
        model = model,
        startAxis = rememberStartAxis(
            valueFormatter = yLabels,
            itemPlacer = remember(steps) { com.patrykandpatrick.vico.core.axis.AxisItemPlacer.Vertical.default(maxItemCount = steps + 1) },
        ),
        bottomAxis = rememberBottomAxis(
            valueFormatter = xLabels,
            // Every other day, ending on today.
            itemPlacer = remember { com.patrykandpatrick.vico.core.axis.AxisItemPlacer.Horizontal.default(spacing = 2, offset = 1) },
        ),
        chartScrollSpec = com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec(isScrollEnabled = false),
        isZoomEnabled = false,
    )
}

@Composable
fun DonutChart(
    data: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    if (data.sum() == 0f) {
        Canvas(modifier = modifier) {
            drawCircle(color = Color.LightGray.copy(alpha = 0.3f), style = Stroke(width = size.width / 4f))
        }
        return
    }
    
    var startAngle = -90f
    Canvas(modifier = modifier) {
        val sweepAngles = data.map { it / data.sum() * 360f }
        val strokeWidth = size.width / 4f
        
        sweepAngles.forEachIndexed { i, angle ->
            if (angle > 0f) {
                drawArc(
                    color = colors[i],
                    startAngle = startAngle,
                    sweepAngle = angle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                )
                startAngle += angle
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StateRow(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(4.dp), color = color, modifier = Modifier.size(12.dp)) {}
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        // In the reader's digits: Persian beside Persian labels (it printed "26" among "۴۲" and "۲۱").
        val languageCode = com.example.ui.i18n.LocalStrings.current.languageCode
        Text(
            if (languageCode == "fa") com.example.ui.i18n.PersianDate.faDigits(count) else count.toString(),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The permanent growth sprout: a stem that rises with [progress] (0..1) and gains leaf pairs along
 * the way; at 1.0 a small blossom appears and stays forever. Deliberately quiet — brand sage, no
 * numbers, no animation. Drawn bottom-up so partial progress still looks like a healthy young plant.
 */
@Composable
fun GrowthSprout(progress: Float, modifier: Modifier = Modifier) {
    val stemColor = MaterialTheme.colorScheme.primary
    val leafColor = MaterialTheme.colorScheme.secondary
    val bloomColor = MaterialTheme.colorScheme.tertiary
    val groundColor = MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p = progress.coerceIn(0f, 1f)
        // Ground line.
        drawLine(
            color = groundColor,
            start = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.95f),
            end = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.95f),
            strokeWidth = h * 0.02f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // Stem: even a fresh plant shows a little sprout (15%), fully grown at p = 1.
        val stemTop = h * 0.95f - (h * 0.15f + h * 0.72f * p)
        drawLine(
            color = stemColor,
            start = androidx.compose.ui.geometry.Offset(w / 2f, h * 0.95f),
            end = androidx.compose.ui.geometry.Offset(w / 2f, stemTop),
            strokeWidth = h * 0.035f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // Leaf pairs appear as the stem passes them (6 leaves over the journey).
        repeat(6) { i ->
            val threshold = (i + 1) / 7f
            if (p >= threshold) {
                val leafY = h * 0.95f - (h * 0.15f + h * 0.72f * threshold)
                val leftLeaf = i % 2 == 0
                val dir = if (leftLeaf) -1f else 1f
                val leafW = w * 0.22f
                val leafH = h * 0.09f
                rotate(degrees = dir * 32f, pivot = androidx.compose.ui.geometry.Offset(w / 2f, leafY)) {
                    drawOval(
                        color = leafColor,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            if (leftLeaf) w / 2f - leafW else w / 2f,
                            leafY - leafH / 2f
                        ),
                        size = androidx.compose.ui.geometry.Size(leafW, leafH),
                    )
                }
            }
        }
        // Fully grown: one calm blossom, permanent.
        if (p >= 1f) {
            drawCircle(color = bloomColor, radius = h * 0.06f, center = androidx.compose.ui.geometry.Offset(w / 2f, stemTop))
        }
    }
}

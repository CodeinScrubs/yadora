package com.example.ui.progress

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
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

import com.example.data.repository.MedReviewRepository
import com.example.ui.theme.HighYieldOrange
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
        
    // Counted from all logs with a FRESH 7-day window each emission, so it can't go stale overnight.
    val reviewsLast7Days = repository.getLogsSince(0L)
        .map { logs ->
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            logs.count { it.reviewedAt >= cutoff }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    // Null = not enough data yet (the UI shows an honest "no data" state instead of a fake-perfect line).
    val retentionChartData = repository.getLogsSince(0L)
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

    val consistencyChartData = repository.getLogsSince(0L)
        .map { list ->
            fun localDay(ms: Long): Int = ((ms + java.util.TimeZone.getDefault().getOffset(ms)) / (1000L * 60 * 60 * 24)).toInt()
            val counts = mutableMapOf<Int, Int>()
            for (log in list) {
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
    data class CalibrationStats(val n: Int, val predictedPct: Int, val actualPct: Int)

    // Only real recall events count: first-study rows (elapsed < 1 day with R≈1) and pre-v2 rows
    // (sentinel -1) are excluded so the comparison is honest — predicted R vs "did it come back?".
    val calibrationStats = repository.getLogsSince(0L)
        .map { logs ->
            val recallLogs = logs.filter { it.retrievabilityAtReview in 0.0..1.0 && it.elapsedDays >= 1.0 && it.logType != "FIRST_STUDY" }
            if (recallLogs.size < 10) return@map null // too little data to be meaningful
            val predicted = recallLogs.sumOf { it.retrievabilityAtReview } / recallLogs.size
            val actual = recallLogs.count { it.memoryRating != "Forgot" }.toDouble() / recallLogs.size
            CalibrationStats(
                n = recallLogs.size,
                predictedPct = Math.round(predicted * 100).toInt(),
                actualPct = Math.round(actual * 100).toInt(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(repository: MedReviewRepository) {
    val viewModel: ProgressViewModel = viewModel(factory = ProgressViewModelFactory(repository))
    val total by viewModel.totalCount.collectAsStateWithLifecycle()
    val strong by viewModel.strongCount.collectAsStateWithLifecycle()
    val building by viewModel.buildingCount.collectAsStateWithLifecycle()
    val newTopics by viewModel.newCount.collectAsStateWithLifecycle()
    val learning by viewModel.learningCount.collectAsStateWithLifecycle()
    val relearn by viewModel.relearnCount.collectAsStateWithLifecycle()
    val reviews7d by viewModel.reviewsLast7Days.collectAsStateWithLifecycle()
    
    val retentionData by viewModel.retentionChartData.collectAsStateWithLifecycle()
    val consistencyData by viewModel.consistencyChartData.collectAsStateWithLifecycle()
    val subjectDifficultyData by viewModel.subjectDifficultyData.collectAsStateWithLifecycle()
    val calibration by viewModel.calibrationStats.collectAsStateWithLifecycle()
    
    val strings = com.example.ui.i18n.LocalStrings.current
    
    var selectedTab by remember { mutableStateOf(0) }
    val isFarsiLanguage = strings.languageCode == "fa"
    val useJalali = com.example.ui.i18n.LocalUseJalali.current
    val tabTitles = if (isFarsiLanguage) listOf("نمای کلی", "تقویم مرور") else listOf("Overview", "Calendar Plan")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(strings.progress, fontWeight = FontWeight.Bold) })
                TabRow(selectedTabIndex = selectedTab) {
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
                
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(title = strings.totalTopics, value = total.toString(), modifier = Modifier.weight(1f))
                        StatCard(title = strings.past7days, value = reviews7d.toString(), modifier = Modifier.weight(1f))
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
                                StateRow(if (isFarsiLanguage) "در حال ساخت" else "Building", building, MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.height(8.dp))
                                StateRow(if (isFarsiLanguage) "جدید" else "New", newTopics, Color.Gray)
                                Spacer(modifier = Modifier.height(8.dp))
                                StateRow(strings.needsRelearn, relearn, relearnColor)
                            }
                        }
                    }
                }
                
                item {
                    Text(if (isFarsiLanguage) "نرخ به‌خاطرسپاری (۱۴ روز اخیر)" else "Retention Rate (Last 14 Days)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
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
                                Chart(
                                    chart = lineChart(),
                                    model = model,
                                    startAxis = rememberStartAxis(),
                                    bottomAxis = rememberBottomAxis(),
                                )
                            } else {
                                // Honest empty state: never plot a fake-perfect 100% before real data.
                                Text(
                                    if (isFarsiLanguage) "هنوز داده‌ای نیست — بعد از چند مرور واقعی نمایش داده می‌شود." else "No data yet — appears after your first real reviews.",
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
                        Text(if (isFarsiLanguage) "دقت زمان‌بندی" else "Scheduler Calibration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
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
                                    else
                                        "Model predicted ${cal.predictedPct}% recall · you actually recalled ${cal.actualPct}%",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                val gap = cal.actualPct - cal.predictedPct
                                Text(
                                    text = when {
                                        Math.abs(gap) <= 5 -> if (isFarsiLanguage) "زمان‌بندی با حافظه‌ات هماهنگ است." else "The schedule matches your memory well."
                                        gap > 5 -> if (isFarsiLanguage) "بهتر از پیش‌بینی به‌ خاطر می‌آوری — فاصله‌ها می‌توانند کمی بلندتر باشند (هدف به‌خاطرسپاری را در تنظیمات کمی پایین بیاور)." else "You remember better than predicted — intervals could stretch a little (try a slightly lower retention target in Settings)."
                                        else -> if (isFarsiLanguage) "کمی بیشتر از پیش‌بینی فراموش می‌کنی — صادقانه امتیاز بده و در صورت نیاز هدف به‌خاطرسپاری را بالاتر ببر." else "You forget a bit more than predicted — rate honestly, and consider a higher retention target in Settings."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isFarsiLanguage) "بر اساس ${fmt(cal.n)} مرور واقعی" else "Based on ${cal.n} real recall reviews",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Text(if (isFarsiLanguage) "ثبات مرور (۱۴ روز اخیر)" else "Review Consistency (Last 14 Days)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Chart(
                                chart = lineChart(),
                                model = consistencyData,
                                startAxis = rememberStartAxis(),
                                bottomAxis = rememberBottomAxis(),
                            )
                        }
                    }
                }
                
                item {
                    Text(if (isFarsiLanguage) "دشواری بر اساس موضوع" else "Difficulty by Subject", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
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
                                Text(if (isFarsiLanguage) "$nWeak ضعیف" else "$nWeak Weak", style = MaterialTheme.typography.bodySmall, color = com.example.ui.theme.Overdue)
                                Text(if (isFarsiLanguage) "$nStrong قوی" else "$nStrong Strong", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
            val limitValue = sharedPrefs.getFloat("daily_review_limit", 50f).toInt()
            
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
                        text = if (isFarsiLanguage) "پیش‌بینی حجم دروس" else "Review Forecast (Next 10 Days)",
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
                        0 -> if (isFarsiLanguage) "امروز" else "Today"
                        1 -> if (isFarsiLanguage) "فردا" else "Tomorrow"
                        else -> com.example.ui.i18n.AppDate.weekdayDate(useJalali, dayNameCalendar.timeInMillis)
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
                                        text = if (isFarsiLanguage) "تعداد نکات مرور: ${com.example.ui.i18n.PersianDate.faDigits(reviewCount)} موضوع" else "$reviewCount topics to review",
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
                                            reviewCount == 0 -> if (isFarsiLanguage) "آزاد" else "Relax"
                                            reviewCount > limitValue -> if (isFarsiLanguage) "پُر" else "Full"
                                            reviewCount > limitValue / 2 -> if (isFarsiLanguage) "متوسط" else "Moderate"
                                            else -> if (isFarsiLanguage) "سبک" else "Light"
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
                                        "این روز از حد تعیین‌شده‌ی شما (${com.example.ui.i18n.PersianDate.faDigits(limitValue)} مرور) بیشتر است. برای سبک‌تر شدن، چند مبحث را زودتر یا دیرتر تنظیم کنید."
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
                                                style = MaterialTheme.typography.bodyMedium,
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
                                                    Color(android.graphics.Color.parseColor(it))
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
                                        } else {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = unit.studyType.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
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
                                        text = if (isFarsiLanguage) "مرورهای دورتر" else "Later Reviews",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isFarsiLanguage) "برنامه‌ریزی برای بیش از ۱۰ روز آینده" else "Scheduled beyond 10 days",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "+${laterUnits.size}",
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
        Text(count.toString(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

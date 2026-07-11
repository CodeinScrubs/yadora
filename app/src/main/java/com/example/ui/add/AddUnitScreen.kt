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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    private var logsJob: kotlinx.coroutines.Job? = null

    fun loadUnit(id: Long) {
        viewModelScope.launch { existingUnit = repository.getUnitById(id) }
        // Cancel any previous log collector so repeated Edit visits don't pile up infinite collectors.
        logsJob?.cancel()
        logsJob = viewModelScope.launch {
            repository.getLogsForUnit(id).collect { logs ->
                _reviewLogs.value = logs
            }
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

    fun saveUnit(title: String, subjectId: Long?, systemId: Long?, studyType: String, prompt: String, notes: String, source: String, highYield: Boolean, studiedAt: Long?, nextReviewAt: Long?, onSaved: () -> Unit = {}, onError: () -> Unit = {}, onDuplicate: () -> Unit = {}) {
        viewModelScope.launch {
            // Block true duplicates on NEW topics only (editing an existing one is never a dup of itself).
            if (existingUnit == null && repository.isDuplicate(title, subjectId, notes, source)) {
                onDuplicate()
                return@launch
            }
            // The DB write runs NonCancellable: navigating back clears this ViewModel and cancels its
            // scope, and without this guard the insert/update could be aborted mid-flight, silently
            // losing the topic. onSaved() fires only AFTER the write commits; on failure onError() fires
            // so the Save button never stays stuck disabled.
            val ok = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val current = existingUnit
                if (current != null) {
                    val newStudiedAt = studiedAt ?: current.studiedAt
                    val updated = current.copy(
                        title = title,
                        subjectId = subjectId,
                        systemId = systemId,
                        studyType = studyType,
                        recallPrompt = prompt,
                        notes = notes,
                        source = source,
                        highYield = highYield,
                        studiedAt = newStudiedAt,
                        lastReviewedAt = current.lastReviewedAt,
                        nextReviewAt = nextReviewAt ?: current.nextReviewAt,
                        updatedAt = System.currentTimeMillis()
                    )
                    // The study date is the replay origin: if it moved and real reviews exist, the
                    // whole history is recomputed from the new origin, atomically with the edit —
                    // this is what backs the "recalculates this topic's review history" caption.
                    if (newStudiedAt != current.studiedAt && current.reviewCount > 0) {
                        repository.updateUnitReplayingHistory(updated)
                    } else {
                        repository.updateUnit(updated)
                    }
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
                    repository.insertUnit(
                        StudyUnitEntity(
                            title = title,
                            subjectId = subjectId,
                            systemId = systemId,
                            studyType = studyType,
                            recallPrompt = prompt,
                            notes = notes,
                            source = source,
                            highYield = highYield,
                            studiedAt = baseTime,
                            lastReviewedAt = null,
                            nextReviewAt = nextReviewAt ?: computedNext,
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
                false
            }
            if (ok) onSaved() else onError()
        }
    }

    /** Correct a past review's ratings; the repository replays history to recompute the schedule. */
    fun editReviewRating(logId: Long, mem: com.example.domain.model.MemoryRating, und: UnderstandingRating) {
        val unitId = existingUnit?.id ?: return
        viewModelScope.launch {
            // If replay aborts (a corrupt log), the topic is left untouched — never half-replayed.
            runCatching { repository.editReviewRating(unitId, logId, mem, und) }
            existingUnit = repository.getUnitById(unitId)
        }
    }
}



/** A small forgetting-curve sparkline: recall probability decaying over time for this topic's stability. */
@androidx.compose.runtime.Composable
private fun ForgettingCurve(stability: Double, modifier: Modifier = Modifier) {
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
            val r = com.example.domain.srs.Fsrs.retrievability(t, stability).toFloat()
            val x = w * i / steps
            val y = h * (1f - r)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
    }
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
    onBack: () -> Unit
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
    var highYield by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selectedSubjectId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var studiedAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var nextReviewAt by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    var editingLog by remember { mutableStateOf<ReviewLogEntity?>(null) }

    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    var showSubjectDropdown by remember { mutableStateOf(false) }
    var showAddSubjectDialog by remember { mutableStateOf(false) }
    var newSubjectName by remember { mutableStateOf("") }
    var newSubjectColor by remember { mutableStateOf("") }
    var sourceLink by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    // Populate ONCE per edit session: without the guard, rotation re-runs this effect and clobbers
    // the user's in-progress (rememberSaveable-restored) edits with the stored DB values.
    var loadedFromUnit by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel.existingUnit) {
        if (loadedFromUnit) return@LaunchedEffect
        viewModel.existingUnit?.let {
            title = it.title
            notes = it.notes ?: ""
            sourceLink = it.source ?: ""
            highYield = it.highYield
            selectedSubjectId = it.subjectId
            studiedAt = it.studiedAt
            nextReviewAt = it.nextReviewAt
            loadedFromUnit = true
        }
    }
    
    var showStudiedAtPicker by remember { mutableStateOf(false) }
    var showNextReviewAtPicker by remember { mutableStateOf(false) }
    
    val strings = com.example.ui.i18n.LocalStrings.current
    val reminderContext = androidx.compose.ui.platform.LocalContext.current
    // Dates honor the user's calendar preference (Jalali/Gregorian), independent of UI language.
    val useJalali = com.example.ui.i18n.LocalUseJalali.current
    val fmtDate: (Long) -> String = { m -> com.example.ui.i18n.AppDate.date(useJalali, m) }

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
                        onClick = {
                            if (!saving) {
                                saving = true
                                // System/study-type/recall-prompt were removed as v1 bloat — persist
                                // neutral values (columns kept to avoid a migration; simply dormant).
                                viewModel.saveUnit(title, selectedSubjectId, null, "Topic", "", notes, sourceLink, highYield, studiedAt, nextReviewAt,
                                    onSaved = {
                                        com.example.notifications.NotificationScheduler.scheduleDailyReminder(reminderContext)
                                        com.example.widget.DueWidgetProvider.updateAll(reminderContext) // new topic changes today's count
                                        onBack()
                                    },
                                    onError = { saving = false },
                                    onDuplicate = {
                                        saving = false
                                        android.widget.Toast.makeText(
                                            reminderContext,
                                            if (strings.languageCode == "fa") "این مبحث از قبل وجود دارد. برای تفکیک، درس یا یادداشت متفاوتی اضافه کن." else "This topic already exists. To keep both, give one a different subject or note.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    })
                            }
                        },
                        enabled = title.isNotBlank() && !saving
                    ) {
                        Text(strings.save)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(strings.topicTitleLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(strings.subjectFolder, style = MaterialTheme.typography.titleSmall)
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
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
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
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Simple color presets
                            val presets = listOf("#E57373", "#81C784", "#64B5F6", "#FFD54F", "#BA68C8", "#4DB6AC")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(presets) { colorHex ->
                                    val parsedColor = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorHex))
                                    Card(
                                        onClick = { newSubjectColor = colorHex },
                                        colors = CardDefaults.cardColors(containerColor = parsedColor),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        modifier = Modifier.size(32.dp).padding(2.dp),
                                        border = if (newSubjectColor == colorHex) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null
                                    ) {}
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
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
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(strings.notesExplanation) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                // Unit-size coaching: scheduling precision depends on topic granularity more than on
                // any algorithm detail — one memory rating can't describe a mega-topic honestly.
                supportingText = if (notes.length > 1500) {
                    {
                        Text(
                            if (strings.languageCode == "fa") "مباحث کوچک‌تر دقیق‌تر زمان‌بندی می‌شوند — اگر می‌شود، این را به چند مبحث بشکن."
                            else "Smaller topics schedule more precisely — consider splitting this into a few."
                        )
                    }
                } else null,
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = sourceLink,
                onValueChange = { sourceLink = it },
                label = { Text(if (strings.languageCode == "fa") "منبع (اختیاری)" else "Source (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(strings.scheduling, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            if (unitId == null) {
                Text(
                    text = if (strings.languageCode == "fa") "این مبحث برای اولین مرور در تب «امروز» نمایش داده می‌شود؛ همان‌جا سختی و میزان درکت را ثبت می‌کنی." else "This appears in Today for its first review — you'll rate difficulty & understanding there.",
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
                            // The study date is the replay origin: changing it after real reviews exist
                            // rebuilds this topic's whole schedule history — warn, don't surprise.
                            if ((viewModel.existingUnit?.reviewCount ?: 0) > 0) {
                                Text(
                                    if (strings.languageCode == "fa") "تغییر این تاریخ، تاریخچهٔ مرور این مبحث را بازمحاسبه می‌کند." else "Changing this date recalculates this topic's review history.",
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
                        supportingContent = { Text(nextReviewAt?.let { fmtDate(it) } ?: (if (strings.languageCode == "fa") "پیش‌فرض: روز مطالعه" else "Default: on study date")) },
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
                    Text(if (strings.languageCode == "fa") "منحنی فراموشی" else "Forgetting curve", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    ForgettingCurve(stability = unit.stability, modifier = Modifier.fillMaxWidth().height(100.dp))
                }
            }

            val logs by viewModel.reviewLogs.collectAsStateWithLifecycle()
            if (logs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(strings.reviewLogs, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (strings.languageCode == "fa") "برای اصلاح یادآوری/درک، روی یک مرور بزن." else "Tap a review to correct its recall / understanding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                logs.forEach { log ->
                    Card(
                        onClick = { editingLog = log },
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
                                    text = com.example.ui.i18n.AppDate.dateTime(useJalali, log.reviewedAt),
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
                                        text = when (logRating) {
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
                            Text(
                                text = "Interval: ${log.previousIntervalDays}d → ${log.nextIntervalDays}d  |  State: ${log.previousState} → ${log.nextState}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    editingLog?.let { log ->
        val fa = strings.languageCode == "fa"
        var mem by remember(log.id) { mutableStateOf(runCatching { MemoryRating.valueOf(log.memoryRating) }.getOrDefault(MemoryRating.Good)) }
        var und by remember(log.id) { mutableStateOf(runCatching { UnderstandingRating.valueOf(log.understandingRating) }.getOrDefault(UnderstandingRating.Clear)) }
        AlertDialog(
            onDismissRequest = { editingLog = null },
            title = { Text(if (fa) "اصلاح ارزیابی" else "Correct this rating") },
            text = {
                Column {
                    Text(strings.memoryRating, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MemoryRating.entries.forEach { r ->
                            FilterChip(
                                selected = mem == r,
                                onClick = { mem = r },
                                label = { Text(when (r) {
                                    MemoryRating.Forgot -> strings.ratingFail
                                    MemoryRating.Hard -> strings.ratingHard
                                    MemoryRating.Good -> strings.ratingGood
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
                                onClick = { und = r },
                                label = { Text(when (r) {
                                    UnderstandingRating.Confused -> strings.urConfused
                                    UnderstandingRating.Partial -> strings.urPartial
                                    UnderstandingRating.Clear -> strings.urClear
                                }) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (fa) "کل زمان‌بندی این مبحث بر اساس ارزیابی اصلاح‌شده بازمحاسبه می‌شود." else "This topic's whole schedule is recalculated from the corrected rating.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = {
                viewModel.editReviewRating(log.id, mem, und)
                // Replay can move the due date → keep the reminder + widget in sync.
                com.example.notifications.NotificationScheduler.scheduleDailyReminder(reminderContext)
                com.example.widget.DueWidgetProvider.updateAll(reminderContext)
                editingLog = null
            }) { Text(strings.save) } },
            dismissButton = { TextButton(onClick = { editingLog = null }) { Text(strings.cancel) } }
        )
    }

    if (showStudiedAtPicker) {
        if (useJalali) {
            // Jalali-calendar users pick on a real Jalali (شمسی) grid — leap years exact.
            com.example.ui.components.JalaliDatePickerDialog(
                initialMillis = studiedAt ?: System.currentTimeMillis(),
                onDismiss = { showStudiedAtPicker = false },
                onConfirm = { millis -> studiedAt = millis; showStudiedAtPicker = false },
            )
        } else {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = studiedAt ?: System.currentTimeMillis())
            DatePickerDialog(
                onDismissRequest = { showStudiedAtPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        studiedAt = datePickerState.selectedDateMillis?.let { datePickerUtcToLocalDay(it) }
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
                onConfirm = { millis -> nextReviewAt = millis; showNextReviewAtPicker = false },
            )
        } else {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = nextReviewAt ?: (System.currentTimeMillis() + 86400000))
            DatePickerDialog(
                onDismissRequest = { showNextReviewAtPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        nextReviewAt = datePickerState.selectedDateMillis?.let { datePickerUtcToLocalDay(it) }
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

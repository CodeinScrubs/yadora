package com.example.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import com.example.ui.i18n.autoDirection
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.StudyUnitEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch

import com.example.data.repository.MedReviewRepository
import com.example.ui.today.StudyUnitCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Suppress("UNCHECKED_CAST")
class LibraryViewModelFactory(private val repository: MedReviewRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return LibraryViewModel(repository) as T
    }
}

enum class LibraryFilter { ALL, DUE, WEAK, HIGH_YIELD }
enum class LibrarySort { DUE, TITLE, WEAKNESS }

class LibraryViewModel(private val repository: MedReviewRepository) : ViewModel() {
    val searchQuery = MutableStateFlow("")
    val showArchived = MutableStateFlow(false)
    
    // We would need repository.archivedUnits, but let's emulate it or we need to add to repository.
    private val _activeUnits = repository.activeUnits.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    
    private val _archivedUnits = repository.archivedUnits.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    
    val subjects: StateFlow<List<com.example.data.local.entity.SubjectEntity>> = repository.allSubjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val systems: StateFlow<List<com.example.data.local.entity.SystemEntity>> = repository.allSystems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filter = MutableStateFlow(LibraryFilter.ALL)
    fun setFilter(f: LibraryFilter) { filter.value = f }

    val sortBy = MutableStateFlow(LibrarySort.DUE)
    fun setSort(s: LibrarySort) { sortBy.value = s }

    private val baseList = combine(_activeUnits, _archivedUnits, showArchived) { active, archived, show ->
        if (show) archived else active
    }

    /**
     * Every topic in the current view (active or archived) whatever the search and filter: what a selection can
     * hold. Finding a duplicate pair means selecting across two searches ("Appendicitis", then "آپاندیسیت"), so
     * the merge dialog must list the whole selection, not only what the current search shows.
     */
    val viewUnits: StateFlow<List<StudyUnitEntity>> = baseList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredUnits: StateFlow<List<StudyUnitEntity>> = combine(
        baseList, searchQuery, subjects, systems,
        combine(filter, sortBy) { f, s -> f to s }
    ) { units, query, subjectList, systemList, (activeFilter, sort) ->
        var result = units
        if (query.isNotBlank()) {
            result = result.filter { u ->
                u.title.contains(query, ignoreCase = true) ||
                    u.studyType.contains(query, ignoreCase = true) ||
                    (u.recallPrompt?.contains(query, ignoreCase = true) == true) ||
                    (u.keyPoints?.contains(query, ignoreCase = true) == true) ||
                    (u.notes?.contains(query, ignoreCase = true) == true) ||
                    (u.source?.contains(query, ignoreCase = true) == true) ||
                    (subjectList.find { it.id == u.subjectId }?.name?.contains(query, ignoreCase = true) == true) ||
                    (systemList.find { it.id == u.systemId }?.name?.contains(query, ignoreCase = true) == true)
            }
        }
        val endOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis
        result = when (activeFilter) {
            LibraryFilter.DUE -> result.filter { it.nextReviewAt <= endOfToday }
            // "Weak" is about the topic NOW, not its record. lapseCount only ever grows, so keying
            // on it meant one bad day years ago branded a topic weak forever -- exactly the kind of
            // permanent judgement the rest of the app avoids. Current state plus RECENT trouble
            // (still relearning, or lapses that have not yet been outgrown) is the honest filter.
            LibraryFilter.WEAK -> result.filter {
                it.state == "NeedsRelearn" || it.state == "Learning" ||
                    (it.lapseCount > 0 && it.stability < com.example.domain.srs.MedScheduler.STRONG_STABILITY_DAYS)
            }
            LibraryFilter.HIGH_YIELD -> result.filter { it.highYield }
            LibraryFilter.ALL -> result
        }
        result = when (sort) {
            LibrarySort.DUE -> result.sortedBy { it.nextReviewAt }
            LibrarySort.TITLE -> result.sortedBy { it.title.lowercase() }
            // Lapses count only up to MAX_SCORED_LAPSES: lapseCount never decays, so an uncapped term let
            // one bad stretch years ago outrank a topic that is weak right now.
            LibrarySort.WEAKNESS -> result.sortedByDescending {
                minOf(it.lapseCount, com.example.domain.srs.MedScheduler.MAX_SCORED_LAPSES) * 10 +
                    (when (it.state) { "NeedsRelearn" -> 50; "Learning" -> 20; else -> 0 })
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun archiveUnit(unitId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.archiveUnit(unitId)
            onComplete()
        }
    }

    fun unarchiveUnit(unitId: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.unarchiveUnit(unitId)
            onComplete()
        }
    }

    // Batch actions run under runCatching so a DB failure surfaces as "didn't work" instead of an
    // unhandled coroutine exception, and onComplete still runs so the UI can never stick in
    // selection mode with a spinner. Matches the discipline already used for review commits.
    fun archiveUnits(unitIds: Collection<Long>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.archiveUnits(unitIds) }
                .onFailure { android.util.Log.w("Yadora", "archive failed", it) }
            onComplete()
        }
    }

    fun unarchiveUnits(unitIds: Collection<Long>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.unarchiveUnits(unitIds) }
                .onFailure { android.util.Log.w("Yadora", "unarchive failed", it) }
            onComplete()
        }
    }

    // 30-day recoverable soft delete (DB v5): only reachable from the archive view.
    val recentlyDeleted: StateFlow<List<StudyUnitEntity>> = repository.recentlyDeleted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun softDelete(unitId: Long) {
        viewModelScope.launch { repository.softDeleteUnit(unitId) }
    }

    /**
     * A merge permanently rewrites the survivor's memory state, so it must run EXACTLY once per
     * confirmation. `isMerging` guards against a double-tap firing it twice (which used to fold the
     * absorbed copies' counts in a second time), mirroring the isProcessing pattern the review
     * screen already uses for ratings. [onResult] reports whether work actually happened, so a
     * no-op merge can no longer be presented to the user as a success.
     */
    var isMerging by mutableStateOf(false)
        private set

    fun mergeUnits(keepId: Long, mergeIds: Collection<Long>, onResult: (Boolean) -> Unit = {}) {
        if (isMerging) return
        isMerging = true
        viewModelScope.launch {
            val merged = runCatching { repository.mergeUnits(keepId, mergeIds) }.getOrNull()
            isMerging = false
            onResult(merged != null)
        }
    }

    fun softDeleteUnits(unitIds: Collection<Long>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.softDeleteUnits(unitIds) }
                .onFailure { android.util.Log.w("Yadora", "batch delete failed", it) }
            onComplete()
        }
    }

    fun restoreDeleted(unitId: Long) {
        viewModelScope.launch { repository.restoreDeletedUnit(unitId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: MedReviewRepository,
    onNavigateToEdit: (Long) -> Unit = {},
    onNavigateToAdd: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    /**
     * Review ONE topic now, whether or not it is due. An early review is ordinary FSRS: recall is
     * predicted high, a success grows stability a little, a lapse is a lapse. The exam date stays
     * decorative; this is the learner choosing to check a topic before a test, and the calibration
     * leaves such reviews out of its evidence (RecallCalibration.EARLY_REVIEW_FRACTION).
     */
    onNavigateToReview: (Long) -> Unit = {},
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(repository))
    val units by viewModel.filteredUnits.collectAsStateWithLifecycle()
    val viewUnits by viewModel.viewUnits.collectAsStateWithLifecycle()
    val recentlyDeleted by viewModel.recentlyDeleted.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val strings = com.example.ui.i18n.LocalStrings.current
    val libContext = androidx.compose.ui.platform.LocalContext.current
    
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    // A selection belongs to one view: switching between the library and the archive starts a new one, so an
    // action here can never reach topics selected over there.
    androidx.compose.runtime.LaunchedEffect(showArchived) { selectedIds = emptySet() }
    // Deleting FROM the archive: the archive toolbar previously offered only Restore, so an archived
    // topic could not be deleted from selection mode at all.
    var showBatchPurgeConfirm by remember { mutableStateOf(false) }
    // Merging duplicates (same material added twice, often in two languages).
    var showMergeDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val isFarsi = strings.languageCode == "fa"

    if (showMergeDialog) {
        // The WHOLE selection, including topics the current search hides: every selected id is merged, so every
        // one must be shown and choosable. Filtering by the visible list let a topic selected under an earlier
        // search be absorbed without appearing in the dialog (seen on the Samsung).
        val candidates = viewUnits.filter { it.id in selectedIds }
        var keepId by remember(selectedIds) {
            // Default to the copy with the most reviews — the one the user has invested most in.
            mutableStateOf(candidates.maxByOrNull { it.reviewCount }?.id ?: candidates.firstOrNull()?.id)
        }
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = {
                Text(when (strings.languageCode) {
                    "fa" -> "ادغام مباحث"
                    "de" -> "Themen zusammenführen"
                    else -> "Merge topics"
                })
            },
            text = {
                Column {
                    Text(
                        when (strings.languageCode) {
                            "fa" -> "کدام عنوان بماند؟ تاریخچهٔ مرورِ همهٔ نسخه‌ها روی همین مبحث جمع می‌شود؛ هیچ مروری از بین نمی‌رود. عنوان‌های دیگر تا ۳۰ روز در «حذف‌شده‌های اخیر» می‌مانند و می‌توانی آن‌ها را به‌صورت مبحثی تازه برگردانی (تاریخچه روی مبحث باقی‌مانده می‌ماند)."
                            "de" -> "Welcher Titel soll bleiben? Der Wiederholungsverlauf aller Kopien wird auf diesem Thema zusammengeführt — keine Wiederholung geht verloren. Die übrigen Titel bleiben 30 Tage in \"Kürzlich gelöscht\" und lassen sich als neues, leeres Thema zurückholen; der Verlauf bleibt beim zusammengeführten Thema."
                            else -> "Which title should stay? The review history of every copy is combined into it — no review is lost. The other titles stay in Recently deleted for 30 days and can be brought back as a fresh topic; the history itself stays with the merged topic."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    candidates.forEach { u ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { keepId = u.id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = keepId == u.id, onClick = { keepId = u.id })
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    u.title,
                                    style = MaterialTheme.typography.bodyLarge.autoDirection(),
                                    maxLines = 2
                                )
                                Text(
                                    when (strings.languageCode) {
                                        "fa" -> "${com.example.ui.i18n.PersianDate.faDigits(u.reviewCount)} مرور"
                                        "de" -> if (u.reviewCount == 1) "1 Wiederholung" else "${u.reviewCount} Wiederholungen"
                                        else -> if (u.reviewCount == 1) "1 review" else "${u.reviewCount} reviews"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = keepId != null && !viewModel.isMerging,
                    onClick = {
                        val keep = keepId ?: return@TextButton
                        val others = selectedIds - keep
                        viewModel.mergeUnits(keep, others) { merged ->
                            if (merged) {
                                com.example.widget.DueWidgetProvider.updateAll(libContext)
                                selectedIds = emptySet()
                                showMergeDialog = false
                            } else {
                                // Nothing was combined (e.g. a copy was deleted from another screen
                                // meanwhile). Say so instead of closing as if it had worked.
                                android.widget.Toast.makeText(
                                    libContext,
                                    when (strings.languageCode) {
                                        "fa" -> "ادغام انجام نشد — چیزی تغییر نکرد."
                                        "de" -> "Zusammenführen fehlgeschlagen — nichts wurde geändert."
                                        else -> "Merge didn't go through — nothing was changed."
                                    },
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                showMergeDialog = false
                            }
                        }
                    }
                ) {
                    Text(when (strings.languageCode) { "fa" -> "ادغام"; "de" -> "Zusammenführen"; else -> "Merge" })
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showBatchPurgeConfirm) {
        val n = selectedIds.size
        AlertDialog(
            onDismissRequest = { showBatchPurgeConfirm = false },
            title = {
                Text(when (strings.languageCode) {
                    "fa" -> "حذف مباحث انتخاب‌شده؟"
                    "de" -> "Ausgewählte Themen löschen?"
                    else -> "Delete selected topics?"
                })
            },
            text = {
                Text(when (strings.languageCode) {
                    "fa" -> "${com.example.ui.i18n.PersianDate.faDigits(n)} مبحث به «حذف‌شده‌های اخیر» می‌رود و تا ۳۰ روز قابل بازگردانی است، سپس برای همیشه پاک می‌شود."
                    "de" -> "$n Themen wandern in \"Kürzlich gelöscht\" und lassen sich 30 Tage lang wiederherstellen, danach werden sie endgültig entfernt."
                    else -> "$n topics move to Recently deleted. You can restore them for 30 days, after which they are removed for good."
                })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds
                        viewModel.softDeleteUnits(ids) {
                            com.example.widget.DueWidgetProvider.updateAll(libContext)
                            selectedIds = emptySet()
                            showBatchPurgeConfirm = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(when (strings.languageCode) { "fa" -> "حذف"; "de" -> "Löschen"; else -> "Delete" })
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchPurgeConfirm = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showBatchDeleteConfirm) {
        val batchDeleteTitle = when (strings.languageCode) {
            "fa" -> if (selectedIds.size == 1) "بایگانی مبحث انتخاب‌شده؟" else "بایگانی مباحث انتخاب‌شده؟"
            "de" -> if (selectedIds.size == 1) "Ausgewähltes Thema archivieren?" else "Ausgewählte Themen archivieren?"
            else -> if (selectedIds.size == 1) "Archive Selected Topic?" else "Archive Selected Topics?"
        }
        val batchDeleteConfirmText = when (strings.languageCode) {
            "fa" -> "مطمئنی که می‌خواهی ${com.example.ui.i18n.PersianDate.faDigits(selectedIds.size)} مبحث انتخاب‌شده را بایگانی کنی؟"
            "de" -> if (selectedIds.size == 1) "Möchtest du das ausgewählte Thema wirklich archivieren?" else "Möchtest du die ${selectedIds.size} ausgewählten Themen wirklich archivieren?"
            else -> if (selectedIds.size == 1) "Are you sure you want to archive the selected topic?" else "Are you sure you want to archive the ${selectedIds.size} selected topics?"
        }
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(batchDeleteTitle) },
            text = { Text(batchDeleteConfirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds
                        viewModel.archiveUnits(ids) {
                            com.example.widget.DueWidgetProvider.updateAll(libContext) // due count changed
                            selectedIds = emptySet()
                            showBatchDeleteConfirm = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(when (strings.languageCode) { "fa" -> "بایگانی"; "de" -> "Archivieren"; else -> "Archive" })
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (selectedIds.isNotEmpty()) {
                TopAppBar(
                    title = {
                        Text(
                            text = when (strings.languageCode) {
                                "fa" -> "${com.example.ui.i18n.PersianDate.faDigits(selectedIds.size)} انتخاب شده"
                                "de" -> "${selectedIds.size} ausgewählt"
                                else -> "${selectedIds.size} Selected"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = when (strings.languageCode) { "fa" -> "لغو انتخاب"; "de" -> "Auswahl aufheben"; else -> "Clear selection" }
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                selectedIds = units.map { it.id }.toSet()
                            }
                        ) {
                            Text(
                                text = strings.selectAll,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (showArchived) {
                            IconButton(onClick = { 
                                val ids = selectedIds
                                viewModel.unarchiveUnits(ids) {
                                    com.example.widget.DueWidgetProvider.updateAll(libContext)
                                    selectedIds = emptySet()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = when (strings.languageCode) {
                                        "fa" -> "بازگردانی موارد انتخاب‌شده"
                                        "de" -> "Ausgewählte wiederherstellen"
                                        else -> "Restore Selected"
                                    },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { showBatchPurgeConfirm = true }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = when (strings.languageCode) {
                                        "fa" -> "حذف موارد انتخاب‌شده"
                                        "de" -> "Ausgewählte löschen"
                                        else -> "Delete Selected"
                                    },
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            // One topic selected: review it now, due or not (a self-test before an exam).
                            if (selectedIds.size == 1) {
                                val reviewId = selectedIds.first()
                                IconButton(onClick = { selectedIds = emptySet(); onNavigateToReview(reviewId) }) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = when (strings.languageCode) {
                                            "fa" -> "همین حالا مرور کن"
                                            "de" -> "Jetzt wiederholen"
                                            else -> "Review now"
                                        },
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (selectedIds.size in 2..4) {
                                IconButton(onClick = { showMergeDialog = true }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MergeType,
                                        contentDescription = when (strings.languageCode) {
                                            "fa" -> "ادغام موارد انتخاب‌شده"
                                            "de" -> "Ausgewählte zusammenführen"
                                            else -> "Merge Selected"
                                        },
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { showBatchDeleteConfirm = true }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = when (strings.languageCode) {
                                        "fa" -> "بایگانی موارد انتخاب‌شده"
                                        "de" -> "Ausgewählte archivieren"
                                        else -> "Archive Selected"
                                    },
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            } else if (showArchived) {
                // Distinct archive header: centered, serif + bold so it's unmistakably a different
                // place, with an explicit way back to the main library on both sides.
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.showArchived.value = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = when (strings.languageCode) { "fa" -> "بازگشت"; "de" -> "Zurück"; else -> "Back" })
                        }
                    },
                    title = {
                        Text(
                            text = when (strings.languageCode) { "fa" -> "کتابخانه · بایگانی"; "de" -> "Bibliothek · Archiv"; else -> "Library · Archived" },
                            fontWeight = FontWeight.Black,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        TextButton(onClick = { viewModel.showArchived.value = false }) {
                            Text(when (strings.languageCode) { "fa" -> "برگشت به اصلی"; "de" -> "Zur Hauptliste"; else -> "Return to main" })
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(strings.library, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = strings.settings, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            // Same quick-add entry point as Today, so adding a topic never requires switching tabs. Not in the archive:
            // nothing is added there, and on a short list the button sat on the last "Restore" (seen on the Samsung).
            if (selectedIds.isEmpty() && !showArchived) {
                FloatingActionButton(
                    onClick = onNavigateToAdd,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = strings.addNewTopic, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Exam countdown, consistent with Today/Progress (exam day not counted).
            val examText = com.example.ui.i18n.ExamCountdown.text(
                libContext.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE).getString("exam_name", "") ?: "",
                libContext.getSharedPreferences("medreview_settings", android.content.Context.MODE_PRIVATE).getLong("exam_date", 0L),
                strings.languageCode
            )
            if (examText != null) {
                Text(
                    text = examText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.searchQuery.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(strings.searchUnits) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = when (strings.languageCode) { "fa" -> "جستجو"; "de" -> "Suchen"; else -> "Search" }) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = showArchived,
                    onClick = { viewModel.showArchived.value = !showArchived },
                    label = { Text(when (strings.languageCode) { "fa" -> "بایگانی"; "de" -> "Archiviert"; else -> "Archived" }) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                var sortExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortExpanded = true }) { Text(when (strings.languageCode) { "fa" -> "مرتب‌سازی"; "de" -> "Sortieren"; else -> "Sort" }) }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        listOf(
                            LibrarySort.DUE to (when (strings.languageCode) { "fa" -> "موعد"; "de" -> "Fälligkeit"; else -> "Due date" }),
                            LibrarySort.TITLE to (when (strings.languageCode) { "fa" -> "عنوان"; "de" -> "Titel"; else -> "Title" }),
                            LibrarySort.WEAKNESS to (when (strings.languageCode) { "fa" -> "ضعف"; "de" -> "Schwäche"; else -> "Weakness" })
                        ).forEach { (s, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.setSort(s); sortExpanded = false })
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    LibraryFilter.ALL to (when (strings.languageCode) { "fa" -> "همه"; "de" -> "Alle"; else -> "All" }),
                    LibraryFilter.DUE to (when (strings.languageCode) { "fa" -> "موعد رسیده"; "de" -> "Fällig"; else -> "Due" }),
                    LibraryFilter.WEAK to (when (strings.languageCode) { "fa" -> "ضعیف"; "de" -> "Schwach"; else -> "Weak" }),
                    // "Important": the word the cards, the Add screen and Today use for the same flag.
                    LibraryFilter.HIGH_YIELD to (when (strings.languageCode) { "fa" -> "مهم"; "de" -> "Wichtig"; else -> "Important" })
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room under the last card for the floating button (see TodayScreen).
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Titles shared by 2+ topics → show the differing detail so they're distinguishable.
                val dupTitles = units.groupingBy { com.example.data.text.TopicTitle.normalize(it.title) }.eachCount().filterValues { it > 1 }.keys
                item {
                    Text((if (units.size == 1) strings.itemsCountOne else strings.itemsCount.format(units.size)).let { if (isFarsi) com.example.ui.i18n.PersianDate.faDigits(it) else it }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(units, key = { it.id }) { unit ->
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    
                    val dismissState = rememberSwipeToDismissBoxState()
                    
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = {
                                coroutineScope.launch {
                                    dismissState.reset()
                                }
                                showDeleteConfirm = false
                            },
                            title = { Text(if (showArchived) when (strings.languageCode) { "fa" -> "بازگردانی مبحث"; "de" -> "Thema wiederherstellen"; else -> "Restore Topic" } else when (strings.languageCode) { "fa" -> "بایگانی مبحث"; "de" -> "Thema archivieren"; else -> "Archive Topic" }) },
                            text = { Text((if (showArchived) strings.restoreTopicConfirm else strings.archiveTopicConfirm).format(unit.title)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        val done = {
                                            com.example.widget.DueWidgetProvider.updateAll(libContext)
                                            showDeleteConfirm = false
                                        }
                                        if (showArchived) {
                                            viewModel.unarchiveUnit(unit.id, done)
                                        } else {
                                            viewModel.archiveUnit(unit.id, done)
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = if (showArchived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                ) {
                                    // Archive is recoverable — the button must never say "Delete".
                                    Text(if (showArchived) when (strings.languageCode) { "fa" -> "بازگردانی"; "de" -> "Wiederherstellen"; else -> "Restore" } else when (strings.languageCode) { "fa" -> "بایگانی"; "de" -> "Archivieren"; else -> "Archive" })
                                }
                            },
                            dismissButton = {
                                Row {
                                    // Archived topics can also be DELETED (recoverable for 30 days,
                                    // then purged) — the only per-topic delete path in the app.
                                    if (showArchived) {
                                        TextButton(
                                            onClick = {
                                                viewModel.softDelete(unit.id)
                                                showDeleteConfirm = false
                                            },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text(when (strings.languageCode) { "fa" -> "حذف"; "de" -> "Löschen"; else -> "Delete" })
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                dismissState.reset()
                                            }
                                            showDeleteConfirm = false
                                        }
                                    ) {
                                        Text(strings.cancel)
                                    }
                                }
                            }
                        )
                    }
                    
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = selectedIds.isEmpty(),
                        // A swipe only ASKS: open the confirmation and put the card straight back, as the
                        // old veto in confirmValueChange did (deprecated in Material3 1.4, no replacement).
                        onDismiss = { direction ->
                            if (direction == SwipeToDismissBoxValue.EndToStart) {
                                showDeleteConfirm = true
                                coroutineScope.launch { dismissState.reset() }
                            }
                        },
                        backgroundContent = {
                            val color = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color, RoundedCornerShape(24.dp))
                                    .padding(end = 24.dp),
                                contentAlignment = androidx.compose.ui.Alignment.CenterEnd
                            ) {
                                Icon(
                                    imageVector = if (showArchived) Icons.Default.Refresh else Icons.Default.Delete,
                                    contentDescription = if (showArchived) (when (strings.languageCode) { "fa" -> "بازگردانی"; "de" -> "Wiederherstellen"; else -> "Restore" }) else (when (strings.languageCode) { "fa" -> "بایگانی"; "de" -> "Archivieren"; else -> "Archive" }),
                                    tint = MaterialTheme.colorScheme.onError
                                )
                            }
                        }
                    ) {
                        val isSelected = selectedIds.contains(unit.id)
                        val inSelectionMode = selectedIds.isNotEmpty()
                        StudyUnitCard(
                            unit = unit,
                            subjects = subjects,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = if (isSelected) {
                                        selectedIds - unit.id
                                    } else {
                                        selectedIds + unit.id
                                    }
                                } else if (!showArchived) {
                                    onNavigateToEdit(unit.id)
                                }
                            },
                            onLongClick = {
                                if (!inSelectionMode) {
                                    selectedIds = setOf(unit.id)
                                }
                            },
                            selected = isSelected,
                            selectable = inSelectionMode,
                            disambiguator = if (com.example.data.text.TopicTitle.normalize(unit.title) in dupTitles)
                                (unit.notes?.trim()?.take(40)?.takeIf { it.isNotBlank() }
                                    ?: unit.source?.trim()?.take(40)?.takeIf { it.isNotBlank() }
                                    ?: subjects.find { it.id == unit.subjectId }?.name)
                            else null
                        )
                    }
                }

                // Recently deleted (archive view only): recoverable for 30 days, then purged on app
                // start. Plain rows with a Restore action — deliberately quieter than real topics.
                if (showArchived && recentlyDeleted.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            when (strings.languageCode) { "fa" -> "حذف‌شده‌های اخیر"; "de" -> "Kürzlich gelöscht"; else -> "Recently deleted" },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when (strings.languageCode) {
                                "fa" -> "تا ۳۰ روز قابل بازگردانی است؛ بعد از آن برای همیشه پاک می‌شود."
                                "de" -> "30 Tage wiederherstellbar, danach endgültig gelöscht."
                                else -> "Recoverable for 30 days, then removed forever."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    items(recentlyDeleted, key = { "del_${it.id}" }) { del ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                del.title,
                                style = MaterialTheme.typography.bodyMedium.autoDirection(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            TextButton(onClick = {
                                viewModel.restoreDeleted(del.id)
                                com.example.widget.DueWidgetProvider.updateAll(libContext)
                            }) {
                                Text(when (strings.languageCode) { "fa" -> "بازگردانی"; "de" -> "Wiederherstellen"; else -> "Restore" })
                            }
                        }
                    }
                }
            }
        }
    }
}

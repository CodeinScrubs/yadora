package com.example.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
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
                    (u.notes?.contains(query, ignoreCase = true) == true) ||
                    (u.source?.contains(query, ignoreCase = true) == true) ||
                    (subjectList.find { it.id == u.subjectId }?.name?.contains(query, ignoreCase = true) == true) ||
                    (systemList.find { it.id == u.systemId }?.name?.contains(query, ignoreCase = true) == true)
            }
        }
        val now = System.currentTimeMillis()
        result = when (activeFilter) {
            LibraryFilter.DUE -> result.filter { it.nextReviewAt <= now }
            LibraryFilter.WEAK -> result.filter { it.state == "NeedsRelearn" || it.state == "Learning" || it.lapseCount > 0 }
            LibraryFilter.HIGH_YIELD -> result.filter { it.highYield }
            LibraryFilter.ALL -> result
        }
        result = when (sort) {
            LibrarySort.DUE -> result.sortedBy { it.nextReviewAt }
            LibrarySort.TITLE -> result.sortedBy { it.title.lowercase() }
            LibrarySort.WEAKNESS -> result.sortedByDescending { it.lapseCount * 10 + (when (it.state) { "NeedsRelearn" -> 50; "Learning" -> 20; else -> 0 }) }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun archiveUnit(unitId: Long) {
        viewModelScope.launch {
            repository.archiveUnit(unitId)
        }
    }

    fun unarchiveUnit(unitId: Long) {
        viewModelScope.launch {
            repository.unarchiveUnit(unitId)
        }
    }

    // 30-day recoverable soft delete (DB v5): only reachable from the archive view.
    val recentlyDeleted: StateFlow<List<StudyUnitEntity>> = repository.recentlyDeleted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun softDelete(unitId: Long) {
        viewModelScope.launch { repository.softDeleteUnit(unitId) }
    }

    fun restoreDeleted(unitId: Long) {
        viewModelScope.launch { repository.restoreDeletedUnit(unitId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: MedReviewRepository,
    onNavigateToEdit: (Long) -> Unit = {}
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModelFactory(repository))
    val units by viewModel.filteredUnits.collectAsStateWithLifecycle()
    val recentlyDeleted by viewModel.recentlyDeleted.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val strings = com.example.ui.i18n.LocalStrings.current
    val libContext = androidx.compose.ui.platform.LocalContext.current
    
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val isFarsi = strings.languageCode == "fa"

    if (showBatchDeleteConfirm) {
        val batchDeleteTitle = if (isFarsi) "بایگانی مباحث انتخاب شده؟" else "Archive Selected Topics?"
        val batchDeleteConfirmText = if (isFarsi) {
            "آیا مطمئن هستید که می‌خواهید ${com.example.ui.i18n.PersianDate.faDigits(selectedIds.size)} مبحث انتخاب شده را بایگانی کنید؟"
        } else {
            "Are you sure you want to archive the ${selectedIds.size} selected topics?"
        }
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(batchDeleteTitle) },
            text = { Text(batchDeleteConfirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            selectedIds.forEach { id ->
                                viewModel.archiveUnit(id)
                            }
                            com.example.widget.DueWidgetProvider.updateAll(libContext) // due count changed
                            selectedIds = emptySet()
                            showBatchDeleteConfirm = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Archive")
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
                            text = if (isFarsi) "${com.example.ui.i18n.PersianDate.faDigits(selectedIds.size)} انتخاب شده" else "${selectedIds.size} Selected",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Selection"
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
                                coroutineScope.launch {
                                    selectedIds.forEach { id -> viewModel.unarchiveUnit(id) }
                                    com.example.widget.DueWidgetProvider.updateAll(libContext)
                                    selectedIds = emptySet()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Restore Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(onClick = { showBatchDeleteConfirm = true }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Archive Selected",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            } else {
                TopAppBar(title = { Text(strings.library, fontWeight = FontWeight.Bold) })
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = showArchived,
                    onClick = { viewModel.showArchived.value = !showArchived },
                    label = { Text(if (isFarsi) "بایگانی" else "Archived") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                var sortExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortExpanded = true }) { Text(if (isFarsi) "مرتب‌سازی" else "Sort") }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        listOf(
                            LibrarySort.DUE to (if (isFarsi) "موعد" else "Due date"),
                            LibrarySort.TITLE to (if (isFarsi) "عنوان" else "Title"),
                            LibrarySort.WEAKNESS to (if (isFarsi) "ضعف" else "Weakness")
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
                    LibraryFilter.ALL to (if (isFarsi) "همه" else "All"),
                    LibraryFilter.DUE to (if (isFarsi) "موعد رسیده" else "Due"),
                    LibraryFilter.WEAK to (if (isFarsi) "ضعیف" else "Weak"),
                    LibraryFilter.HIGH_YIELD to (if (isFarsi) "پربازده" else "High-yield")
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Titles shared by 2+ topics → show the differing detail so they're distinguishable.
                val dupTitles = units.groupingBy { it.title.trim().lowercase() }.eachCount().filterValues { it > 1 }.keys
                item {
                    Text(strings.itemsCount.format(units.size).let { if (isFarsi) com.example.ui.i18n.PersianDate.faDigits(it) else it }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(units, key = { it.id }) { unit ->
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                showDeleteConfirm = true
                            }
                            false
                        }
                    )
                    
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = {
                                coroutineScope.launch {
                                    dismissState.reset()
                                }
                                showDeleteConfirm = false
                            },
                            title = { Text(if (showArchived) (if (isFarsi) "بازگردانی مبحث" else "Restore Topic") else (if (isFarsi) "بایگانی مبحث" else "Archive Topic")) },
                            text = { Text((if (showArchived) strings.restoreTopicConfirm else strings.archiveTopicConfirm).format(unit.title)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (showArchived) {
                                            viewModel.unarchiveUnit(unit.id)
                                        } else {
                                            viewModel.archiveUnit(unit.id)
                                        }
                                        com.example.widget.DueWidgetProvider.updateAll(libContext)
                                        showDeleteConfirm = false
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = if (showArchived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                ) {
                                    // Archive is recoverable — the button must never say "Delete".
                                    Text(if (showArchived) (if (isFarsi) "بازگردانی" else "Restore") else (if (isFarsi) "بایگانی" else "Archive"))
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
                                    contentDescription = if (showArchived) "Restore" else "Archive",
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
                            disambiguator = if (unit.title.trim().lowercase() in dupTitles)
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
                                style = MaterialTheme.typography.bodyMedium,
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

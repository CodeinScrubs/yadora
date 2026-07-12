package com.example.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

data class AppStrings(
    // Global
    val languageCode: String = "en",
    val cancel: String = "Cancel",
    val delete: String = "Delete",
    val save: String = "Save",
    val done: String = "Done",
    val selectAll: String = "Select All",
    val notToday: String = "Not today",
    
    // Bottom Nav
    val navToday: String = "Today",
    val navLibrary: String = "Library",
    val navAdd: String = "Add",
    val navProgress: String = "Progress",
    val navSettings: String = "Settings",

    // Today Screen
    val todayDateTitle: String = "Today's Review",
    val overdue: String = "OVERDUE",
    val priorityFocus: String = "PRIORITY FOCUS",
    val upcoming: String = "UPCOMING",
    val noDueItems: String = "No due items. Great job!",
    val noUpcomingItems: String = "No upcoming items.",
    val todayEstTime: String = "Est. %d min",
    val startReview: String = "Start Review Session",
    val nextReview: String = "Next: %s",
    val sessionComplete: String = "Session Complete!",
    val timeLabel: String = "Time",
    
    // Understanding Ratings
    val urConfused: String = "Confused",
    val urPartial: String = "Partial",
    val urClear: String = "Clear",
    
    // Add Screen
    val addEditTopic: String = "Edit Topic",
    val addNewTopic: String = "Add New Topic",
    val topicTitleLabel: String = "Topic Title (e.g., Photosynthesis)", // subject-neutral: Yadora is for every learner
    val subjectFolder: String = "Subject / Folder",
    val noSubject: String = "No Subject",
    val addNewSubject: String = "+ Add New Subject",
    val subjectName: String = "Subject Name",
    val newSubjectTitle: String = "New Subject",
    val add: String = "Add",
    val highYieldTopic: String = "Important Topic",
    val activeRecallPrompt: String = "Active Recall Prompt",
    val activeRecallPlaceholder: String = "What is the primary mechanism...?",
    val notesExplanation: String = "Notes / Explanation",
    val notesPlaceholder: String = "Key details to remember...",
    val reviewLogs: String = "Review Logs",
    val neverReviewed: String = "Never reviewed",
    val scheduling: String = "Scheduling",
    val lastStudiedAdded: String = "Last Studied / Added",
    val today: String = "Today",
    val nextReviewDate: String = "Next Review Date",
    val defaultTomorrow: String = "Default (Tomorrow)",
    val okBtn: String = "OK",
    val appName: String = "Yadora",
    
    // Library Screen
    val library: String = "Library",
    val searchUnits: String = "Search units...",
    val itemsCount: String = "%d items",
    val deleteTopic: String = "Delete Topic?",
    val deleteTopicConfirm: String = "Are you sure you want to delete '%s'? This action cannot be undone.",
    // Archive is RECOVERABLE — its copy must never claim irreversibility (the old "delete" wording did).
    val archiveTopicConfirm: String = "Archive '%s'? You can restore it from the archive at any time.",
    val restoreTopicConfirm: String = "Restore '%s' back into your active library?",
    
    // Progress Screen
    val progress: String = "Progress",
    val overview: String = "Overview",
    val activeTopics: String = "Active Topics",
    val highYield: String = "Important",
    val needsRelearn: String = "Needs Relearn",
    val knowledgeState: String = "Knowledge State",
    val totalTopics: String = "Total Topics",
    val past7days: String = "7-Day Reviews",
    val strong: String = "Strong",
    val learning: String = "Learning",
    
    // Settings Screen
    val settings: String = "Settings",
    val notifications: String = "Notifications",
    val dailyReviewReminder: String = "Daily Review Reminder",
    val dailyReviewReminderTime: String = "8:00 PM everyday",
    val appearanceRegion: String = "Appearance & Region",
    val language: String = "Language",
    val persianLanguage: String = "فارسی (Persian)",
    val englishLanguage: String = "English",
    val germanLanguage: String = "Deutsch (German)",
    val soundVibration: String = "Sound & Vibration",
    val reminderSound: String = "Reminder Sound",
    val vibration: String = "Vibration",
    val algorithmControl: String = "Algorithm", // read-only section: don't promise "control"
    val spacedRepAlgorithm: String = "Spaced Repetition Algorithm",
    val algorithmDesc: String = "Yadora uses %s to optimize your memory retention. Your items are scheduled based on active recall difficulty ratings.",
    val limitsConstraints: String = "Limits & Constraints",
    val dailyReviewLimit: String = "Daily Review Limit",
    val appSubtitle: String = "Built for serious learners.",
    
    // Language Selection Screen
    val selectLanguage: String = "Select Language / انتخاب زبان",
    val continueBtn: String = "Continue / ادامه",
    
    // Review Session Screen
    val showNotes: String = "Show Notes",
    val memoryRating: String = "Memory Rating",
    val understandingRating: String = "Understanding Rating",
    val recallFirstPrompt: String = "Recall first. Explain from memory before restudy or review.",
    val dueNow: String = "Due Now",
    val needsRelearnState: String = "Needs Relearn",

    // Ratings
    val ratingFail: String = "Forgot", // never "Fail" — forgetting is data, not failure (no-shame rule)
    val ratingHard: String = "Hard",
    val ratingGood: String = "Good",
    val ratingEasy: String = "Easy",
)

val EnglishStrings = AppStrings()

val PersianStrings = AppStrings(
    // Global
    languageCode = "fa",
    cancel = "لغو",
    delete = "حذف",
    save = "ذخیره",
    done = "انجام شد",
    selectAll = "انتخاب همه",
    notToday = "امروز نه",
    
    // Bottom Nav
    navToday = "امروز",
    navLibrary = "کتابخانه",
    navAdd = "افزودن",
    navProgress = "پیشرفت",
    navSettings = "تنظیمات",

    // Today Screen
    todayDateTitle = "مرور امروز",
    overdue = "عقب افتاده",
    priorityFocus = "تمرکز با اولویت",
    upcoming = "آینده",
    noDueItems = "هیچ آیتمی برای مرور نیست. عالیه!",
    noUpcomingItems = "هیچ آیتم آینده‌ای وجود ندارد.",
    todayEstTime = "حدود %d دقیقه",
    startReview = "شروع جلسه مرور",
    nextReview = "بعدی: %s",
    sessionComplete = "جلسه مرور تمام شد!",
    timeLabel = "زمان",
    
    // Understanding Ratings
    urConfused = "مبهم",
    urPartial = "ناقص",
    urClear = "واضح",
    
    // Add Screen
    addEditTopic = "ویرایش مبحث",
    addNewTopic = "افزودن مبحث جدید",
    topicTitleLabel = "عنوان مبحث (مثلاً فتوسنتز)",
    subjectFolder = "موضوع / پوشه",
    noSubject = "بدون موضوع",
    addNewSubject = "+ افزودن موضوع جدید",
    subjectName = "نام موضوع",
    newSubjectTitle = "موضوع جدید",
    add = "افزودن",
    highYieldTopic = "مبحث مهم",
    activeRecallPrompt = "پرسش یادآوری فعال",
    activeRecallPlaceholder = "مکانیسم اصلی چیست...؟",
    notesExplanation = "یادداشت‌ها / توضیحات",
    notesPlaceholder = "نکات کلیدی برای یادآوری...",
    reviewLogs = "تاریخچه مرور",
    neverReviewed = "هرگز مرور نشده",
    scheduling = "زمان‌بندی",
    lastStudiedAdded = "آخرین مطالعه / زمان ذخیره",
    today = "امروز",
    nextReviewDate = "تاریخ مرور بعدی",
    defaultTomorrow = "پیش‌فرض (فردا)",
    okBtn = "تایید",
    appName = "یادورا",
    
    // Library Screen
    library = "کتابخانه",
    searchUnits = "جستجوی مباحث...",
    itemsCount = "%d مورد",
    deleteTopic = "حذف مبحث؟",
    deleteTopicConfirm = "آیا مطمئن هستید که می‌خواهید '%s' را حذف کنید؟ این عمل غیرقابل بازگشت است.",
    archiveTopicConfirm = "«%s» بایگانی شود؟ هر زمان می‌توانی آن را از بایگانی بازگردانی.",
    restoreTopicConfirm = "«%s» به کتابخانهٔ فعال بازگردانده شود؟",
    
    // Progress Screen
    progress = "پیشرفت",
    overview = "نمای کلی",
    activeTopics = "مباحث فعال",
    highYield = "مهم",
    needsRelearn = "نیاز به یادگیری مجدد",
    knowledgeState = "وضعیت دانش",
    totalTopics = "کل مباحث",
    past7days = "مرور ۷ روز گذشته",
    strong = "مسلط",
    learning = "در حال یادگیری",
    
    // Settings Screen
    settings = "تنظیمات",
    notifications = "اعلان‌ها",
    dailyReviewReminder = "یادآوری مرور روزانه",
    dailyReviewReminderTime = "۸:۰۰ شب هر روز",
    appearanceRegion = "ظاهر و منطقه",
    language = "زبان",
    persianLanguage = "فارسی (Persian)",
    englishLanguage = "English",
    soundVibration = "صدا و لرزش",
    reminderSound = "صدای یادآوری",
    vibration = "لرزش",
    algorithmControl = "الگوریتم",
    spacedRepAlgorithm = "الگوریتم تکرار با فاصله",
    algorithmDesc = "برنامه از %s برای بهینه‌سازی حفظ حافظه شما استفاده می‌کند. آیتم‌های شما بر اساس درجه‌بندی دشواری یادآوری فعال زمان‌بندی می‌شوند.",
    limitsConstraints = "محدودیت‌ها",
    dailyReviewLimit = "محدودیت مرور روزانه",
    appSubtitle = "ساخته شده برای یادگیرندگان جدی.",
    
    // Language Selection Screen
    selectLanguage = "Select Language / انتخاب زبان",
    continueBtn = "Continue / ادامه",
    
    // Review Session Screen
    showNotes = "نمایش یادداشت‌ها",
    memoryRating = "درجه‌بندی حافظه",
    understandingRating = "درجه‌بندی درک مطلب",
    recallFirstPrompt = "ابتدا یادآوری کنید. قبل از مطالعه مجدد یا مرور، از حفظ توضیح دهید.",
    dueNow = "موعد الان",
    needsRelearnState = "نیاز به یادگیری مجدد",

    // Ratings
    ratingFail = "فراموشی",
    ratingHard = "سخت",
    ratingGood = "خوب",
    ratingEasy = "آسان",
)

val GermanStrings = AppStrings(
    // Global
    languageCode = "de",
    cancel = "Abbrechen",
    delete = "Löschen",
    save = "Speichern",
    done = "Fertig",
    selectAll = "Alle auswählen",
    notToday = "Heute nicht",

    // Bottom Nav
    navToday = "Heute",
    navLibrary = "Bibliothek",
    navAdd = "Hinzufügen",
    navProgress = "Fortschritt",
    navSettings = "Einstellungen",

    // Today Screen
    todayDateTitle = "Heutige Wiederholung",
    overdue = "ÜBERFÄLLIG",
    priorityFocus = "PRIORITÄT",
    upcoming = "ANSTEHEND",
    noDueItems = "Keine fälligen Themen.",
    noUpcomingItems = "Keine anstehenden Themen.",
    todayEstTime = "ca. %d Min.",
    startReview = "Wiederholung starten",
    nextReview = "Nächste: %s",
    sessionComplete = "Sitzung abgeschlossen",
    timeLabel = "Zeit",

    // Understanding Ratings
    urConfused = "Unklar",
    urPartial = "Teilweise",
    urClear = "Klar",

    // Add Screen
    addEditTopic = "Thema bearbeiten",
    addNewTopic = "Neues Thema",
    topicTitleLabel = "Thementitel (z. B. Photosynthese)",
    subjectFolder = "Fach / Ordner",
    noSubject = "Kein Fach",
    addNewSubject = "+ Neues Fach",
    subjectName = "Fachname",
    newSubjectTitle = "Neues Fach",
    add = "Hinzufügen",
    highYieldTopic = "Wichtiges Thema",
    activeRecallPrompt = "Aktive Abruffrage",
    activeRecallPlaceholder = "Was ist der grundlegende Mechanismus …?",
    notesExplanation = "Notizen / Erklärung",
    notesPlaceholder = "Wichtige Details zum Merken …",
    reviewLogs = "Wiederholungsverlauf",
    neverReviewed = "Noch nie wiederholt",
    scheduling = "Zeitplanung",
    lastStudiedAdded = "Zuletzt gelernt / hinzugefügt",
    today = "Heute",
    nextReviewDate = "Nächster Wiederholungstermin",
    defaultTomorrow = "Standard (morgen)",
    okBtn = "OK",
    appName = "Yadora",

    // Library Screen
    library = "Bibliothek",
    searchUnits = "Themen durchsuchen …",
    itemsCount = "%d Einträge",
    deleteTopic = "Thema löschen?",
    deleteTopicConfirm = "„%s“ wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.",
    archiveTopicConfirm = "„%s“ archivieren? Du kannst es jederzeit aus dem Archiv wiederherstellen.",
    restoreTopicConfirm = "„%s“ zurück in deine aktive Bibliothek holen?",

    // Progress Screen
    progress = "Fortschritt",
    overview = "Überblick",
    activeTopics = "Aktive Themen",
    highYield = "Wichtig",
    needsRelearn = "Neu lernen",
    knowledgeState = "Wissensstand",
    totalTopics = "Themen gesamt",
    past7days = "Wiederholungen (7 Tage)",
    strong = "Gefestigt",
    learning = "Im Lernen",

    // Settings Screen
    settings = "Einstellungen",
    notifications = "Benachrichtigungen",
    dailyReviewReminder = "Tägliche Erinnerung",
    dailyReviewReminderTime = "täglich 20:00 Uhr",
    appearanceRegion = "Darstellung & Region",
    language = "Sprache",
    persianLanguage = "فارسی (Persisch)",
    englishLanguage = "English",
    germanLanguage = "Deutsch",
    soundVibration = "Ton & Vibration",
    reminderSound = "Erinnerungston",
    vibration = "Vibration",
    algorithmControl = "Algorithmus",
    spacedRepAlgorithm = "Spaced-Repetition-Algorithmus",
    algorithmDesc = "Yadora nutzt %s, um dein Behalten zu optimieren. Deine Themen werden anhand deiner Abruf-Bewertungen geplant.",
    limitsConstraints = "Limits",
    dailyReviewLimit = "Tägliches Wiederholungslimit",
    appSubtitle = "Für ernsthafte Lernende.",

    // Language Selection Screen
    selectLanguage = "Select Language / انتخاب زبان",
    continueBtn = "Weiter",

    // Review Session Screen
    showNotes = "Notizen anzeigen",
    memoryRating = "Erinnerung",
    understandingRating = "Verständnis",
    recallFirstPrompt = "Erst abrufen: Erkläre aus dem Gedächtnis, bevor du nachliest.",
    dueNow = "Jetzt fällig",
    needsRelearnState = "Neu lernen",

    // Ratings
    ratingFail = "Vergessen", // never "Fehler" — forgetting is data, not failure (no-shame rule)
    ratingHard = "Schwer",
    ratingGood = "Gut",
    ratingEasy = "Leicht",
)

val LocalStrings = staticCompositionLocalOf { EnglishStrings }

/**
 * Localized label for a stored memory-state name (states live in the DB as raw enum names, so every
 * display site must translate — previously only NeedsRelearn was, and Persian users saw raw English
 * "Learning"/"Building"/"Strong" on cards).
 */
fun AppStrings.stateLabel(state: String): String = when (state) {
    "NeedsRelearn" -> needsRelearnState
    "Strong" -> strong
    "Learning" -> learning
    "Building" -> when (languageCode) { "fa" -> "در حال ساخت"; "de" -> "Im Aufbau"; else -> "Building" }
    "New" -> when (languageCode) { "fa" -> "جدید"; "de" -> "Neu"; else -> "New" }
    else -> state
}

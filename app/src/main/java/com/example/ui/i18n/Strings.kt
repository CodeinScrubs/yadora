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
    val navProgress: String = "Progress",

    // Today Screen
    val todayDateTitle: String = "Today's Review",
    val overdue: String = "OVERDUE",
    val priorityFocus: String = "PRIORITY FOCUS",
    val upcoming: String = "UPCOMING",
    val startReview: String = "Start Review Session",
    val nextReview: String = "Next: %s",
    val sessionComplete: String = "Session complete",
    
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
    val notesExplanation: String = "Notes / Explanation",
    val reviewLogs: String = "Review Logs",
    val scheduling: String = "Scheduling",
    val lastStudiedAdded: String = "Last Studied / Added",
    val today: String = "Today",
    val nextReviewDate: String = "Next Review Date",
    val okBtn: String = "OK",
    val appName: String = "Yadora",
    
    // Library Screen
    val library: String = "Library",
    val searchUnits: String = "Search topics…",
    // "%d items" printed "1 items"; the count is of topics, one form each for one and several.
    val itemsCount: String = "%d topics",
    val itemsCountOne: String = "1 topic",
    // Archive is RECOVERABLE — its copy must never claim irreversibility (the old "delete" wording did).
    val archiveTopicConfirm: String = "Archive '%s'? You can restore it from the archive at any time.",
    val restoreTopicConfirm: String = "Restore '%s' back into your active library?",
    
    // Progress Screen
    val progress: String = "Progress",
    val overview: String = "Overview",
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
    val algorithmDesc: String = "Yadora uses %s to decide when each topic is due. After every review you rate how much you still remembered, and that sets the next date.",
    val limitsConstraints: String = "Limits & Constraints",
    val dailyReviewLimit: String = "Daily Review Limit",
    
    // Language Selection Screen
    val continueBtn: String = "Continue / ادامه",
    
    // Review Session Screen
    val memoryRating: String = "Memory Rating",
    val understandingRating: String = "Understanding Rating",
    // A review is whatever the learner chooses (questions, notes, a lecture, a video); the rating is how
    // much of the topic they still had when they came back to it — the recall outcome FSRS models.
    val memoryQuestion: String = "How much did you still remember?",
    // One sentence: the method row just above already says a review can be done any way.
    val memoryQuestionHint: String = "Rate what you still knew before rereading or checking answers.",
    val understandingNowQuestion: String = "How well do you understand it now?",
    // Key points (DB v8): the scoring standard, ticked after the reveal.
    val dueNow: String = "Due Now",
    val needsRelearnState: String = "Needs Relearn",

    // Ratings
    val ratingFail: String = "Forgot", // never "Fail" — forgetting is data, not failure (no-shame rule)
    val ratingHard: String = "Hard",
    val ratingGood: String = "Good",
    val ratingEasy: String = "Easy",
    // What each rating means for a review done any way. Behaviourally anchored: they describe what the
    // learner still had, never which button is "right".
    val ratingFailMeaning: String = "Most of it was gone",
    val ratingHardMeaning: String = "The core was there, with real gaps",
    val ratingGoodMeaning: String = "I remembered most of it",
    val ratingEasyMeaning: String = "I knew it thoroughly",
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
    navProgress = "پیشرفت",

    // Today Screen
    todayDateTitle = "مرور امروز",
    overdue = "عقب افتاده",
    priorityFocus = "تمرکز با اولویت",
    upcoming = "آینده",
    startReview = "شروع جلسه مرور",
    nextReview = "بعدی: %s",
    sessionComplete = "جلسهٔ مرور تمام شد",
    
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
    notesExplanation = "یادداشت‌ها / توضیحات",
    reviewLogs = "تاریخچه مرور",
    scheduling = "زمان‌بندی",
    lastStudiedAdded = "آخرین مطالعه / زمان ذخیره",
    today = "امروز",
    nextReviewDate = "تاریخ مرور بعدی",
    okBtn = "تایید",
    appName = "یادورا",
    
    // Library Screen
    library = "کتابخانه",
    searchUnits = "جستجوی مباحث…",
    itemsCount = "%d مبحث",
    itemsCountOne = "۱ مبحث",
    archiveTopicConfirm = "«%s» بایگانی شود؟ هر زمان می‌توانی آن را از بایگانی بازگردانی.",
    restoreTopicConfirm = "«%s» به کتابخانهٔ فعال بازگردانده شود؟",
    
    // Progress Screen
    progress = "پیشرفت",
    overview = "نمای کلی",
    highYield = "مهم",
    needsRelearn = "نیاز به یادگیری مجدد",
    knowledgeState = "وضعیت دانش",
    totalTopics = "کل مباحث",
    past7days = "مرور ۷ روز گذشته",
    // "Durable", not "مسلط" (mastered): the state is a memory stability of 21+ days, not exam mastery (an outside audit,
    // 2026-10-04); English says "Strong", German "Gefestigt".
    strong = "پایدار",
    learning = "در حال یادگیری",
    
    // Settings Screen
    settings = "تنظیمات",
    notifications = "اعلان‌ها",
    dailyReviewReminder = "یادآوری مرور روزانه",
    appearanceRegion = "ظاهر و منطقه",
    language = "زبان",
    persianLanguage = "فارسی (Persian)",
    englishLanguage = "English",
    soundVibration = "صدا و لرزش",
    reminderSound = "صدای یادآوری",
    vibration = "لرزش",
    algorithmControl = "الگوریتم",
    spacedRepAlgorithm = "الگوریتم تکرار با فاصله",
    algorithmDesc = "یادورا با %s تعیین می‌کند هر مبحث کِی باید مرور شود. بعد از هر مرور می‌گویی چقدر از آن یادت مانده بود و همین، تاریخ مرور بعدی را تعیین می‌کند.",
    limitsConstraints = "محدودیت‌ها",
    dailyReviewLimit = "محدودیت مرور روزانه",
    
    // Language Selection Screen
    continueBtn = "Continue / ادامه",
    
    // Review Session Screen
    memoryRating = "درجه‌بندی حافظه",
    understandingRating = "درجه‌بندی درک مطلب",
    memoryQuestion = "چقدر از آن یادت مانده بود؟",
    memoryQuestionHint = "بگو پیش از دوباره‌خواندن یا دیدن جواب‌ها چقدر از آن یادت بود.",
    understandingNowQuestion = "الان چقدر آن را می‌فهمی؟",
    dueNow = "موعد الان",
    needsRelearnState = "نیاز به یادگیری مجدد",

    // Ratings
    ratingFail = "فراموشی",
    ratingHard = "سخت",
    ratingGood = "خوب",
    ratingEasy = "آسان",
    ratingFailMeaning = "بیشترش را فراموش کرده بودم",
    ratingHardMeaning = "اصلش یادم بود، ولی با جاهای خالی جدی",
    ratingGoodMeaning = "بیشترش یادم بود",
    ratingEasyMeaning = "کامل و مسلط بودم",
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
    navProgress = "Fortschritt",

    // Today Screen
    todayDateTitle = "Heutige Wiederholung",
    overdue = "ÜBERFÄLLIG",
    priorityFocus = "PRIORITÄT",
    upcoming = "ANSTEHEND",
    startReview = "Wiederholung starten",
    nextReview = "Nächste: %s",
    sessionComplete = "Sitzung abgeschlossen",

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
    notesExplanation = "Notizen / Erklärung",
    reviewLogs = "Wiederholungsverlauf",
    scheduling = "Zeitplanung",
    lastStudiedAdded = "Zuletzt gelernt / hinzugefügt",
    today = "Heute",
    nextReviewDate = "Nächster Wiederholungstermin",
    okBtn = "OK",
    appName = "Yadora",

    // Library Screen
    library = "Bibliothek",
    searchUnits = "Themen durchsuchen …",
    itemsCount = "%d Themen",
    itemsCountOne = "1 Thema",
    archiveTopicConfirm = "„%s“ archivieren? Du kannst es jederzeit aus dem Archiv wiederherstellen.",
    restoreTopicConfirm = "„%s“ zurück in deine aktive Bibliothek holen?",

    // Progress Screen
    progress = "Fortschritt",
    overview = "Überblick",
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
    algorithmDesc = "Yadora bestimmt mit %s, wann jedes Thema fällig ist. Nach jeder Wiederholung bewertest du, wie viel du noch wusstest — das legt den nächsten Termin fest.",
    limitsConstraints = "Limits",
    dailyReviewLimit = "Tägliches Wiederholungslimit",

    // Language Selection Screen
    continueBtn = "Weiter",

    // Review Session Screen
    memoryRating = "Erinnerung",
    understandingRating = "Verständnis",
    memoryQuestion = "Wie viel wusstest du noch?",
    memoryQuestionHint = "Bewerte, was du noch wusstest, bevor du nachgelesen oder Lösungen angesehen hast.",
    understandingNowQuestion = "Wie gut verstehst du es jetzt?",
    dueNow = "Jetzt fällig",
    needsRelearnState = "Neu lernen",

    // Ratings
    ratingFail = "Vergessen", // never "Fehler" — forgetting is data, not failure (no-shame rule)
    ratingHard = "Schwer",
    ratingGood = "Gut",
    ratingEasy = "Leicht",
    ratingFailMeaning = "Das meiste war weg",
    ratingHardMeaning = "Der Kern war da, mit echten Lücken",
    ratingGoodMeaning = "Das meiste wusste ich noch",
    ratingEasyMeaning = "Ich konnte es sicher und vollständig",
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

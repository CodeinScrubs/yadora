package com.example.domain.model

enum class StudyType(val displayName: String) {
    Anatomy("Anatomy"),
    Physiology("Physiology"),
    Pathology("Pathology"),
    Pharmacology("Pharmacology"),
    Microbiology("Microbiology"),
    Biochemistry("Biochemistry"),
    ClinicalMedicine("Clinical Medicine"),
    Other("Other")
}

enum class MemoryRating {
    Forgot, Hard, Good, Easy
}

enum class UnderstandingRating {
    Confused, Partial, Clear
}

enum class StudyState(val displayName: String) {
    New("New"),
    Learning("Learning"),
    Building("Building"),
    Strong("Strong"),
    NeedsRelearn("Needs Relearn")
}

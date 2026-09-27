# What users of a comparable app asked for

The closest app to Yadora on Google Play is **Review - Spaced Repetition** (`fred.tasks`, intelligentreview.app).
It has the same idea: topics instead of flashcards, you study however you like, you rate afterwards, and the app
picks the next date.

- **Standing (Sept 2026):** 3.9 stars, 50K+ installs, 517 ratings, last updated January 2026.
- **Algorithm:** a December 2025 rewrite moved it to FSRS with a retention setting.
- **Premium (one-time):** reminders and cloud sync.

On 2026-09-24 every review with text was read: 271 reviews from June 2020 to September 2026, in 22 languages,
fetched through Play's own review endpoint. This file records what those users valued, what hurt them, and what
Yadora does about each. It is a product input, like [RESEARCH.md](RESEARCH.md). Re-read it before adding a
feature, and before removing one.

## What users loved (keep all of it)

| they said | reviews (thumbs-up) | Yadora |
|---|---|---|
| "exactly what I was looking for": spaced repetition **without flashcards**, for chapters, lectures, courses | 16 (82) | The core concept, a settled decision (CLAUDE.md: a review is whatever the learner chooses). |
| **simple, clean, minimal, no ads** | 37 (217) | No ads, no account, offline. Mature, calm UI. Keep every new feature out of the way. |
| the app **sets the dates from how the review went**, so "I don't have to take care of the dates" | many | FSRS-6 plus Yadora's product layer. |
| flexibility: "rigid sequences create a snowball" | several | Daily limit, "Not today", Spread out, and edits that respect the schedule. |
| a developer who listens and fixes fast | several | — |

## What hurt them, and where Yadora stands

| problem | reviews (thumbs-up) | Yadora |
|---|---|---|
| **Reminders don't arrive**, even after paying: "for a spaced repetition app, timely reminders ARE the whole point" | 34 (98), the #1 complaint | Free, and built in layers: exact alarms, boot re-arm, a WorkManager safety sweep, a Reminder Health panel, and battery-optimisation guidance. The onboarding asks for the exact-alarm permission and explains why. A test reminder can be sent. Scripted device checks: `tools/device/`. |
| **A reminder when nothing is due** (a paid user, unfixed for over a year) | 1 (12) | Never: the receiver posts only when today's plan has something, and it stops once the day's reviews are done (DailyPlan). |
| **Reminders behind a paywall** ("even the most basic reminder is asking for money") | many | Everything is free. |
| **Data loss**: "WHY IS THERE NO BACKUP I LOST ALL MY DATA", "wiped my 7 years of data after Mi Mover", cloud entries that disappeared, a sync provider that shut down | 13 (41) | Full JSON backup and restore, streamed so even a multi-year history fits in memory. Restore validates the whole file before touching anything and keeps a safety copy. **New: automatic daily backup** into a folder the learner picks (a folder a cloud app syncs survives losing the phone), with a week of daily and six monthly copies. Today suggests it once a real history exists. No third-party sync provider can shut down under us. |
| **Premium not recognised, sync needing a second purchase** | 42 (111) | No purchases. |
| **Crashes and "cannot add tasks" after an update** | 23 | CI on every change: unit tests, lint, debug and R8 release builds, a two-year soak test through the real code, and a replay of every exported review by an independent implementation. |
| Organising: folders, subjects, subtopics ("one giant unorganised mess") | 21 (181) | Subjects (folders), filters, search and sort. A subject filter shows only its topics. Collections from older versions are kept and searchable, but the form no longer assigns them. |
| Custom intervals ("let me change the spacing", "one day, one week, one month") | 25 (153) | Deliberately not fixed intervals: a fixed ladder leaves a tail of forgotten topics (below). The principled control is the retention target. Dates can be moved without lying to the model ("Not today", Spread out, edit), and the queue order is simulated. |
| No tutorial ("how does this work?", "we are thrown into the app") | 11 (133) | **New:** the empty Today explains the loop in three steps. The Settings guide explains every rating in plain language. |
| Search, sort by due date, filter today | 14 (102) | All in the Library, plus Today's plan. |
| Calendar view | 12 (55) | Progress → Calendar plan (the next 10 days), and Today's upcoming list. |
| Undo a wrong button; edit a task; set the date it was studied | 10 (88) | Undo in the session, rating correction from the topic's history (the whole history replays), editable study date, and "Save and rate now" for topics studied earlier. |
| Exam date: "distribute reviews until my exam" | 8 (88) | The exam date is a countdown only (settled: it never compresses intervals). **Review ahead** covers the final weeks; the two-year simulation below shows it gets nearly everything to 90%+ on exam day. |
| Links in notes not clickable (Notion); attachments | 16 (28) | **New:** web addresses in notes open when tapped. The source field already opens a link. No file attachments: a link to the PDF or the notes page does the job without copying files into the app. |
| Widget | 8 (14) | A home-screen widget with today's count. |
| A new "retention percentage" confused a long-time user | 1 | Yadora's retention target lives in Settings with a plain explanation, defaulting to 90%, the equal-time optimum. |
| A retention setting that would not stick | 1 | Clamped and rounded on read; slider stops stored exactly (CLAUDE.md). |
| "A single entry is repeating daily" | 1 | The repair clock backs off (YADORA-6) and a lapse relearns once. The soak test checks two years for loops. |
| The app defaulted to Arabic | 1 | The language is chosen on first run. |
| Sync, web, iOS, PC | 17 (35) | Out of scope for an offline Android app. The automatic backup into a synced folder covers the "new phone" case. |

## Is Yadora's scheduling better?

`tools/pilot/residency.py` gives every twin the same review time. It covers two years, about 2,500 topics and an
exam on the last day, for an average learner and for one who forgets 2x faster or slower. Two of the twins stand in
for this app's scheduling:

- **The fixed ladder** reviews after 1, 3, 7, 14, 30, 60, 120, 240 and 365 days, and goes back to 1 after a lapse.
  Its spare time goes to the oldest topics. Several reviewers describe a fixed schedule like this.
- **Plain FSRS-6** is the same memory model with none of Yadora's product layer. It stands in for the current
  version.

**On the average**, all three are close: at equal time Yadora is ahead by 0.2–1.3 points against the ladder and
0.2–0.4 against plain FSRS-6. **On the tail**, the ladder leaves the weakest tenth of topics at 71–82% recall on exam
day, against 88–90% for Yadora.

**With the final push**, Yadora puts 99–100% of topics at 90%+ on exam day. A plain FSRS app has no feature for
that final push. Details: [RESEARCH.md](RESEARCH.md) §2.5.

The gap that matters most is not the equations. It is everything around them that decides whether reviews
happen at all, and whether two years of history survive a new phone. Yadora's reminders work and are free, its
plan is honest about the daily limit, and its backups happen by themselves.

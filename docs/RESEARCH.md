# Yadora — research basis, simulation results and what the pilot must answer

Written 2026-09-24. The owner's standard is the identical-twins test: two students with the same ability,
the same classes and the same study hours. One uses Yadora, the other reviews without a schedule. A year
later, on a quiz about everything they studied, the Yadora twin must remember more, and Yadora must cost
nothing else. This document collects what is known, what the simulation of that exact test shows, what
changed in the app because of it, and what only the pilot can settle.

The honest summary is in three parts:

- **The literature** supports the mechanism strongly: spaced retrieval beats massed or unscheduled review,
  and personalised spacing beats one-size-fits-all.
- **The simulation**, with memory modelled by FSRS-6 (fitted on hundreds of millions of real reviews), shows
  the Yadora twin ahead in every realistic scenario tried. That includes a learner the model misjudges,
  inflated ratings, missed days and a holiday.
- **What is not yet known** is whether Yadora's users, rating whole medical topics reviewed by any method,
  behave like the model assumes. That is what the pilot, and the tooling built for it (`tools/pilot/`), are
  for.

Nothing here guarantees the twin result. Adherence and honest ratings decide it more than any scheduler
parameter.

## 1. What the literature establishes

| finding | evidence | what it means for Yadora |
|---|---|---|
| Spacing retrieval beats massing it | Meta-analysis of 29 studies: spaced vs massed retrieval practice g = 0.74 ([Latimier, Peyre & Ramus 2021](https://link.springer.com/article/10.1007/s10648-020-09572-8)) | The core mechanism works. |
| The shape of the spacing matters little | Same meta-analysis: expanding vs uniform schedules g = 0.034, not significant | Getting reviews to happen, and not too early or too late, matters more than the exact ladder. FSRS's adaptive expanding intervals are fine; nothing to tune here. |
| The best gap grows with how long you need to remember | 1,350+ learners, final tests up to a year later: the optimal gap falls from 20–40% of a one-week retention interval to 5–10% of a one-year one ([Cepeda et al. 2008](https://journals.sagepub.com/doi/10.1111/j.1467-9280.2008.02209.x)) | A schedule should stretch as memory stabilises. FSRS does this per topic. |
| Retrieval practice raises classroom achievement | 222 classroom studies, 48,478 students: g = 0.499 ([Yang et al. 2021](https://pubmed.ncbi.nlm.nih.gov/33683913/)); practice testing and distributed practice rated "high utility", rereading and highlighting "low" ([Dunlosky et al. 2013](https://journals.sagepub.com/doi/abs/10.1177/1529100612453266)) | Yadora schedules when, not how. The Settings guide already says questions usually stick better than rereading. The pilot now records the method, so this can be checked on Yadora's own users (§5, D7). |
| Returns diminish after a few spaced relearning sessions | Successive relearning: little extra long-term benefit beyond 3–5 widely spaced sessions ([Rawson & Dunlosky 2022](https://journals.sagepub.com/doi/full/10.1177/09637214221100484)) | Matches FSRS's growing intervals and the 365-day cap. No change. |
| Personalised spacing beats one-size-fits-all spacing | Semester-long classroom study: personalised review +16.5% retention over massed study and +10.0% over a fixed spaced schedule, a month after the course ([Lindsey et al. 2014](https://journals.sagepub.com/doi/abs/10.1177/0956797613504302)); optimal-control scheduling on Duolingo data ([Tabibian et al. 2019](https://www.pnas.org/doi/10.1073/pnas.1815156116)); SSP-MMC on 220M MaiMemo logs ([Ye et al. 2022](https://dl.acm.org/doi/10.1145/3534678.3539081)) | Supports per-user calibration and the personal weight set. |
| Spaced education works in medicine and lasts | Randomised trials: spaced e-mailed questions improved end-of-year scores ([Kerfoot 2007](https://pubmed.ncbi.nlm.nih.gov/17209889/)); across 724 urology residents, ~4× learning efficiency; benefits persisted 2 years ([Kerfoot 2009](https://www.auajournals.org/doi/10.1016/j.juro.2009.02.024)) | Direct evidence in the target population. |
| Spaced-repetition use tracks exam scores, but causality is unproven | Systematic review, 11 studies, 1,135 students: consistent Step 1 associations, dose-dependent; none randomised, strong self-selection ([2026 review](https://pmc.ncbi.nlm.nih.gov/articles/PMC13197492/)); ~1 Step 1 point per 1,700 cards ([Deng 2015](https://ijms.info/IJMS/article/view/1549)) | Encouraging, not proof. It is also why the twin claim cannot be settled by comparing friends with classmates. |
| Unrehearsed medical knowledge fades, but not to nothing | Modal retention after a year is ~⅔–¾ for unrehearsed basic science ([Custers 2010 review](https://hopkins-stile.med.jhmi.edu/media/Custers.pdf)) | This is the baseline Yadora has to beat: the no-schedule twin still keeps most of it. |
| A judgement made right after studying is unreliable; a delayed one is accurate | Immediate judgements of learning predict recall poorly; judgements made after a delay, from a retrieval attempt, are nearly perfect ([Nelson & Dunlosky 1991](https://journals.sagepub.com/doi/10.1111/j.1467-9280.1991.tb00147.x)) | Supports the first-study cap (the first rating is an immediate judgement), and the review question "how much did you still remember **before** rereading?", which is a delayed, retrieval-based judgement. |
| Knowing the answer inflates what you think you knew | Hindsight bias reaches metamemory: after seeing the answer, people overestimate their earlier confidence ([Memory 2021](https://www.tandfonline.com/doi/abs/10.1080/09658211.2021.1919144)) | **The largest threat to the ratings.** A learner who rates after rereading drifts toward Hard/Good on a topic they had lost. The pilot measures this against question scores (D6); §2 shows the cost. |
| FSRS-6 is the most accurate transparent model available | Public benchmark, ~10k Anki users: FSRS-6 log loss 0.346, RMSE(bins) 0.065, AUC 0.703 vs FSRS-5 0.356 / 0.074 / 0.701 ([srs-benchmark](https://github.com/open-spaced-repetition/srs-benchmark)); per-user optimisation beats the defaults for ~84% of users ([benchmark write-up](https://expertium.github.io/Benchmark.html)) | FSRS-6 stays the live model. Personalisation (calibration, then the personal set) is worth it. |
| The best retention target trades workload against knowledge | FSRS computes the optimum by minimising workload per unit of knowledge in simulation ([FSRS wiki](https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-optimal-retention)) | Yadora's own sweep (§2.3) confirms 0.90 as the default. |

## 2. The twin test, simulated

`tools/pilot/simulate.py` runs the owner's thought experiment directly. Both twins study the same 3 new
topics on 6 days a week for a year (~940 topics), and each day they spend the same review time. The Yadora
twin reviews exactly what the app would schedule: the same FSRS-6 code path, first-study cap, relearn step,
fuzz, priority order, daily limit and per-user calibration. The other twin reviews without a schedule.
Both twins' true memory follows FSRS-6. A review that finds the topic forgotten costs 1.5× a successful
one, since relearning is slower. A year later, both are quizzed on every topic they studied. 8 random
seeds per row, mean ± sd, one year:

| scenario | Yadora twin | other twin | gain | Yadora wins | average over the year (Yadora / other) |
|---|---|---|---|---|---|
| other twin reviews at random | 95.1% | 88.3% | **+6.8** | 8/8 | 95.5% / 90.1% |
| other twin reviews what was studied recently | 95.1% | 89.9% | **+5.2** | 8/8 | 95.5% / 91.1% |
| other twin cycles, oldest first (the disciplined no-app student) | 95.1% | 90.3% | **+4.8** | 8/8 | 95.5% / 91.9% |
| learner forgets 2× faster than the model assumes | 95.1% | 87.2% | **+7.9** | 8/8 | 95.3% / 88.8% |
| learner forgets 2× slower | 94.8% | 89.2% | **+5.6** | 8/8 | 95.4% / 90.9% |
| 30% of forgotten reviews rated Hard (inflated ratings) | 92.5% | 86.3% | **+6.2** | 8/8 | 93.2% / 88.0% |
| skips 30% of days | 95.1% | 88.3% | **+6.8** | 8/8 | 95.3% / 89.7% |
| 3-week holiday mid-year | 95.2% | 88.8% | **+6.4** | 8/8 | 95.4% / 90.2% |
| all at once: forgets 2× faster, 30% inflated, skips 30%, disciplined other twin | 92.4% | 86.7% | **+5.7** | 8/8 | 92.9% / 88.2% |
| heavy load: 6 new topics a day, daily limit 30 | 93.3% | 85.8% | **+7.5** | 8/8 | 94.8% / 89.0% |

Put as forgetting, the Yadora twin forgets 4.9% of the year's topics. The random-review twin forgets
11.7% and the disciplined cycler 9.7%. That is **about half the forgetting for the same hours**.

### 2.1 The one way the other twin can win, and what closed it

If the other twin saves **all** review time for a four-week cram right before an **announced** exam, they
score higher on that day: 98.3% vs 95.1%, or 96.8% when only half is saved. But the cram needs **199**
(or 100) topic reviews a day for four weeks, which is not humanly possible for real medical topics. And
the crammer's knowledge averages **66.6%** over the year (84.8% for half), against Yadora's 95.5%. In
clinical terms, the crammer knows it on exam day and not the rest of the year.

The realistic version gives both twins the same daily hours all year and the same final push: 30 topics
a day for the last four weeks. The Yadora twin spends its push on the topics predicted weakest; the other
twin reviews everything oldest-first. **Yadora wins again: 96.4% vs 91.7%, 8/8.**

Until now, reviewing ahead in Yadora meant opening topics one at a time from the Library. So the app now
has **Review ahead** (Today, once the day's reviews are done). It offers rated topics that are not yet
due, weakest predicted recall first, 20 per session (`ui/today/ReviewAhead`). It reads no exam date and
compresses no interval: each review is an ordinary early review that FSRS scores honestly. The settled
decision that the exam date feeds nothing stands.

### 2.2 What inflated ratings cost

Rating 30% of forgotten reviews as Hard still leaves the Yadora twin ahead of the other twin at equal
time. But it costs the learner 2.6 points of absolute knowledge (95.1 → 92.5%), because the scheduler
stretches intervals on topics that had actually been lost. At 60% the cost is 7.3 points. **Honest "Forgot"
ratings are worth more than any parameter in this document.** The review screen already asks what the
learner had *before* rereading, and each button says what it means. The pilot now checks ratings against
question scores (D6).

### 2.3 The retention target

Same twins, the Yadora twin at different targets, the other twin given the same time each time:

| target | Yadora twin | other twin | gain | reviews per topic per year |
|---|---|---|---|---|
| 0.80 | 89.7% | 83.8% | +5.8 | 3.6 |
| 0.85 | 92.3% | 86.0% | +6.3 | 4.4 |
| **0.90** | **95.1%** | 88.3% | **+6.8** | 5.9 |
| 0.93 | 96.5% | 90.2% | +6.3 | 7.3 |
| 0.95 | 97.3% | 91.7% | +5.6 | 9.0 |
| 0.97 | 98.3% | 94.2% | +4.1 | 13.1 |

The advantage of scheduling at equal time peaks at 0.90, the default. Higher targets buy absolute
knowledge at a steep price: 0.95 gives +2.2 points for 1.5× the reviews, 0.97 gives +3.2 points for
2.2×. That matches the Settings guide, so the default stays.

### 2.4 What the simulation cannot tell

- True memory is FSRS-6. It is the best available model, but its heavy tail makes unreviewed forgetting
  mild: under it, an unreviewed Medium topic is still ~50% recalled after six months. If whole medical
  topics decay faster than flashcards, the scheduling advantage grows; the k = 0.5 row points that way.
- A topic review is treated as one retrieval event, whatever the method. The pilot records the method so
  this can be checked.
- The understanding repair clock and the Important flag are not simulated.
- The equal-time assumption holds the other twin to Yadora's time. It does not model the Yadora twin
  spending logging time: a minute or so per topic by estimate, which is real but small.

## 3. What changed in the app because of this research

1. **Review ahead.** Weakest first, not yet due, on Today when the day is done (§2.1). The exam-countdown
   copy in Settings now points to it for the final weeks.
2. **Pilot data that cannot be backfilled later** (DB v10, export v12, backup v9):
   - how each review was done (optional: Questions, Reading, Lecture/video, Other);
   - an optional question score (right / out of);
   - which kind of session logged it (today's plan, review more anyway, one topic, review ahead);
   - a pseudonymous research ID (`YD-XXXX-XXXX`: random, no name, no account) so several participants'
     files can be pooled;
   - content-free topic-size proxies (notes length, has source, title length);
   - a field guide inside every export, so a person or an AI can read the file cold.
   None of it feeds the scheduler.
3. **Share research data.** One tap in Settings sends the export through any app (chat, e-mail, drive). The
   file name carries the research ID and date.
4. **The analysis toolkit** (`tools/pilot/`). `analyze.py` replays every exported review through an
   independent Python transcription of the scheduler (`yadora_model.py`). That transcription is checked
   against all 7,048 py-fsrs golden values and against kotlin-stdlib's RNG bit for bit. The phone must
   have stored exactly the elapsed days, the prediction and the interval the rules produce. On a real app
   export (`PilotExportFixtureTest`), all 208 logs match. Any mismatch in a friend's file is a bug on that
   phone, found without the phone.

## 4. Considered and not changed

- **Default weights and the 5-day first-study cap**: the pilot decides (D3, D5). The on-device personal
  model keeps its strict held-out gate.
- **Expanding vs uniform spacing, and within-day interleaving**: the evidence says schedule shape matters
  little, and interleaving helps with discriminating similar categories, not with ordering whole-topic
  reviews. Priority order stays.
- **Using the question score in scheduling**: not until the pilot shows how it relates to self-ratings (D6).
- **Exam-date interval compression**: a settled decision. Review ahead gives the learner the same power
  without the exam date touching the schedule.
- **A randomised in-app control arm** (withholding reviews from some topics to measure the effect): it
  would give the participant exactly the downside the twin standard forbids. The pilot measures the model
  instead, and the twin question is answered by simulation on the fitted model (§5).

## 5. What the pilot must answer

The decision rules are fixed in advance in [PILOT.md](PILOT.md), so two months of noisy data cannot talk
anyone into a change. Ranked by how much they matter to the twin claim:

1. **Adherence** (D8, D9): do reviews happen near their dates, and is the first rating given on the
   study day? A schedule that is not followed helps nobody.
2. **Rating honesty** (D6): do memory ratings follow question scores? This is the hindsight-bias check.
3. **Calibration** (D2, D4): does reported recall match the prediction, and do learners forget
   systematically faster or slower than the defaults assume?
4. **The first interval** (D3): is the first check-in too early or too late for each first rating?
5. **Method** (D7): after a Questions review, does the next one go better than after rereading?
6. **A pooled refit** (D5): do fitted weights predict held-out reviews better than the defaults?

Afterwards, rerun the twin simulation with the fitted weights as the true memory:
`python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json`. That answers the twin
question for the pilot's real learners rather than for an average Anki user.

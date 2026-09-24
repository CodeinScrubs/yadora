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
| other twin reviews at random | 95.0% | 88.4% | **+6.6** | 8/8 | 95.3% / 89.8% |
| other twin reviews what was studied recently | 95.0% | 89.8% | **+5.2** | 8/8 | 95.3% / 90.8% |
| other twin cycles, oldest first (the disciplined no-app student) | 95.0% | 90.1% | **+4.9** | 8/8 | 95.3% / 91.6% |
| learner forgets 2× faster than the model assumes | 95.1% | 87.6% | **+7.5** | 8/8 | 95.3% / 88.7% |
| learner forgets 2× slower | 94.9% | 89.2% | **+5.7** | 8/8 | 95.5% / 91.1% |
| 30% of forgotten reviews rated Hard (inflated ratings) | 92.4% | 86.1% | **+6.3** | 8/8 | 93.2% / 87.9% |
| skips 30% of days | 95.0% | 88.1% | **+6.9** | 8/8 | 95.3% / 89.5% |
| 3-week holiday mid-year | 95.0% | 88.0% | **+7.0** | 8/8 | 95.2% / 89.6% |
| all at once: forgets 2× faster, 30% inflated, skips 30%, disciplined other twin | 92.3% | 86.1% | **+6.2** | 8/8 | 92.7% / 88.2% |
| heavy load: 6 new topics a day, daily limit 30 | 93.8% | 85.9% | **+7.9** | 8/8 | 95.1% / 89.1% |

Put as forgetting, the Yadora twin forgets 5.0% of the year's topics. The random-review twin forgets
11.6% and the disciplined cycler 9.9%. That is **about half the forgetting for the same hours**.

### 2.1 The one way the other twin can win, and what closed it

If the other twin saves **all** review time for a four-week cram right before an **announced** exam, they
score higher on that day: 98.3% vs 95.0%, or 96.6% when only half is saved. But the cram needs **193**
(or 100) topic reviews a day for four weeks, which is not humanly possible for real medical topics. And
the crammer's knowledge averages **66.5%** over the year (84.7% for half), against Yadora's 95.3%. In
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
time. But it costs the learner 2.6 points of absolute knowledge (95.0 → 92.4%), because the scheduler
stretches intervals on topics that had actually been lost. At 60% the cost is 7.3 points. **Honest "Forgot"
ratings are worth more than any parameter in this document.** The review screen already asks what the
learner had *before* rereading, and each button says what it means. The pilot now checks ratings against
question scores (D6).

### 2.3 The retention target

Same twins, the Yadora twin at different targets, the other twin given the same time each time:

| target | Yadora twin | other twin | gain | reviews per topic per year |
|---|---|---|---|---|
| 0.80 | 89.7% | 84.1% | +5.6 | 3.7 |
| 0.85 | 92.5% | 86.3% | +6.3 | 4.5 |
| **0.90** | **95.0%** | 88.4% | **+6.6** | 5.7 |
| 0.93 | 96.5% | 90.4% | +6.0 | 7.3 |
| 0.95 | 97.4% | 91.7% | +5.7 | 9.2 |
| 0.97 | 98.4% | 94.4% | +4.0 | 13.0 |

The advantage of scheduling at equal time peaks at 0.90, the default. Higher targets buy absolute
knowledge at a steep price: 0.95 gives +2.4 points for 1.6× the reviews, 0.97 gives +3.4 points for
2.3×. That matches the Settings guide, so the default stays.

### 2.4 The remaining scheduling choices, tested one at a time

`tools/pilot/experiments.py` changes one policy knob at a time on the Yadora twin. The queue order is
compared on 16 paired seeds; every other result below is 6 seeds over a year (three years for the interval
cap), except the difficulty-adaptive sweep, which used 4 seeds.

A knob that changes the workload is judged **at equal time**: its retention target is swept, and
knowledge is read at the review count the current policy spends at 0.90. A knob that doesn't is compared
directly, with the daily limit genuinely binding.

**Queue order, when the daily limit binds.** This decides which due topics get today's slots after a
holiday or under overload. The limit fixes the workload, so knowledge is compared directly. Every order
runs on the same 16 seeds (the same classes and first ratings), so the differences are paired. The first
row is Yadora's final quiz (with the year's average in brackets); the other rows are the paired difference
from it in points, final (year average). Standard errors are 0.03–0.22.

| order | heavy load (6 new/day, limit 15) | holiday, then limit 20 | forgets 2× faster | forgets 2× slower | heavy load, 40% overconfident first ratings |
|---|---|---|---|---|---|
| **Yadora: Important first, then most overdue** | **85.4% (90.6)** | **94.9% (95.1)** | **82.4% (88.7)** | **92.2% (94.3)** | **85.1% (90.4)** |
| Yadora before 2026-09-24: + bonuses for weak states and past lapses | −0.26 (−0.45) | −0.12 (−0.12) | −0.32 (−0.55) | −0.25 (−0.23) | −0.05 (−0.43) |
| lowest recall first | −1.20 (−0.80) | +0.05 (−0.04) | −1.55 (−0.97) | −1.39 (−0.49) | −1.40 (−0.97) |
| most overdue relative to interval | −1.26 (−0.83) | −0.04 (−0.07) | −1.25 (−0.81) | −1.72 (−0.67) | −1.22 (−0.83) |
| highest recall first | −6.93 (−4.34) | −0.15 (−0.05) | −5.62 (−3.79) | −5.14 (−1.97) | −6.66 (−4.44) |

(Both forgetting-rate worlds: 5 new topics a day, limit 15.)

**This changed the app.** Yadora's score used to add bonuses for weak states (just forgotten +80,
learning +40, building +20) and +10 per past lapse. Under a backlog those spent the day's slots on the
topics a review strengthens least, while stronger topics slid further past due. Without them the queue
knew more through the year in all five worlds (+0.12 to +0.55 points) and at the one-year quiz in four
(+0.12 to +0.32; the fifth is equal within noise). It never knew less. Both bonuses cost knowledge; in
most worlds the lapse term cost more. So the queue is now Important first, then the most overdue
(`MedScheduler.priorityScore`). A topic that was just forgotten still comes back on its relearn date,
but under a backlog it waits its turn behind older debt instead of jumping ahead. Nothing changes on a
day whose reviews all fit, which is most days at the default limit of 50. "Highest recall first", which
some apps use for backlogs, is clearly the worst.

**The other knobs, at equal time** (re-run with the new queue order; the earlier run agreed within
0.2 points, except the over-confident row, which sat up to 0.5 points lower then; see the caution below):

| choice | options, final knowledge (and year average) | verdict |
|---|---|---|
| Relearn step after Forgot | **1 day 94.9% (95.0)** · 2 days 95.0% (95.1) · FSRS's own post-lapse interval 95.0% (95.1) | within noise; keep 1 day, the sensible next-day check after restudying |
| First-study cap, honest ratings | **5 d 94.9% (95.0)** · 3 d 94.8% (95.0) · 7 d 94.9% (95.0) · none 94.9% (95.1) | no difference |
| First-study cap, 40% of first ratings a grade too high | **5 d 95.2% (95.3)** · 3 d 95.2% (95.4) · 7 d 95.2% (95.2) · none 95.2% (95.1) | the cap keeps 0.2 points over the year here (0.2–0.3 in the earlier run) and costs nothing when ratings are honest: a cheap guard; keep 5 d |
| Maximum interval, three years | **365 d 95.6% (95.2)** · 180 d 96.2% (95.2) · none 95.1% (95.2) | same average knowledge; no cap is slightly worse at the end; keep 365 d |

A caution for reading these: one seed's review count can differ from another's by ±8%, because the
per-user calibration amplifies early luck. That is why every workload-changing knob is compared at equal
time along its own retention sweep, and why differences under ~0.2 points are called noise.

**Difficulty-adaptive retention (evaluated, not adopted).** FSRS's equations make a review of a hard topic
buy less stability, so optimal-control work on spaced repetition (SSP-MMC) targets lower retention for
harder items. Tested as target = base + slope × (D − 5.5) / 4.5:

| slope | year-average knowledge vs flat | hardest quarter of topics at the quiz | worst tenth of topics |
|---|---|---|---|
| −0.02 | +0.2 to +0.4 points | −0.5 to −0.7 points | about unchanged |
| −0.04 | +0.1 to +0.6 | −1.5 to −1.9 | −1.0 to −1.5 |
| −0.06 / −0.08 | smaller or negative | −2 to −4 | −3 to −5 |

The results are the same across the default, fast, slow and overconfident learners. The best version buys
about 0.3 points (≈6% less forgetting at equal time) by letting the hardest topics slip a little, and for a
medical student those are often the high-yield ones. That is a product trade-off rather than a free gain,
so the target stays flat until the owner decides and the pilot's own data can weigh it. The reverse
direction, higher targets for harder topics, is clearly worse (−0.9 points).

**Conclusion.** Within the model, every scheduling choice Yadora makes is now at or within noise of the
best alternative tested. One was not: the queue order, which changed (above). The one remaining
alternative with a measurable gain, the difficulty-adaptive target, comes with a trade-off the owner
should choose.

### 2.5 Two years to a residency exam

The owner's case, simulated directly (`tools/pilot/residency.py`, 6 seeds). A candidate studies 4 new topics
(chapters, lectures, question blocks) on 6 days a week for two years, about 2,500 topics, and sits the exam on
the last day. Every twin gets exactly the Yadora twin's review time, day by day.

Exam day, a learner the defaults describe. The first number is the average recall over every topic studied in
the two years; the second is the share of topics at 90% or more; the third is the recall of the weakest tenth:

| twin | Yadora at the default 0.90 | + Review ahead in the last 4 weeks (up to 60 a day) |
|---|---|---|
| **Yadora** | **95.2% · 96% · 90.0%** | **96.4% · 100% · 92.2%** |
| reviews at random | 87.7% · 65% · 52.0% | 90.8% · 72% · 59.0% |
| reviews oldest first (the disciplined student) | 89.3% · 69% · 55.4% | 90.9% · 73% · 58.2% |
| fixed ladder: 1, 3, 7, 14, 30, 60, 120, 240, 365 days | 95.0% · 86% · 79.6% | 96.1% · 91% · 85.3% |
| plain FSRS-6, no product layer, at equal time | 95.0% · 98% · 90.5% | (no such feature) |

Yadora wins every seed against random and oldest-first review, in every world and every strategy tested: +5 to
+9 points on exam day. With the final push it reaches **100% of topics at 90%+**, where the other twins reach
72–73%.

Across learners, Yadora's exam-day average · share at 90%+ · weakest tenth, and its reviews a day:

| learner | default 0.90 | + final push | target 0.95 for the last 6 months | 0.95 for the last 6 months + push |
|---|---|---|---|---|
| as the defaults assume | 95.2 · 96% · 90.0 (27/day) | **96.4 · 100% · 92.2** (28/day) | 96.3 · 97% · 89.7 (30/day) | 96.7 · 99% · 91.6 (31/day) |
| forgets 2× faster | 95.1 · 96% · 89.7 (36/day) | **95.6 · 99% · 91.1** (36/day) | 95.2 · 92% · 81.1 (36/day) | 95.9 · 94% · 86.7 (37/day) |
| forgets 2× slower | 95.3 · 95% · 89.9 (21/day) | **97.1 · 100% · 93.2** (22/day) | 96.6 · 97% · 90.6 (25/day) | 97.4 · 100% · 93.5 (25/day) |
| 6 new topics a day | 94.8 · 92% · 88.5 (39/day) | 95.2 · 95% · 89.8 (39/day) | 94.8 · 90% · 81.2 (39/day) | 95.3 · 92% · 85.1 (39/day) |

**The exam playbook that follows:**

- Stay at the 0.90 default all the way.
- In the last four weeks, after each day's reviews, use **Review ahead**.

Raising the target to 0.95 for the last six months costs more reviews and buys less on exam day. For a fast
forgetter or a heavy load it is worse than doing nothing: the extra reviews overflow the daily limit, and the
weakest tenth drops from about 90% to 81%. The Settings copy used to recommend raising the target, and now says
this instead.

**Against a fixed ladder or a plain FSRS app**, at equal time the averages are close. Yadora is ahead by 0.2–1.3
points against the ladder (3–6 seeds out of 6) and by 0.2–0.4 against plain FSRS-6 (4–5 of 6). The difference is
the tail. The ladder leaves its weakest tenth at 71–82% on exam day, against Yadora's 88–90%. That is why "no topic left behind" is a claim only a model-based schedule can make. Plain
FSRS-6 at equal time matches Yadora's tail; Yadora's lead over it is the per-user calibration (largest for learners
the defaults misjudge) and the final push, which a plain FSRS app has no feature for.

**Checked on the real code.** `TwoYearSoakTest` runs the same case through the app itself:

- **What it runs:** 730 days, 2,432 topics and 22,804 reviews, through the review screen's own commit path, with
  today's plan, the daily limit, the queue order, the calibration refresh, deferrals, a holiday and 40 Review ahead
  topics a day in the last four weeks.
- **Exam day:** 96.6% recall, with every topic at 90%+ and the weakest tenth at 92.7%. The twin who spent the same
  time on random reviews reaches 90.1%.
- **Every invariant held** on every day.
- **Independent replay:** the export replays 25,236 of 25,236 logs exactly in `analyze.py`. CI runs that replay on
  every change.

### 2.6 What the simulation cannot tell

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
5. **Queue order: Important first, then the most overdue** (§2.4). The bonuses for weak states and past
   lapses are gone: under a backlog they spent the day's slots where a review buys least, and every
   simulated backlog knew more without them.

## 4. Considered and not changed

- **Default weights and the 5-day first-study cap**: the pilot decides (D3, D5). The on-device personal
  model keeps its strict held-out gate.
- **Expanding vs uniform spacing, and within-day interleaving**: the evidence says schedule shape matters
  little, and interleaving helps with discriminating similar categories, not with ordering whole-topic
  reviews. (Which due topics come first under a backlog is a different question; §2.4 tested it and
  changed it.)
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

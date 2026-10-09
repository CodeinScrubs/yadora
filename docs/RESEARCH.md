# Yadora — research basis, simulation results and what the pilot must answer

Written 2026-09-24. The owner's standard is the identical-twins test: two students with the same ability,
the same classes and the same study hours. One uses Yadora, the other reviews without a schedule. A year
later, on a quiz about everything they studied, the Yadora twin must remember more, and Yadora must cost
nothing else. This document collects what is known, what the simulation of that exact test shows, what
changed in the app because of it, and what only the pilot can settle.

The honest summary is in three parts:

- **The literature** supports the mechanism strongly: spaced retrieval beats massed or unscheduled review,
  and adaptive review has outperformed fixed spacing in specific studied settings. These findings do not
  establish superiority of every personalised scheduler or of whole-topic post-study ratings.
- **The simulation**, with memory modelled by FSRS-6 (fitted on hundreds of millions of real reviews), shows
  the Yadora twin ahead in every realistic scenario tried. That includes a learner the model misjudges,
  inflated ratings, missed days and a holiday.
- **What is not yet known** is whether Yadora's users, rating whole medical topics reviewed by any method,
  behave like the model assumes. That is what the pilot, and the tooling built for it (`tools/pilot/`), are
  for.

Nothing here guarantees the twin result. Adherence, rating semantics, content quality and transfer to exam questions remain major uncertainties.

## 1. What the literature establishes

| finding | evidence | what it means for Yadora |
|---|---|---|
| Spacing retrieval beats massing it | Meta-analysis of 29 studies: spaced vs massed retrieval practice g = 0.74 ([Latimier, Peyre & Ramus 2021](https://link.springer.com/article/10.1007/s10648-020-09572-8)) | Evidence for spaced retrieval; not a test of Yadora's whole-topic ratings or its scheduling policy. |
| Expanding and uniform retrieval schedules were not significantly different in this synthesis | Same meta-analysis: g = 0.034, not significant | This is not equivalence and does not compare every adaptive scheduler. It gives no general proof that the choice of scheduling rule is unimportant. Yadora keeps its fitted model and tests its own choices separately. |
| The optimal two-session gap depends on the retention horizon | 1,350+ learners: the optimal gap falls from 20–40% of a one-week retention interval to 5–10% of a one-year one ([Cepeda et al. 2008](https://pubmed.ncbi.nlm.nih.gov/19076480/)) | This does not validate the exact chain of FSRS intervals. The exam date remains display-only; any future horizon-aware policy needs its own evidence. |
| Retrieval practice raises classroom achievement | 222 classroom studies, 48,478 students: g = 0.499 ([Yang et al. 2021](https://pubmed.ncbi.nlm.nih.gov/33683913/)); practice testing and distributed practice rated "high utility", rereading and highlighting "low" ([Dunlosky et al. 2013](https://journals.sagepub.com/doi/abs/10.1177/1529100612453266)) | Yadora schedules when, not how. The Settings guide already says questions usually stick better than rereading. The pilot now records the method, so this can be checked on Yadora's own users (§5, D7). |
| Repeated successful relearning can improve durable learning efficiently | Successive relearning, reviewed by [Rawson & Dunlosky 2022](https://journals.sagepub.com/doi/full/10.1177/09637214221100484); the [2011 experiments](https://pubmed.ncbi.nlm.nih.gov/21707204/) recommend three correct concept recalls initially and three spaced relearning sessions | Whole-topic post-study Good ratings are a different measurement. This does not establish the five-day first-study ceiling, the 365-day cap or a universal number of whole-topic reviews. |
| Personalised spacing has outperformed fixed spacing in a specific classroom study | A time-matched middle-school foreign-language study reports +16.5% retention over massed study and +10.0% over fixed spacing ([Lindsey et al. 2014](https://journals.sagepub.com/doi/abs/10.1177/0956797613504302)); memory-model scheduling research includes [Tabibian et al. 2019](https://pubmed.ncbi.nlm.nih.gov/30670661/) and [Ye et al. 2022](https://dl.acm.org/doi/10.1145/3534678.3539081) | A reason to evaluate personalisation, not proof that a fitted Yadora model benefits every learner or every clinical topic. |
| Spaced education works in medicine and lasts | Randomised trials: spaced e-mailed questions improved end-of-year scores ([Kerfoot 2007](https://pubmed.ncbi.nlm.nih.gov/17209889/)); across 724 urology residents, ~4× learning efficiency; benefits persisted 2 years ([Kerfoot 2009](https://www.auajournals.org/doi/10.1016/j.juro.2009.02.024)) | Evidence for question-based spaced education in particular medical settings, not a Yadora equal-time residency-exam trial. |
| Anki use has observational associations with some exams, with mixed results elsewhere | A [2026 systematic review](https://pmc.ncbi.nlm.nih.gov/articles/PMC13197492/) includes 11 studies and 1,135 students; none randomly assigned Anki use. Its one Step 2 CK study found no significant benefit. In 72 students, ~1 Step 1 point per 1,700 unique cards was an adjusted association ([Deng 2015](https://pubmed.ncbi.nlm.nih.gov/26498443/), [coefficient erratum](https://link.springer.com/article/10.1007/s40037-016-0312-2)) | Associations do not establish a causal gain at equal study time, and Step 1 does not automatically transfer to a clinical residency exam. |
| Spaced repetition works in medical education, at scale | Systematic review of 14 studies, 13 included in the meta-analysis, 21,415 learners: SMD 0.78 (0.56–0.99) against standard studying ([Maye & Hurley 2026](https://doi.org/10.1111/tct.70353)); a randomized trial in 26,258 family physicians: learning d = 0.62, transfer to new questions d = 0.26, and two spaced repetitions beat one ([Price et al. 2025](https://doi.org/10.1097/ACM.0000000000005856)); 9 RCTs in health professions: retention g = 0.62, at high risk of bias ([Sezgin & Bektaş 2026](https://doi.org/10.1016/j.nedt.2026.107285)) | Supports evaluating spaced education in health professions. These interventions and comparators do not establish Yadora's superiority at equal total study time (§5.1). |
| A single study session fades fast; higher-order knowledge fades less | Residents after one online tutorial kept half its gain at 3–8 days and none measurable at 55 days, the curve following the 1/4 power of the delay ([Bell et al. 2008](https://doi.org/10.1007/s11606-008-0604-2)); over about 15 months physical-therapy students forgot 9% of higher-level (Bloom's) items and 17.5% of lower-level ones, at the same rate for top and bottom performers ([Ambler et al. 2025](https://doi.org/10.1093/ptj/pzaf133)) | Topics can forget faster than an average Anki card early on, which the per-user calibration shortens for; how fast depends on the kind of knowledge, which the pilot's per-subject table watches. |
| The shape of forgetting depends on the material | 210 data sets: the logarithmic, power and two square-root functions fit about equally ([Rubin & Wenzel 1996](https://doi.org/10.1037/0033-295x.103.4.734)); 916 data sets: exponential-power, logarithmic and linear fit most widely, the power function was not the best fit most often, and some materials showed stable or improving memory ([Radvansky et al. 2024](https://doi.org/10.3758/s13423-024-02514-3)) | FSRS-6's curve is a fitted choice, not a law, so the pilot checks it (calibration by predicted recall and by time since the last review). In two simulated examples of topic mixtures, one FSRS-6 curve stayed within 2 points over a year; this is not a bound on arbitrary heterogeneous topics (computed 2026-10-03: 1.8 points for 70% of a topic at S = 50 d and 30% at S = 3 d; at most 1 point for a log-normal spread of 1). |
| Unrehearsed medical knowledge fades, but not to nothing | Modal retention after a year is ~⅔–¾ for unrehearsed basic science ([Custers 2010 review](https://hopkins-stile.med.jhmi.edu/media/Custers.pdf)) | This is one reported context for unrehearsed basic science, not a measured baseline for the residency exam or every no-schedule learner. |
| A judgement made right after studying is unreliable; a delayed one is far better | Immediate judgements of learning predict recall poorly; judgements made after a delay, from a retrieval attempt, rank which items will be recalled far more accurately (relative accuracy, on word pairs: it says which items are known, not that the absolute level is right) ([Nelson & Dunlosky 1991](https://journals.sagepub.com/doi/10.1111/j.1467-9280.1991.tb00147.x)) | Supports the first-study cap (the first rating is an immediate judgement), and the review question "how much did you still remember **before** rereading?", which asks for the delayed, retrieval-based judgement. It is answered afterwards, though, so it is not the same measurement: hindsight (next row) can still inflate it, which is why the pilot checks ratings against question scores (D6). |
| Knowing the answer inflates what you think you knew | Hindsight bias reaches metamemory: once people know the outcome, they misremember their earlier judgements of learning toward it ([Zimdahl & Undorf 2021](https://doi.org/10.1080/09658211.2021.1919144)). Across 95 studies hindsight bias averages d = 0.39, and the manipulations designed to reduce it did not ([Guilbault et al. 2004](https://doi.org/10.1080/01973533.2004.9646399)) | **A major measurement risk.** A learner who rates after rereading drifts toward Hard/Good on a topic they had lost. A better wording is unlikely to remove it; the pilot measures it against question scores (D6, and the score bands in the report); §2 shows the cost. |
| FSRS-6 is a benchmarked memory model with a pinned implementation in Yadora | The [primary benchmark](https://github.com/open-spaced-repetition/srs-benchmark) reports 9,999 collections and 349,923,850 evaluated reviews. Without same-day reviews: FSRS-6 log loss 0.3460, plain FSRS-7 0.3401, FSRS-7 recency 0.3370, MOVING-AVG 0.3369. These are distinct variants and prediction tasks, not exam trials | Keep the pinned model while checking prospective predictive value on Yadora data. A simple reference can beat a memory model on log loss without providing a useful scheduling rule. Personalisation requires held-out validation; benchmark averages do not guarantee each user improves. |
| A retention target trades workload against modelled knowledge | The [FSRS workload simulation](https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-optimal-retention) and Yadora's sweep (§2.3) evaluate declared memory models and policies | 0.90 remains a practical pilot default supported in those simulated scenarios, not a measured universal optimum for whole topics or exam scores. |

## 2. The twin test, simulated

`tools/pilot/simulate.py` runs the owner's thought experiment directly. Both twins study the same 3 new
topics on 6 days a week for a year (~940 topics), and each day they spend the same review time. The Yadora
twin reviews exactly what the app would schedule: the same FSRS-6 code path, first-study cap, relearn step,
fuzz, priority order, daily limit and per-user calibration. The other twin reviews without a schedule.
Both twins' true memory follows FSRS-6. A review that finds the topic forgotten costs 1.5× a successful
one, since relearning is slower. A year later, both are quizzed on every topic they studied. 8 random
seeds per row, mean ± sd, one year.

Equal time means equal to within one review. The other twin starts a review while any of the day's time is
left, and a forgotten topic costs more than a remembered one, so a day can end over budget; since
2026-09-27 that overspend comes off the next day. Before, it was forgiven, and the other twin got 2.3–2.6%
more time than the Yadora twin (found by an outside audit). The table was re-run after the fix; the gains
moved by −0.3 to +0.5 points, mostly up, since the other twin lost its extra time.

Re-run again on 2026-09-28, after the calibration stopped lengthening intervals (§2.7). The Yadora twin now
reviews a little more (5.9 reviews per topic a year at 0.90, against 5.7) and knows more (95.3% against 95.0%).
The other twin gets the same extra time, so the gains moved by −0.5 to +0.2 points; where ratings are inflated
or the learner forgets slower, Yadora's own knowledge rose 0.9–1.4 points.

Re-run on 2026-09-29 with the new queue order (§2.4); the tables in §2–§2.3 are that run. The daily limit
binds for long only in the heavy-load row, which gained 0.3 points. Elsewhere Yadora's own quiz moved by at most
0.2, and the gains by −0.2 to +0.8: the order also decides which random draw each review receives, and every
other twin's time follows the Yadora twin's.

| scenario | Yadora twin | other twin | gain | Yadora wins | average over the year (Yadora / other) |
|---|---|---|---|---|---|
| other twin reviews at random | 95.3% | 88.6% | **+6.7** | 8/8 | 95.6% / 90.0% |
| other twin reviews what was studied recently | 95.3% | 90.0% | **+5.3** | 8/8 | 95.6% / 91.1% |
| other twin cycles, oldest first (the disciplined no-app student) | 95.3% | 90.3% | **+5.0** | 8/8 | 95.6% / 91.7% |
| learner forgets 2× faster than the model assumes | 95.2% | 87.8% | **+7.4** | 8/8 | 95.4% / 88.9% |
| learner forgets 2× slower | 95.8% | 90.0% | **+5.8** | 8/8 | 96.1% / 91.4% |
| 30% of forgotten reviews rated Hard (inflated ratings) | 93.8% | 86.3% | **+7.5** | 8/8 | 94.3% / 88.2% |
| skips 30% of days | 95.3% | 88.4% | **+6.9** | 8/8 | 95.5% / 89.9% |
| 3-week holiday mid-year | 95.3% | 88.4% | **+6.9** | 8/8 | 95.5% / 89.6% |
| all at once: forgets 2× faster, 30% inflated, skips 30%, disciplined other twin | 92.7% | 86.5% | **+6.2** | 8/8 | 93.1% / 88.3% |
| heavy load: 6 new topics a day, daily limit 30 | 94.3% | 85.9% | **+8.4** | 8/8 | 95.3% / 89.0% |

Put as forgetting, the Yadora twin forgets 4.7% of the year's topics. The random-review twin forgets
11.4% and the disciplined cycler 9.7%. That is **about half the forgetting for the same hours**.

### 2.1 The one way the other twin can win, and what closed it

If the other twin saves **all** review time for a four-week cram right before an **announced** exam, they
score higher on that day: 98.2% vs 95.3%, or 96.8% when only half is saved. But the cram needs **200**
(or 100) topic reviews a day for four weeks, which is not humanly possible for real medical topics. And
the crammer's knowledge averages **66.6%** over the year (84.3% for half), against Yadora's 95.6%. In
clinical terms, the crammer knows it on exam day and not the rest of the year.

The realistic version gives both twins the same daily hours all year and the same final push: 30 topics
a day for the last four weeks. The Yadora twin spends its push on the topics predicted weakest; the other
twin reviews everything oldest-first. **Yadora wins again: 96.5% vs 91.8%, 8/8** (91.9% if the other
twin reviewed at random before the push).

Until now, reviewing ahead in Yadora meant opening topics one at a time from the Library. So the app now
has **Review ahead** (Today, once the day's reviews are done). It offers rated topics that are not yet
due, weakest predicted recall first, 20 per session (`ui/today/ReviewAhead`). It reads no exam date and
compresses no interval: each review is an ordinary early review that FSRS scores honestly. The settled
decision that the exam date feeds nothing stands.

### 2.2 What inflated ratings cost

Rating 30% of forgotten reviews as Hard still leaves the Yadora twin ahead of the other twin at equal
time. But it costs the learner 1.5 points of absolute knowledge (95.3 → 93.8%), because the scheduler
stretches intervals on topics that had actually been lost. At 60% the cost is 3.3 points. Until 2026-09-28
it was 2.6 and 7.3: the calibration read the generous ratings as slower forgetting and stretched every
interval on top; it no longer lengthens intervals (§2.7). **Inflated ratings can undermine this policy; the relative importance of rating errors and individual
parameters in real users has not been measured here.** The review screen already asks what the
learner had *before* rereading, and each button says what it means. The pilot now checks ratings against
question scores (D6).

### 2.3 The retention target

Same twins, the Yadora twin at different targets, the other twin given the same time each time:

| target | Yadora twin | other twin | gain | reviews per topic per year |
|---|---|---|---|---|
| 0.80 | 90.0% | 84.1% | +5.9 | 3.8 |
| 0.85 | 92.8% | 86.0% | +6.8 | 4.5 |
| **0.90** | **95.3%** | 88.6% | **+6.7** | 6.0 |
| 0.93 | 96.8% | 90.5% | +6.2 | 7.5 |
| 0.95 | 97.7% | 92.2% | +5.5 | 9.7 |
| 0.97 | 98.6% | 94.6% | +4.0 | 14.3 |

The advantage of scheduling at equal time is largest at 0.85–0.90 (a tie within noise). 0.90 stays the
default: it knows 2.5 points more than 0.85 for 1.3× the reviews. Higher targets buy absolute knowledge at a
steep price: 0.95 gives +2.4 points for 1.6× the reviews, 0.97 gives +3.3 points for 2.4×. That matches the
Settings guide.

### 2.4 The remaining scheduling choices, tested one at a time

`tools/pilot/experiments.py` changes one policy knob at a time on the Yadora twin. The queue order is
compared on 16 paired seeds; every other result below is 6 seeds over a year (three years for the interval
cap), except the difficulty-adaptive sweep, which used 4 seeds.

A knob that changes the workload is judged **at equal time**: its retention target is swept, and
knowledge is read at the review count the current policy spends at 0.90. A knob that doesn't is compared
directly, with the daily limit genuinely binding. In `experiments.py`, "equal time" is an equal NUMBER of
reviews: every review costs the same there, while a real review of a forgotten topic takes longer, so a
knob that lapses more is flattered slightly (`simulate.py` and `residency.py` charge a lapse 1.5×). A count
outside a policy's swept range is reported as such; until 2026-09-28 it was extrapolated from the two
nearest points, which an outside audit rightly flagged.

**Queue order, when the daily limit binds.** This decides which due topics get today's slots after a
holiday or under overload. The limit fixes the workload, so knowledge is compared directly. Every order
runs on the same 16 seeds (the same classes and first ratings), so the differences are paired. Each cell
gives three numbers: the one-year quiz, the weakest tenth of topics on that day, and the longest any due topic
waited past its due day (the starvation check). The first row is absolute; the other rows are the paired
difference from it in points. Standard errors are 0.04–0.16 for the quiz and 0.07–0.84 for the weakest tenth.
Re-run 2026-09-29, when the first row became Yadora's order.

| order | heavy load (6 new/day, limit 15) | holiday, then limit 20 | forgets 2× faster | forgets 2× slower | heavy load, 40% overconfident first ratings |
|---|---|---|---|---|---|
| **Yadora since 2026-09-29: lateness + capped review value** | **86.28% · 59.0% · 76 d** | **95.22% · 90.5% · 24 d** | **83.82% · 53.6% · 71 d** | **92.61% · 77.8% · 48 d** | **86.16% · 59.3% · 73 d** |
| lateness alone (Yadora 2026-09-24 to 09-29) | −0.92 · −2.06 · 56 d | −0.01 · −0.10 · 22 d | −1.53 · −2.46 · 51 d | −0.42 · −4.82 · 31 d | −0.89 · −2.35 · 57 d |
| + bonuses for weak states and past lapses (Yadora before 2026-09-24) | −0.96 · −0.56 · 67 d | −0.23 · −0.51 · 26 d | −1.84 · −1.20 · 63 d | −0.58 · −3.03 · 44 d | −1.07 · −0.65 · 66 d |
| lowest recall first | −2.14 · +15.44 · 308 d | −0.08 · +0.04 · 38 d | −2.97 · +16.50 · 308 d | −1.70 · +5.87 · 227 d | −2.41 · +12.28 · 303 d |
| most overdue relative to interval | −2.07 · +14.91 · 295 d | −0.12 · −0.15 · 37 d | −2.59 · +15.46 · 312 d | −1.88 · +5.50 · 234 d | −2.27 · +12.64 · 297 d |
| highest recall first | −7.89 · −16.21 · 339 d | −0.27 · −2.66 · 98 d | −6.97 · −16.27 · 340 d | −7.18 · −27.46 · 298 d | −7.41 · −16.05 · 340 d |

(Both forgetting-rate worlds: 5 new topics a day, limit 15.)

**The first change, 2026-09-24.** Yadora's score used to add bonuses for weak states (just forgotten +80,
learning +40, building +20) and +10 per past lapse. They were set by label, large (+80 is 16 days of lateness)
and blind to how far recall had actually fallen. Measured then, against lateness alone, the queue without them
knew more through the year in all five worlds (+0.12 to +0.55 points) and at the one-year quiz in four (+0.12 to
+0.32; the fifth equal within noise). It never knew less; in most worlds the lapse term cost more. So the queue
became Important first, then the most overdue.

**The second change, 2026-09-29: the review value.** The score now adds how much a review NOW would strengthen
the topic: (1 − R) · R · the relative stability gain of a Good review, on the topic's own model and weight set.
It is large for a young topic whose recall is falling, where the next successful review multiplies stability,
and small for a mature topic, whose flat curve can wait. The score is 100 if Important, plus 5 points per day
overdue, plus 80 × the value, capped at 200 points (40 days of lateness) (`MedScheduler.priorityScore`).

- **Where the limit binds for weeks**, it knew more than lateness alone at the quiz (+0.42 to +1.53 points) and
  over the year (+0.09 to +1.02). It lifted the weakest tenth by 2.1 to 4.8 points. After the holiday, where the
  limit binds only briefly, it ties. At the default load (3 new a day, limit 50), where the limit never binds, the
  two are equal within noise (−0.02 ± 0.04 at the quiz).
- **Two harder worlds**, where the model is wrong about the learner (paired as above): with 30% of lapses rated
  Hard, +0.90 at the quiz and +0.74 on the weakest tenth; with a true forgetting curve twice as steep as the
  model's, +2.36 and +2.32.
- **On the real app**, `TwoYearSoakTest`'s two years (§2.5) at a daily limit of 25 instead of 50: exam-day recall
  94.1% → 95.6%, weakest tenth 66.6% → 76.4%, topics at 90%+ 83.6% → 88.6%, topics more than two weeks overdue on
  exam day 362 → 259. One seed. At the default limit of 50 the two orders tie (96.9% both).
- **The price is a longer wait for mature topics**: the longest wait past due rose from 22–57 days to 24–76 in the
  five worlds of the table, and from 47 and 65 to 65 and 84 in the two harder ones. The cap makes the bound a rule,
  not just a measurement: a topic is never passed over by one more than 40 days less overdue (60 if that one is
  Important).

The term came from an outside report (a "Whittle index plus concave aging", 2026-09-28) with lateness as
10·ln(1 + days) and a write-off below 10% recall. That form knew more still: +0.1 to +3.9 at the quiz and +7.9 to
+15.1 on the weakest tenth wherever the limit binds for weeks. But it let topics wait 153–291 days past due, because a log barely
grows, so a mature topic can lose to fresher ones for most of a year. Yadora keeps lateness linear and caps the
value. The weights were checked too. At 5 points a day, value weights of 40, 80, 100 and 160 each gained a little
more than the last, and the topics waited a little longer: heavy load +0.71, +1.10, +1.16 and +1.37 at the quiz,
with waits of 65, 79, 82 and 95 days. A cap of 100 gave up as much as half the gain. A cap of 300 never bound:
in the seed measured, 80 × the value reached at most 262 points under the heavy load and 148 at the default load.
The cap of 200 bound on 1.7% of due topic-days under the heavy load, and it came within a few tenths of no cap.
The report's +60 repair bonus and its Important multiplier were not taken. Neither was the write-off: on FSRS-6's
flat curve, recall falls below 10% only after about three million times the stability.

**The table's other lesson.** Lowest recall first and relative lateness lift the weakest tenth far more than any
other order (+5.5 to +16.5 points), because they always serve the weakest topic. They pay with 1.7–3.0 points on
the average, which is what an exam sampling the whole syllabus measures, and they leave mature topics unreviewed
for 227–312 days. Yadora keeps the average. At a load the limit can hold, "no topic left behind" comes from the
limit having room (every topic at 90%+ on exam day in §2.5) and from Review ahead, not from the backlog order.
"Highest recall first", which some apps use for backlogs, is the worst on every count.

**Backlog-triage proposals (2026-09-28).** Two outside reports called ranking by lateness a "FIFO trap" and
proposed two alternatives. One ranks by the recall deficit times a recoverability factor, (R* − R)+ × (1 − e^(−S/τ)).
The other ranks by the recall that three more days of waiting would cost (`experiments.py`, `YADORA_EXTRA_ORDERS`).
Their premise does not hold on FSRS-6: a topic 30 days overdue still sits at 59–88% predicted recall, not near 5%,
and the one-day drop in recall is largest right after a review, not near R 0.75–0.85. The table uses the same five
backlogs and 16 paired seeds, with the current calibration. It gives the points against Yadora's order of the
time (lateness alone) at the quiz, with the year average in brackets:

| order | heavy load | holiday, then limit 20 | forgets 2× faster | forgets 2× slower | heavy, over-confident first ratings |
|---|---|---|---|---|---|
| deficit × recoverability, τ = 10 d | −1.20 (−0.71) | −0.05 (+0.01) | −0.38 (−0.12) | −1.14 (−0.44) | −0.81 (−0.46) |
| τ = 30 d | −1.90 (−0.91) | −0.08 (+0.01) | −0.86 (−0.35) | −1.68 (−0.55) | −1.36 (−0.65) |
| τ = 100 d | −2.03 (−0.99) | −0.07 (−0.01) | −1.21 (−0.54) | −1.93 (−0.62) | −1.66 (−0.78) |
| largest 3-day recall loss first | −7.42 (−4.69) | −0.60 (−0.13) | −7.50 (−4.97) | −6.42 (−2.91) | −8.01 (−5.15) |
| lowest recall first (re-run) | −1.22 (−0.84) | −0.07 (0.00) | −1.44 (−0.75) | −1.28 (−0.67) | −1.52 (−0.94) |

Standard errors are 0.02–0.20. Neither proposal beats the current order anywhere. The recoverability index ties it
only after the holiday, where the limit binds briefly. The 3-day-loss order is the worst: it favours topics that
have only just come due, whose recall is falling fastest, and lets the oldest debt grow.

**The other knobs, at equal time** (re-run with the 2026-09-24 queue order; the earlier run agreed within
0.2 points, except the over-confident row, which sat up to 0.5 points lower then; see the caution below). They
were not re-run for the 2026-09-29 order: their world is the default load, where the limit never binds and the
two orders are equal within noise (above).

| choice | options, final knowledge (and year average) | verdict |
|---|---|---|
| Relearn step after Forgot | **1 day 94.9% (95.0)** · 2 days 95.0% (95.1) · FSRS's own post-lapse interval 95.0% (95.1) | within noise; keep 1 day, the sensible next-day check after restudying |
| First-study cap, honest ratings | **5 d 94.9% (95.0)** · 3 d 94.8% (95.0) · 7 d 94.9% (95.0) · none 94.9% (95.1) | no difference |
| First-study cap, 40% of first ratings a grade too high | **5 d 95.2% (95.3)** · 3 d 95.2% (95.4) · 7 d 95.2% (95.2) · none 95.2% (95.1) | the cap keeps 0.2 points over the year here (0.2–0.3 in the earlier run) and costs nothing when ratings are honest: a cheap guard; keep 5 d |
| Maximum interval, three years | **365 d 95.6% (95.2)** · 180 d 96.2% (95.2) · none 95.1% (95.2) | same average knowledge; no cap is slightly worse at the end; keep 365 d |

A caution for reading these: one seed's review count can differ from another's by ±8%, because the
per-user calibration amplifies early luck. That is why every workload-changing knob is compared at equal
time along its own retention sweep, and why differences under ~0.2 points are called noise.

**Re-run on 2026-09-28**, with the calibration that never lengthens intervals (§2.7) and the out-of-range
guard (every review budget fell inside its policy's sweep, so nothing above was extrapolated). Relearn:
1 d 95.2% (95.5) · 2 d 95.3% (95.5) · FSRS's interval 95.3% (95.5). First-study cap, honest: 5 d 95.2% (95.5)
· 3 d 95.1% (95.4) · 7 d 95.2% (95.4) · none 95.4% (95.4); over-confident: 5 d 95.2% (95.4) · 3 d 95.1% (95.3) ·
7 d 95.3% (95.3) · none 95.3% (95.3). Maximum interval over three years: 365 d 95.8% (95.5) · 180 d 96.4% (95.5)
· none 95.3% (95.4). Difficulty target ±0.03 (4 seeds): lower for harder topics +0.2 to +0.4 at the quiz and
+0.3 to +0.5 over the year, higher for harder topics −0.9 to −1.1. The same conclusions. One pattern worth a
proper test: the 180-day ceiling has come out ahead at the three-year quiz in both runs (+0.6, +0.7), with the
same average. Both runs used the same three seeds, so that is one observation, not two; more paired seeds
would settle whether it is real.

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
best alternative tested. One was not: the queue order, which changed twice (above). The one remaining
alternative with a measurable gain, the difficulty-adaptive target, comes with a trade-off the owner
should choose.

### 2.5 Two years to a residency exam

The owner's case, simulated directly (`tools/pilot/residency.py`, 6 seeds). A candidate studies 4 new topics
(chapters, lectures, question blocks) on 6 days a week for two years, about 2,500 topics, and sits the exam on
the last day. Every twin gets exactly the Yadora twin's review time, day by day. Re-run 2026-09-27 after the
equal-time fix (§2): a comparison twin's overspend now comes off its next day. Yadora's own numbers do not depend
on that and are unchanged; the other twins moved by a few tenths of a point. Re-run again on 2026-09-28 after
the calibration stopped lengthening intervals (§2.7): the Yadora twin now reviews 1–9% more (the slow forgetter
most) and knows 0.0–0.6 points more on exam day at the default target. Re-run on 2026-09-29 with the new queue
order (§2.4); the tables below are that run. At the playbook's settings Yadora's numbers moved by at most 0.1 point
(0.4 on the weakest tenth). Where a raised target overflows the daily limit they rose, by 0.3–0.5 points and 3.4–4.8
on the weakest tenth, because the new order spends the overflow better.

Exam day, a learner the defaults describe. The first number is the average recall over every topic studied in
the two years; the second is the share of topics at 90% or more; the third is the recall of the weakest tenth:

| twin | Yadora at the default 0.90 | + Review ahead in the last 4 weeks (up to 60 a day) |
|---|---|---|
| **Yadora** | **95.4% · 99.5% · 90.8%** | **96.6% · 100% · 92.7%** |
| reviews at random | 87.8% · 65% · 52.1% | 91.0% · 73% · 59.6% |
| reviews oldest first (the disciplined student) | 89.5% · 69% · 55.8% | 91.0% · 73% · 58.5% |
| fixed ladder: 1, 3, 7, 14, 30, 60, 120, 240, 365 days | 94.9% · 85% · 78.9% | 96.0% · 90% · 84.7% |
| plain FSRS-6, no product layer, at equal time | 95.1% · 99.3% · 90.7% | (no such feature) |

Yadora wins every seed against random and oldest-first review, in every world and every strategy tested: +3 to
+9 points on exam day. The gap is smallest for a learner who forgets more slowly than the defaults assume (+3 to
+6), whose unreviewed topics fade least. (Until 2026-09-27 this said +5 to +9, which the slow forgetter's rows
never supported.) With the final push Yadora reaches **100% of topics at 90%+**, where the other twins reach
72–73%.

Across learners, Yadora's exam-day average · share at 90%+ · weakest tenth, and its reviews a day:

| learner | default 0.90 | + final push | target 0.95 for the last 6 months | 0.95 for the last 6 months + push |
|---|---|---|---|---|
| as the defaults assume | 95.4 · 99.5% · 90.8 (28/day) | **96.6 · 100% · 92.7** (29/day) | 96.6 · 98.7% · 91.0 (30/day) | 96.9 · 99.9% · 92.0 (31/day) |
| forgets 2× faster | 95.2 · 96.5% · 90.1 (36/day) | **95.7 · 99.8% · 91.2** (36/day) | 95.7 · 94.1% · 86.6 (36/day) | 96.2 · 95.9% · 89.3 (36/day) |
| forgets 2× slower | 95.9 · 100% · 91.6 (23/day) | **97.4 · 100% · 93.9** (24/day) | 97.1 · 100% · 92.3 (27/day) | 97.7 · 100% · 94.2 (28/day) |
| 6 new topics a day | 95.1 · 96.5% · 89.8 (40/day) | **95.4 · 99.0% · 90.6** (40/day) | 95.2 · 92.7% · 85.1 (40/day) | 95.7 · 94.2% · 87.7 (40/day) |

**The exam playbook that follows:**

- Stay at the 0.90 default all the way.
- In the last four weeks, after each day's reviews, use **Review ahead**.

Raising the target to 0.95 for the last six months costs more reviews and buys no more on exam day than the
push: the same average or less, and a weaker tail. For a fast forgetter or a heavy load the extra reviews overflow
the daily limit, and the weakest tenth drops from about 90% to 85–87% (to 81% with the queue order before
2026-09-29, which spent the overflow worse). The Settings copy used to recommend raising the target, and now says
this instead.

**Against a fixed ladder or a plain FSRS app**, at equal time the averages are close. At the playbook's settings
(0.90, with or without the push) Yadora is ahead by 0.2–1.4 points against the ladder (3–6 seeds out of 6) and by
0.3–0.5 against plain FSRS-6 (5–6 of 6). With the target raised to 0.95 the ladder draws level only for the slow
forgetter (−0.1 points); elsewhere Yadora leads by 0.5–1.4. The bigger difference is the tail. The ladder leaves
its weakest tenth at 72–83% on exam day, against Yadora's 90–92%. That is why "no topic left behind" is a claim only a model-based schedule can make. Plain
FSRS-6 at equal time matches Yadora's tail; Yadora's lead over it is the per-user calibration (largest for learners
the defaults misjudge) and the final push, which a plain FSRS app has no feature for.

**Checked on the real code.** `TwoYearSoakTest` runs the same case through the app itself:

- **What it runs:** 730 days, 2,432 topics and 23,601 reviews, through the review screen's own commit path, with
  today's plan, the daily limit, the queue order, the calibration refresh, deferrals, a holiday and 40 Review ahead
  topics a day in the last four weeks.
- **Exam day:** 96.8% recall, with every topic at 90%+ and the weakest tenth at 93.0%. The twin who spent the same
  time on random reviews reaches 89.6%. (Before the calibration stopped lengthening intervals: 96.6%, 92.7% and
  22,804 reviews. The 2026-09-29 queue order left recall and the weakest tenth where they were, 96.9% and 93.1%; it
  changed which random draw each review gets, and the run did 1.4% more reviews, 24,673 against 24,335. Policy
  YADORA-7, 2026-10-04, which keeps a repair date inside the fuzz band, changed the draws again: 96.9% and 90.6%
  before it. So did YADORA-8, 2026-10-09, which keeps a fuzzed interval from falling under three days: 96.8%, 91.0%
  and 24,631 reviews before it.)
- **Every invariant held** on every day.
- **Independent replay:** the export replays 26,033 of 26,033 logs exactly in `analyze.py`. CI runs that replay on
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

### 2.7 Is anything left to improve? (2026-09-28)

The owner asked whether anything in the mathematics could still buy more retention for fewer reviews. Three
things were checked: what the field has published since FSRS-6, how far Yadora is from the best any scheduler
could do, and the two policy ideas not yet tested (`tools/pilot/experiments.py`, sections 6–8; 8 seeds a year
unless stated).

**The field.** FSRS-7 now exists. On the public benchmark (about 10,000 Anki collections, one review a day
counted), the recency-weighted FSRS-7 variant has log loss 0.3370 against FSRS-6's 0.3460,
RMSE(bins) 0.0593 against 0.0653; plain FSRS-7 is a separate row at log loss 0.3401. These
are flashcard prediction results, not evidence of higher exam scores or equal-time scheduling superiority.
It was built for fractional intervals and same-day reviews, has 34 parameters against 21, and py-fsrs, the
reference Yadora pins, has not released it. It is not adoptable under the rule that a model is taken only with a
pinned reference and goldens, and the headroom below shows how little a better model adds in simulation.
py-fsrs 6.3.2 released the same-day "Hard" stability floor that 6.3.1 lacked (see CLAUDE.md); it only touches
a topic reviewed twice on one calendar day and rated Hard. Intervals are whole days, so the daily plan offers a
topic again on a later day (the one exception: a review in the first hour of a daylight-saving fall-back day,
where a one-day interval comes due that evening; Iran has no DST). Since 2026-09-28 Review ahead leaves out
topics reviewed today too, so in practice only a deliberate second review from the Library reaches it (none in
the 23,601 reviews of `TwoYearSoakTest`; `OwnerYearSoakTest`, whose learner also takes same-day second looks, has 4
in 13,238). Yadora adopted 6.3.2 on 2026-10-09 (POLICY YADORA-9): a review stamped earlier replays 6.3.1's equation.

**Headroom.** An ORACLE twin schedules from the learner's true memory (true stability, true curve, true speed
of forgetting) under the same product rules and the same scheduling rule, one fixed target. No model can know
more, so its advantage at equal time is what a better memory MODEL (FSRS-7, a personal weight set) could add
under that rule, in these worlds. It is not a bound on every improvement: a different rule can add more (the
stability-adaptive target below does, while the limit has room), and the simulated learners' true memory is
itself FSRS-6-shaped, so a real learner whose memory the FSRS family describes poorly could leave more room than
this shows. The pilot's calibration checks (D2–D5) are what would reveal that.

| learner | Yadora: year-end quiz · year average · weakest tenth | oracle, same review time | what perfect knowledge adds |
|---|---|---|---|
| a learner the defaults describe | 95.0% · 95.1% · 89.9% | 95.1% · 95.3% · 90.4% | +0.1 · +0.3 · +0.5 |
| forgets 2× faster | 95.1% · 95.2% · 90.2% | 95.1% · 95.4% · 90.6% | +0.0 · +0.3 · +0.4 |
| forgets 2× slower | 94.7% · 95.2% · 89.3% | 95.0% · 95.3% · 90.1% | +0.3 · +0.1 · +0.8 |
| a first study worth half what the defaults say | 95.2% · 95.2% · 90.3% | 95.2% · 95.5% · 90.7% | +0.0 · +0.3 · +0.5 |
| a steeper forgetting curve (decay 0.30 against 0.15) | 95.4% · 95.5% · 90.0% | 95.5% · 95.7% · 90.8% | +0.1 · +0.2 · +0.8 |
| 30% of lapses rated Hard | 92.1% · 92.9% · 75.1% | 92.2% · 92.7% · 85.3% | +0.0 · −0.2 · **+10.3** |

(Measured with the calibration as it was before the change below; the conclusion does not depend on it.)

For every honest learner simulated, perfect knowledge of the learner's memory buys at most a quarter of a point
at the year-end quiz and 0.1–0.3 points over the year. The model side is at its ceiling in these worlds: the
default weights plus the one-number calibration already schedule almost as well as the truth. The exception is
inflated ratings. The average is unchanged, but the oracle's weakest tenth sits ten points higher, because it
knows which "Hard" answers were really lapses. That gap is missing information, not missing mathematics.

**Calibration when ratings are inflated.** A learner who calls some lapses "Hard" looks, to the calibration,
exactly like a slow forgetter, so it lengthens every interval on top of the stretched topics themselves. At the
learner's own 0.90 target:

| learner | before: may lengthen up to ×2 | **now: never lengthens** | calibration off | between: never past ×1.25 |
|---|---|---|---|---|
| honest, the defaults describe them | 95.0% · 89.9% · 5,321 | **95.3% · 90.7% · 5,499** | 95.2% · 90.5% · 5,347 | 95.1% · 90.1% · 5,414 |
| honest, forgets 2× faster | 95.1% · 90.2% · 7,123 | **95.1% · 90.2% · 7,137** | 94.3% · 89.2% · 6,499 | 95.0% · 90.2% · 7,050 |
| honest, forgets 2× slower | 94.7% · 89.3% · 4,070 | **95.8% · 91.6% · 4,705** | 95.8% · 91.6% · 4,710 | 95.2% · 90.4% · 4,304 |
| 30% of lapses rated Hard | 92.1% · 75.1% · 4,061 | **93.8% · 79.9% · 4,774** | 93.9% · 80.7% · 4,782 | 93.0% · 77.8% · 4,344 |
| 60% of lapses rated Hard | 87.9% · 59.0% · 2,954 | **92.0% · 67.5% · 4,126** | 92.0% · 67.7% · 4,105 | 90.8% · 64.1% · 3,702 |
| 30% rated Hard and forgets 2× faster | 92.4% · 74.1% · 5,587 | **92.7% · 75.6% · 5,745** | 92.7% · 75.8% · 5,640 | 92.2% · 73.5% · 5,634 |

Each cell: year-end quiz · weakest tenth · reviews in the year. A cap at ×1.5 and a prior three times stronger
against lengthening sit between the first two columns.

The calibration caused most of what inflated ratings cost: 1.8 of the 2.9 points lost at 30% inflation, and
4.1 of 7.2 at 60%, with the weakest tenth falling to 75% and 59%. A calibration that may shorten intervals
but never lengthens them keeps what honest learners need (the fast forgetter's correction intact), recovers
nearly all of that loss, and even helps an honest average learner (+0.3 points: the estimate's upward noise no
longer stretches anything). Its cost falls on the honest slow forgetter: about 16% more reviews than strictly
needed, for 1.1 points more knowledge (true recall at review 91.8% where 90% was asked). Under-reviewing is what
fails an exam; over-reviewing costs time. **This changed the app (2026-09-28, the owner's decision):**
`RecallCalibration` now never lengthens intervals. The raw estimate is still computed and reported by
`analyze.py`, so the pilot still sees who forgets slower.

**A stability-adaptive target (evaluated, not adopted).** Cost-optimal scheduling (SSP-MMC) varies the target
by the topic's state. The difficulty version was tested in §2.4. A stability version was new: target =
0.90 + slope × clamp(log₁₀(S / 20 d) / 1.5, −1, 1), so a young topic may drift a little lower before review (a
review at lower recall buys more stability) and a mature one is held a little higher (a lapse there throws away
months). At equal time, paired seed by seed against the flat target (points, mean ± standard error; 16 seeds for
the first world, 8 for the next four, 6 for three years). The first five rows were measured before the
calibration change above; the heavy-load row was re-measured after it (2026-09-28, with the out-of-range guard:
every seed inside its sweep). The change made the heavy-load cost larger (before it: −1.63 / −3.41 / −6.17):

| learner | slope | year-end quiz | year average | weakest tenth |
|---|---|---|---|---|
| the defaults describe them | +0.02 / +0.03 / +0.05 | +0.17 / +0.38 / +0.70 (±0.04–0.07) | +0.08 / +0.06 / +0.21 | +0.15 / +0.39 / +0.46 |
| forgets 2× slower | +0.02 / +0.03 / +0.05 | +0.23 / +0.41 / +0.83 | +0.07 / +0.14 / +0.21 | +0.51 / +0.63 / +0.95 |
| forgets 2× faster | +0.02 / +0.03 / +0.05 | +0.39 / +0.41 / +0.76 | +0.23 / +0.16 / +0.28 | +0.41 / +0.19 / −0.35 |
| 30% of lapses rated Hard | +0.02 / +0.03 / +0.05 | +0.26 / +0.53 / +0.60 | −0.07 / +0.05 / +0.05 | +0.56 / +0.84 / +0.02 (±0.3–0.7) |
| three years | +0.02 / +0.03 / +0.05 | +0.13 / +0.36 / +0.53 | +0.25 / +0.35 / +0.34 | +0.22 / +0.49 / +0.38 |
| **heavy load: 6 new a day, limit 30** | +0.02 / +0.03 / +0.05 | −0.04 / +0.12 / −0.10 | +0.17 / +0.24 / +0.21 | **−3.88 / −3.66 / −7.56** (±0.7–0.9) |
| heavy load, with the 2026-09-29 queue order | +0.02 / +0.03 / +0.05 | +0.08 / +0.15 / +0.32 (±0.07–0.08) | +0.09 / +0.18 / +0.26 | **−1.46 / −2.38 / −3.26** (±0.3–0.5) |

When the daily limit has room it is a real gain, 0.4–0.8 points at the quiz without hurting the tail. When the
limit binds, which is the medical student's normal case, the extra reviews of mature topics take the slots the
weakest topics need, and the weakest tenth pays 3.7–7.6 points. The 2026-09-29 queue order (§2.4) gives those
slots back to the topics a review helps most, and re-measured with it on the same seeds the cost falls to 1.5–3.3
points while the quiz turns slightly positive (+0.1 to +0.3). The other rows were not re-run: their limit has room,
so the order hardly matters there. Like the difficulty target, it is still a trade-off rather than a free gain, so
the target stays flat; the owner can weigh it again, ideally with pilot data.

**The guarantee.** No schedule can promise that a student scores higher than others. Others may study more,
start ahead, or use better review methods, and one exam samples only some topics. Even the ideal case, an
identical twin with identical study time, is not certain: with the one-year results (Yadora 95.3% against
88.4% for random review and 90.1% for the disciplined oldest-first student), and exam questions drawn from the
studied topics, the Yadora twin scores strictly higher than the disciplined twin on about 79% of 50-question exams,
90% of 100-question exams and 97% of 200-question exams, ties on 10%, 4% and 1%, and scores lower on the rest
(every answer assumed independent). These figures used to count a tie as half a win (84%, 92%, 98%; an outside
audit, 2026-09-30). How answers move together changes them in both directions: the two students facing the same
questions narrows the gap's spread and makes the order more certain, while questions clustered on a few topics, which
a student knows or does not as a block, widen it and make it less certain. What can be said honestly is the twin result itself, stated as a simulation, until the
pilot measures real learners.

**What moves real outcomes more than any parameter left.** Honest "Forgot" ratings (above). Doing the reviews
at all. Reviewing by retrieval (questions, explaining from memory) rather than rereading alone: in the
literature the testing effect is worth about half a standard deviation over restudy (Rowland 2014, g = 0.50;
Adesope et al. 2017, g = 0.51 against restudying), far more than any interval rule left to tune. The Settings guide already says so; the pilot
records the method (D7) so Yadora's own data can confirm it.

### 2.8 One exam, a fixed daily budget (2026-10-03)

The owner's year, as they described it: one residency exam in about a year, at least seven hours of study a day,
about 2,000 topics by the end, new topics on some days and none on others. §2 to §2.7 give every learner a fixed
number of new topics a day and ask whether scheduling beats no schedule at equal time. A student with one exam asks
something else: with this much time a day and this syllabus, how should the day be split between new material and
reviews, which target leaves the most on exam day, and what is a spare evening worth?

`tools/pilot/one_exam.py` simulates exactly that (3 seeds a cell; with no budget it reproduces §2's Yadora twin,
95.4% against 95.3%, 6.0 reviews a topic). Each study day (6 of 7) has a budget of review units: a remembered review
costs 1, a forgotten one 1.5, and first studying a topic and rating it costs 3. The day runs as the app runs it: due
reviews by the queue's own score, then new topics with what is left (up to 10 a day) until the syllabus has been
studied once, then Review ahead with any time left (on Today since 2026-10-09 the "Next up · weakest first"
list). The exam score is the average recall over the WHOLE syllabus, a topic never studied counting 0: what a
perfectly calibrated candidate earns on a four-option exam with negative marking. True memory is FSRS-6 and ratings are honest.

A syllabus of 1,000 topics, a learner the defaults describe. Exam score at targets 0.85 / 0.90 / 0.95 (every topic
studied at least once, unless a share is given):

| review units a day | reviews first, new topics with what is left | reviews take at most 60% of the day until the syllabus is covered | at most 40% |
|---|---|---|---|
| 20 | 69.8 / 60.2 / 44.7 (studied 75 / 63 / 46%) | 79.9 / 77.1 / 74.6 (89 / 85 / 82%) | 89.2 / 89.1 / 88.1 |
| 30 | 96.2 / 92.9 / 66.1 (100 / 98 / 68%) | 96.7 / 96.1 / 96.8 | 95.9 / 95.5 / 96.4 |
| 45 | 98.6 / 98.7 / 98.0 | 98.7 / 98.6 / 98.5 | 98.5 / 98.5 / 98.4 |

- **When time is tight, the split decides, not the target.** With reviews first, every review the schedule asks for
  takes time from material not yet studied once, and a higher target asks for more of them: at 20 units a day, 0.95
  leaves more than half the syllabus never studied. Protecting time for new material until the syllabus is covered
  is worth 20–45 points there, and the target then moves the score by about one point. With time to spare (45 units)
  nothing matters much. In the app the split is the daily limit, and doing the day's new material before "Review more
  anyway". The price of protecting new material is the tail: at 20 units and 40%, the weakest tenth sits at 57–68%,
  against 86–95% for the topics a reviews-first learner covered. (The weakest tenth counts studied topics only, so a
  high one can sit beside half a syllabus never studied; the exam score counts every topic.)
- **The same held** for a learner who forgets 1.7× faster (at 30 units: 89.7 / 81.2 / 59.4 reviews first, 92.8–94.6
  with the share protected), for a first study costing 5 units, and for 1,600 topics, where 30 units a day cannot
  cover the syllabus with reviews first (58% at 0.90) and covers it with 40% protected (86.5%), and 45 units reaches
  95% only when new material is protected or the target is 0.85.
- **Spare time is worth more on Review ahead than on a higher target.** At 45 units a day and 0.90, the time left
  after the day's reviews brings 98.7% on exam day when it goes to Review ahead and 95.0% when it is left unused; a
  0.95 target without Review ahead reaches 97.6%. At 70 units: 99.3% against 95.1% and 97.7%. So Review ahead pays
  whenever time is left, not only in the last four weeks (§2.5 tested only those). Today's caption says so since
  2026-10-03. Compared with leaving the time unused or raising the target only: in this model new material stops at
  10 topics a day, so it was never weighed against more new material, a block of new questions or fixing a concept
  (an outside audit, 2026-10-04, rightly said so).
- **Switching the target in phases** (0.85 or 0.90 until the syllabus is covered, then 0.95 or 0.97) never beat the
  best flat target by more than 0.1 point, and 0.90 then 0.97 cost about 3 points on the weakest tenth. That is about
  the retention TARGET only; it says nothing about phases of study (a QB pass, a base-book pass, rapid review), which
  the model does not contain.
- **Feasibility is the real risk.** If a review of one chapter-sized topic takes 10–15 minutes, seven hours is about
  28–42 units. Around 30 is the edge for 1,000 topics. For 1,600, 30 units covers the syllabus only with new material
  protected (86.5% on exam day), and 45 units reaches 95–96%. For **2,000 topics**, the owner's own target (run
  2026-10-04, 3 seeds a cell; at most 10 new topics a day, so covering them takes about eight months anyway):

  | review units a day | reviews first: 0.85 / 0.90 / 0.95 | reviews at most 40% of the day until covered |
  |---|---|---|
  | 30 | 52.4 / 46.5 / 33.1 (studied 57 / 49 / 34%) | 72.8 / 72.6 / 72.1 (93% studied) |
  | 45 | 79.9 / 70.5 / 50.1 (86 / 74 / 51%) | 91.7 / 92.1 / 91.9 |
  | 70 | 97.6 / 97.5 / 77.2 | 97.6 / 97.6 / 97.5 |

  Thirty units a day cannot cover 2,000 topics, 45 does only with new material protected, and 70 does with new
  material protected at any target, or reviews first at 0.85–0.90; at 0.95 with reviews first a fifth of the syllabus
  is never studied (77.2%), so even with 70 units a higher target is not free. Seven hours is 70 units only if a review averages about six minutes (a skim, a block of questions, a
  rapid-review page, not a reread of the chapter), and the model charges a first study only three reviews' time, less
  than a first reading of a chapter takes, so a real budget is tighter still. Time per review is the number that
  decides the year, and the app does not measure it (by the owner's choice). Only the logs can show which side of
  the edge a learner is on, so the app records the day's load since 2026-10-03 (export v15, DAILY_SNAPSHOT;
  `analyze.py` reports whether a backlog grows month after month).
- **How many passes the default gives.** A topic studied now and reviewed on schedule until an exam 365 days later
  gets 9.3 passes at 0.90, the first study included (7.3 at 0.85, 11.1 at 0.93, 15.1 at 0.95), and is recalled at
  94.8% on exam day (0.95: 97.6%). A plan of "about ten passes per subject" is what the default already does for the
  first topics; the last ones get fewer.

Not tested: exam-aware skipping or ordering, subjects that weigh more on the exam, topics with different review
costs, whole topics forgetting differently from flashcards (§2.6), and inflated ratings in this setting. The exam
date still feeds nothing: none of this needs it.

## 3. What changed in the app because of this research

1. **Review ahead.** Weakest first, not yet due, on Today when the day is done (§2.1). The exam-countdown
   copy in Settings now points to it for the final weeks, and since 2026-10-03 (§2.8) Today's caption and the
   countdown copy call it a good use of any spare time.
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
5. **Queue order: Important, lateness, and a capped review value** (§2.4). The bonuses for weak states and
   past lapses are gone: every simulated backlog knew more without them. Since 2026-09-29, among topics equally
   late, the one a review would strengthen most comes first. Under a binding limit that knows more and lifts the
   weakest tenth. The value is capped, so no topic is held back more than 40 days behind its turn.
6. **The calibration never lengthens intervals** (§2.7, 2026-09-28). Generous ratings look exactly like
   slower forgetting, and stretching every interval for them was most of what inflated ratings cost. It still
   shortens intervals for a learner who forgets faster.

## 4. Considered and not changed

- **Default weights and the 5-day first-study cap**: the pilot decides (D3, D5). The on-device personal
  model keeps its strict held-out gate.
- **Expanding vs uniform spacing, and within-day interleaving**: the evidence says schedule shape matters
  little, and interleaving helps with discriminating similar categories, not with ordering whole-topic
  reviews. (Which due topics come first under a backlog is a different question; §2.4 tested it and
  changed it.) A 2026-09-28 proposal to rotate the day's topics by subject (cardiology, nephrology,
  pharmacology, ...) would mix the least similar topics, where the interleaving meta-analysis finds the benefit
  smallest or reversed (Brunmair & Richter 2019). A second assistant proposed the opposite, grouping confusable
  topics. Neither fits whole topics reviewed by any method, mostly outside the app.
- **Using the question score or the review method in scheduling**: not until the pilot shows how the score
  relates to self-ratings (D6) and whether the method matters (D7). PILOT.md now records the form either change
  should take if the data calls for one: a suggestion, never an override, after a multiple-choice chance
  correction; a multiplier on the stability gain, fitted with the weights frozen.
- **Reminder times by chronotype, or hard topics just before sleep**: the evidence is modest and partly
  conflicting, and the learner already chooses both reminder times.
- **Exam-date interval compression**: a settled decision. Review ahead gives the learner the same power
  without the exam date touching the schedule.
- **A randomised in-app control arm** (withholding reviews from some topics to measure the effect): it
  would give the participant exactly the downside the twin standard forbids. The pilot measures the model
  instead. Simulation on a fitted model (§5) answers only a conditional model question; it cannot settle
  the real causal twin question. An optional external controlled assessment is a separate research decision,
  not an in-app control arm or a change to the current study flow.
- **FSRS-7**: better predictions on the flashcard benchmark, but no pinned py-fsrs release, and §2.7's oracle
  shows a better model adds only a few tenths of a point under the same rule, in simulated learners whose memory
  is FSRS-6-shaped. Revisit when a reference exists and pilot data say the default curve misfits topics.
- **State-dependent targets** (by difficulty, §2.4; by stability, §2.7): each buys a few tenths of a point
  and makes the weakest topics pay, the stability version badly when the daily limit binds.
- **Guaranteeing a score**: impossible for any scheduler (§2.7), and ruled out by MARKETING.md.

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
`python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json`. That gives the twin result for
learners whose memory those weights describe, rather than for an average Anki user. It is still a simulation: the fit
learns from the learners' own ratings, so it describes how they rate as much as how they remember (an outside audit,
2026-10-04), and only a study with a comparison (§5.1) measures the twin question itself.

### 5.1 The study that would test the goal (after the pilot; not decided)

The pilot measures the model; it cannot show that Yadora raises exam scores at equal study time, because it has no
comparison. The study that could is a later decision. Its main points, worked out on 2026-10-03 when outside
researchers proposed designs (their sample sizes disagreed, from 30 to 120 students, and none checked what effect to
expect):

- **Randomise topics within each student.** Each student's topics are split at random between Yadora's schedule and
  the comparison, so ability, motivation and hours cancel. Splitting students instead needs several times more of
  them. Every arm reviews (nothing is withheld), the app times nothing, and "equal study" is held as an equal number
  of reviews per topic, counted from the logs. An equal count is not equal time: a review after a lapse, a reread and
  a skim cost different minutes, so the study should also record the time each arm took where it can, and report the
  difference instead of assuming none (an outside audit, 2026-10-04).
- **The comparison decides whether the study is feasible.** Against a fixed ladder (§2.5 tested 1, 3, 7, 14, 30, 60,
  120, 240 and 365 days), the averages came within about 1 point (+0.2 to +1.4); the gain is in the weakest tenth of
  topics (72–83% against 90–92%). Against the student's own unscheduled review, §2 expects 5 to 8 points. A study powered for 5 points will
  almost surely find nothing against a ladder. That is not evidence against Yadora: the expected gain there is
  below what the study can see.
- **How many students** (accuracy about 80%, topics differing by about 15 points, one paired difference per student,
  80% power, two-sided 5%; normal approximation):

  | topics per arm × questions per topic | 1 point | 3 points | 5 points | 8 points |
  |---|---|---|---|---|
  | 20 × 2 | 876 | 98 | 36 | 14 |
  | 40 × 2 | 473 | 53 | 19 | 8 |
  | 40 × 4 | 316 | 36 | 13 | 5 |
  | 80 × 2 | 272 | 31 | 11 | 5 |

  More topics per student buys more than more students. A 5-point difference is within reach of about 20 students
  with 40 topics in each arm. One point, the expected average gain over a fixed ladder, would take a very large study (about 270
  students with 80 topics in each arm).
- **The outcome:** questions on randomly sampled topics at one fixed date, written by someone who does not know the
  arms, analysed as one difference per student or by a mixed-effects logistic model with students and topics as
  random effects. Threats: learning one topic helps a related topic in the other arm (randomise related topics
  together), reviews done outside the schedule (visible in the logs), and unequal effort.

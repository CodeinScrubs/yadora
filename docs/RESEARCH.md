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
| The shape of the spacing matters little | Same meta-analysis: expanding vs uniform schedules g = 0.034, not significant | Getting reviews to happen, and not too early or too late, matters more than the exact ladder. The evidence gives no reason to hand-tune the shape, so Yadora does not; FSRS's intervals come from a fitted model instead. (Absence of a significant difference is not proof of none; §2.4 and §2.7 test the remaining knobs in simulation.) |
| The best gap grows with how long you need to remember | 1,350+ learners, final tests up to a year later: the optimal gap falls from 20–40% of a one-week retention interval to 5–10% of a one-year one ([Cepeda et al. 2008](https://journals.sagepub.com/doi/10.1111/j.1467-9280.2008.02209.x)) | A schedule should stretch as memory stabilises. FSRS does this per topic. |
| Retrieval practice raises classroom achievement | 222 classroom studies, 48,478 students: g = 0.499 ([Yang et al. 2021](https://pubmed.ncbi.nlm.nih.gov/33683913/)); practice testing and distributed practice rated "high utility", rereading and highlighting "low" ([Dunlosky et al. 2013](https://journals.sagepub.com/doi/abs/10.1177/1529100612453266)) | Yadora schedules when, not how. The Settings guide already says questions usually stick better than rereading. The pilot now records the method, so this can be checked on Yadora's own users (§5, D7). |
| Returns diminish after a few spaced relearning sessions | Successive relearning: little extra long-term benefit beyond 3–5 widely spaced sessions ([Rawson & Dunlosky 2022](https://journals.sagepub.com/doi/full/10.1177/09637214221100484)) | Matches FSRS's growing intervals and the 365-day cap. No change. |
| Personalised spacing beats one-size-fits-all spacing | Semester-long classroom study: personalised review +16.5% retention over massed study and +10.0% over a fixed spaced schedule, a month after the course ([Lindsey et al. 2014](https://journals.sagepub.com/doi/abs/10.1177/0956797613504302)); optimal-control scheduling on Duolingo data ([Tabibian et al. 2019](https://www.pnas.org/doi/10.1073/pnas.1815156116)); SSP-MMC on 220M MaiMemo logs ([Ye et al. 2022](https://dl.acm.org/doi/10.1145/3534678.3539081)) | Supports per-user calibration and the personal weight set. |
| Spaced education works in medicine and lasts | Randomised trials: spaced e-mailed questions improved end-of-year scores ([Kerfoot 2007](https://pubmed.ncbi.nlm.nih.gov/17209889/)); across 724 urology residents, ~4× learning efficiency; benefits persisted 2 years ([Kerfoot 2009](https://www.auajournals.org/doi/10.1016/j.juro.2009.02.024)) | Direct evidence in the target population. |
| Spaced-repetition use tracks exam scores, but causality is unproven | Systematic review, 11 studies, 1,135 students: consistent Step 1 associations, dose-dependent; none randomised, strong self-selection ([2026 review](https://pmc.ncbi.nlm.nih.gov/articles/PMC13197492/)); ~1 Step 1 point per 1,700 cards ([Deng 2015](https://ijms.info/IJMS/article/view/1549)) | Encouraging, not proof. It is also why the twin claim cannot be settled by comparing friends with classmates. |
| Unrehearsed medical knowledge fades, but not to nothing | Modal retention after a year is ~⅔–¾ for unrehearsed basic science ([Custers 2010 review](https://hopkins-stile.med.jhmi.edu/media/Custers.pdf)) | This is the baseline Yadora has to beat: the no-schedule twin still keeps most of it. |
| A judgement made right after studying is unreliable; a delayed one is far better | Immediate judgements of learning predict recall poorly; judgements made after a delay, from a retrieval attempt, rank which items will be recalled far more accurately (relative accuracy, on word pairs: it says which items are known, not that the absolute level is right) ([Nelson & Dunlosky 1991](https://journals.sagepub.com/doi/10.1111/j.1467-9280.1991.tb00147.x)) | Supports the first-study cap (the first rating is an immediate judgement), and the review question "how much did you still remember **before** rereading?", which asks for the delayed, retrieval-based judgement. It is answered afterwards, though, so it is not the same measurement: hindsight (next row) can still inflate it, which is why the pilot checks ratings against question scores (D6). |
| Knowing the answer inflates what you think you knew | Hindsight bias reaches metamemory: after seeing the answer, people overestimate their earlier confidence ([Memory 2021](https://www.tandfonline.com/doi/abs/10.1080/09658211.2021.1919144)) | **The largest threat to the ratings.** A learner who rates after rereading drifts toward Hard/Good on a topic they had lost. The pilot measures this against question scores (D6); §2 shows the cost. |
| FSRS-6 is the most accurate transparent model with a released reference implementation | Public benchmark, ~10k Anki users: FSRS-6 log loss 0.346, RMSE(bins) 0.065, AUC 0.703 vs FSRS-5 0.356 / 0.074 / 0.701 ([srs-benchmark](https://github.com/open-spaced-repetition/srs-benchmark)); per-user optimisation beats the defaults for ~84% of users ([benchmark write-up](https://expertium.github.io/Benchmark.html)). FSRS-7 now predicts better on the same benchmark (0.337 / 0.059) but has no py-fsrs release (§2.7). All of it is flashcard data, not whole topics | FSRS-6 stays the live model. Personalisation (calibration, then the personal set) is worth it. |
| The best retention target trades workload against knowledge | FSRS computes the optimum by minimising workload per unit of knowledge in simulation ([FSRS wiki](https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-optimal-retention)) | Yadora's own sweep (§2.3) confirms 0.90 as the default. |

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

| scenario | Yadora twin | other twin | gain | Yadora wins | average over the year (Yadora / other) |
|---|---|---|---|---|---|
| other twin reviews at random | 95.3% | 88.4% | **+6.9** | 8/8 | 95.6% / 89.7% |
| other twin reviews what was studied recently | 95.3% | 90.2% | **+5.1** | 8/8 | 95.6% / 90.8% |
| other twin cycles, oldest first (the disciplined no-app student) | 95.3% | 90.1% | **+5.2** | 8/8 | 95.6% / 91.5% |
| learner forgets 2× faster than the model assumes | 95.1% | 87.6% | **+7.5** | 8/8 | 95.3% / 88.4% |
| learner forgets 2× slower | 95.8% | 90.2% | **+5.6** | 8/8 | 96.1% / 91.6% |
| 30% of forgotten reviews rated Hard (inflated ratings) | 93.8% | 87.1% | **+6.7** | 8/8 | 94.3% / 88.5% |
| skips 30% of days | 95.3% | 88.8% | **+6.5** | 8/8 | 95.5% / 89.9% |
| 3-week holiday mid-year | 95.3% | 88.7% | **+6.6** | 8/8 | 95.4% / 89.7% |
| all at once: forgets 2× faster, 30% inflated, skips 30%, disciplined other twin | 92.6% | 86.5% | **+6.0** | 8/8 | 93.0% / 88.1% |
| heavy load: 6 new topics a day, daily limit 30 | 94.0% | 85.8% | **+8.1** | 8/8 | 95.2% / 89.1% |

Put as forgetting, the Yadora twin forgets 4.7% of the year's topics. The random-review twin forgets
11.6% and the disciplined cycler 9.9%. That is **about half the forgetting for the same hours**.

### 2.1 The one way the other twin can win, and what closed it

If the other twin saves **all** review time for a four-week cram right before an **announced** exam, they
score higher on that day: 98.2% vs 95.3%, or 96.8% when only half is saved. But the cram needs **198**
(or 99) topic reviews a day for four weeks, which is not humanly possible for real medical topics. And
the crammer's knowledge averages **66.6%** over the year (84.0% for half), against Yadora's 95.6%. In
clinical terms, the crammer knows it on exam day and not the rest of the year.

The realistic version gives both twins the same daily hours all year and the same final push: 30 topics
a day for the last four weeks. The Yadora twin spends its push on the topics predicted weakest; the other
twin reviews everything oldest-first. **Yadora wins again: 96.5% vs 91.5%, 8/8** (91.6% if the other
twin reviewed at random before the push).

Until now, reviewing ahead in Yadora meant opening topics one at a time from the Library. So the app now
has **Review ahead** (Today, once the day's reviews are done). It offers rated topics that are not yet
due, weakest predicted recall first, 20 per session (`ui/today/ReviewAhead`). It reads no exam date and
compresses no interval: each review is an ordinary early review that FSRS scores honestly. The settled
decision that the exam date feeds nothing stands.

### 2.2 What inflated ratings cost

Rating 30% of forgotten reviews as Hard still leaves the Yadora twin ahead of the other twin at equal
time. But it costs the learner 1.5 points of absolute knowledge (95.3 → 93.8%), because the scheduler
stretches intervals on topics that had actually been lost. At 60% the cost is 3.5 points. Until 2026-09-28
it was 2.6 and 7.3: the calibration read the generous ratings as slower forgetting and stretched every
interval on top; it no longer lengthens intervals (§2.7). **Honest "Forgot" ratings are still worth more than
any parameter in this document.** The review screen already asks what the
learner had *before* rereading, and each button says what it means. The pilot now checks ratings against
question scores (D6).

### 2.3 The retention target

Same twins, the Yadora twin at different targets, the other twin given the same time each time:

| target | Yadora twin | other twin | gain | reviews per topic per year |
|---|---|---|---|---|
| 0.80 | 89.9% | 83.8% | +6.1 | 3.7 |
| 0.85 | 92.8% | 86.1% | +6.7 | 4.5 |
| **0.90** | **95.3%** | 88.4% | **+6.9** | 5.9 |
| 0.93 | 96.7% | 90.5% | +6.3 | 7.6 |
| 0.95 | 97.7% | 92.3% | +5.4 | 9.8 |
| 0.97 | 98.6% | 94.7% | +3.8 | 14.4 |

The advantage of scheduling at equal time is largest at 0.85–0.90 (a tie within noise). 0.90 stays the
default: it knows 2.5 points more than 0.85 for 1.3× the reviews. Higher targets buy absolute knowledge at a
steep price: 0.95 gives +2.4 points for 1.7× the reviews, 0.97 gives +3.3 points for 2.4×. That matches the
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

**Backlog-triage proposals (2026-09-28).** Two outside reports called ranking by lateness a "FIFO trap" and
proposed two alternatives. One ranks by the recall deficit times a recoverability factor, (R* − R)+ × (1 − e^(−S/τ)).
The other ranks by the recall that three more days of waiting would cost (`experiments.py`, `YADORA_EXTRA_ORDERS`).
Their premise does not hold on FSRS-6: a topic 30 days overdue still sits at 59–88% predicted recall, not near 5%,
and the one-day drop in recall is largest right after a review, not near R 0.75–0.85. The table uses the same five
backlogs and 16 paired seeds, with the current calibration. It gives the points against Yadora's order at the
quiz, with the year average in brackets:

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
best alternative tested. One was not: the queue order, which changed (above). The one remaining
alternative with a measurable gain, the difficulty-adaptive target, comes with a trade-off the owner
should choose.

### 2.5 Two years to a residency exam

The owner's case, simulated directly (`tools/pilot/residency.py`, 6 seeds). A candidate studies 4 new topics
(chapters, lectures, question blocks) on 6 days a week for two years, about 2,500 topics, and sits the exam on
the last day. Every twin gets exactly the Yadora twin's review time, day by day. Re-run 2026-09-27 after the
equal-time fix (§2): a comparison twin's overspend now comes off its next day. Yadora's own numbers do not depend
on that and are unchanged; the other twins moved by a few tenths of a point. Re-run again on 2026-09-28 after
the calibration stopped lengthening intervals (§2.7): the Yadora twin now reviews 1–9% more (the slow forgetter
most) and knows 0.0–0.6 points more on exam day at the default target; the tables below are that run.

Exam day, a learner the defaults describe. The first number is the average recall over every topic studied in
the two years; the second is the share of topics at 90% or more; the third is the recall of the weakest tenth:

| twin | Yadora at the default 0.90 | + Review ahead in the last 4 weeks (up to 60 a day) |
|---|---|---|
| **Yadora** | **95.4% · 99.6% · 90.8%** | **96.5% · 100% · 92.6%** |
| reviews at random | 87.9% · 66% · 52.0% | 91.0% · 73% · 59.3% |
| reviews oldest first (the disciplined student) | 89.5% · 70% · 55.6% | 91.0% · 74% · 58.3% |
| fixed ladder: 1, 3, 7, 14, 30, 60, 120, 240, 365 days | 95.2% · 87% · 81.1% | 96.2% · 91% · 86.0% |
| plain FSRS-6, no product layer, at equal time | 95.2% · 99.6% · 90.9% | (no such feature) |

Yadora wins every seed against random and oldest-first review, in every world and every strategy tested: +3 to
+9 points on exam day. The gap is smallest for a learner who forgets more slowly than the defaults assume (+3 to
+6), whose unreviewed topics fade least. (Until 2026-09-27 this said +5 to +9, which the slow forgetter's rows
never supported.) With the final push Yadora reaches **100% of topics at 90%+**, where the other twins reach
73–74%.

Across learners, Yadora's exam-day average · share at 90%+ · weakest tenth, and its reviews a day:

| learner | default 0.90 | + final push | target 0.95 for the last 6 months | 0.95 for the last 6 months + push |
|---|---|---|---|---|
| as the defaults assume | 95.4 · 99.6% · 90.8 (28/day) | **96.5 · 100% · 92.6** (29/day) | 96.5 · 98.5% · 90.2 (31/day) | 96.9 · 99.9% · 91.9 (31/day) |
| forgets 2× faster | 95.1 · 96% · 89.9 (36/day) | **95.6 · 99.7% · 91.1** (36/day) | 95.2 · 93% · 81.8 (36/day) | 95.8 · 94% · 86.3 (37/day) |
| forgets 2× slower | 95.9 · 100% · 91.6 (23/day) | **97.4 · 100% · 93.9** (24/day) | 97.1 · 99.9% · 92.1 (27/day) | 97.6 · 100% · 93.9 (28/day) |
| 6 new topics a day | 95.1 · 97% · 89.4 (40/day) | **95.3 · 98.7% · 90.5** (40/day) | 94.9 · 92% · 81.2 (40/day) | 95.5 · 93% · 85.5 (40/day) |

**The exam playbook that follows:**

- Stay at the 0.90 default all the way.
- In the last four weeks, after each day's reviews, use **Review ahead**.

Raising the target to 0.95 for the last six months costs more reviews and buys less on exam day. For a fast
forgetter or a heavy load it is worse than doing nothing: the extra reviews overflow the daily limit, and the
weakest tenth drops from about 90% to 81%. The Settings copy used to recommend raising the target, and now says
this instead.

**Against a fixed ladder or a plain FSRS app**, at equal time the averages are close. At the playbook's settings
(0.90, with or without the push) Yadora is ahead by 0.1–1.5 points against the ladder (3–6 seeds out of 6) and by
0.2–0.4 against plain FSRS-6 (5–6 of 6). With the target raised to 0.95 the ladder draws level in three of the four
worlds (−0.1 to +0.3 points), and stays 1.2 behind only under the heavy load. The difference is the tail. The
ladder leaves its weakest tenth at 72–83% on exam day, against Yadora's 89–92%. That is why "no topic left behind" is a claim only a model-based schedule can make. Plain
FSRS-6 at equal time matches Yadora's tail; Yadora's lead over it is the per-user calibration (largest for learners
the defaults misjudge) and the final push, which a plain FSRS app has no feature for.

**Checked on the real code.** `TwoYearSoakTest` runs the same case through the app itself:

- **What it runs:** 730 days, 2,432 topics and 24,335 reviews, through the review screen's own commit path, with
  today's plan, the daily limit, the queue order, the calibration refresh, deferrals, a holiday and 40 Review ahead
  topics a day in the last four weeks.
- **Exam day:** 96.9% recall, with every topic at 90%+ and the weakest tenth at 93.1%. The twin who spent the same
  time on random reviews reaches 90.5%. (Before the calibration stopped lengthening intervals: 96.6%, 92.7% and
  22,804 reviews.)
- **Every invariant held** on every day.
- **Independent replay:** the export replays 26,767 of 26,767 logs exactly in `analyze.py`. CI runs that replay on
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
counted) it predicts better than FSRS-6: log loss 0.3370 against 0.3460, RMSE(bins) 0.0593 against 0.0653.
It was built for fractional intervals and same-day reviews, has 34 parameters against 21, and py-fsrs, the
reference Yadora pins, has not released it. It is not adoptable under the rule that a model is taken only with a
pinned reference and goldens, and the headroom below shows how little a better model adds in simulation.
py-fsrs 6.3.2 released the same-day "Hard" stability floor that 6.3.1 lacked (see CLAUDE.md); it only touches
a topic reviewed twice on one calendar day and rated Hard. Intervals are whole days, so the daily plan offers a
topic again on a later day (the one exception: a review in the first hour of a daylight-saving fall-back day,
where a one-day interval comes due that evening; Iran has no DST). Since 2026-09-28 Review ahead leaves out
topics reviewed today too, so in practice only a deliberate second review from the Library reaches it (none in
the 24,335 reviews of `TwoYearSoakTest`). The pin stays at 6.3.1.

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

When the daily limit has room it is a real gain, 0.4–0.8 points at the quiz without hurting the tail. When the
limit binds, which is the medical student's normal case, the extra reviews of mature topics take the slots the
weakest topics need, and the weakest tenth pays 3.7–7.6 points. Like the difficulty target, that is a trade-off
rather than a free gain, so the target stays flat; a version that knows about the day's load would be new work,
best designed against pilot data.

**The guarantee.** No schedule can promise that a student scores higher than others. Others may study more,
start ahead, or use better review methods, and one exam samples only some topics. Even the ideal case, an
identical twin with identical study time, is not certain: with the one-year results (Yadora 95.3% against
88.4% for random review and 90.1% for the disciplined oldest-first student), and exam questions drawn from the
studied topics, the Yadora twin outscores the disciplined twin on about 84% of 50-question exams, 92% of
100-question exams and 98% of 200-question exams (independent answers assumed; correlated answers make it
somewhat more certain). What can be said honestly is the twin result itself, stated as a simulation, until the
pilot measures real learners.

**What moves real outcomes more than any parameter left.** Honest "Forgot" ratings (above). Doing the reviews
at all. Reviewing by retrieval (questions, explaining from memory) rather than rereading alone: in the
literature the testing effect is worth about half a standard deviation over restudy (Rowland 2014, g = 0.50;
Adesope et al. 2017, g = 0.51 against restudying), far more than any interval rule left to tune. The Settings guide already says so; the pilot
records the method (D7) so Yadora's own data can confirm it.

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
  instead, and the twin question is answered by simulation on the fitted model (§5).
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
`python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json`. That answers the twin
question for the pilot's real learners rather than for an average Anki user.

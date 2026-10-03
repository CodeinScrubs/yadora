# Research brief: the mathematics of Yadora's review scheduler

*Written on 3 October 2026 for an outside researcher. It describes the main branch of
github.com/CodeinScrubs/yadora on that date. The code and our own documents (`docs/RESEARCH.md`: evidence
and simulations; `docs/PILOT.md`: the pilot protocol) are public there. Use them for detail. Do not review
the code; these questions are scientific.*

## 0. What we need, and the standard

Yadora is a working app. Its implementation has been verified, and a pilot with real students is about to start.
We do not need an overview of spaced repetition. We need scientifically grounded answers to the questions in
section 5, because each answer feeds a specific decision about the algorithm, its mathematics or the analysis
of the pilot. The developer's engineering assistant will read your report and check each claim against its
source and against the system described here before anything changes. Earlier AI reports to this project:

- invented papers;
- quoted wrong parameter counts;
- proposed formulas that did not fit the system.

Those parts were thrown away. Accuracy matters more than coverage.

1. **Sources.** Use primary sources: peer-reviewed papers, preregistered studies, documented public datasets and
   benchmarks. Give the authors, year, title, venue and a DOI or stable URL for each.
2. **Numbers.** For every number you rely on, quote the sentence, table or figure it comes from. If you saw only
   the abstract, say "abstract only".
3. **Grades.** Grade each conclusion:
   - **Established**: a meta-analysis or several independent replications.
   - **Supported**: at least one well-designed study.
   - **Tentative**: indirect, small or lab-only evidence.
   - **Unknown**: no usable evidence; say so plainly.

   Never round a grade up.
4. **Transfer.** Say what material and population the evidence comes from (word pairs, flashcards, prose, course
   content, medical knowledge; laboratory or real world; the delays tested). Evidence about single facts is not
   automatically evidence about whole topics. Say how far it transfers, and why.
5. **No invented constants.** If a formula needs a number that no source supplies, leave it as a named parameter
   and say how it could be estimated.
6. **Mathematics.** State your assumptions and derive your results. Check each result against the FSRS-6
   equations in section 2 with the default weights, and give a worked numerical example. If you run code, include
   it (Python; numpy and scipy are fine) so it can be re-run.
7. **Corrections first.** If an assumption in this brief is wrong, say so before anything else. If something is
   ambiguous, state your reading and carry on.
8. **Depth over breadth.** If you cannot answer everything in full, answer Priority A fully and stop, listing what
   you skipped.

## 1. The app

- **Users.** Yadora is an offline Android app (no server, no account) for medical students and residency-exam
  candidates, mostly in Iran. Its interface is in Persian, English and German.
- **What it schedules.** It decides **when to review a topic**. A topic is a chunk studied in one sitting:
  - a lecture;
  - a textbook section;
  - a disease ("Acute appendicitis");
  - the subject of a question-bank block.

  A topic may carry a one-line scope ("What does this topic cover?") and notes, and it belongs to a subject such
  as Cardiology. Yadora is not a flashcard app: it has no cards, no in-app test and no answer reveal.
- **What a review is.** Whatever the learner chooses, almost always outside the app: rereading notes, doing
  practice questions, watching a lecture again, or anything else.
- **What the learner reports after a review:**
  - **Memory:** "How much did you still remember?", meaning what they still had when they came back to the
    topic, before rereading or checking answers. The answers map to FSRS grades 1–4: Forgot (most of it was
    gone) is a failure; Hard (the core was there, with real gaps), Good (remembered most of it) and Easy (knew it
    thoroughly) are successes.
  - **Understanding:** "How well do you understand it now?" Clear, Partial or Confused. It is not asked after
    Forgot.
  - **Optional:** the method or methods used (Questions, Reading, Lecture, Other), and after questions, a score
    (number correct out of the total).
- **The first rating.** Straight after first studying a topic, the learner rates it Hard, Medium or Easy (FSRS
  grades 2, 3 and 4). The schedule counts from that moment.
- **Scale.** Our simulations assume 3 to 6 new topics on most days. Libraries grow to hundreds of topics, and to
  about 2,500 over two years. A daily limit caps the reviews offered each day (50 by default).

## 2. The algorithm, exactly

### 2.1 The memory model: FSRS-6

The code matches py-fsrs 6.3.1 on 4,932 golden test vectors. The default weights w0 to w20 are:

`0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542`

The symbols:

- `G` is the grade: 1 Again (Forgot), 2 Hard, 3 Good, 4 Easy.
- `S` is stability, in days.
- `D` is difficulty, between 1 and 10.
- `t` is the time elapsed since the last review, in days.

The equations:

- **Recall probability:** `R(t, S) = (1 + F·t/S)^(−w20)`, where `F = 0.9^(−1/w20) − 1 ≈ 0.9803`. So `R(S, S) = 0.90`.
- **Interval for a target r:** `I(r, S) = (S/F)·(r^(−1/w20) − 1)`. This gives `I(0.90, S) = S`, `I(0.85, S) ≈ 1.906·S`
  and `I(0.95, S) ≈ 0.403·S`.
- **First grade:** `S0 = w[G−1]`, which is 1.29, 2.31 and 8.30 days for Hard, Good and Easy;
  `D0(G) = clamp(w4 − e^(w5·(G−1)) + 1, 1, 10)`.
- **Difficulty:** `D′ = D − w6·(G − 3)·(10 − D)/9`, then `D″ = clamp(w7·D0*(4) + (1 − w7)·D′, 1, 10)`. Here `D0*(4)` is
  the unclamped `w4 − e^(3·w5) + 1 ≈ −4.77`.
- **Success** (`G ≥ 2`, `t ≥ 1`):
  `S′ = S·(1 + e^(w8)·(11 − D)·S^(−w9)·(e^(w10·(1 − R)) − 1)·h·b)`.
  `h = w15` for Hard, otherwise 1. `b = w16` for Easy, otherwise 1.
- **Lapse** (`G = 1`, `t ≥ 1`):
  `S′ = min(w11·D^(−w12)·((S + 1)^(w13) − 1)·e^(w14·(1 − R)), S/e^(w17·w18))`.
  The second bound is `≈ 0.952·S`.
- **Same day** (`t < 1`): `S′ = S·e^(w17·(G − 3 + w18))·S^(−w19)`. The multiplier is floored at 1 for Good and Easy.
- **Bounds:** `S ≥ 0.001`; S has no upper bound.
- **Elapsed time:** `t` counts whole local calendar days between two reviews, which is how the weights were
  fitted (Anki day numbers).

### 2.2 Yadora's layer around the model

- **Target.** The retention target is `r = 0.90` by default; the learner may choose anything from 0.85 to 0.97.
  Topics the learner marks Important use `r + 0.03`, at most 0.97.
- **Interval after a success.** The interval is `I(r, S′)·k`, where `k` is the learner's calibration scale
  (below). It is kept between 1 and 365 days, and at most 5 days after a first rating. If the interval is at
  least 3 days, a deterministic jitter of ±5% (uniform, seeded by topic and review number) is applied last.
- **After Forgot.** The topic returns the next day, and its state follows the lapse equation.
- **Understanding clock.** This is a second deadline, separate from memory; the memory model never sees
  understanding.
  - Confused brings the topic back in 1 day.
  - Partial brings it back in 2, 3 or 4 days, after a Hard, Good or Easy memory rating.
  - Each review in a row that leaves understanding unrepaired (Partial or Confused, with a successful recall)
    doubles the deadline: 3, 6, 12, 24 days and so on.
  - A deadline that would not come before the memory interval is dropped.

  The topic is due at the earlier of the two clocks.
- **Calibration scale k.** Each review stores the R the model predicted at that moment.
  - **Evidence:** the last 600 eligible reviews. A review is eligible if it came at least 3 calendar days after
    the previous review and at least half way through the interval that review set, so repairs and early reviews
    are left out.
  - **Raw estimate:** `k̂` solves `Σᵢ (1 + xᵢ/k̂)^(−w20) = number of successes`, where `xᵢ = Rᵢ^(−1/w20) − 1`. This is
    calibration-in-the-large under a rescaling of stability.
  - **Scale used:** `k = exp(n/(n + 120)·ln k̂)`, clamped to [0.5, 1].

  The upper bound of 1 is deliberate: the calibration may shorten intervals but never lengthens them (section 4).
- **Personal weight set.** When a learner has at least 640 eligible reviews (and 20% more than at the last
  attempt, or 30 days later), the phone runs py-fsrs 6.3.1's training procedure on that learner's history:
  - binary cross-entropy on success or failure, with same-day reviews left out of the loss;
  - Adam with a learning rate of 0.04 and cosine annealing, 5 epochs, batches of 512;
  - every weight clamped to the reference bounds;
  - a pretraining step for the initial stabilities, shrunk toward the defaults.

  The new set is adopted only if all three of these hold:
  1. In forward-chaining validation, its pooled per-review log loss beats the current weights with a one-sided
     paired z of at least 2.33. The history is cut in time into 5 chunks, and each of the last 4 is predicted by
     a fit on the chunks before it.
  2. The initial stabilities stay in grade order.
  3. It does not lengthen intervals: across topics, the geometric mean of the new next interval divided by the
     default one is at most 1.

  In simulation, it was never adopted for a learner the defaults describe (0 of 40 refits). For a strongly
  different learner it was adopted 9 times in 10, at about 9,000 reviews.
- **Daily plan.** First ratings are never held back. Due reviews are ranked by:

  `priority = 100·[Important] + 5·(days past the earlier due date) + min(200, 80·(1 − R)·R·g)`

  Here `g = (S′_Good − S)/S` is the relative gain in stability that a Good review would give now. The top of the
  list fills what remains of the day's limit.
- **Review ahead.** Once the day's reviews are done, the learner may review rated topics that are not yet due,
  lowest predicted recall first, 20 at a time. FSRS scores them as ordinary early reviews.
- **Backlog recovery.** On request, overdue reviews beyond today's remaining capacity are spread over the next 3
  to 14 days, depending on the daily limit, most urgent first.

### 2.3 The data each learner produces

For every review, the export holds:

- the time of the review;
- the memory and understanding ratings, and whether it was a first rating;
- the whole days elapsed since the last review;
- the predicted R;
- the interval that was set;
- the calibration scale and the target in force;
- the method or methods used, and the question score if one was given;
- the kind of session: today's plan, extra reviews, a single topic, or review ahead.

It also holds deferrals, reminders that fired and were tapped, and rating corrections.

For every topic, it holds the subject, the Important flag and size proxies: note length, whether a source is
given, and title length. Titles and notes themselves are not exported. Stability and difficulty are not stored;
they are recomputed by replaying the history.

**Study time is not measured, and will not be.** The export includes how long the review screen was open, but
the review itself happens mostly outside the app.

## 3. What is already settled for this project

You do not need to redo any of this.

**Implementation.** The FSRS-6 code matches py-fsrs 6.3.1's golden vectors. An independent Python transcription
replays every exported review exactly.

**Simulation results.** These are circular for questions about real memory, because in the simulation the
learner's true memory is FSRS-6 itself.

- **Against no schedule.** At equal review time, a simulated student following Yadora beats one who reviews
  without a schedule by 5.0 to 8.4 points on a quiz a year later.
- **Inflated ratings.** These are the largest loss a learner controls. If 30% of lapses are rated Hard, the
  student loses 1.5 points (2.6 before the "never lengthen" rule).
- **A perfect model.** An oracle that knows the learner's true memory adds at most about 0.3 points for an
  honest learner. When ratings are inflated, it adds about 10 points on the weakest tenth of topics.
- **A stability-dependent target.** It gains 0.4 to 0.8 points when the daily limit has room, but costs the
  weakest tenth 1.5 to 3.3 points when the limit binds.
- **Queue order.** The order above was chosen over lateness alone and over lowest recall first: it knows more on
  average. Lowest recall first lifts the weakest tenth more, but loses 1.7 to 3.0 points on average.

**What simulation cannot answer.** Whether real topic-level memory, and real ratings given after a review, behave
the way the model assumes. That needs the literature, sound statistics and real data.

**Already in our evidence base.** Build on these or correct them; do not summarize them again:

- Cepeda et al. 2008 (Psychological Science);
- Latimier, Peyre & Ramus 2021 (Educational Psychology Review);
- Yang et al. 2021 (Psychological Bulletin);
- Dunlosky et al. 2013;
- Rowland 2014;
- Adesope et al. 2017;
- Rawson & Dunlosky 2022;
- Lindsey et al. 2014;
- Tabibian et al. 2019 (PNAS);
- Ye, Su & Cao 2022 (KDD);
- Kerfoot et al. 2007 and 2009;
- Deng et al. 2015;
- Custers 2010;
- Nelson & Dunlosky 1991;
- Brunmair & Richter 2019;
- a 2021 paper in Memory on hindsight bias in metamemory (doi:10.1080/09658211.2021.1919144);
- the open-spaced-repetition srs-benchmark;
- a 2026 meta-analysis in The Clinical Teacher (Maye et al.), reported as 13 studies, 21,415 learners and a
  standardized mean difference of 0.78 against usual study. Please verify this one.

**Facts that earlier reports got wrong:**

- FSRS-7 has 34 parameters according to the srs-benchmark, not 35 or 21.
- In FSRS, Hard is a success.
- In MEMORIZE (Tabibian et al.), the optimal review intensity is proportional to 1 − R, and the method covers
  many items, treated as independent.
- Yadora stores no stability, difficulty or time zone with each review.
- Two AI reports that agree are not independent evidence.

**The pilot.** It starts soon: a small group (plan for 5 to 20 students) for 8 weeks, with data exported at
weeks 2 and 8. Its decision rules are fixed in advance. Among them:

- reported recall within 5 points of the calibrated prediction (n ≥ 300);
- the first review after each kind of first rating within −7 to +5 points of the prediction (n ≥ 60 each);
- most learners' raw scale between 0.8 and 1.25;
- a pooled refit beating the defaults (z ≥ 2.33);
- memory ratings correlating with question scores (rank correlation ≥ 0.3, n ≥ 50);
- review methods compared within each learner (n ≥ 100 each);
- checks on adherence and on reminder delivery.

## 4. Fixed constraints: answer within them

These are the owner's decisions. Do not propose changing them. If you believe one is costly, quantify the cost in
one paragraph, then answer within it.

1. **Rating comes after the review.** The learner rates after reviewing, by any method. No recall test,
   answer-reveal step or "rate before you look" gate will be added.
2. **No study-time measurement.** The app will not ask how long a review took or estimate study time: the time a
   student spends depends on how much time they have that day, so it says little about the topic. Cost
   assumptions taken from the literature are fine.
3. **The exam date is cosmetic.** It must not influence intervals, order, targets or hints.
4. **Personalization never lengthens intervals.** Calibration and personal weights may shorten intervals but not
   lengthen them, until an objective signal separates slow forgetting from generous rating. Question A3 asks what
   that signal could be.
5. **Topics stay whole.** No splitting topics into cards, no learning steps, no special handling of
   repeatedly forgotten topics ("leeches"), and no load balancing that makes one topic's interval depend on other
   topics.
6. **Offline, on the phone.** Anything that runs in the app must finish in seconds on a phone. Heavier analysis
   can run offline, in Python, on exported data.
7. **No constant without evidence.** No method multipliers, bonuses, floors or ceilings chosen by judgement.
8. **Model changes are expensive.** A new equation or model is a new model identity, with new golden tests and a
   replay of every learner's history. Propose one only with strong evidence that it predicts real topic-level
   reviews better.
9. **Whole days.** Intervals are whole days, and a day starts at local midnight. Any jitter must be deterministic,
   seeded per topic and review as the current ±5% is, so that a preview, the commit and a later replay always agree.

## 5. Questions

### Priority A

#### A1. Does a whole topic forget like a flashcard?

FSRS-6's default weights were fitted on Anki reviews, mostly of single facts. Yadora applies the same curve to
whole topics, where "Forgot" means "most of it was gone".

- **(a) Evidence.** What is known about forgetting curves for meaningful material with many facts, learned in
  courses (lectures, textbook chapters, medical science), measured from days to years after learning? Report:
  - the shape of the curve (power, exponential, a mixture, or an asymptote such as "permastore");
  - how fast it falls compared with word lists and word pairs;
  - the effect of how well the material was first learned, and of how much material there was.

  Include retention studies from medical education.
- **(b) Mathematics.** Model a topic as n parts (facts, or knowledge components). Give their stabilities a
  distribution, for example log-normal with spread σ, and let each part forget on an FSRS-6 curve or an
  exponential one.
  1. Derive the expected fraction of the topic retained over time.
  2. Derive the probability that the fraction retained is above a threshold θ, the "Forgot" boundary, for θ
     between 0.3 and 0.6.
  3. Compare both with a single FSRS-6 curve. When is one curve close enough, meaning within 2 percentage points
     of R from 1 to 365 days?
  4. Should a topic's size (n) enter the model at all, and if so, how?
- **(c) Implications.** Is there evidence that topics need a different decay (w20), or different initial
  stabilities, from flashcards? Which check on our data would detect a mismatch (for example, observed against
  predicted success, binned by elapsed time and by R), and how many reviews would it need?

*Feeds: whether the pilot should test a topic-specific curve, and how.*

#### A2. Ratings given after re-exposure: bias, noise and wording

The memory rating is given after rereading or doing questions. The risk is hindsight or fluency: rating a topic
the learner had largely forgotten as Hard or Good, once it has been studied again.

- **(a) The bias.** How large is it, and in which direction, when people judge their earlier knowledge or recall
  after seeing the material again? Relevant literatures: hindsight bias in memory ("I knew it all along"),
  retrospective confidence, judgements made with the answer in view, and illusions of competence. Give effect
  sizes, and what makes the bias larger or smaller: the material, the delay, whether recall was attempted first,
  feedback, expertise.
- **(b) Medical learners.** How accurately do medical learners assess themselves, at the level of a topic or a
  test, compared with objective performance?
- **(c) Cheap fixes.** Which low-cost changes measurably reduce the bias while the rating stays after the review
  and nothing becomes a gate? Evaluate these candidates rather than assuming they work:
  - wording tied to a concrete moment, such as "When you opened it, could you have explained the core without
    looking?";
  - rating the core and the details separately;
  - a one-line suggestion at the start of a review to notice what comes back (a suggestion, not a gate);
  - a description of behaviour on each button;
  - the number of answer levels (four, or more or fewer).

  Give effect sizes where they exist, and say "Unknown" where they do not.

*Feeds: the wording of the rating, the cheapest change we can make.*

#### A3. A measurement model for generous ratings, and what question scores can add

In simulation, the largest loss a learner controls is calling some lapses Hard. From self-ratings alone, such a
learner looks exactly like one who forgets slowly. That is why the calibration never lengthens intervals. The
only objective signal is the optional question score.

- **(a) The model.** Let a review's true recall be `Y* ~ Bernoulli(R(t, κ·S))`, where `κ` is how fast this learner
  forgets. A true failure is reported as a success with probability `φ` (and, if needed, a true success as a
  failure with probability `ψ`).
  - Can `κ` and `φ` both be identified from rating histories alone? Use the fact that each rating changes the next
    interval, and so the next outcome.
  - Under what assumptions, and with how much data?
  - Derive the Fisher information, or show it by simulation, as a function of how widely R varies at review
    time. In Yadora, most reviews happen near R = 0.90.
- **(b) Adding the question score.** A score is k correct out of m multiple-choice questions, each with c
  options and of unknown difficulty, usually from a question bank.
  - Propose a joint model that links the score to the topic's true recall, with a correction for guessing.
  - How many scored reviews per learner are needed to estimate `φ` to within ±0.1?
  - Scores come mostly from reviews done with questions. What does that selection do?
- **(c) An estimator.** Give one that can run offline on pilot data, and ideally on a phone, together with an
  identifiability check we can run in our simulator.
- **(d) A decision rule.** State, with error rates, when the calibration may lengthen a learner's intervals. What
  evidence separates slow forgetting from generous rating?

*Feeds: the largest open gap in the algorithm.*

#### A4. Personalization that improves with use

The scheduler should get better the longer a learner uses it. Today there are two layers: a one-number
calibration, whose prior of 120 pseudo-reviews was chosen by reasoning, and a full personal refit, which starts
only after thousands of reviews.

- **(a) From the benchmark.** Use the public FSRS benchmark data (open-spaced-repetition/srs-benchmark and its
  Anki review-log datasets).
  - How does the gain from fitting each user (log loss, calibration) over the defaults depend on how many reviews
    the user has?
  - With few reviews, is partial personalization better: fitting only the initial stabilities, only the decay
    w20, or only a stability scale?

  If you can run code on the data, do so and report the results.
- **(b) The calibration's prior.**
  - How is the stability scale distributed across users? (That is, the ratio of each user's fitted interval to
    the default interval at r = 0.90.)
  - From that distribution, derive the shrinkage that minimizes expected loss, to replace our 120 pseudo-reviews.
  - How noisy is our estimate `k̂` at a sample size of n?
- **(c) Per subject.** Does the speed of forgetting vary more between kinds of material (subjects such as
  pharmacology and anatomy) than between people? Sense et al. 2016 is one starting point. If it does, should
  calibration be per learner and subject, shrunk hierarchically, and how much data would each learner–subject
  pair need?
- **(d) Using all four answers.** Would a model that uses Hard, Good and Easy as ordered grades, not only success
  or failure, estimate parameters faster when a learner has few reviews, without bias? What assumptions would it
  need, and is a learner's mix of grades at a given R stable enough over time?

*Feeds: the calibration's constants, and whether personalization can start sooner.*

#### A5. The adoption test and the evidence rules

- **(a) Comparing forecasts.** A personal weight set is adopted when its forecasts beat the current ones. The
  outcomes are success or failure, ordered in time and clustered within topics, and the comparison is repeated
  each time the data grow.
  - Is our paired z on pooled per-review log loss, across forward-chaining folds, valid?
  - Recommend a procedure that controls error despite within-topic dependence and repeated looks: for example, a
    bootstrap that resamples whole topics (or blocks), Diebold–Mariano with a variance robust to autocorrelation,
    or anytime-valid e-values or confidence sequences.
  - Give the formula, and compare its power with ours on a realistic case: 2,000 reviews over 400 topics.
- **(b) When learners choose to review.** Learners decide when to review, and that can carry information: they
  may put off topics they expect to have forgotten, or review early the ones they feel unsure of.
  - Does that bias the calibration and the fit?
  - Can it be detected and corrected, for example by weighting reviews by their lateness?
  - Do our evidence rules (at least 3 days after the previous review, at least half the interval) help or hurt?
- **(c) A pooled default.** With only a few learners, how should a pooled "Yadora default" weight set be estimated
  from the pilot and validated (leaving one participant out at a time)? How much data makes it trustworthy?

*Feeds: whether the adoption test keeps its stated error rate, and how pilot data are pooled.*

#### A6. The pilot's statistics, and the study that would test the goal

- **(a) What the pilot can measure.** For 5, 10 and 20 participants over 8 weeks (state the review counts you
  assume), how precisely can we expect to estimate:
  - calibration-in-the-large and the calibration slope;
  - the success rate at the first review after each kind of first rating;
  - the rank correlation between ratings and question scores;
  - differences between review methods within each learner;
  - the misclassification rate `φ` from A3?

  Allow for clustering within learners and within topics. For each pre-registered rule in section 3, give the
  chance it fires when nothing is wrong, and the chance it misses a real problem of a stated size.
- **(b) The study that tests the goal.** The goal is that a student using Yadora, with the same study hours,
  scores higher on an exam. Design a study to test it. It must meet two constraints: every arm reviews (nothing is
  withheld from any participant's topics), and the app times nothing. Compare two designs:
  - randomizing each student's topics between Yadora's schedule and a fixed accepted schedule (such as 1-3-7-21-60
    days), at an equal number of reviews;
  - randomizing students between the two schedules.

  For each design, give:
  - what is held equal, and how to check it without timing reviews;
  - the outcome measure: a question test on randomly sampled topics at a fixed date, written by someone
    independent;
  - the sample size (students × topics × questions) needed to detect differences of 3, 5 and 8 percentage points
    with 80% power;
  - the analysis (mixed-effects logistic regression with crossed random effects, or something better);
  - the threats: contamination between arms, reviews done off schedule, unequal effort.

*Feeds: what the pilot can conclude, and the study that comes after it.*

### Priority B

#### B1. How the cost of a review grows as recall falls

The best retention target depends on how the cost of a review grows as recall falls: a review after heavy
forgetting is really relearning. Yadora will not time reviews, so this cost curve has to come from the literature.

- **(a) Evidence.** What does the research on savings in relearning (relearning faster the second time) say
  about relearning time, as a function of how much was retained or of the delay? Focus on meaningful material, and
  give a functional form with fitted values.
- **(b) The optimum.** Using that cost `c(R)` and FSRS-6 with the default weights:
  - derive the target that maximizes retention per unit of study time, both at a final test and averaged over
    time;
  - compare it with 0.85 to 0.90. Our default is 0.90, and our simulation assumed a failed review costs 1.5
    successful ones;
  - show how sensitive the optimum is to `c(R)`.

*Feeds: the default target and the Settings advice about it.*

#### B2. Are the reviews informative enough to learn the curve?

Nearly all reviews happen near the target (R ≈ 0.90, with ±5% jitter); late reviews add some spread. Estimating
the curve's shape (w20) or a learner's scale k needs outcomes at a wide range of R.

- **(a) Information.** What is the Fisher information for w20 and for k, given a realistic spread of R at review?
  How much does real-world lateness contribute?
- **(b) Jitter.** What deterministic jitter, recorded for analysis, would make w20 and k estimable within an
  8-week pilot, or within six months of one learner's use? What would it cost in expected retention?
- **(c) Literature.** Is there published work on exploration in spaced-repetition scheduling (as a bandit or
  active-learning problem) that measures this trade-off?

*Feeds: whether the pilot can learn the curve at all, and what jitter would cost.*

#### B3. The first rating, right after study

The first rating is an immediate judgement. It sets the starting stability `S0` at 1.29, 2.31 or 8.30 days, and
the first interval is capped at 5 days.

- **(a) Accuracy.** How accurate are judgements of learning or comprehension made immediately after study,
  compared with delayed ones, for complex material (prose, lectures, medical content)? Report how well they rank
  topics (resolution) and how well their level matches later performance (calibration). Which procedures
  improve immediate judgements without a test?
- **(b) A Bayesian rule.** Derive a rule for the starting stability that combines a population prior with a
  rating of that accuracy. Is a cap of about 5 days consistent with it?
- **(c) The first gap.** What first gap does the spacing evidence support for a newly studied medical topic?

*Feeds: the first-rating stabilities and the 5-day cap.*

#### B4. The understanding repair clock

Confused brings a topic back in 1 day and Partial in 2 to 4 days, doubling while understanding stays unrepaired.
These numbers are policy, not evidence.

- **(a) Timing.** When is the best time to restudy material that was poorly understood, as opposed to poorly
  remembered? Relevant evidence includes:
  - lag effects for material that was not fully learned;
  - successive relearning, studying to a criterion again in each session;
  - absolute against relative spacing in relearning.
- **(b) A separate clock.** Does any model of comprehension and retention support a repair clock separate from
  the memory clock? If the evidence points to different intervals, give them, with sources.

*Feeds: the repair clock's day counts and its doubling.*

#### B5. Weighting Important topics

Important topics get a target 0.03 higher (at most 0.97) and 100 extra priority points, worth 20 days of lateness.

- **(a) The optimal target.** Derive the optimal target for each topic as a function of its weight (its share of
  exam questions, or its clinical importance), under a fixed review budget and FSRS-6 dynamics; for example, by
  setting the marginal value of a review equal across topics with a Lagrangian.
  - What weight ratio does +0.03 at 0.90 correspond to?
  - Is there a simple rule that does better than a flat increase?
- **(b) Priority.** How should importance enter a priority index derived from first principles? For example, a
  Whittle-type index, treating the schedule as a restless bandit. How does that compare with adding a constant?

*Feeds: what the Important flag does.*

### Priority C

#### C1. Review methods as priors

- Convert the meta-analytic effect of practice testing against restudy (and of lectures or videos, if any
  evidence exists) into FSRS stability gains, with stated assumptions and uncertainty. This gives the pilot's
  within-learner comparison of methods an informed prior and a minimum detectable effect.
- FSRS assumes that a harder successful review produces a larger gain in stability: the gain rises as R falls.
  Does the evidence support that when the review is rereading, rather than an attempt to recall?

#### C2. Retention over years, and the 365-day ceiling

What does the evidence say about retaining medical knowledge over 1 to 5 years: is there a floor below which it
stops falling, and how much is kept with spaced review at intervals of about a year? Does it support a maximum
interval of about a year for material that must last years? Answer this without reference to any exam date.

#### C3. Time of day and sleep

Do reviews (not first learning) done in the evening, before sleep, lead to better retention than morning
reviews? Give effect sizes. This would affect only the default reminder times, which the learner can change.

#### C4. Adherence

What is the evidence on daily reminders for self-directed study? Cover:

- one reminder a day or two;
- when to send them;
- whether people stop responding to them over weeks;
- sending reminders only when something is due.

Give effect sizes on engagement and on learning. There is no gamification; the tone is deliberately mature.

#### C5. Models for sparse topic-level data

- Which models predict best when each item has few reviews at long intervals, and each user has only hundreds of
  items? Include FSRS-7.
- Do default parameters transfer between domains, for example from language learning to medicine?
- Is there a public dataset of spaced reviews of whole topics or course units, rather than flashcards, that could
  test the curve?

#### C6. Relations between topics

When related material is integrated, which wins: forgetting caused by retrieving neighbouring topics, or help
from it? Does reviewing related topics close together, or far apart, change how well medical knowledge is
retained? If the evidence is weak, say so; we will not build a map of related topics without strong evidence.

## 6. What to deliver

1. **A one-page summary first:** the five findings that should change Yadora, each with its evidence grade.
2. **For each question:**
   - the short answer;
   - the evidence, graded and quoted;
   - the mathematics;
   - the implication for Yadora: a formula, a constant with its source, or "no change";
   - how to test it, in our simulator or in the pilot data, naming the recorded fields and the amount of data
     needed;
   - what remains uncertain.
3. **A ranked table of proposed changes:** for each change, its expected effect (with uncertainty), the evidence
   grade, the risk, the data needed to confirm it, and any conflict with section 4. There must be none.
4. **A bibliography** with DOIs, and a separate list of claims you could not verify or sources you could not open.
5. **Code** for every estimator or test you propose.
6. **Optional:** up to three important questions we did not ask, held to the same standard.

Please do not send back:

- proposals that conflict with section 4;
- the FSRS-6 equations restated, or a general introduction to spaced repetition;
- guarantees, or headline multipliers such as "2× retention";
- numbers without a source.

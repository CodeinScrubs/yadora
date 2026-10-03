# The two-month pilot: protocol, data and decision rules

For the organiser, and for whoever — a person or an AI — analyses the data afterwards. Participants get
the short Persian guide in [PILOT_GUIDE_FA.md](PILOT_GUIDE_FA.md). The reasoning behind the design is in
[RESEARCH.md](RESEARCH.md).

## What two months can and cannot answer

**Can:**

- whether every phone scheduled exactly what the rules say (the integrity replay);
- whether friends use it as intended: first ratings on the study day, reviews near their dates, the backlog;
- whether each phone delivered a reminder every day, on time (Doze and the makers' battery managers over real
  weeks, which no afternoon of device testing shows);
- whether reported recall matches predicted recall, overall and at the first review;
- whether memory ratings follow question scores;
- whether learners forget faster or slower than the defaults assume;
- whether a pooled refit predicts better;
- which review methods hold up.

**Cannot:** anything about intervals longer than ~2 months, and the twin claim itself (there is no control
twin). Rerunning `tools/pilot/simulate.py` on weights fitted to the pilot's own learners makes the simulated
memory more like theirs, which makes the simulated twin result more credible; it is still a simulation, in
which both twins' memories are a model, and it observes no real control. A randomised control arm inside the
app was rejected on purpose: withholding reviews from a friend's topics is exactly the downside the standard
forbids. A study comparing Yadora with another accepted way of reviewing (both groups review) would be a
separate decision.

## Setup (week 0)

1. **Distribute one signed build through Google Play's internal or closed testing track.** Build it from a
   tagged commit with a higher `versionCode`. Every participant runs the same build, and the build is
   written down (it is also in every export). Do not hand out a debug APK, or an APK signed with any other
   key. Android refuses to update an app in place when the signing certificate changes, so moving to the
   Play version later would mean uninstalling, and uninstalling deletes the pilot data. If a sideloaded
   build is unavoidable, participants must export a full backup (Settings → Export full backup) before
   switching, then restore it.
2. On each phone:
   - finish onboarding, including the REMINDERS step: grant notifications and exact alarms;
   - on Samsung, Xiaomi, Huawei, Oppo and Vivo, set Yadora's battery use to unrestricted;
   - send yourself a test reminder (Settings → Send a test reminder);
   - turn on automatic backup (Settings → Data) into a folder, ideally one a cloud app syncs. Two months of
     pilot data must survive a lost or reset phone.
3. **Keep the defaults**: retention target 0.90 and the default daily limit, unless the participant has a
   reason. Nobody changes them mid-pilot; a change mid-way splits the data in two.
4. Note each participant's research ID (Settings, next to "Share research data") against their name,
   privately. The files themselves carry only the ID.
5. Consent: tell participants what the file contains (below), and that they can see it before sending.

## What participants do

Everything is in the Persian guide. The essentials:

- **One topic = one chunk studied in one sitting**: a lecture, a chapter or a UWorld block's subject. Give
  it a one-line scope ("What does this topic cover?"). Very large topics make "remembered most of it" vague.
- **Rate the first study the same day**, ideally straight after studying ("Save and rate now"). The
  schedule counts from that moment.
- **Review when Yadora says**, by any method. Then answer **how much you still had before you reread or
  checked answers**, not how the session felt.
- **"Forgot" is not failure.** It is the most useful answer the model gets. Rating a lost topic "Hard"
  quietly stretches its intervals (RESEARCH.md §2.2).
- Optionally tick how you reviewed, and if you did questions, the score. Ten seconds; it is what lets the
  pilot tell methods apart and check the ratings.
- "Not today" and missed days are fine. Deleting and re-adding a topic is not: it throws away its history.
  Edit it instead.

## Collecting data

At **week 2** (a sanity check that catches phone-specific bugs early) and at **week 8**:
Settings → **Share research data** → send the file to the organiser.

The file (`yadora_research_YD-XXXX-XXXX_<date>.json`):

- **Contains:** every review's timing, ratings, predictions and intervals; subject names; device model and
  Android version; time zone; the last crash log; the research ID; every reminder alarm (when it was due, how
  late it came, whether the phone was in Doze) and what the phone allowed reminders to do when the file was made
  (notifications, exact alarms, battery optimization, standby bucket); each tap on a reminder, the alarm or the widget
  that opened the app; and every corrected rating with the answer it replaced.
- **Does not contain:** topic titles, notes, scope lines, key points or sources.

It is sensitive, not anonymous: subject names and a device model can identify someone who knows them.
Keep the files private and do not publish them.

A full backup (Settings → Export full backup) is a different file: it contains titles and notes. The
analysis refuses it.

## Analysis

```
python3 tools/pilot/analyze.py path/to/exports/ --out pilot_report
python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json   # only if the fit ran
```

Standard library only. `pilot_report/` gets:

- `report.md`: the findings, including the standard calibration checks for each weight set: the Brier score,
  observed over expected, calibration-in-the-large and the calibration slope, with 95% intervals (descriptive;
  the decision rules below do not read them); per phone, how many posted reminders were tapped within three hours
  and followed by a review that day; and whether the first rating predicts the first real review better than
  ignoring it (a rating given right after studying measures fluency more than memory);
- `summary.json`: the same numbers, machine-readable;
- `reviews.csv`, `topics.csv`, `participants.csv`: tidy tables, UTF-8, open in Excel.

The toolkit checks itself: `python3 tools/pilot/test_yadora_model.py` and `python3 tools/pilot/test_analyze.py`.

## Decision rules (fixed before the data exists)

Each rule gets one verdict:

- **OK**: no change indicated.
- **LOOK**: simulate a candidate change with `simulate.py` before arguing for it.
- **BUG**: fix first and read nothing else.
- **WAIT**: too little data.

No rule authorises a change on its own: a LOOK is a reason to investigate, and anything listed as a
settled decision in CLAUDE.md stays settled unless the owner reopens it.

| id | question | threshold | if LOOK |
|---|---|---|---|
| D1 | Did every phone schedule exactly what the rules say? | 0 replay mismatches, 0 self-check issues | BUG. Find the phone, the build and the log in `report.md` §2. A time-zone change is the one benign cause. |
| D2 | Does reported recall match the calibrated prediction? | within 5 points, n ≥ 300 | The per-user calibration is not keeping up. Check D4 before touching it. |
| D3 | First review after a Hard / Medium / Easy first rating: close to predicted? | −7 to +5 points, n ≥ 60 per rating | Compare "implied S0" with the default. Simulate a first-study prior for topics, or a different cap, before changing either. |
| D4 | Do most learners forget systematically faster or slower than the defaults? | most raw scales inside 0.8–1.25, ≥ 3 participants | A population prior for the calibration, or a Yadora default weight set (see D5). |
| D5 | Does the pooled refit beat the defaults on held-out reviews? | one-sided paired z ≥ 2.33 (the app's own bar) | A candidate Yadora default set. It ships only under a new parameter-set id, after the goldens and replay tests, and never overwrites FSRS-6's published defaults. |
| D6 | Do memory ratings follow question scores? | rank correlation ≥ 0.3, n ≥ 50 | Ratings are noisy or inflated. Change the rating copy first (the cheapest fix). Consider suggesting a rating from the score, never overriding it. |
| D7 | After a Questions review, does the next one go better than after a Reading one? | difference < 5 points within learners, n ≥ 100 each | Advise the better method in the guide. A method-specific stability gain only if the difference survives a refit. |
| D8 | Are fewer than 25% of reviews more than 3 days late? | < 25%, n ≥ 100 | Adherence or reminder problem, not a model problem. Check the reminder events per phone. |
| D9 | Are at least 80% of first ratings given on the study day? | ≥ 80%, n ≥ 50 | The first-rating flow is being skipped. Look at "Save and rate now" and the Today prompt. |
| D10 | Are fewer than 40% of successful reviews answered Partial/Confused? | < 40%, n ≥ 100 | The repair clock is adding a lot of load. Check its backoff in simulation. |
| D11 | Did every phone deliver a reminder on at least 95% of days, and at least 95% within 10 minutes of their time? | per phone with ≥ 14 days of reminder data (export v13+) | A phone that drops or delays reminders. Read its "health at export" in `report.md` §3 first (battery optimization, exact alarms, standby bucket) and fix that phone's settings. If they were right, it is a bug in the reminder path: a missed reminder is a product failure. |

Why these thresholds:

- D2 and D3 allow ~5–7 points, because two months of self-reported recall on whole topics cannot resolve
  finer than that.
- D11 (added 2026-10-03, before any data): every reminder alarm that reaches the app is logged, and every day
  with reminders on has at least one (the set time or the second slot fires even when nothing is due), so a day
  without one is a reminder the phone never delivered. Days with reminders switched off in Settings are left
  out. Exact alarms come within seconds, even in Doze; 10 minutes leaves room for a busy phone, and an inexact
  alarm (no exact-alarm permission) can come up to about an hour late, which D11 should flag.
- D5 uses exactly the gate the app applies to the personal model, so the pilot cannot adopt weights the app
  itself would reject. Two limits, stated before the data exists (2026-09-28): the split is in TIME within the
  same participants, so a pass shows the refit predicts these learners' later reviews, not a new student's;
  and reviews of one topic or one person are not independent, so z ≥ 2.33 is a nominal 1% bar, not a real
  one. A LOOK on D5 is therefore not enough for a default that new users get: first hold out whole
  participants (fit on all but one, score the one left out, for each) and require the gain there too.
- D6's 0.3 is a modest bar: questions and "how much of the topic" measure overlapping but different things.
  If D6 leads to a score-based suggestion, two details matter. It stays a suggestion and never overrides the
  rating (settled). And a multiple-choice score has a chance floor: k right of n on c-option questions reads as
  recall (k/n − 1/c) / (1 − 1/c), floored at 0. D6's rank correlation hardly moves under that correction
  (it is monotone; only the floor adds ties). A suggestion rule would change.
- If D7 shows a difference, the candidate change is a multiplier on the stability GAIN, not on stability:
  S′ = S · [1 + m · (SInc − 1)], where SInc is FSRS-6's own growth factor. Reading is anchored at m = 1. The
  multiplier is fitted with the 21 weights frozen, on successful recalls only, so it cannot trade variance
  with them. D7's comparison is observational: learners choose their method, and harder topics may draw
  more questions. The difference is therefore taken WITHIN each learner who used both methods at least 10
  times (each their own control; amended 2026-09-28, before any data): pooled, a generous rater who mostly
  does questions and a strict one who mostly reads would look like a method effect. It stays observational
  (topics are still chosen by the learner). Stronger designs exist: weighting by the chance of choosing each
  method, or the app suggesting a method at random for a few topics. The random suggestion steers how
  participants study, so it is the owner's decision for a later study, not part of this pilot.
  A fitted m is a candidate to simulate, not a measured effect. (Outside reviews, 2026-09-28:
  one plan multiplied stability itself, up to ×1.25; a follow-up answer used this gain form with m up to 2.2.
  Neither range has a source.)

## Optional: a direct retention check at week 8

This is the closest the pilot can come to the twin quiz without a control group. It happens outside the app:

1. For each participant, draw 20 topics at random from those studied in weeks 1–4, using `topics.csv`
   (look the titles up on their phone).
2. For each topic, the participant writes how much they remember **now** (0–100%) and answers 5 questions
   from a question bank on it, without reviewing first.
3. Compare three things: the model's predicted recall today, their estimate, and the question score. The
   prediction is `stability_after` of the topic's last review, read on the curve of the model and weight set
   that scheduled it (the `scheduler` and `weight_set` columns; a personal set has its own curve), with the
   time since that review counted in whole local calendar days as the app counts it. Report it both raw and
   with the review's `calibration_scale` applied to the stability.

If predicted and measured recall agree, the memory model the twin simulation runs on is supported for these
learners. That is all it shows: the twin comparison itself stays a simulation, because the pilot has no
control group, and 20 topics with 5 questions each measure one person's recall only roughly. Record the
results next to the exports; the toolkit does not need them.

## Handing the data to an AI

Give it `report.md`, `summary.json`, `reviews.csv`, `topics.csv`, `CLAUDE.md`, `docs/RESEARCH.md` and this
file, with this prompt:

> You are analysing a two-month pilot of Yadora, an offline Android app that schedules WHEN medical
> students review a topic (FSRS-6 plus a product layer), not a flashcard app. Read CLAUDE.md first,
> especially "Settled decisions": do not propose anything listed there as rejected unless the data directly
> contradicts the reason given. Then:
>
> 1. Check report.md §2 (integrity). Any mismatch is a bug: say which phone, build and log, and what code
>    path could produce it.
> 2. Go through decision rules D1–D11 in docs/PILOT.md. For every LOOK, use reviews.csv to test whether it
>    holds per participant, or whether one person drives it.
> 3. For any change you recommend, state the expected effect and how to test it with
>    tools/pilot/simulate.py, and name the tests that must change (goldens, ReplayEqualsLiveTest,
>    SchedulerInvariantsTest).
> 4. Separate what the data shows, what it suggests, and what it cannot tell. Do not claim the algorithm
>    is validated. The implementation can be verified; the predictions can only be calibrated against
>    this data.

## Timeline

| when | what |
|---|---|
| week 0 | build tagged, installed, onboarding done, reminders tested, research IDs noted |
| week 2 | first export from everyone → `analyze.py` → fix any D1 bug, fix the settings of any phone the reminder table in §3 flags, reply to confusion |
| weeks 3–8 | normal use; no setting changes |
| week 8 | final export, optional retention check, full analysis, simulation on fitted weights |

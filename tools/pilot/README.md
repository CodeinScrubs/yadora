# tools/pilot — analysing Yadora's research exports

Standard-library Python (3.9+); nothing to install. See [docs/PILOT.md](../../docs/PILOT.md) for the
protocol and decision rules, and [docs/RESEARCH.md](../../docs/RESEARCH.md) for the evidence and the
simulation results.

| file | what it does |
|---|---|
| `analyze.py` | Reads research exports (Settings → Share research data), replays every review, writes `report.md`, `summary.json`, CSVs, and a pooled refit when there is enough data. |
| `simulate.py` | The identical-twins simulation: a year of study, Yadora vs review without a schedule at equal time, across learner types, rating honesty, missed days, cramming and retention targets. |
| `experiments.py` | One scheduling choice at a time against its alternatives: queue order under a binding limit, the relearn step, the first-study cap, the maximum interval, a difficulty-adaptive target. Workload-changing choices are compared at equal time. |
| `residency.py` | Two years to an exam: Yadora's strategies (default, a final Review-ahead push, a higher target) against random and oldest-first review, a fixed-interval ladder and plain FSRS-6, all at the Yadora twin's review time. Reports the average recall on exam day, the share of topics at 90%+ and the weakest tenth. |
| `one_exam.py` | One exam, one year, a fixed daily budget and a finite syllabus: how to split the day between new material and reviews, which target leaves the most on exam day, and what spare time on Review ahead is worth (RESEARCH.md §2.8). Never-studied topics count 0. |
| `yadora_model.py` | FSRS-6 and Yadora's scheduling rules, transcribed from the Kotlin sources. Both tools use it. |
| `test_yadora_model.py` | Checks the transcription against the py-fsrs 6.3.1 goldens the app is tested with, and against kotlin-stdlib's RNG (`kotlin_fuzz_reference.json`) for the interval fuzz. |
| `test_analyze.py` | Runs `analyze.py` on `fixtures/sample_export.json`, a real app export, and checks every log replays exactly and that tampering is caught. |
| `forecast_audit.py` | Scores immutable `REVIEW_FORECAST v1` events. Keeps original predictions and subjective answers separate from mutable replay/corrected answers; never invents forecasts for legacy rows. |
| `test_forecast_audit.py` | Checks corrections, merged log ownership, exclusions, invalid/duplicate snapshots, finite scores and small-cluster uncertainty. |

```
python3 tools/pilot/analyze.py exports/ --out pilot_report
python3 tools/pilot/simulate.py                      # ~3 minutes; --quick for a smoke test
python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json
python3 tools/pilot/experiments.py                   # ~30 minutes; --quick, --only order,relearn,cap,maxivl,adaptive
python3 tools/pilot/residency.py --seeds 6            # ~30-40 minutes; --quick, --only default,fast,slow,heavy
python3 tools/pilot/test_yadora_model.py && python3 tools/pilot/test_analyze.py
python3 tools/pilot/test_forecast_audit.py
python3 tools/pilot/forecast_audit.py exports/ --out prospective_report
```

`yadora_model.py` must change whenever `Fsrs6.kt`, `MedScheduler.kt` or `RecallCalibration.kt` change a
rule that decides an interval. If it falls behind, `test_analyze.py` fails on the fixture and the
integrity check (D1) reports every pilot review as a mismatch. Regenerate the fixture after changing the
export format; the command is at the top of `test_analyze.py`.

`forecast_audit.py` is a second view of the data: `analyze.py` verifies the current mutable replay;
the forecast audit describes what the scheduler predicted **when each review was saved**. Corrections
do not change these events, Undo removes them, and a merge keeps their log ids. Old exports without
the events produce a coverage report with zero prospective forecasts. They are never backfilled.
The original mixed post-study ratings are a **subjective proxy**, not objective recall. A good Brier
or log-loss result does not prove a review policy improved exam performance. Groups stay separate
by learner, weight set, policy and due context. Topic-clustered gap intervals are conditional on
these learners; the approximate interval is withheld below 20 effective topic clusters. Topic weights
are checked so many tiny topics cannot hide one dominant topic; this is a concentration guard, not
a power calculation or an independent sample count. That floor is a diagnostic
heuristic, not a sample-size or power calculation. No app schedule or calibration estimate reads it.


### Compare immutable forecasts with a past-only reference

`forecast_audit.py` also compares raw FSRS predictions with a fixed simple reference on the same reviews.
No additional app input, network service, dependency, refit or schedule change is needed:

```sh
python3 tools/pilot/test_forecast_baselines.py
python3 tools/pilot/forecast_audit.py exports/ --out prospective_report
```

The reference uses the last 50 **retained valid original delayed-review outcomes** in saved-id order,
not wall-clock order. Before observing the current answer, it predicts `(past_successes + 1) / (n + 2)`.
The first 20 outcomes of each learner/model/set/policy/session/due-context stream are warm-up only.
These are fixed diagnostic choices, not validated optimal constants. Missing forecasts, first studies,
same-day ratings and orphaned/invalid snapshots are excluded by the existing audit. Corrected answers
cannot change the original learning signal. Purged history cannot be reconstructed: this is a
counterfactual reference calculated at analysis time, not an assertion that it was deployed or logged.

`forecasts.csv` includes `baseline_p` and `baseline_history_n`. The JSON includes paired log loss and
Brier scores, sample counts and `log_loss_advantage` = baseline loss minus raw FSRS loss. For example,
+0.01 means raw FSRS predicts these binary post-study ratings better by 0.01 natural-log loss units per
scored review; it is **not** a 1% learning or exam improvement. Warm-up rows are excluded from **both**
models' paired scores. Results remain separated by learner, set, policy, session and due context.

Approximate 95% intervals for the review-weighted mean loss difference use topic-cluster sandwich
variance, conditional on the observed learner, and are withheld below 20 effective topics (weight
concentration). That is an uncertainty safeguard, not a power calculation; day effects, model fitting,
review-selection confounding and repeated checks remain limitations. A predictor of mostly successful
ratings can win log loss without identifying which topic needs review. **Do not replace a scheduler or
claim educational benefit from this comparison alone.** Combine these diagnostics with separately
collected assessment results and study time when deciding the next experiment.

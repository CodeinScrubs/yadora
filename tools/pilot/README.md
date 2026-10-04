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

```
python3 tools/pilot/analyze.py exports/ --out pilot_report
python3 tools/pilot/simulate.py                      # ~3 minutes; --quick for a smoke test
python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json
python3 tools/pilot/experiments.py                   # ~30 minutes; --quick, --only order,relearn,cap,maxivl,adaptive
python3 tools/pilot/residency.py --seeds 6            # ~30-40 minutes; --quick, --only default,fast,slow,heavy
python3 tools/pilot/test_yadora_model.py && python3 tools/pilot/test_analyze.py
```

`yadora_model.py` must change whenever `Fsrs6.kt`, `MedScheduler.kt` or `RecallCalibration.kt` change a
rule that decides an interval. If it falls behind, `test_analyze.py` fails on the fixture and the
integrity check (D1) reports every pilot review as a mismatch. Regenerate the fixture after changing the
export format; the command is at the top of `test_analyze.py`.

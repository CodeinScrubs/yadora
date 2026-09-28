"""
Checks the pilot analysis against a REAL app export.

  python3 tools/pilot/test_analyze.py

fixtures/sample_export.json was written by the app's own exporter (PilotExportFixtureTest drives the real
repository through the review screen's commit path for a simulated six-week learner, then exports). If the
app and this toolkit ever disagree about how a review is scheduled, the integrity check fails here first.
Regenerate the fixture after changing the export format:

  ./gradlew :app:testDebugUnitTest --tests com.example.data.PilotExportFixtureTest
  python3 -c "import json; json.dump(json.load(open('app/build/pilot-fixture/sample_export.json')), \
      open('tools/pilot/fixtures/sample_export.json', 'w'), separators=(',', ':'), ensure_ascii=False)"
"""
import copy
import csv
import json
import math
import os
import random
import sys
import tempfile

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
import analyze  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "sample_export.json")


def run(exports_data, fit=False):
    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for i, d in enumerate(exports_data):
            p = os.path.join(tmp, f"export_{i}.json")
            with open(p, "w", encoding="utf-8") as f:
                json.dump(d, f)
            paths.append(p)
        warnings = []
        exports = analyze.load_exports(paths, warnings.append)
        out = os.path.join(tmp, "out")
        summary = analyze.analyze(exports, out, warnings, fit=fit) if exports else None
        files = {name: open(os.path.join(out, name), encoding="utf-8-sig").read()
                 for name in os.listdir(out)} if exports else {}
        return summary, warnings, files


def fixture():
    with open(FIXTURE, encoding="utf-8") as f:
        return json.load(f)


def test_real_export_replays_exactly():
    d = fixture()
    summary, warnings, files = run([d], fit=True)
    integ = summary["integrity"]
    logs = len(d["reviewLogs"])
    assert integ["checked"] == logs, integ
    assert integ["mismatched"] == 0, integ
    assert integ["consistency_issues"] == 0
    rows = list(csv.DictReader(files["reviews.csv"].splitlines()))
    assert len(rows) == logs, (len(rows), logs)
    assert all(not r["replay_mismatch"] for r in rows)
    assert "report.md" in files and "## 2. Integrity" in files["report.md"]
    assert {x["id"] for x in summary["decisions"]} >= {"D1", "D2", "D5", "D6", "D8"}
    assert summary["decisions"][0]["verdict"] == "OK"
    print(f"real export: {logs} logs replayed exactly; {len(summary['decisions'])} decision rules evaluated")


def test_a_tampered_interval_is_caught():
    d = fixture()
    bad = copy.deepcopy(d)
    target = next(l for l in bad["reviewLogs"] if l["logType"] == "RECALL" and l["nextIntervalDays"] > 3)
    target["nextIntervalDays"] += 1.0
    summary, _, files = run([bad])
    assert summary["integrity"]["mismatched"] == 1, summary["integrity"]
    assert summary["decisions"][0]["verdict"] == "BUG"
    assert "interval" in files["report.md"]
    print("a one-day change to one stored interval is reported as a bug")


def test_a_tampered_prediction_and_elapsed_are_caught():
    d = fixture()
    bad = copy.deepcopy(d)
    recalls = [l for l in bad["reviewLogs"] if l["logType"] == "RECALL"]
    recalls[3]["retrievabilityAtReview"] *= 0.9
    recalls[7]["elapsedDays"] += 1
    summary, _, _ = run([bad])
    assert summary["integrity"]["mismatched"] == 2, summary["integrity"]
    print("a changed prediction and a changed day count are both caught")


def test_several_participants_and_duplicates():
    a = fixture()
    b = copy.deepcopy(a)
    b["participantId"] = "YD-TEST-TWO2"
    older = copy.deepcopy(a)
    older["exportedAt"] -= 86_400_000
    older["reviewLogs"] = older["reviewLogs"][:10]
    summary, warnings, _ = run([older, a, b])
    assert set(summary["participants"]) == {a["participantId"], "YD-TEST-TWO2"}
    assert any("two exports" in w for w in warnings), warnings
    # The newer export of the first participant was kept (all its logs), so twice the logs overall.
    assert summary["integrity"]["checked"] == 2 * len(a["reviewLogs"])
    print("two participants pooled; an older duplicate export is set aside with a warning")


def test_rows_a_correction_recomputed_are_not_calibration_evidence():
    # As the app since 2026-09-28: a rating correction replays a topic onto the weight set it is on now and stamps
    # every row with it; the rows reviewed before that set began scheduling were recomputed, not predicted.
    d = fixture()
    recalls = sorted((l for l in d["reviewLogs"] if l["logType"] == "RECALL"), key=lambda l: (l["reviewedAt"], l["id"]))
    activated = recalls[len(recalls) // 2]["reviewedAt"]
    d["memoryParameterSets"] = [{"id": 7, "createdAt": activated, "status": "ACTIVE", "weights": list(analyze.ym.DEFAULT_WEIGHTS),
                                 "activatedAt": activated, "retiredAt": None}]
    for log in d["reviewLogs"]:
        log["parameterSetId"] = 7  # the same numbers as the defaults, so every row still replays exactly
    summary, _, files = run([d])
    assert summary["integrity"]["mismatched"] == 0, summary["integrity"]
    after = sum(1 for l in recalls if l["reviewedAt"] >= activated and 0 <= l["retrievabilityAtReview"] <= 1)
    assert summary["calibration"]["FSRS-6/7"]["raw"]["n"] == after, (summary["calibration"]["FSRS-6/7"]["raw"]["n"], after)
    assert "left out" in files["report.md"]
    print(f"recall rows that predate their weight set are left out of its calibration ({after} of {len(recalls)} kept)")


def test_calibration_slope_and_intercept_recover_a_planted_miscalibration():
    # Outcomes drawn from logit P(y) = a + b * logit(p): calibrated predictions (a=0, b=1) must read as slope 1 and
    # intercept 0, over-extreme ones (b=0.5) as slope 0.5, and a systematic shift (a=-0.5) as an intercept below 0.
    rng = random.Random(7)

    def draw(a, b, n=20000):
        out = []
        for _ in range(n):
            p = rng.uniform(0.55, 0.99)
            q = 1 / (1 + math.exp(-(a + b * math.log(p / (1 - p)))))
            out.append((p, rng.random() < q))
        return out

    good = analyze.logistic_calibration(draw(0.0, 1.0))
    assert abs(good["slope"] - 1.0) < 0.1 and abs(good["citl"]) < 0.1 and abs(good["oe"] - 1.0) < 0.02, good
    assert good["slope_ci"][0] < 1.0 < good["slope_ci"][1], good
    flat = analyze.logistic_calibration(draw(0.0, 0.5))
    assert abs(flat["slope"] - 0.5) < 0.1, flat
    shifted = analyze.logistic_calibration(draw(-0.5, 1.0))
    assert shifted["citl"] < -0.3 and shifted["citl_ci"][1] < 0, shifted
    assert analyze.logistic_calibration([(0.9, True)] * 10).keys() == {"brier", "oe"}, "too few reviews: no fit"
    print("calibration slope and intercept recover planted miscalibration (1.0 / 0.5 / shifted)")


def test_method_comparison_is_made_within_each_learner():
    # A generous rater who mostly does questions and a strict one who mostly reads: pooled, questions look 20 points
    # better; within each learner the methods are identical. D7 must read the within-learner number.
    lo = analyze.WITHIN_MIN
    people = {
        "A": {"Questions only": [0.10] * 40, "Reading only": [0.10] * lo},
        "B": {"Questions only": [-0.10] * lo, "Reading only": [-0.10] * 40},
    }
    pooled_q = [x for p in people.values() for x in p["Questions only"]]
    pooled_r = [x for p in people.values() for x in p["Reading only"]]
    assert sum(pooled_q) / len(pooled_q) - sum(pooled_r) / len(pooled_r) > 0.1, "the confounded pooled gap"
    wp = analyze.within_participant_difference(people)
    assert wp["participants"] == 2 and abs(wp["diff"]) < 1e-12, wp
    specialists = {"A": {"Questions only": [0.1] * 50}, "B": {"Reading only": [-0.1] * 50}}
    assert analyze.within_participant_difference(specialists) is None, "no learner used both: nothing to compare"
    print("the method comparison is made within each learner (a between-person gap is not read as a method effect)")


def test_a_backup_is_refused_with_an_explanation():
    backup = {"backupVersion": 9, "studyUnits": [], "reviewLogs": [], "subjects": []}
    backup.pop("reviewLogs")
    summary, warnings, _ = run([backup])
    assert summary is None
    assert any("BACKUP" in w for w in warnings), warnings
    print("a full backup (with titles and notes) is refused, and the warning says what to send instead")


if __name__ == "__main__":
    test_real_export_replays_exactly()
    test_a_tampered_interval_is_caught()
    test_a_tampered_prediction_and_elapsed_are_caught()
    test_several_participants_and_duplicates()
    test_rows_a_correction_recomputed_are_not_calibration_evidence()
    test_calibration_slope_and_intercept_recover_a_planted_miscalibration()
    test_method_comparison_is_made_within_each_learner()
    test_a_backup_is_refused_with_an_explanation()
    print("all checks passed")

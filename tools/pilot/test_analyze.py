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
import os
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
    test_a_backup_is_refused_with_an_explanation()
    print("all checks passed")

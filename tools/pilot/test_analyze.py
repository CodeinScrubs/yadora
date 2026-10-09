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
from dataclasses import replace
import csv
import json
import math
import os
import random
import statistics
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


def rows_of(d):
    """The analysed rows of one export, each with its list of mismatches."""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "export.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(d, f)
        exports = analyze.load_exports([path], lambda w: None)
        return analyze.build_rows(exports[0])[0]


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


def test_a_clock_set_back_and_a_corrected_rating_replay_exactly():
    """The fixture holds a topic reviewed with the clock set back (a later review with an earlier time) and one
    corrected rating (export v14). Both replay exactly in saved order, and the predictions the correction recomputed
    are left out of the calibration evidence here (the app's own calibration still counts them; recorded in CLAUDE.md)."""
    d = fixture()
    by_unit = {}
    for log in d["reviewLogs"]:
        by_unit.setdefault(log["studyUnitId"], []).append(log)
    skewed = [u for u, logs in by_unit.items()
              if [l["id"] for l in sorted(logs, key=lambda l: l["reviewedAt"])] != sorted(l["id"] for l in logs)]
    assert skewed, "the fixture must keep a history whose saved order differs from its time order"
    fixes = [e for e in d["eventLogs"] if e["type"] == "RATING_CORRECTED"]
    assert len(fixes) == 1, fixes
    summary, _, files = run([d])
    assert summary["integrity"]["mismatched"] == 0, summary["integrity"]
    unit, fix = fixes[0]["unitId"], fixes[0]
    corrected_log = int(analyze.parse_detail(fix["detail"])["log"])
    recomputed = [l for l in by_unit[unit] if l["logType"] == "RECALL" and l["id"] > corrected_log and l["reviewedAt"] < fix["at"]]
    assert recomputed, "the correction must have recomputed later reviews"
    assert "a rating correction recomputed their prediction" in files["report.md"]
    assert summary["participants"][d["participantId"]]["rating_corrections"] == 1
    assert summary["first_rating_value"]["n"] > 0, summary["first_rating_value"]
    print(f"a clock set back and a corrected rating replay exactly; {len(recomputed)} recomputed predictions left out")


def test_a_correction_after_the_clock_went_back_still_marks_what_it_recomputed():
    """Which predictions a correction recomputed is decided by saved order (the event's `upto`, since 2026-10-04), not
    by the clock: with the clock set back between the reviews and the correction, the rewritten reviews carry later
    times than the correction itself (an outside audit, 2026-10-04). The app's RecomputedPredictionsTest pins the same
    case."""
    logs = [dict(id=1, reviewedAt=100), dict(id=2, reviewedAt=900), dict(id=3, reviewedAt=950), dict(id=4, reviewedAt=520)]

    def marked(detail, at):
        fixes = analyze.corrections({"eventLogs": [dict(type="RATING_CORRECTED", unitId=7, at=at, detail=detail)]})[7]
        return {l["id"] for l in logs if analyze.recomputed_by(l, fixes)}

    assert marked("log=1 upto=3 memory=Good>Hard", 500) == {2, 3}
    assert marked("log=1 memory=Good>Hard", 500) == set(), "an event without the bound falls back to the clock"
    # The real export: its correction carries the bound, and with the clock running normally both rules agree.
    d = fixture()
    fix = next(e for e in d["eventLogs"] if e["type"] == "RATING_CORRECTED")
    kv = analyze.parse_detail(fix["detail"])
    assert "upto" in kv, f"regenerate the fixture: {fix['detail']}"
    unit_logs = [l for l in d["reviewLogs"] if l["studyUnitId"] == fix["unitId"]]
    by_order = {l["id"] for l in unit_logs if analyze.recomputed_by(l, analyze.corrections(d)[fix["unitId"]])}
    by_clock = {l["id"] for l in unit_logs if l["id"] > int(kv["log"]) and l["reviewedAt"] < fix["at"]}
    assert by_order and by_order == by_clock, (by_order, by_clock)
    print(f"a correction marks what it recomputed by saved order ({len(by_order)} reviews), also after the clock went back")


def test_a_corrected_day_recomputes_the_moved_review_too():
    """REVIEW_DATE_CORRECTED (export v16, 2026-10-09) moves a review to another day: its own gap changed, so its own
    prediction was recomputed as well as every later one up to `upto`. The app's RecomputedPredictions applies the
    same rule; a rating correction still leaves the corrected review's prediction alone."""
    logs = [dict(id=1, reviewedAt=100), dict(id=2, reviewedAt=200), dict(id=3, reviewedAt=300), dict(id=4, reviewedAt=400)]

    def marked(kind, detail):
        fixes = analyze.corrections({"eventLogs": [dict(type=kind, unitId=7, at=500, detail=detail)]})[7]
        return {l["id"] for l in logs if analyze.recomputed_by(l, fixes)}

    assert marked("REVIEW_DATE_CORRECTED", "log=2 upto=3 from=200 to=150") == {2, 3}
    assert marked("RATING_CORRECTED", "log=2 upto=3 memory=Good>Hard") == {3}
    d = fixture()
    d = copy.deepcopy(d)
    d["eventLogs"].append(dict(id=99999, at=1, type="REVIEW_DATE_CORRECTED", unitId=7, detail="log=2 upto=3 from=200 to=150"))
    d["eventLogs"].append(dict(id=99998, at=1, type="RATING_CORRECTED", unitId=7, detail="log=2 upto=3 memory=Good>Hard"))
    fixes = analyze.corrections(d)[7]
    assert sum(1 for c in fixes if c[3]) == 1 and sum(1 for c in fixes if not c[3]) == 1
    print("a corrected day marks the moved review and the later ones; a corrected rating only the later ones")


def test_review_time_reports_coverage_before_the_minutes():
    """The learner's optional minutes (export v16): not given (-1) is never read as zero, coverage comes first, and a
    review saved hours after it happened (a backdated rating) is counted apart."""
    base = 1_790_000_000_000
    logs = [dict(id=1, logType="FIRST_STUDY", reviewedAt=base, studyMinutes=60, loggedAt=base),
            dict(id=2, logType="RECALL", reviewedAt=base + 5 * 86_400_000, studyMinutes=-1, loggedAt=base + 5 * 86_400_000,
                 reviewMethods=["Questions"]),
            dict(id=3, logType="RECALL", reviewedAt=base + 9 * 86_400_000, studyMinutes=20,
                 loggedAt=base + 10 * 86_400_000, reviewMethods=["Questions"]),
            dict(id=4, logType="RECALL", reviewedAt=base + 20 * 86_400_000, studyMinutes=30, loggedAt=-1,
                 reviewMethods=["Reading"])]
    t = analyze.review_time(dict(reviewLogs=logs, eventLogs=[dict(type="REVIEW_DATE_CORRECTED", detail="log=3 upto=4")]))
    assert (t["logs"], t["given"], t["first_studies_given"], t["reviews_given"]) == (4, 3, 1, 2), t
    assert t["first_study_median"] == 60 and t["review_median"] == 25, t
    assert t["by_method"]["Questions only"] == dict(n=1, median=20) and t["by_method"]["Reading only"] == dict(n=1, median=30), t
    assert (t["with_save_time"], t["saved_later"], t["date_corrections"]) == (3, 1, 1), t
    empty = analyze.review_time(dict(reviewLogs=[dict(id=1, logType="RECALL", reviewedAt=base)]))
    assert empty["given"] == 0 and empty["review_median"] is None, empty
    print("review time: coverage first, not given is never zero, a backdated save is counted apart")


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
    rows = rows_of(bad)
    flagged = {r.log_id: r for r in rows if r.mismatch}
    assert recalls[3]["id"] in flagged and recalls[7]["id"] in flagged, sorted(flagged)
    assert any(m.startswith("elapsed") for m in flagged[recalls[7]["id"]].mismatch), flagged[recalls[7]["id"]].mismatch
    # The replay follows each review's stored day count, as the app's own replays do (since 2026-10-04), so a count
    # changed by hand also moves the later reviews of its topic; nothing else is flagged.
    unit = recalls[7]["studyUnitId"]
    for lid, r in flagged.items():
        assert lid in (recalls[3]["id"], recalls[7]["id"]) or (r.unit_id == unit and lid > recalls[7]["id"]), (lid, r.mismatch)
    summary, _, _ = run([bad])
    assert summary["integrity"]["mismatched"] == len(flagged) and summary["decisions"][0]["verdict"] == "BUG"
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
    assert any("exports; using the newest" in w for w in warnings), warnings
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
    # Rows a correction recomputed are left out too (`recomputed_by`), whichever side of the activation they fall on.
    fixes = analyze.corrections(d)
    after = sum(1 for l in recalls if l["reviewedAt"] >= activated and 0 <= l["retrievabilityAtReview"] <= 1
                and not analyze.recomputed_by(l, fixes.get(l["studyUnitId"], [])))
    key = "FSRS-6/7@" + d["participantId"]  # a personal set is one learner's (its id is local to the phone)
    assert summary["calibration"][key]["raw"]["n"] == after, (summary["calibration"][key]["raw"]["n"], after)
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


def as_topics(people):
    return {pid: {group: [(value, (group, i)) for i, value in enumerate(values)]
                  for group, values in groups.items()} for pid, groups in people.items()}


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
    wp = analyze.within_participant_difference(as_topics(people))
    assert wp["participants"] == 2 and abs(wp["diff"]) < 1e-12, wp
    specialists = {"A": {"Questions only": [0.1] * 50}, "B": {"Reading only": [-0.1] * 50}}
    assert analyze.within_participant_difference(as_topics(specialists)) is None, "no learner used both: nothing to compare"
    print("the method comparison is made within each learner (a between-person gap is not read as a method effect)")


def test_the_clustered_interval_widens_when_a_topic_s_reviews_move_together():
    # 300 topics x 5 reviews at 90% predicted. Independent outcomes: the design effect is about 1. A topic that is
    # remembered or lost as a block (all 5 reviews alike): about 5, the cluster size, and the interval sqrt(5) wider.
    rng = random.Random(11)
    independent = [(0.9, rng.random() < 0.9, t) for t in range(300) for _ in range(5)]
    blocks = []
    for t in range(300):
        y = rng.random() < 0.9
        blocks += [(0.9, y, t)] * 5
    a, b = analyze.clustered_gap(independent), analyze.clustered_gap(blocks)
    assert 0.7 < a["design_effect"] < 1.4, a
    assert 3.5 < b["design_effect"] < 6.5, b
    assert a["clusters"] == b["clusters"] == 300
    assert a["gap_ci"][0] < a["gap"] < a["gap_ci"][1]
    print(f"clustered interval: design effect {a['design_effect']:.2f} independent, {b['design_effect']:.2f} in blocks of 5")


def test_the_raw_scale_interval_matches_its_real_sampling_spread():
    # 400 learners the defaults describe, 300 evidence reviews each near 90% predicted recall. The delta-method
    # interval must match how much the moment estimate really varies, and cover the truth about 95% of the time.
    # This is also the number behind the app's prior: var(ln k) is about 19 / n at these predictions.
    rng = random.Random(5)
    model = analyze.ym.Fsrs6()
    logs, ses, covered = [], [], 0
    for _ in range(400):
        preds = [rng.uniform(0.86, 0.94) for _ in range(300)]
        est = analyze.moment_scale_ci(preds, [rng.random() < p for p in preds], model)
        logs.append(math.log(est["scale"]))
        ses.append(est["se_log"])
        covered += est["ci"][0] <= 1.0 <= est["ci"][1]
    spread = statistics.stdev(logs)
    assert 0.85 < spread / statistics.fmean(ses) < 1.18, (spread, statistics.fmean(ses))
    assert 0.92 <= covered / 400 <= 0.98, covered
    assert 15 < statistics.fmean(ses) ** 2 * 300 < 24
    print(f"raw-scale interval: real spread {spread:.3f} vs stated {statistics.fmean(ses):.3f}, coverage {covered / 400:.0%}")


def test_generous_rating_shows_in_the_question_score_bands():
    # A learner who calls lapses Hard: every review rated Forgot or Hard got 1 of 5 right, the rest 5 of 5. From
    # ratings alone this learner looks like a slow forgetter; the bands show the Hard answers sitting under 40%.
    d = fixture()
    hard = forgot = 0
    for log in d["reviewLogs"]:
        if log["logType"] != "RECALL":
            continue
        low = log["memoryRating"] in ("Forgot", "Hard")
        log["questionsCorrect"], log["questionsTotal"] = (1, 5) if low else (5, 5)
        hard += log["memoryRating"] == "Hard"
        forgot += log["memoryRating"] == "Forgot"
    summary, _, files = run([d])
    band = summary["question_scores"]["bands"]["under 40%"]
    assert band["n"] == hard + forgot and band["counts"]["Hard"] == hard, band
    assert abs(band["rated_success"] - hard / (hard + forgot)) < 1e-9
    assert "rated a success" in files["report.md"]
    print(f"score bands: {hard} Hard answers with 1 of 5 right show as {band['rated_success']:.0%} 'rated a success' under 40%")


def test_d2_and_d7_need_an_interval_that_excludes_zero():
    # Amended 2026-10-03, before any pilot data: a gap past the threshold is LOOK only when its 95% interval excludes 0,
    # otherwise WAIT (not enough evidence), so noise cannot pass for a finding.
    base = dict(integrity=dict(mismatched=0, consistency_issues=0), participants={}, adherence={}, understanding={},
                reminders={}, first_review={}, question_scores={}, pooled_fit={})

    def verdict(rule, **extra):
        s = dict(base, **extra)
        return next(x["verdict"] for x in analyze.decide(s, []) if x["id"] == rule)

    def cal(observed, ci):
        return {"FSRS-6/0": dict(raw={}, calibrated=dict(n=400, observed=observed, predicted=0.90, clustered=dict(gap_ci=ci)))}

    assert verdict("D2", calibration=cal(0.92, (-0.01, 0.05))) == "OK"
    assert verdict("D2", calibration=cal(0.83, (-0.10, -0.04))) == "LOOK"
    assert verdict("D2", calibration=cal(0.83, (-0.15, 0.01))) == "WAIT"

    def d7(diff, ci):
        return verdict("D7", method_residuals={"Questions only": dict(n=150, mean=0.0), "Reading only": dict(n=150, mean=0.0)},
                       method_within_participant=dict(diff=diff, ci=ci, participants=3))

    assert d7(0.02, (-0.05, 0.09)) == "OK"
    assert d7(0.07, (0.01, 0.13)) == "LOOK"
    assert d7(0.07, (-0.01, 0.15)) == "WAIT"
    rng = random.Random(3)
    noisy = {p: {"Questions only": [rng.gauss(0.0, 0.3) for _ in range(40)],
                 "Reading only": [rng.gauss(0.0, 0.3) for _ in range(40)]} for p in "ABC"}
    wp = analyze.within_participant_difference(as_topics(noisy))
    assert wp["ci"][0] < wp["diff"] < wp["ci"][1] and 0.03 < wp["se"] < 0.05, wp
    print("D2 and D7: a gap past the threshold is LOOK only when its interval excludes 0, otherwise WAIT")


def test_subjects_and_the_spread_between_learners_are_reported():
    # Three copies of one learner: no spread beyond noise, so no prior the data could support; and the per-subject
    # calibration table is there.
    a = fixture()
    copies = []
    for i, pid in enumerate(("YD-TEST-AAA1", "YD-TEST-BBB2", "YD-TEST-CCC3")):
        c = copy.deepcopy(a)
        c["participantId"] = pid
        copies.append(c)
    summary, _, files = run(copies)
    sp = summary["scale_spread"]
    assert sp["learners"] == 3 and sp["tau"] == 0.0 and sp["prior_reviews"] is None, sp
    assert summary["by_subject"] and "By subject:" in files["report.md"]
    assert all(p.get("scale_raw_ci") for p in summary["participants"].values())
    print(f"per-subject calibration ({len(summary['by_subject'])} subjects) and the between-learner spread are reported")


def with_reminders(d, missing_days=(), late_days=(), off_days=()):
    """The fixture with a planted reminder history (export v13): two alarms a day for the 20 days before the export,
    none on `missing_days`, the evening one 30 minutes late on `late_days`, reminders switched off from noon to noon
    across `off_days` (two consecutive days), a test reminder and one safety-net catch-up. Days count back from the
    export day."""
    import datetime as dt
    d = copy.deepcopy(d)
    tz, _ = analyze.zone_of(d)
    export_day = dt.datetime.fromtimestamp(d["exportedAt"] / 1000, tz).replace(hour=0, minute=0, second=0, microsecond=0)

    def at(days_back, hour, minute=0):
        return int((export_day - dt.timedelta(days=days_back)).replace(hour=hour, minute=minute).timestamp() * 1000)

    events = [e for e in d.get("eventLogs") or [] if e["type"] not in ("REMINDER_FIRED", "REMINDERS_ON", "REMINDERS_OFF")]
    for k in range(1, 21):
        if k in missing_days or k in off_days:
            continue
        for hour, slot in ((10, "primary"), (20, "secondary")):
            late = 1800 if (k in late_days and slot == "secondary") else 2
            events.append(dict(at=at(k, hour) + late * 1000, type="REMINDER_FIRED", unitId=None,
                               detail=f"slot={slot} scheduled={at(k, hour)} late_s={late} exact=1 idle=1 saver=0 bucket=10 outcome=posted due=3"))
    if off_days:
        events.append(dict(at=at(max(off_days), 12), type="REMINDERS_OFF", unitId=None, detail=None))
        events.append(dict(at=at(min(off_days), 12), type="REMINDERS_ON", unitId=None, detail=None))
    events.append(dict(at=at(3, 9), type="REMINDER_FIRED", unitId=None,
                       detail=f"slot=test scheduled={at(3, 9) - 99_000_000} late_s=99000 exact=1 idle=0 saver=0 bucket=10 outcome=test"))
    events.append(dict(at=at(4, 16), type="NOTIF_SHOWN", unitId=None, detail="source=safety_worker due=3"))
    # one posted reminder that was tapped five minutes later, and a widget tap
    events.append(dict(at=at(2, 10) + 2000, type="NOTIF_SHOWN", unitId=None, detail="source=alarm due=3"))
    events.append(dict(at=at(2, 10, 5), type="APP_OPENED", unitId=None, detail="from=notification"))
    events.append(dict(at=at(6, 13), type="APP_OPENED", unitId=None, detail="from=widget"))
    d["eventLogs"] = sorted(events, key=lambda e: e["at"])
    d["exportVersion"] = 13
    d["reminderHealth"] = dict(notificationsAllowed=True, reminderChannelOn=True, exactAlarmsAllowed=True, fullScreenAllowed=True,
                               batteryOptimizationIgnored=False, backgroundRestricted=False, standbyBucket=10, powerSaveMode=False)
    return d


def test_reminder_delivery_finds_the_days_a_phone_never_reminded():
    d = with_reminders(fixture(), missing_days=(15, 5), late_days=(7,), off_days=(10, 9))
    summary, _, files = run([d])
    r = summary["reminders"][d["participantId"]]
    assert r["days"] == 18, r  # 20 days, less the two with reminders switched off
    assert len(r["missed_days"]) == 2, r
    assert r["late"] == 1 and r["fires"] == 2 * 16, r  # the test reminder is not counted
    assert r["safety_net"] == 1 and r["health_problems"] == ["battery optimization on"], r
    assert (r["posted"], r["opened"], r["widget_opens"]) == (2, 1, 1), r  # the safety-net one was not tapped
    d11 = next(x for x in summary["decisions"] if x["id"] == "D11")
    assert d11["verdict"] == "LOOK" and "88.9% of days" in d11["result"], d11
    assert "days without a reminder" in files["report.md"] and r["missed_days"][0] in files["report.md"]
    clean = with_reminders(fixture())
    summary, _, _ = run([clean])
    assert next(x for x in summary["decisions"] if x["id"] == "D11")["verdict"] == "OK"
    none, _, files = run([fixture()])  # a v13 export without a single fire: WAIT, and the report says why
    assert next(x for x in none["decisions"] if x["id"] == "D11")["verdict"] == "WAIT"
    assert "No reminder alarm fired" in files["report.md"]
    old = copy.deepcopy(fixture())
    old["exportVersion"] = 12
    old.pop("reminderHealth", None)
    summary, _, files = run([old])
    assert next(x for x in summary["decisions"] if x["id"] == "D11")["verdict"] == "WAIT"
    assert "predate it" in files["report.md"]
    print("reminder delivery: missed days, a late alarm, days switched off and a safety-net catch-up are told apart")


def with_daily_load(d, days=40, missing=(33,), backlog_from=20):
    """The fixture with a planted daily-load history (export v15): one DAILY_SNAPSHOT at 07:00 on each of the `days`
    days before the export, none on `missing` (days back), a backlog that starts `backlog_from` days into the span and
    grows by 2 a day with half of it held back by the limit, a second snapshot on one day that must not count, and two
    builds (4 from the first day, 5 from day 25)."""
    import datetime as dt
    d = copy.deepcopy(d)
    tz, _ = analyze.zone_of(d)
    export_day = dt.datetime.fromtimestamp(d["exportedAt"] / 1000, tz).replace(hour=0, minute=0, second=0, microsecond=0)

    def at(days_back, hour):
        return int((export_day - dt.timedelta(days=days_back)).replace(hour=hour).timestamp() * 1000)

    events = [e for e in d.get("eventLogs") or [] if e["type"] not in ("DAILY_SNAPSHOT", "APP_VERSION")]
    for k in range(days):  # k = days into the span; days_back = days - k
        if days - k in missing:
            continue
        overdue = 2 * max(0, k - backlog_from)
        events.append(dict(at=at(days - k, 7), type="DAILY_SNAPSHOT", unitId=None,
                           detail=f"active={100 + k} rated={90 + k} due={30 + overdue} overdue={overdue} "
                                  f"oldest_overdue_days={max(0, k - backlog_from)} first=2 offered={min(50, 30 + overdue)} "
                                  f"held={overdue // 2} done=0 deferred=1 limit=50"))
    events.append(dict(at=at(days - 30, 21), type="DAILY_SNAPSHOT", unitId=None,  # a second writer that day: ignored
                       detail="active=1 rated=1 due=999 overdue=999 oldest_overdue_days=999 first=0 offered=0 held=999 done=0 deferred=0 limit=10"))
    events.append(dict(at=at(days, 6), type="APP_VERSION", unitId=None, detail="code=4 name=1.1 previous=0"))
    events.append(dict(at=at(days - 25, 13), type="APP_VERSION", unitId=None, detail="code=5 name=1.2 previous=4"))
    d["eventLogs"] = sorted(events, key=lambda e: e["at"])
    d["exportVersion"] = 15
    return d


def test_the_daily_load_and_the_app_builds_are_reported():
    d = with_daily_load(fixture())
    summary, _, files = run([d])
    pid = d["participantId"]
    load = summary["load"][pid]
    assert (load["days"], load["span_days"], load["days_without"]) == (39, 40, 1), load
    assert load["overdue_max"] == 2 * 19 and load["oldest_overdue_max"] == 19, load  # the 999 day is not counted
    assert load["days_with_backlog"] == 19 and load["days_held"] == 19 and load["held_max"] == 19, load
    assert load["overdue_first30"] < load["overdue_last30"] and load["overdue_trend_per_30d"] > 0, load
    assert load["limits"] == [50] and load["active_last"] == 139, load
    builds = summary["app_versions"][pid]
    assert [(b["code"], b["name"], b["previous"]) for b in builds] == [(4, "1.1", 0), (5, "1.2", 4)], builds
    report = files["report.md"]
    assert "Daily load: was the plan keeping up?" in report and "39 (40)" in report, report[-3000:]
    assert "5 (1.2) from" in report
    assert "app_builds" in files["participants.csv"].splitlines()[0]
    # A file from before v15 says why there is no load table; a v15 file without snapshots says what writes one.
    old = copy.deepcopy(fixture())
    old["exportVersion"] = 14
    _, _, files = run([old])
    assert "these files predate it" in files["report.md"].split("Daily load")[1][:200]
    none = copy.deepcopy(fixture())
    none["exportVersion"] = 15
    none["eventLogs"] = [e for e in none.get("eventLogs") or [] if e["type"] != "DAILY_SNAPSHOT"]
    _, _, files = run([none])
    assert "no snapshots in these files" in files["report.md"]
    print("the daily load (a growing backlog, days held back, a day without a snapshot) and each build's first day are reported")


def test_days_are_counted_in_the_zone_the_phone_was_in():
    """FSRS-6 counts local calendar days in the zone the phone is in at the review. Read in another zone (here +07:00,
    where the fixture's 20:xx Tehran reviews fall on both sides of midnight), the counts differ: each is flagged on
    its own row, and the predictions and intervals still replay exactly, because the chain follows the stored counts
    as the app's replays do. With the phone's own TIME_ZONE record (export v15) every count matches again."""
    d = fixture()
    moved = copy.deepcopy(d)
    moved["environment"]["timeZoneId"] = "Asia/Bangkok"
    moved["environment"]["utcOffsetMinutesAtExport"] = 420
    rows = rows_of(moved)
    flagged = [r for r in rows if r.mismatch]
    assert flagged, "some reviews must cross midnight at +07:00"
    assert all(all(m.startswith("elapsed") for m in r.mismatch) for r in flagged), [r.mismatch for r in flagged][:3]
    first = min(int(l["reviewedAt"]) for l in moved["reviewLogs"])
    moved["eventLogs"] = sorted((moved.get("eventLogs") or []) + [
        dict(at=first - 1, type="TIME_ZONE", unitId=None, detail="zone=Asia/Tehran offset=+03:30 previous=none"),
        dict(at=int(moved["exportedAt"]) - 1, type="TIME_ZONE", unitId=None, detail="zone=Asia/Bangkok offset=+07:00 previous=Asia/Tehran"),
    ], key=lambda e: e["at"])
    summary, _, files = run([moved])
    assert summary["integrity"]["mismatched"] == 0, summary["integrity"]
    assert "time zones on this phone: Asia/Tehran from" in files["report.md"]
    print(f"days are counted in the zone the phone was in ({len(flagged)} reviews read in another zone, none with its record)")


def test_the_backlog_trend_compares_snapshots_taken_at_the_same_point_of_the_day():
    """An outside audit's case (2026-10-04): every day starts 30 reviews behind and ends with all of them done. Sampled
    at night for 30 days and in the morning for 30, the trend through every snapshot reads +22.5 a month where there is
    none; through the snapshots taken before any review that day it is flat."""
    import datetime as dt
    d = copy.deepcopy(fixture())
    tz, _ = analyze.zone_of(d)
    export_day = dt.datetime.fromtimestamp(d["exportedAt"] / 1000, tz).replace(hour=0, minute=0, second=0, microsecond=0)

    def at(days_back, hour):
        return int((export_day - dt.timedelta(days=days_back)).replace(hour=hour).timestamp() * 1000)

    def snapshots(with_done):
        out = []
        for k in range(60):
            night = k < 30
            overdue, done = (0, 30) if night else (30, 0)
            out.append(dict(at=at(60 - k, 22 if night else 7), type="DAILY_SNAPSHOT", unitId=None,
                            detail=f"active=500 rated=480 due={overdue + 5} overdue={overdue} oldest_overdue_days={1 if overdue else 0} "
                                   f"first=0 offered={overdue + 5} held=0" + (f" done={done}" if with_done else "")
                                   + f" deferred=0 limit=50 source={'app' if night else 'worker'}"))
        return out

    base = [e for e in d.get("eventLogs") or [] if e["type"] != "DAILY_SNAPSHOT"]
    d["exportVersion"] = 15
    d["eventLogs"] = sorted(base + snapshots(True), key=lambda e: e["at"])
    summary, _, files = run([d])
    load = summary["load"][d["participantId"]]
    assert (load["trend_basis"], load["trend_days"]) == ("before any review that day", 30), load
    assert abs(load["overdue_trend_per_30d"]) < 1e-9, load
    assert load["sources"] == {"app": 30, "worker": 30}, load
    # Without `done`, the snapshots cannot be told apart, and the trend through all of them shows the artefact, labelled.
    d["eventLogs"] = sorted(base + snapshots(False), key=lambda e: e["at"])
    summary, _, files = run([d])
    load = summary["load"][d["participantId"]]
    assert load["trend_basis"] == "all snapshots, mixed times of day" and abs(load["overdue_trend_per_30d"] - 22.5) < 0.1, load
    assert "mixed times of day" in files["report.md"]
    print("the backlog trend is drawn through comparable snapshots (flat), not through mixed ones (+22.5 a month)")


def test_settings_changes_are_listed_with_their_dates():
    d = copy.deepcopy(fixture())
    d["eventLogs"] = sorted((d.get("eventLogs") or []) + [
        dict(at=int(d["exportedAt"]) - 5 * 86_400_000, type="SETTINGS_CHANGED", unitId=None, detail="key=daily_review_limit old=50 new=30"),
        dict(at=int(d["exportedAt"]) - 2 * 86_400_000, type="SETTINGS_CHANGED", unitId=None, detail="key=desired_retention old=0.90 new=0.85"),
    ], key=lambda e: e["at"])
    summary, _, files = run([d])
    changes = summary["settings_changes"][d["participantId"]]
    assert [(c["key"], c["old"], c["new"]) for c in changes] == [("daily_review_limit", "50", "30"), ("desired_retention", "0.90", "0.85")]
    assert "settings changed:" in files["report.md"] and "daily_review_limit 50→30" in files["report.md"]
    assert "settings_changes" in files["participants.csv"].splitlines()[0]
    print("settings changes are listed with their dates")


def test_d3_says_what_it_checks():
    """D3's rule since 2026-09-24: LOOK when the first review is more than 7 points below its prediction, or more than 5
    above it with over 95% recalled (so early it was nearly wasted). The question used to give only the band; an outside
    audit (2026-10-04) found an OK at +8.3 points. The rule stands; the question and PILOT.md now say it."""
    def d3(n, observed, predicted):
        s = dict(integrity=dict(mismatched=0, consistency_issues=0), participants={}, adherence={}, understanding={},
                 reminders={}, first_review={"Hard": dict(n=n, observed=observed, predicted=predicted)}, question_scores={},
                 pooled_fit={}, calibration={})
        return next(x for x in analyze.decide(s, []) if x["id"] == "D3-Hard")

    assert d3(59, 0.80, 0.80)["verdict"] == "WAIT"
    assert d3(60, 0.72, 0.80)["verdict"] == "LOOK", "8 points below: too late"
    assert d3(60, 0.74, 0.80)["verdict"] == "OK", "6 below"
    assert d3(604, 0.8957, 0.8124)["verdict"] == "OK", "the audit's +8.3 at 89.6%: early, not wasted"
    assert d3(60, 0.96, 0.90)["verdict"] == "LOOK", "+6 at 96%: so early it was nearly wasted"
    assert d3(60, 0.95, 0.89)["verdict"] == "OK", "95% itself is not over 95%"
    assert "95%" in d3(60, 0.9, 0.9)["question"]
    print("D3's question states both sides of its rule")


def test_a_backup_is_refused_with_an_explanation():
    backup = {"backupVersion": 9, "studyUnits": [], "reviewLogs": [], "subjects": []}
    backup.pop("reviewLogs")
    summary, warnings, _ = run([backup])
    assert summary is None
    assert any("BACKUP" in w for w in warnings), warnings
    print("a full backup (with titles and notes) is refused, and the warning says what to send instead")


def test_a_file_saved_with_a_byte_order_mark_still_loads():
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "export.json")
        with open(p, "w", encoding="utf-8-sig") as f:  # what Windows Notepad writes
            json.dump(fixture(), f)
        warnings = []
        exports = analyze.load_exports([p], warnings.append)
        assert len(exports) == 1 and not warnings, warnings
    print("an export re-saved with a byte-order mark still loads")


def test_fitted_weights_saved_with_a_byte_order_mark_still_load_in_the_simulation():
    import simulate
    weights = [0.2] * 21
    with tempfile.TemporaryDirectory() as tmp:
        p = os.path.join(tmp, "fitted_weights.json")
        with open(p, "w", encoding="utf-8-sig") as f:  # what Windows Notepad can write
            json.dump({"weights": weights}, f)
        assert simulate.load_weights(p) == weights
    print("fitted_weights.json re-saved with a byte-order mark still loads in simulate.py")


def test_the_summary_prints_on_a_console_that_cannot_encode_it():
    import subprocess
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ, PYTHONIOENCODING="cp1252", PYTHONUTF8="0")
        r = subprocess.run([sys.executable, os.path.join(HERE, "analyze.py"), FIXTURE, "--out", os.path.join(tmp, "out"), "--no-fit"],
                           capture_output=True, env=env)
        assert r.returncode == 0, r.stderr.decode("utf-8", "replace")[-600:]
        assert b"Report:" in r.stdout
    print("the summary prints even where stdout is cp1252 (a redirected Windows console)")


def test_a_malformed_export_is_set_aside_and_the_others_are_analysed():
    # An outside audit (2026-10-03): one file with its version as text, or a null review list, stopped the whole batch
    # with a TypeError. Each is now set aside with a reason, and the other participants are analysed.
    good = fixture()
    bad = []
    for i, (key, value) in enumerate((("exportVersion", "12"), ("reviewLogs", None), ("studyUnits", [1, 2]),
                                       ("participantId", 5), ("exportedAt", "yesterday"))):
        d = copy.deepcopy(good)
        d["participantId"] = f"YD-TEST-BAD{i}"
        d[key] = value
        bad.append(d)
    summary, warnings, _ = run([good] + bad)
    assert set(summary["participants"]) == {good["participantId"]}, summary["participants"].keys()
    assert sum("skipped, and the other files are analysed without it" in w for w in warnings) == len(bad), warnings
    assert summary["integrity"]["checked"] == len(good["reviewLogs"])
    print(f"{len(bad)} malformed exports set aside with a reason; the good one analysed")


def test_history_missing_from_the_newest_export_is_reported():
    # The newest export is analysed, but it is no longer assumed to hold everything an older one held (an outside
    # audit, 2026-10-03): a topic deleted for good between week 2 and week 8 takes its logs with it.
    older = fixture()
    newer = copy.deepcopy(older)
    newer["exportedAt"] += 40 * 86_400_000
    # A topic with two reviews (found, not assumed: the fixture is regenerated when the export changes); its events stay
    # behind, as they do when a topic is purged.
    per_topic = {}
    for l in older["reviewLogs"]:
        per_topic[l["studyUnitId"]] = per_topic.get(l["studyUnitId"], 0) + 1
    gone = min(u for u, n in per_topic.items() if n == 2)
    lost = [l for l in newer["reviewLogs"] if l["studyUnitId"] == gone]
    newer["reviewLogs"] = [l for l in newer["reviewLogs"] if l["studyUnitId"] != gone]
    newer["studyUnits"] = [u for u in newer["studyUnits"] if u["id"] != gone]
    summary, warnings, files = run([newer, older])
    info = summary["participants"][older["participantId"]]
    assert info["older_exports"] == 1 and info["logs_missing_from_newest"] == len(lost) == 2, info
    assert info["topics_missing_from_newest"] == 1, info
    assert any(f"lacks {len(lost)} review logs and 1 topics" in w for w in warnings), warnings
    assert "the newest export lacks 2 review logs and 1 topics" in files["report.md"]
    assert summary["integrity"]["checked"] == len(newer["reviewLogs"]) and summary["integrity"]["mismatched"] == 0
    # Nothing lost: said so, and nothing reported missing.
    same = copy.deepcopy(older)
    same["exportedAt"] += 86_400_000
    summary, warnings, _ = run([older, same])
    assert summary["participants"][older["participantId"]]["logs_missing_from_newest"] == 0
    assert any("still holds every log and topic" in w for w in warnings), warnings
    print("history missing from the newest export is counted and reported; a complete newer export says so")


def test_pooled_validation_follows_saved_order_after_clock_rollback():
    """A later rating must stay held out even when the phone records an earlier wall time.
    Stored elapsed days keep model inputs identical; only the split is being challenged."""
    origin, day = 1_790_000_000_000, 86_400_000
    logs = [dict(id=i + 1, studyUnitId=1, reviewedAt=origin + i * 2 * day,
                 memoryRating=rating, logType="FIRST_STUDY" if i == 0 else "RECALL",
                 schedulerVersion="FSRS-6", elapsedDays=float(i * 2))
            for i, rating in enumerate(["Good", "Good", "Hard", "Good", "Forgot"])]
    export = analyze.Export("synthetic", dict(studyUnits=[dict(id=1, studiedAt=origin)], reviewLogs=logs),
                            "learner", origin + 10 * day, analyze.dt.timezone.utc, "UTC")

    def partition(e):
        cuts = analyze.split_review_ids([e])
        hist = [(p, u, l, un, tz, cuts[p]) for p, u, l, un, tz in analyze.histories([e])]
        return analyze.predictions(hist, analyze.ym.DEFAULT_WEIGHTS)

    before = partition(export)
    assert len(before[0]) == 3 and [y for _, y in before[1]] == [False]
    changed = copy.deepcopy(export)
    changed.data["reviewLogs"][-1]["reviewedAt"] -= 30 * day
    after = partition(changed)
    assert after == before, "clock rollback moved a later rating into training and exposed a future state to the fit"
    print("pooled validation keeps later saved reviews held out despite a clock rollback")


def test_the_pooled_refit_must_meet_the_app_s_conditions():
    # The pooled refit used to be judged on z alone. The app also refuses a set whose first-rating grades are out of
    # order, or that would schedule longer than the published defaults (Fsrs6Optimizer.keepsGradeOrder, .lengthening).
    copies = []
    for pid in ("YD-TEST-POOL1", "YD-TEST-POOL2", "YD-TEST-POOL3"):
        c = fixture()
        c["participantId"] = pid
        copies.append(c)
    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for i, d in enumerate(copies):
            paths.append(os.path.join(tmp, f"e{i}.json"))
            with open(paths[-1], "w", encoding="utf-8") as f:
                json.dump(d, f)
        exports = analyze.load_exports(paths, lambda w: None)
    hist = [(p, u, l, un, tz, 1 << 62) for p, u, l, un, tz in analyze.histories(exports)]
    base = list(analyze.ym.DEFAULT_WEIGHTS)
    assert analyze.pooled_lengthening(hist, base) == 1.0
    less, more = list(base), list(base)
    less[8] -= 0.5
    more[8] += 0.5
    assert analyze.pooled_lengthening(hist, less) < 1.0 < analyze.pooled_lengthening(hist, more)

    def refit_to(weights):
        # Stand in for the optimiser: the "fit" lands on these weights, and the rest of pooled_fit judges them.
        x = [math.log(weights[i]) for i in analyze.FIT_INDICES]
        saved = analyze.nelder_mead
        analyze.nelder_mead = lambda f, x0, **kw: (x, f(x))
        try:
            return analyze.pooled_fit(exports)
        finally:
            analyze.nelder_mead = saved

    inverted = list(base)
    inverted[1], inverted[2] = 12.5, 0.14  # the audit's set: S0(Hard) 12.5 days over S0(Good) 0.14
    pf = refit_to(inverted)
    assert pf["fitted"] and not pf["grade_order_ok"] and not pf["better"], {k: pf[k] for k in ("z", "grade_order_ok", "better")}
    pf = refit_to(more)
    assert pf["fitted"] and pf["grade_order_ok"] and pf["lengthening"] > 1.0 and not pf["better"], pf["lengthening"]
    d5 = next(x for x in analyze.decide(dict(integrity=dict(mismatched=0, consistency_issues=0), participants={}, adherence={},
                                             understanding={}, reminders={}, first_review={}, question_scores={},
                                             pooled_fit=pf), []) if x["id"] == "D5")
    assert d5["verdict"] == "OK" and "lengthening" in d5["result"], d5
    print(f"the pooled refit meets the app's conditions: inverted grades and a set that lengthens (x{pf['lengthening']:.2f}) are refused")


def test_each_learner_s_personal_set_is_its_own_calibration_group():
    # A personal set's id is local to one phone: two learners' "set 7" are different weights and were pooled as one
    # (an outside audit, 2026-10-03). Each is its own group now, judged by D2 on its own, and the app's scale on the
    # set in use is reported beside the defaults'.
    pair = []
    for pid in ("YD-TEST-SETA", "YD-TEST-SETB"):
        d = fixture()
        d["participantId"] = pid
        recalls = sorted((l for l in d["reviewLogs"] if l["logType"] == "RECALL"), key=lambda l: (l["reviewedAt"], l["id"]))
        activated = recalls[len(recalls) // 3]["reviewedAt"]
        d["memoryParameterSets"] = [{"id": 7, "createdAt": activated, "status": "ACTIVE", "weights": list(analyze.ym.DEFAULT_WEIGHTS),
                                     "activatedAt": activated, "retiredAt": None}]
        d["policy"]["activeParameterSetId"] = 7
        for log in d["reviewLogs"]:
            log["parameterSetId"] = 7
        pair.append(d)
    summary, _, files = run(pair)
    assert {"FSRS-6/7@YD-TEST-SETA", "FSRS-6/7@YD-TEST-SETB"} <= set(summary["calibration"]), summary["calibration"].keys()
    assert "the personal set of YD-TEST-SETA" in files["report.md"]
    for pid in ("YD-TEST-SETA", "YD-TEST-SETB"):
        k = summary["participants"][pid].get("scale_active_set")
        assert k and k["set"] == 7 and k["n"] > 0, k

    def d2(calibration):
        s = dict(integrity=dict(mismatched=0, consistency_issues=0), participants={}, adherence={}, understanding={},
                 reminders={}, first_review={}, question_scores={}, pooled_fit={}, calibration=calibration)
        return next(x for x in analyze.decide(s, []) if x["id"] == "D2")

    good = dict(raw={}, calibrated=dict(n=400, observed=0.91, predicted=0.90, clustered=dict(gap_ci=(-0.02, 0.04))))
    off = dict(raw={}, calibrated=dict(n=350, observed=0.65, predicted=0.90, clustered=dict(gap_ci=(-0.33, -0.17))))
    assert d2({"FSRS-6/0": good})["verdict"] == "OK"
    verdict = d2({"FSRS-6/0": good, "FSRS-6/1@YD-TEST-SETA": off})
    assert verdict["verdict"] == "LOOK" and "YD-TEST-SETA" in verdict["result"], verdict
    print("each learner's personal set is its own calibration group; D2 flags one 25 points off")


def test_calibration_window_follows_saved_ids_and_filters_before_limiting():
    # Use the real export's Row type and replay, then deliberately reverse its wall-clock order.
    row = next(r for r in rows_of(fixture()) if r.is_recall and r.predicted is not None)
    old = [replace(row, participant="owner", log_id=i + 1, at=100_000 + i, parameter_set=0,
                   scheduler_version="FSRS-6", predicted=.9, elapsed_days=10., previous_interval=10.,
                   rating="Good", recomputed=False) for i in range(analyze.ym.CAL_WINDOW)]
    recent = [replace(r, log_id=r.log_id + analyze.ym.CAL_WINDOW, at=1_000 + i,
                      rating="Good" if i % 100 < 80 else "Forgot") for i, r in enumerate(old)]
    invalid = [replace(recent[0], log_id=10_000 + i, **kw) for i, kw in enumerate([
        dict(participant="another phone"), dict(parameter_set=7), dict(log_type="FIRST_STUDY"),
        dict(scheduler_version="FSRS-5"), dict(recomputed=True), dict(predicted=None),
        dict(predicted=1.01), dict(predicted=-.1), dict(predicted=float("nan")),
        dict(elapsed_days=None), dict(elapsed_days=1.), dict(previous_interval=100.), dict(at=0),
    ])]
    rows = old + recent + invalid
    for ordered in (rows, rows[::-1], sorted(rows, key=lambda r: r.at)):
        selected = analyze.calibration_window(ordered, "owner", 0, 1_000)
        assert [r.log_id for r in selected] == [r.log_id for r in recent]
        scale = analyze.ym.calibration_scale([r.predicted for r in selected], [r.success for r in selected])
        expected = analyze.ym.calibration_scale([.9] * len(recent), [r.success for r in recent])
        assert abs(scale - expected) < 1e-12 and scale < 1., scale
    print("calibration uses the latest 600 saved eligible ids after clock rollback, in every display order")


if __name__ == "__main__":
    test_calibration_window_follows_saved_ids_and_filters_before_limiting()
    test_real_export_replays_exactly()
    test_a_clock_set_back_and_a_corrected_rating_replay_exactly()
    test_a_correction_after_the_clock_went_back_still_marks_what_it_recomputed()
    test_a_corrected_day_recomputes_the_moved_review_too()
    test_review_time_reports_coverage_before_the_minutes()
    test_a_tampered_interval_is_caught()
    test_a_tampered_prediction_and_elapsed_are_caught()
    test_several_participants_and_duplicates()
    test_rows_a_correction_recomputed_are_not_calibration_evidence()
    test_calibration_slope_and_intercept_recover_a_planted_miscalibration()
    test_method_comparison_is_made_within_each_learner()
    test_the_clustered_interval_widens_when_a_topic_s_reviews_move_together()
    test_the_raw_scale_interval_matches_its_real_sampling_spread()
    test_generous_rating_shows_in_the_question_score_bands()
    test_d2_and_d7_need_an_interval_that_excludes_zero()
    test_subjects_and_the_spread_between_learners_are_reported()
    test_reminder_delivery_finds_the_days_a_phone_never_reminded()
    test_the_daily_load_and_the_app_builds_are_reported()
    test_days_are_counted_in_the_zone_the_phone_was_in()
    test_the_backlog_trend_compares_snapshots_taken_at_the_same_point_of_the_day()
    test_settings_changes_are_listed_with_their_dates()
    test_d3_says_what_it_checks()
    test_a_backup_is_refused_with_an_explanation()
    test_a_file_saved_with_a_byte_order_mark_still_loads()
    test_fitted_weights_saved_with_a_byte_order_mark_still_load_in_the_simulation()
    test_the_summary_prints_on_a_console_that_cannot_encode_it()
    test_a_malformed_export_is_set_aside_and_the_others_are_analysed()
    test_history_missing_from_the_newest_export_is_reported()
    test_pooled_validation_follows_saved_order_after_clock_rollback()
    test_the_pooled_refit_must_meet_the_app_s_conditions()
    test_each_learner_s_personal_set_is_its_own_calibration_group()
    print("all checks passed")

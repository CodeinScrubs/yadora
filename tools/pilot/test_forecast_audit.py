"""Prospective evidence survives mutable replay; no future reconstruction is presented as a historic prediction."""
import copy
import json
import math
import os
import tempfile
import unittest
from types import SimpleNamespace

import forecast_audit as fa


def snapshot(log=1, **changes):
    fields = dict(log=log, v=1, at=1000, type="RECALL", model="FSRS-6", set=0, policy="YADORA-7", zone="Asia/Tehran",
                  p=.8, elapsed=3.0, stability=10.0, difficulty=5.0, previousInterval=3.0, previousReview=0,
                  memoryDue=900, repairDue=-1, effectiveDue=900, deferredUntil=-1, dueContext="MEMORY_DUE",
                  retention=.9, scale=1.0, memory="Good", understanding="Clear", session="TOPIC", outcome="SUBJECTIVE_POST_STUDY")
    fields.update(changes)
    return dict(id=log, at=fields["at"], type="REVIEW_FORECAST", unitId=7,
                detail=" ".join(f"{k}={v}" for k, v in fields.items()))


def export(events=None, rating="Good", uid=7, participant="YD-TEST-AAAA"):
    return SimpleNamespace(participant=participant, data=dict(
        reviewLogs=[dict(id=1, studyUnitId=uid, reviewedAt=1000, memoryRating=rating, understandingRating="Clear")],
        eventLogs=[snapshot()] if events is None else events))


class ForecastAuditTest(unittest.TestCase):
    def test_replayed_predictions_and_corrected_ratings_cannot_change_original_evidence(self):
        e = export(rating="Forgot")
        e.data["reviewLogs"][0].update(retrievabilityAtReview=.2, parameterSetId=17, schedulerVersion="future-model")
        rows, excluded, issues = fa.observations(e)
        self.assertFalse(issues)
        self.assertEqual(.8, rows[0]["p"])
        self.assertEqual("Good", rows[0]["memory"])
        self.assertEqual(0, rows[0]["set"])
        m = fa.metrics(rows)
        self.assertAlmostEqual(.04, m["brier"])
        self.assertAlmostEqual(-math.log(.8), m["log_loss"])
        self.assertEqual(1, m["answer_changed"])

    def test_legacy_missing_and_purged_forecasts_are_not_backfilled_or_scored(self):
        rows, excluded, issues = fa.observations(export(events=[]))
        self.assertEqual([], rows)
        self.assertEqual(1, excluded["missing_forecast"])
        e = export(events=[snapshot(log=2)])
        rows, excluded, issues = fa.observations(e)
        self.assertEqual([], rows)
        self.assertEqual(1, excluded["orphaned_after_purge_or_legacy_undo"])

    def test_merge_joins_on_log_id_and_uses_current_topic_for_clustering(self):
        rows, _, issues = fa.observations(export(uid=99))
        self.assertEqual(99, rows[0]["topic"])
        self.assertFalse(issues)

    def test_first_study_and_same_day_are_explicitly_excluded(self):
        for change, reason in [(dict(type="FIRST_STUDY", dueContext="FIRST_STUDY"), "first_study"), (dict(elapsed=0), "same_day")]:
            rows, excluded, issues = fa.observations(export(events=[snapshot(**change)]))
            self.assertFalse(issues)
            self.assertEqual([], rows)
            self.assertEqual(1, excluded[reason])

    def test_nonfinite_invalid_duplicate_and_newer_snapshots_fail_closed(self):
        for change in [dict(p="nan"), dict(p=1.1), dict(stability=0), dict(memory="unknown"), dict(v=2), dict(set=-1), dict(log=1.5), dict(scale=0)]:
            rows, _, issues = fa.observations(export(events=[snapshot(**change)]))
            self.assertEqual([], rows, change)
            self.assertEqual(1, len(issues), change)
        rows, _, issues = fa.observations(export(events=[snapshot(), snapshot(p=.3)]))
        self.assertEqual([], rows)
        self.assertEqual(1, len(issues))
        with self.assertRaises(ValueError):
            fa.parse(snapshot()["detail"] + " p=.1")

    def test_duplicate_saved_log_ids_are_not_silently_joined_to_the_last_row(self):
        e = export()
        e.data["reviewLogs"].append(dict(e.data["reviewLogs"][0], studyUnitId=99, memoryRating="Forgot"))
        rows, excluded, issues = fa.observations(e)
        self.assertEqual([], rows)
        self.assertEqual(1, excluded["ambiguous_review_log"])
        self.assertEqual(1, len(issues))
        self.assertIn("duplicate saved review log 1", issues[0])

    def test_one_dominant_topic_withholds_interval_despite_many_nominal_topics(self):
        rows = [dict(p=.8, original_success=False, participant="A", topic=0, answer_changed=False)] * 1000
        rows += [dict(p=.8, original_success=True, participant="A", topic=i, answer_changed=False) for i in range(1, 40)]
        metrics = fa.metrics(rows)
        self.assertEqual(40, metrics["topics"])
        self.assertEqual(1039, metrics["n"])
        self.assertLess(metrics["gap"]["effective_clusters"], 2)
        self.assertIsNone(metrics["gap"]["gap_ci"])
        self.assertAlmostEqual((1000 * .8 ** 2 + 39 * .2 ** 2) / 1039, metrics["brier"])

    def test_small_clusters_withhold_interval_and_personal_sets_stay_separate(self):
        summary, rows = fa.audit([export(participant="A"), export(participant="B")])
        self.assertEqual(2, len(summary["groups"]))
        self.assertTrue(all(g["gap"] is None or g["gap"]["gap_ci"] is None for g in summary["groups"]))
        self.assertIsNone(fa.metrics([rows[0]])["gap"])

    def test_extreme_probabilities_have_finite_scores_and_inputs_are_unchanged(self):
        for p in [0, 1]:
            e = export(events=[snapshot(p=p)], rating="Forgot")
            before = copy.deepcopy(e.data)
            summary, rows = fa.audit([e])
            self.assertTrue(math.isfinite(fa.metrics(rows)["log_loss"]))
            self.assertEqual(before, e.data)
            with tempfile.TemporaryDirectory() as out:
                fa.write(summary, rows, out)
                self.assertTrue(os.path.exists(os.path.join(out, "forecasts.csv")))
                with open(os.path.join(out, "summary.json"), encoding="utf-8") as f:
                    json.load(f)


if __name__ == "__main__":
    unittest.main()

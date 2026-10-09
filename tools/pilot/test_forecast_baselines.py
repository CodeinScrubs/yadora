"""Past-only prediction comparison: no future leakage or spurious confidence from repeated topics."""
import copy
import csv
import json
import math
import os
import tempfile
import unittest
from collections import defaultdict
from types import SimpleNamespace

import forecast_audit as fa
from test_forecast_audit import snapshot


def row(log, topic=None, participant="A", success=True, p=.8, **changes):
    r = dict(log=log, topic=log if topic is None else topic, participant=participant, original_success=success,
             p=p, model="FSRS-6", set=0, policy="YADORA-8", session="TOPIC", dueContext="MEMORY_DUE",
             at=100_000-log, answer_changed=False)
    r.update(changes)
    return r


class ForecastBaselineTest(unittest.TestCase):
    def test_current_outcome_cannot_change_its_own_reference_prediction(self):
        rows = [row(i, success=i % 4 != 0) for i in range(1, 61)]
        before = fa.with_past_baseline(rows)
        changed = copy.deepcopy(rows)
        changed[30]["original_success"] = not changed[30]["original_success"]
        after = fa.with_past_baseline(changed)
        self.assertEqual(before[:30], after[:30])
        self.assertEqual(before[30]["baseline_p"], after[30]["baseline_p"])
        self.assertNotEqual(before[31]["baseline_p"], after[31]["baseline_p"])

    def test_future_outcomes_cannot_change_any_earlier_prediction(self):
        rows = [row(i, success=i % 5 != 0) for i in range(1, 81)]
        before = fa.with_past_baseline(rows)
        for r in rows[40:]:
            r["original_success"] = not r["original_success"]
        self.assertEqual(before[:40], fa.with_past_baseline(rows)[:40])

    def test_saved_ids_decide_order_after_clock_rollback_and_any_export_permutation(self):
        rows = [row(i, success=i % 5 != 0) for i in range(1, 81)]
        expected = fa.with_past_baseline(rows)
        self.assertEqual(expected, fa.with_past_baseline(rows[::-1]))
        self.assertEqual(expected, fa.with_past_baseline(sorted(rows, key=lambda r: r["at"])))

    def test_learner_set_policy_session_and_due_context_have_independent_histories(self):
        scopes = [{}, dict(participant="B"), dict(set=7), dict(policy="YADORA-7"),
                  dict(session="QUESTIONS"), dict(dueContext="AHEAD"), dict(model="older")]
        rows = [row(i * len(scopes) + j + 1, success=j != 0, **scope)
                for i in range(22) for j, scope in enumerate(scopes)]
        grouped = defaultdict(list)
        for r in fa.with_past_baseline(rows):
            grouped[(r["participant"], r["model"], r["set"], r["policy"], r["session"], r["dueContext"])].append(r)
        self.assertEqual(7, len(grouped))
        for rs in grouped.values():
            self.assertTrue(all(r["baseline_p"] is None for r in rs[:20]))
            expected = 1 / 22 if not rs[0]["original_success"] else 21 / 22
            self.assertAlmostEqual(expected, rs[20]["baseline_p"])

    def test_fixed_window_discards_old_outcomes_and_reports_its_history_count(self):
        rows = [row(i, success=i > 25) for i in range(1, 77)]
        output = fa.with_past_baseline(rows)
        self.assertEqual(50, output[-1]["baseline_history_n"])
        self.assertAlmostEqual(51 / 52, output[-1]["baseline_p"])
        self.assertIsNone(output[19]["baseline_p"])
        self.assertIsNotNone(output[20]["baseline_p"])

    def test_corrected_current_answer_does_not_replace_original_training_signal(self):
        rows = [row(i, success=True, current_memory="Forgot") for i in range(1, 41)]
        before = copy.deepcopy(rows)
        output = fa.with_past_baseline(rows)
        self.assertAlmostEqual(21 / 22, output[20]["baseline_p"])
        self.assertEqual(before, rows)

    def test_model_and_reference_are_scored_on_exactly_the_same_warmup_filtered_rows(self):
        rows = [row(i, success=i % 10 != 0, p=.5) for i in range(1, 81)]
        output = fa.with_past_baseline(rows)
        m = fa.baseline_comparison(output)
        self.assertEqual(60, m["n"])
        self.assertEqual(20, m["warmup_excluded"])
        self.assertAlmostEqual(math.log(2), m["raw_model_log_loss"])
        self.assertLess(m["baseline_log_loss"], m["raw_model_log_loss"])
        self.assertLess(m["log_loss_advantage"], 0.)
        self.assertAlmostEqual(m["baseline_log_loss"] - m["raw_model_log_loss"], m["log_loss_advantage"])

    def test_positive_advantage_means_lower_model_loss_and_never_changes_any_schedule(self):
        rows = [row(i, success=i % 2 == 0, p=.99 if i % 2 == 0 else .01,
                    baseline_p=.5) for i in range(1, 81)]
        before = copy.deepcopy(rows)
        m = fa.baseline_comparison(rows)
        self.assertGreater(m["log_loss_advantage"], .6)
        self.assertEqual(before, rows)

    def test_topic_interval_accounts_for_perfectly_correlated_repeats(self):
        rows = []
        for topic in range(40):
            delta = .2 if topic % 2 == 0 else -.2
            rows.extend(row(topic*10+i+1, topic=topic, p=.6*math.exp(delta), baseline_p=.6) for i in range(10))
        m = fa.baseline_comparison(rows)
        ci = m["log_loss_advantage_ci"]
        expected_half = 1.96 * .2 / math.sqrt(39)
        self.assertAlmostEqual(-expected_half, ci[0])
        self.assertAlmostEqual(expected_half, ci[1])
        self.assertGreater(ci[1], 3 * 1.96 * .2 / math.sqrt(399))

    def test_topic_count_cannot_hide_one_dominant_topic(self):
        rows = [row(i+1, topic=0, baseline_p=.6) for i in range(1000)]
        rows += [row(1000+i, topic=i, baseline_p=.6) for i in range(1, 40)]
        m = fa.baseline_comparison(rows)
        self.assertEqual(40, m["topics"])
        self.assertLess(m["effective_topics"], 2.)
        self.assertIsNone(m["log_loss_advantage_ci"])

    def test_empty_sparse_and_extreme_scores_are_explicit_and_finite(self):
        self.assertEqual(0, fa.baseline_comparison([])["n"])
        self.assertIsNone(fa.baseline_comparison([])["log_loss_advantage"])
        for p in (0., 1.):
            m = fa.baseline_comparison([row(1, p=p, baseline_p=.5)])
            self.assertTrue(math.isfinite(m["raw_model_log_loss"]))
            self.assertIsNone(m["log_loss_advantage_ci"])

    def test_real_snapshot_parser_audit_csv_and_json_share_the_paired_sample(self):
        logs, events = [], []
        for i in range(1, 81):
            rating = "Forgot" if i % 10 == 0 else "Good"
            logs.append(dict(id=i, studyUnitId=i % 25, reviewedAt=1000, memoryRating=rating, understandingRating="Clear"))
            events.append(snapshot(log=i, p=.5, memory=rating))
        exported = SimpleNamespace(participant="A", data=dict(reviewLogs=logs, eventLogs=events))
        before = copy.deepcopy(exported.data)
        summary, rows = fa.audit([exported])
        self.assertEqual(before, exported.data)
        self.assertFalse(summary["issues"])
        self.assertEqual(60, summary["participants"]["A"]["metrics"]["baseline_comparison"]["n"])
        with tempfile.TemporaryDirectory() as out:
            fa.write(summary, rows, out)
            with open(os.path.join(out, "forecasts.csv"), encoding="utf-8-sig") as f:
                saved = list(csv.DictReader(f))
            self.assertEqual(60, sum(bool(r["baseline_p"]) for r in saved))
            self.assertTrue(all(r["session"] == "TOPIC" for r in saved))
            with open(os.path.join(out, "summary.json"), encoding="utf-8") as f:
                self.assertEqual(json.loads(json.dumps(summary, allow_nan=False)), json.load(f))
            with open(os.path.join(out, "report.md"), encoding="utf-8") as f:
                report = f.read()
            self.assertIn("Past-only predictive baseline", report)
            self.assertIn("not logged by the app", report)
            self.assertIn("cannot prove", report)


if __name__ == "__main__":
    unittest.main()

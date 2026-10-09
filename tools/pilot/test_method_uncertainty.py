"""Repeated-topic evidence and saved-order regressions for the observational method comparison."""
import unittest
import math
import random
import statistics
from types import SimpleNamespace

import analyze


class MethodUncertaintyTest(unittest.TestCase):
    def test_null_simulation_does_not_gain_evidence_by_repeating_topics(self):
        # Independent topic outcomes, identical .8 success probability under both methods.
        # Ten copies of each topic create correlation, not ten independent learning outcomes.
        rng = random.Random(981)
        naive_looks = clustered_looks = 0
        trials = 1000
        for _ in range(trials):
            q = [(int(rng.random() < .8) - .8, i) for i in range(40)] * 10
            r = [(int(rng.random() < .8) - .8, i + 40) for i in range(40)] * 10
            out = analyze.within_participant_difference({"A": {"Questions only": q, "Reading only": r}})
            diff = out["diff"]
            naive_se = math.sqrt(statistics.variance(v for v, _ in q) / len(q) +
                                 statistics.variance(v for v, _ in r) / len(r))
            naive_looks += abs(diff) > .05 and abs(diff) > 1.96 * naive_se
            clustered_looks += abs(diff) > .05 and (out["ci"][0] > 0 or out["ci"][1] < 0)
        self.assertGreater(naive_looks / trials, .30)
        self.assertLess(clustered_looks / trials, .08)
        self.assertGreater(clustered_looks / trials, .02)

    def test_repeating_four_topics_does_not_create_a_confidence_interval(self):
        groups = {"A": {"Questions only": [(x, topic) for topic, x in [(1, .9), (2, -.1)] for _ in range(200)],
                        "Reading only": [(x, topic) for topic, x in [(3, .7), (4, -.3)] for _ in range(200)]}}
        out = analyze.within_participant_difference(groups)
        self.assertAlmostEqual(.2, out["diff"])
        self.assertEqual(4, out["clusters"])
        self.assertIsNone(out["ci"])

    def test_duplicate_reviews_of_each_topic_cannot_shrink_the_interval(self):
        base = {"A": {"Questions only": [(i / 20 - .4, i) for i in range(20)],
                      "Reading only": [(i / 20 - .5, i + 20) for i in range(20)]}}
        repeated = {pid: {g: values * 30 for g, values in groups.items()} for pid, groups in base.items()}
        a, b = analyze.within_participant_difference(base), analyze.within_participant_difference(repeated)
        self.assertAlmostEqual(a["diff"], b["diff"])
        self.assertAlmostEqual(a["se"], b["se"])
        self.assertEqual(a["clusters"], b["clusters"])

    def test_same_topic_used_with_both_methods_keeps_covariance(self):
        base = [(i / 20 - .4, i) for i in range(20)]
        paired = {"A": {"Questions only": [(v + .1, topic) for v, topic in base], "Reading only": base}}
        out = analyze.within_participant_difference(paired)
        self.assertAlmostEqual(.1, out["diff"])
        self.assertAlmostEqual(0, out["se"], places=12)
        self.assertEqual(20, out["clusters"])

    def test_one_dominant_topic_does_not_gain_confidence_from_many_tiny_topics(self):
        q = [(.2, 0)] * 1000 + [(-.8, i) for i in range(1, 40)]
        r = [(-.8, 40)] * 1000 + [(.2, i) for i in range(41, 80)]
        out = analyze.within_participant_difference({"A": {"Questions only": q, "Reading only": r}})
        self.assertAlmostEqual(961 / 1039, out["diff"])
        self.assertEqual(80, out["clusters"])
        self.assertIsNone(out["ci"])
        self.assertLess(out["effective_clusters"], 3)

    def test_shared_topics_cannot_count_twice_in_the_concentration_guard(self):
        # Both methods share eleven influential topics and 29 tiny topics.
        # Counting arm masses separately would invent ~22 effective clusters.
        base = [(i / 40, i) for i in range(11) for _ in range(100)]
        base += [(i / 40, i) for i in range(11, 40)]
        out = analyze.within_participant_difference({"A": {"Questions only": base,
                                                          "Reading only": base}})
        self.assertEqual(40, out["clusters"])
        self.assertAlmostEqual(1129 ** 2 / (11 * 100 ** 2 + 29), out["effective_clusters"])
        self.assertIsNone(out["ci"])

    def test_each_learner_method_needs_more_than_one_effective_topic(self):
        groups = {str(i): {"Questions only": [(.2, 1)] * 20,
                           "Reading only": [(-.8, 2)] * 20} for i in range(20)}
        out = analyze.within_participant_difference(groups)
        self.assertEqual(40, out["clusters"])
        self.assertIsNone(out["ci"])

    def test_a_dominant_learner_with_few_topics_cannot_borrow_other_learners_clusters(self):
        groups = {"A": {"Questions only": [(i / 5, i) for i in range(5)] * 100,
                        "Reading only": [(i / 5 - .1, i + 5) for i in range(5)] * 100},
                  "B": {"Questions only": [(i / 5, i) for i in range(5)] * 2,
                        "Reading only": [(i / 5 - .1, i + 5) for i in range(5)] * 2}}
        out = analyze.within_participant_difference(groups)
        self.assertEqual(20, out["clusters"])
        self.assertIsNone(out["ci"])
        self.assertLess(out["effective_clusters"], 12)

    def test_a_concentrated_calibration_gap_withholds_confidence(self):
        rows = [(.8, False, 0)] * 1000 + [(.8, True, i) for i in range(1, 40)]
        out = analyze.clustered_gap(rows)
        self.assertEqual(40, out["clusters"])
        self.assertIsNone(out["gap_ci"])
        self.assertAlmostEqual(1039 ** 2 / (1000 ** 2 + 39), out["effective_clusters"])

    def test_dominant_topic_null_simulation_withholds_instead_of_inventing_evidence(self):
        rng = random.Random(9876)
        guarded_looks = withheld = 0
        for _ in range(1000):
            q = [(int(rng.random() < .8) - .8, i) for i in range(40)]
            r = [(int(rng.random() < .8) - .8, i + 40) for i in range(40)]
            q += [q[0]] * 999
            r += [r[0]] * 999
            out = analyze.within_participant_difference({"A": {"Questions only": q, "Reading only": r}})
            withheld += out["ci"] is None
            guarded_looks += (abs(out["diff"]) > .05 and out["ci"] is not None
                              and (out["ci"][0] > 0 or out["ci"][1] < 0))
        self.assertEqual(1000, withheld)
        self.assertEqual(0, guarded_looks)

    def test_calibration_without_estimable_uncertainty_waits_even_when_the_point_gap_is_small(self):
        summary = dict(integrity=dict(mismatched=0, consistency_issues=0), participants={},
                       calibration={"FSRS-6/0": dict(calibrated=dict(n=400, observed=.91, predicted=.9,
                                                                  clustered=dict(gap_ci=None)))})
        rule = next(r for r in analyze.decide(summary, []) if r["id"] == "D2")
        self.assertEqual("WAIT", rule["verdict"])
        self.assertIn("interval withheld", rule["result"])

    @staticmethod
    def row(log, at, method, success=True, recalled=True, recomputed=False, model="FSRS-6"):
        return SimpleNamespace(participant="A", unit_id=7, log_id=log, at=at, methods=(method,),
                               is_recall=recalled, success=success, predicted=.8, recomputed=recomputed, scheduler_version=model)

    def test_next_review_is_next_saved_event_when_clock_goes_back(self):
        rs = [self.row(1, 300, "Questions"), self.row(2, 100, "Reading", success=False), self.row(3, 200, "Other")]
        resid, _, _ = analyze.method_comparison(rs)
        self.assertEqual([-.8], resid["Questions only"])
        self.assertAlmostEqual(.2, resid["Reading only"][0])

    def test_exposure_and_legacy_events_are_not_bridged(self):
        for middle in [self.row(2, 200, "Reading", recalled=False), self.row(2, 200, "Reading", model="FSRS-5")]:
            resid, _, _ = analyze.method_comparison([self.row(1, 100, "Questions"), middle, self.row(3, 300, "Other")])
            self.assertFalse(resid)

    def test_recomputed_future_prediction_is_not_prospective_evidence(self):
        resid, _, _ = analyze.method_comparison([self.row(1, 100, "Questions"), self.row(2, 200, "Reading", recomputed=True)])
        self.assertFalse(resid)


if __name__ == "__main__":
    unittest.main()

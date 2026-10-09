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

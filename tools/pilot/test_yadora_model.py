"""
Checks the Python transcription against the same references the app is checked against.

  python3 tools/pilot/test_yadora_model.py

1. Every py-fsrs 6.3.1 golden vector in app/src/test/resources/golden_fsrs6.json (the file
   Fsrs6GoldenVectorTest reads), at the same 1e-9 relative tolerance.
2. The Kotlin RNG behind the interval fuzz, against values printed by kotlin-stdlib itself
   (KOTLIN_FUZZ_FACTORS below; regenerate with the jshell line in the comment if the stdlib changes).
3. The calibration estimator recovers a planted scale.
"""
import json
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
import yadora_model as ym  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
GOLDEN = os.path.join(ROOT, "app", "src", "test", "resources", "golden_fsrs6.json")

# kotlin_fuzz_reference.json holds 1.0 + Random(seed).nextDouble(-0.05, 0.05) as kotlin-stdlib 2.2.10 computes
# it (seed = unitId*31 + reviewCount), printed by the stdlib itself:
#   jshell --class-path kotlin-stdlib-2.2.10.jar
#   System.out.println(1.0 + kotlin.random.RandomKt.Random(seed).nextDouble(-0.05, 0.05));


def close(a, b, rel=1e-9):
    return abs(a - b) <= rel * max(1.0, abs(a), abs(b))


def test_goldens():
    g = json.load(open(GOLDEN))
    m = ym.Fsrs6(g["parameters"])
    assert close(m.decay, g["decay"]) and close(m.factor, g["factor"])
    checked = 0
    for v in g["initialStates"]:
        st = m.initial_state(v["g"])
        assert close(st.stability, v["s"]) and close(st.difficulty, v["d"]), v
        checked += 2
    for v in g["retrievability"]:
        assert close(m.retrievability(v["t"], v["s"]), v["r"]), v
        checked += 1
    for v in g["intervals"]:
        assert close(m.interval_days(v["s"], v["req"]), v["days"]), v
        checked += 1
    for v in g["transitions"]:
        kind = v["kind"]
        if kind == "difficulty":
            got = m.next_difficulty(v["d"], v["g"])
        elif kind == "shortTerm":
            got = m.short_term_stability(v["s"], v["g"])
        elif kind == "longTerm":
            st = ym.State(v["s"], v["d"])
            got = (m.lapse_stability(st, v["r"]) if v["g"] == ym.AGAIN
                   else m.recall_stability(st, v["r"], v["g"]))
        else:
            raise AssertionError(f"unknown transition kind {kind}")
        assert close(got, v["out"]), (v, got)
        checked += 1
    for v in g["fullState"]:
        st = m.next_state(ym.State(v["s"], v["d"]), v["t"], v["g"])
        assert close(st.stability, v["outS"]) and close(st.difficulty, v["outD"]), (v, st)
        checked += 2
    print(f"goldens: {checked} values agree with py-fsrs 6.3.1")


def test_kotlin_random():
    ref = os.path.join(os.path.dirname(__file__), "kotlin_fuzz_reference.json")
    data = json.load(open(ref))
    for seed_str, factor in data.items():
        got = 1.0 + ym.KotlinRandom(int(seed_str)).next_double_range(-0.05, 0.05)
        assert got == factor, (seed_str, got, factor)
    print(f"kotlin RNG: {len(data)} fuzz factors match kotlin-stdlib bit for bit")


def test_versioned_fuzz_matches_independent_kotlin_cases():
    with open(os.path.join(os.path.dirname(__file__), "kotlin_fuzz_policy_reference.json"), encoding="utf-8") as f:
        data = json.load(f)
    assert data["policy"] == ym.POLICY_VERSION
    for r in data["cases"]:
        actual = ym.fuzzed_interval(r["interval"], r["base"], int(r["unit_id"]), r["review_count"],
                                   r["first_study"], r["policy"], r["model"])
        assert actual == r["expected"], (r, actual)
    for uid in range(1, 201):
        for count in range(8):
            values = [ym.fuzzed_interval(2.9+i*.005, 2.9+i*.005, uid, count, False) for i in range(81)]
            assert all(a <= b for a, b in zip(values, values[1:])), (uid, count)
    print(f"versioned fuzz: {len(data['cases'])} Kotlin cases agree exactly; 129600 boundary samples stay monotone")


def test_calibration_recovers_planted_scale():
    rnd = random.Random(7)
    m = ym.Fsrs6()
    for planted in (0.6, 1.0, 1.7):
        pred, rec = [], []
        for _ in range(4000):
            s = rnd.uniform(2, 60)
            t = rnd.uniform(3, 90)
            p_model = m.retrievability(t, s)
            p_true = m.retrievability(t, s * planted)
            pred.append(p_model)
            rec.append(rnd.random() < p_true)
        k = ym.moment_scale(pred, rec, m)
        assert abs(math.log(k / planted)) < 0.12, (planted, k)
        # What the app applies never lengthens intervals (RecallCalibration since 2026-09-28), but still shortens.
        applied = ym.calibration_scale(pred, rec, m)
        if planted > 1.0:
            assert applied == 1.0, (planted, applied)
        elif planted < 1.0:
            assert applied < 0.9, (planted, applied)
    print("calibration: planted scales 0.6 / 1.0 / 1.7 recovered; the applied scale never lengthens")


def test_intervals_match_app_ladder():
    # CLAUDE.md's worked ladder at 0.90: Medium first rating, then Good every time on the due day.
    m = ym.Fsrs6()
    st = m.initial_state(ym.GOOD)
    ivl, _ = ym.memory_interval(m, st, ym.GOOD, 0.90, first_study=True)
    ladder = [ivl]
    for _ in range(4):
        st = m.next_state(st, math.floor(ladder[-1]), ym.GOOD)
        ivl, _ = ym.memory_interval(m, st, ym.GOOD, 0.90, first_study=False)
        ladder.append(ivl)
    assert 2.0 < ladder[0] < 2.6 and ladder[-1] == 365.0, ladder
    print("ladder at 0.90:", " -> ".join(f"{x:.1f}" for x in ladder))


if __name__ == "__main__":
    test_goldens()
    test_kotlin_random()
    test_versioned_fuzz_matches_independent_kotlin_cases()
    test_calibration_recovers_planted_scale()
    test_intervals_match_app_ladder()
    print("all checks passed")

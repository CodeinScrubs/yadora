"""
Policy experiments: is each remaining scheduling choice the best available? Simulated, not argued.

  python3 tools/pilot/experiments.py            # all experiments (~30 minutes; the queue order alone ~15)
  python3 tools/pilot/experiments.py --quick    # fewer seeds, shorter horizon

Uses the same learner and memory model as simulate.py (FSRS-6 true memory, honest ratings unless stated)
and a Yadora twin whose policy knobs can be changed one at a time:

  1. ORDER. Which due topics get today's slots when the daily limit binds (after a holiday, under a heavy
     load): Yadora's order (Important first, then the most overdue; without the Important flag, which is not
     simulated, that is earliest-due), the score Yadora used before 2026-09-24 (plus bonuses for weak states
     and past lapses), lowest predicted recall first, highest first, or most overdue relative to the
     interval. The limit fixes the workload, so knowledge is compared directly, on paired seeds.
  2. RELEARN. After "Forgot", come back in 1 day (Yadora), in 2, or at FSRS's own post-lapse interval.
  3. FIRST-STUDY CAP. The first interval capped at 5 days (Yadora), 3, 7, or not at all. Tested with
     honest first ratings AND with over-confident ones (the judgment-of-learning illusion the cap exists for).
  4. MAXIMUM INTERVAL over three years: 365 days (Yadora), 180, or effectively none.

Choices 2-4 change how many reviews are done, so they are judged at EQUAL TIME: for each policy the
retention target is swept, and knowledge is read off its knowledge-vs-reviews curve at the review count
the current policy uses at 0.90. A policy only "wins" if it knows more for the same hours.

Added 2026-09-28, to answer "is there anything left to improve in the algorithm?":

  6. HEADROOM. Yadora against an ORACLE twin that schedules from the learner's TRUE memory state (the true
     stability, on the true curve, with the true speed of forgetting) under the same product rules. No memory
     model can know more than that, so the gap at equal time bounds what any better model could buy: FSRS-7,
     a personal weight set, anything. Worlds: a learner the defaults describe, one who forgets 2x faster or
     slower, a first study worth half what the defaults say, a steeper forgetting curve, and inflated ratings.
  7. CALIBRATION when ratings are inflated. Self-ratings that call a lapse "Hard" look exactly like a slow
     forgetter to the calibration, which then lengthens intervals. At the learner's own target, compare the
     current calibration with none, one that never lengthens past x1.25, and one three times slower to
     lengthen than to shorten: what each delivers in true recall, and what it costs an honest slow forgetter.
  8. STABILITY-ADAPTIVE target, a one-parameter version of cost-optimal scheduling (SSP-MMC): a target that
     rises or falls with a topic's stability, judged at equal time, with the weakest tenth reported too.
"""
from __future__ import annotations

import argparse
import math
import os
import random
import statistics
import sys
from dataclasses import dataclass, field, replace
from typing import List, Optional, Sequence

sys.path.insert(0, os.path.dirname(__file__))
import yadora_model as ym  # noqa: E402
from simulate import (  # noqa: E402
    FIRST_GRADES, REVIEW_HOUR_FRACTION, SUCCESS_GRADES, Topic, TrueMemory, knowledge, mastery, pick, priority,
    priority_before_2026_09_24,
)


@dataclass
class Policy:
    name: str = "Yadora"
    order: str = "yadora"            # yadora | previous | r_asc | r_desc | overdue_rel | due (= yadora here)
    relearn_days: Optional[float] = 1.0   # None = FSRS's own post-lapse interval at the target
    first_cap: Optional[float] = 5.0      # None = no cap
    max_interval: float = 365.0
    retention: float = 0.90
    # Difficulty-adaptive target: retention + slope * (D - 5.5) / 4.5, so D=1 and D=10 sit `slope` either side.
    diff_slope: float = 0.0
    # Stability-adaptive target: retention + slope * clamp(log10(S / 20) / 1.5, -1, 1), so S ~0.6 d and ~630 d
    # sit `slope` either side of a 20-day topic.
    stab_slope: float = 0.0
    # Schedule from the learner's TRUE memory (the headroom bound) instead of the model's estimate.
    oracle: bool = False
    # Calibration: "yadora" (RecallCalibration: never lengthens, since 2026-09-28), "before" (until then: up to
    # x2), "off", "capNNN" (the old estimate never past xNNN/100: cap100 = yadora, cap125, cap150), "asym3" (the old
    # estimate, lengthening shrunk with a prior three times stronger than shortening).
    cal: str = "yadora"


@dataclass
class World:
    days: int = 365
    new_per_study_day: int = 3
    study_days_per_week: int = 6
    daily_limit: int = 50
    k_true: float = 1.0
    first_overconfident: float = 0.0   # share of first ratings one grade above the truth
    holiday: Optional[tuple] = None
    optimistic_rate: float = 0.0       # share of forgotten reviews reported as Hard (inflated ratings)
    true_weights: Optional[Sequence[float]] = None  # the learner's true memory; None = the defaults


def on_holiday(w: World, day: int) -> bool:
    return bool(w.holiday and w.holiday[0] <= day < w.holiday[0] + w.holiday[1])


def fuzz(ivl: float, base: float, tid: int, reviews: int, cap: Optional[float], first: bool, max_ivl: float) -> float:
    if base < ym.FUZZ_MIN_BASE_DAYS:
        return ivl
    f = 1.0 + ym.KotlinRandom(tid * 31 + reviews).next_double_range(-0.05, 0.05)
    out = min(max(ivl * f, ym.MIN_INTERVAL_DAYS), max_ivl)
    return min(out, cap) if (first and cap is not None) else out


def target(p: Policy, st: ym.State) -> float:
    r = p.retention + p.diff_slope * (st.difficulty - 5.5) / 4.5
    if p.stab_slope:
        r += p.stab_slope * min(max(math.log10(max(st.stability, ym.S_MIN) / 20.0) / 1.5, -1.0), 1.0)
    return min(max(r, 0.75), 0.97)


def calibrated(p: Policy, evidence) -> float:
    """The calibration scale under policy p's variant, from (predicted, reported-recalled) evidence."""
    if p.cal == "off" or not evidence:
        return 1.0
    pred, rec = [e[0] for e in evidence], [e[1] for e in evidence]
    if p.cal == "yadora":         # the app since 2026-09-28: never lengthens
        return ym.calibration_scale(pred, rec)
    if p.cal == "before":         # the app until 2026-09-28: may lengthen up to x2
        return ym.calibration_scale_uncapped(pred, rec)
    if p.cal.startswith("cap"):   # the old estimate capped: cap100 = never lengthens (= yadora), cap125, cap150
        return min(ym.calibration_scale_uncapped(pred, rec), int(p.cal[3:]) / 100.0)
    if p.cal == "asym3":
        raw = ym.moment_scale(pred, rec)
        prior = ym.CAL_PRIOR_REVIEWS * (3 if raw > 1.0 else 1)
        return ym.safe_scale(math.exp(math.log(raw) * len(pred) / (len(pred) + prior)))
    raise ValueError(p.cal)


def interval(sched: ym.Fsrs6, st: ym.State, grade: int, p: Policy, first: bool, scale: float):
    """(interval, base) under policy p; mirrors ym.memory_interval with the knobs exposed."""
    if grade == ym.AGAIN and not first:
        if p.relearn_days is not None:
            return p.relearn_days, p.relearn_days
        raw = sched.interval_days(st.stability, target(p, st)) * ym.safe_scale(scale)
        return min(max(raw, ym.MIN_INTERVAL_DAYS), p.max_interval), raw
    raw = sched.interval_days(st.stability, target(p, st)) * ym.safe_scale(scale)
    if first and p.first_cap is not None:
        raw = min(raw, p.first_cap)
    return min(max(raw, ym.MIN_INTERVAL_DAYS), p.max_interval), raw


def oracle_interval(true_model: ym.Fsrs6, t: Topic, grade: int, p: Policy, first: bool, k: float):
    """(interval, base) from the learner's TRUE state: the day true recall reaches the target on the true curve
    (time dilated by k), under the same product rules (relearn step, first-study cap, maximum interval)."""
    true_state = ym.State(t.true_s, t.true_d)
    if grade == ym.AGAIN and not first and p.relearn_days is not None:
        return p.relearn_days, p.relearn_days
    raw = k * true_model.interval_days(true_state.stability, target(p, true_state))
    if first and p.first_cap is not None:
        raw = min(raw, p.first_cap)
    return min(max(raw, ym.MIN_INTERVAL_DAYS), p.max_interval), raw


def run(p: Policy, w: World, seed: int):
    """One Yadora twin under policy p. Returns (final quiz, mean knowledge, reviews, weakest tenth on the last
    day, mean TRUE recall at review): the last is the retention the learner actually experiences."""
    rng_c = random.Random(seed)
    rng = random.Random(seed * 7919 + 1)
    sched = ym.Fsrs6()
    true_weights = w.true_weights or ym.DEFAULT_WEIGHTS
    true_model = ym.Fsrs6(true_weights)
    mem = TrueMemory(true_weights, w.k_true)
    topics: List[Topic] = []
    cal, evidence = 1.0, []
    reviews = 0
    know = []
    recall_at_review = []
    for day in range(w.days):
        if not on_holiday(w, day) and day % 7 < w.study_days_per_week:
            for _ in range(w.new_per_study_day):
                true_g = pick(rng_c, FIRST_GRADES)
                said = true_g
                if w.first_overconfident and rng_c.random() < w.first_overconfident:
                    said = min(true_g + 1, ym.EASY)
                tid = len(topics)
                t = Topic(tid, day, 0, 0, day)
                mem.seed(t, true_g)
                st = sched.initial_state(said)
                t.s, t.d = st.stability, st.difficulty
                if p.oracle:
                    ivl, base = oracle_interval(true_model, t, true_g, p, True, w.k_true)
                else:
                    ivl, base = interval(sched, st, said, p, True, cal)
                ivl = fuzz(ivl, base, tid, 0, p.first_cap, True, p.max_interval)
                t.model_due = day + REVIEW_HOUR_FRACTION + ivl
                t.due_day = math.floor(t.model_due)
                t.reviews, t.state = 1, mastery(st.stability, False)
                topics.append(t)
        if not on_holiday(w, day):
            due = [t for t in topics if t.due_day <= day and t.last_day < day]
            if p.order == "yadora":
                due.sort(key=lambda t: (-priority(t, day), t.model_due, t.tid))
            elif p.order == "previous":
                due.sort(key=lambda t: (-priority_before_2026_09_24(t, day), t.model_due, t.tid))
            elif p.order == "r_asc":
                due.sort(key=lambda t: (sched.retrievability(day - t.last_day, t.s), t.tid))
            elif p.order == "r_desc":
                due.sort(key=lambda t: (-sched.retrievability(day - t.last_day, t.s), t.tid))
            elif p.order == "overdue_rel":
                due.sort(key=lambda t: (-(day - t.last_day) / max(t.model_due - t.last_day - REVIEW_HOUR_FRACTION, 1.0), t.tid))
            elif p.order == "due":
                due.sort(key=lambda t: (t.model_due, t.tid))
            for t in due[: w.daily_limit]:
                r_true = mem.r(t, day)
                recall_at_review.append(r_true)
                recalled = rng.random() < r_true
                true_g = pick(rng, SUCCESS_GRADES) if recalled else ym.AGAIN
                # What the app is told: an inflated rater calls some lapses "Hard" (what simulate.py models).
                g = true_g
                if not recalled and w.optimistic_rate and rng.random() < w.optimistic_rate:
                    g = ym.HARD
                elapsed = day - t.last_day
                pred = sched.retrievability(elapsed, t.s)
                prev_ivl = t.model_due - (t.last_day + REVIEW_HOUR_FRACTION)
                if ym.is_calibration_evidence(elapsed, prev_ivl):
                    evidence.append((pred, g != ym.AGAIN))
                    if len(evidence) > ym.CAL_WINDOW:
                        evidence.pop(0)
                new = sched.next_state(ym.State(t.s, t.d), elapsed, g)
                t.s, t.d = new.stability, new.difficulty
                mem.update(t, day, true_g)
                if p.oracle:
                    ivl, base = oracle_interval(true_model, t, true_g, p, False, w.k_true)
                else:
                    ivl, base = interval(sched, new, g, p, False, cal)
                ivl = fuzz(ivl, base, t.tid, t.reviews, p.first_cap, False, p.max_interval)
                t.reviews += 1
                t.lapses += 1 if g == ym.AGAIN else 0
                t.state = mastery(new.stability, g == ym.AGAIN)
                t.model_due = day + REVIEW_HOUR_FRACTION + ivl
                t.due_day = math.floor(t.model_due)
                reviews += 1
            cal = calibrated(p, evidence)
        know.append(knowledge(mem, topics, day))
    last = w.days - 1
    rs = sorted(mem.r(t, last) for t in topics)
    tail = statistics.fmean(rs[: max(1, len(rs) // 10)])
    return (knowledge(mem, topics, last), statistics.fmean(know), reviews, tail,
            statistics.fmean(recall_at_review) if recall_at_review else float("nan"))


def mean_run(p: Policy, w: World, seeds: int):
    rs = [run(p, w, 500 + s) for s in range(seeds)]
    return (statistics.fmean(r[0] for r in rs), statistics.fmean(r[1] for r in rs),
            statistics.fmean(r[2] for r in rs), [r[0] for r in rs])


def at_equal_time(p: Policy, w: World, seeds: int, budget: float, targets=(0.84, 0.87, 0.90, 0.93, 0.95)):
    """Knowledge (final, mean) of policy p at `budget` reviews, interpolated along its retention sweep."""
    pts = []
    for r in targets:
        f, m, n, _ = mean_run(replace(p, retention=r), w, seeds)
        pts.append((n, f, m))
    pts.sort()
    if budget <= pts[0][0]:
        lo, hi = pts[0], pts[1]
    elif budget >= pts[-1][0]:
        lo, hi = pts[-2], pts[-1]
    else:
        lo, hi = next((a, b) for a, b in zip(pts, pts[1:]) if a[0] <= budget <= b[0])
    t = (budget - lo[0]) / (hi[0] - lo[0]) if hi[0] != lo[0] else 0.0
    return lo[1] + t * (hi[1] - lo[1]), lo[2] + t * (hi[2] - lo[2]), pts


def mean_full(p: Policy, w: World, seeds: int, base_seed: int = 500):
    """Means over seeds of every metric run() returns: (final, mean, reviews, tail, recall at review)."""
    rs = [run(p, w, base_seed + s) for s in range(seeds)]
    return tuple(statistics.fmean(r[i] for r in rs) for i in range(5))


def equal_time_full(p: Policy, w: World, seeds: int, budget: float, targets=(0.84, 0.87, 0.90, 0.93, 0.95)):
    """(final, mean, tail) of policy p at `budget` reviews, interpolated along its retention sweep."""
    pts = sorted((m[2], m[0], m[1], m[3]) for m in (mean_full(replace(p, retention=r), w, seeds) for r in targets))
    if budget <= pts[0][0]:
        lo, hi = pts[0], pts[1]
    elif budget >= pts[-1][0]:
        lo, hi = pts[-2], pts[-1]
    else:
        lo, hi = next((a, b) for a, b in zip(pts, pts[1:]) if a[0] <= budget <= b[0])
    t = (budget - lo[0]) / (hi[0] - lo[0]) if hi[0] != lo[0] else 0.0
    return tuple(lo[i] + t * (hi[i] - lo[i]) for i in (1, 2, 3))


def paired_equal_time(p: Policy, w: World, seeds: int, targets=(0.84, 0.87, 0.90, 0.93, 0.95), base_seed: int = 500):
    """Per seed: policy p's (final, mean, tail) at the review count Yadora's flat 0.90 spends ON THAT SEED, minus
    Yadora's. Paired on the same classes and first ratings, so a small real difference is not lost in the spread
    between seeds. Returns the list of per-seed differences."""
    diffs = []
    for s in range(seeds):
        ref = run(Policy(), w, base_seed + s)
        pts = sorted((r[2], r[0], r[1], r[3]) for r in (run(replace(p, retention=t), w, base_seed + s) for t in targets))
        budget = ref[2]
        if budget <= pts[0][0]:
            lo, hi = pts[0], pts[1]
        elif budget >= pts[-1][0]:
            lo, hi = pts[-2], pts[-1]
        else:
            lo, hi = next((a, b) for a, b in zip(pts, pts[1:]) if a[0] <= budget <= b[0])
        t = (budget - lo[0]) / (hi[0] - lo[0]) if hi[0] != lo[0] else 0.0
        f, m, tl = (lo[i] + t * (hi[i] - lo[i]) for i in (1, 2, 3))
        diffs.append((f - ref[0], m - ref[1], tl - ref[3]))
    return diffs


def mean_se(xs):
    xs = [100 * x for x in xs]
    se = statistics.stdev(xs) / math.sqrt(len(xs)) if len(xs) > 1 else float("nan")
    return f"{statistics.fmean(xs):+.2f} ± {se:.2f}"


def shifted(weights, **changes):
    """The default weights with some entries replaced or scaled: shifted(W, s0=0.5, decay=0.3)."""
    w = list(weights)
    if "s0" in changes:
        for i in range(4):
            w[i] *= changes["s0"]
    if "decay" in changes:
        w[20] = changes["decay"]
    return w


def pct(x):
    return f"{100 * x:.2f}%"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--seeds", type=int, default=6)
    ap.add_argument("--only", help="comma list: order,relearn,cap,maxivl,adaptive,headroom,calibration,stability")
    args = ap.parse_args()
    seeds = 2 if args.quick else args.seeds
    days = 180 if args.quick else 365
    only = set(args.only.split(",")) if args.only else {"order", "relearn", "cap", "maxivl", "adaptive",
                                                        "headroom", "calibration", "stability"}

    if "order" in only:
        print("## 1. Queue order when the daily limit binds\n")
        # Worlds where the limit really binds: more due than the day allows, for weeks at a time. Every order
        # runs on the SAME seeds (the same classes and first ratings), and the table reports the paired
        # difference from Yadora's order with its standard error, so a small but real gap is visible.
        order_seeds = 4 if args.quick else max(seeds, 16)
        worlds = [
            ("heavy load: 6 new/day, limit 15", World(days=days, new_per_study_day=6, daily_limit=15)),
            ("3-week holiday, then limit 20", World(days=days, daily_limit=20, holiday=(days // 2, 21))),
            ("learner forgets 2x faster, 5 new/day, limit 15", World(days=days, new_per_study_day=5, daily_limit=15, k_true=0.5)),
            ("learner forgets 2x slower, 5 new/day, limit 15", World(days=days, new_per_study_day=5, daily_limit=15, k_true=2.0)),
            ("heavy load, 40% of first ratings one grade too high", World(days=days, new_per_study_day=6, daily_limit=15, first_overconfident=0.4)),
        ]
        orders = [("Yadora: most overdue first", "yadora"),
                  ("before 2026-09-24: + weak-state and lapse bonuses", "previous"),
                  ("lowest recall first", "r_asc"), ("highest recall first", "r_desc"),
                  ("most overdue relative to interval", "overdue_rel")]

        def paired(xs, ys):
            d = [100 * (x - y) for x, y in zip(xs, ys)]
            se = statistics.stdev(d) / math.sqrt(len(d)) if len(d) > 1 else float("nan")
            return f"{statistics.fmean(d):+.2f} ± {se:.2f}"

        for wname, w in worlds:
            runs = {o: [run(Policy(order=o), w, 900 + s) for s in range(order_seeds)] for _, o in orders}
            ref = runs["yadora"]
            print(f"**{wname}** ({order_seeds} paired seeds)\n")
            print("| order | final quiz | vs Yadora | average over the year | vs Yadora | reviews |")
            print("|---|---|---|---|---|---|")
            for oname, o in orders:
                rs = runs[o]
                f = statistics.fmean(r[0] for r in rs)
                m = statistics.fmean(r[1] for r in rs)
                n = statistics.fmean(r[2] for r in rs)
                if o == "yadora":
                    print(f"| {oname} | {pct(f)} | | {pct(m)} | | {n:.0f} |")
                else:
                    print(f"| {oname} | {pct(f)} | {paired([r[0] for r in rs], [r[0] for r in ref])} | "
                          f"{pct(m)} | {paired([r[1] for r in rs], [r[1] for r in ref])} | {n:.0f} |")
            print()

    base_world = World(days=days)
    if {"relearn", "cap", "maxivl", "adaptive"} & only:
        f0, m0, n0, _ = mean_run(Policy(), base_world, seeds)
        print(f"Reference: Yadora at 0.90 = {pct(f0)} final, {pct(m0)} average, {n0:.0f} reviews.\n")

    if "relearn" in only:
        print("## 2. Relearn step after Forgot (at equal time)\n")
        print("| policy | final quiz at equal time | average at equal time |")
        print("|---|---|---|")
        for name, rd in [("1 day (Yadora)", 1.0), ("2 days", 2.0), ("FSRS post-lapse interval", None)]:
            f, m, _ = at_equal_time(Policy(relearn_days=rd), base_world, seeds, n0)
            print(f"| {name} | {pct(f)} | {pct(m)} |")
        print()

    if "cap" in only:
        print("## 3. First-study cap (at equal time)\n")
        for wname, w in [("honest first ratings", base_world),
                         ("40% of first ratings one grade too high", replace(base_world, first_overconfident=0.4))]:
            fr, mr, nr, _ = mean_run(Policy(), w, seeds)
            print(f"**{wname}** (reference {nr:.0f} reviews)\n")
            print("| cap | final quiz at equal time | average at equal time |")
            print("|---|---|---|")
            for name, cap in [("5 days (Yadora)", 5.0), ("3 days", 3.0), ("7 days", 7.0), ("none", None)]:
                f, m, _ = at_equal_time(Policy(first_cap=cap), w, seeds, nr)
                print(f"| {name} | {pct(f)} | {pct(m)} |")
            print()

    if "maxivl" in only:
        print("## 4. Maximum interval over three years (at equal time)\n")
        w3 = replace(base_world, days=3 * 365 if not args.quick else 540)
        f3, m3, n3, _ = mean_run(Policy(), w3, max(2, seeds // 2))
        print(f"(reference {n3:.0f} reviews)\n")
        print("| maximum interval | final quiz at equal time | average at equal time |")
        print("|---|---|---|")
        for name, mx in [("365 days (Yadora)", 365.0), ("180 days", 180.0), ("none (36500)", 36500.0)]:
            f, m, _ = at_equal_time(Policy(max_interval=mx), w3, max(2, seeds // 2), n3)
            print(f"| {name} | {pct(f)} | {pct(m)} |")
        print()


    if "adaptive" in only:
        print("## 5. Difficulty-adaptive retention target (at equal time)\n")
        for wname, w in [("default learner", base_world), ("forgets 2x faster", replace(base_world, k_true=0.5))]:
            fr, mr, nr, _ = mean_run(Policy(), w, seeds)
            print(f"**{wname}** (reference {nr:.0f} reviews)\n")
            print("| target | final quiz at equal time | average at equal time |")
            print("|---|---|---|")
            for name, slope in [("flat (Yadora)", 0.0), ("harder topics lower (-0.03)", -0.03), ("harder topics higher (+0.03)", 0.03)]:
                f, m, _ = at_equal_time(Policy(diff_slope=slope), w, seeds, nr)
                print(f"| {name} | {pct(f)} | {pct(m)} |")
            print()

    if "headroom" in only:
        print("## 6. Headroom: Yadora against an oracle that knows the learner's true memory (at equal time)\n")
        print("Same product rules for both. The oracle's gap is the most ANY better memory model could buy.\n")
        worlds = [
            ("a learner the defaults describe", base_world),
            ("forgets 2x faster", replace(base_world, k_true=0.5)),
            ("forgets 2x slower", replace(base_world, k_true=2.0)),
            ("a first study worth half (S0 x0.5)", replace(base_world, true_weights=shifted(ym.DEFAULT_WEIGHTS, s0=0.5))),
            ("steeper forgetting curve (decay 0.30, defaults 0.15)", replace(base_world, true_weights=shifted(ym.DEFAULT_WEIGHTS, decay=0.30))),
            ("30% of lapses rated Hard", replace(base_world, optimistic_rate=0.3)),
        ]
        print("| learner | Yadora: final / average / weakest tenth | oracle at equal time: final / average / weakest tenth | gap (points, final) | reviews |")
        print("|---|---|---|---|---|")
        for wname, w in worlds:
            fy, my, ny, ty, _ = mean_full(Policy(), w, seeds)
            fo, mo, to = equal_time_full(Policy(oracle=True), w, seeds, ny)
            print(f"| {wname} | {pct(fy)} / {pct(my)} / {pct(ty)} | {pct(fo)} / {pct(mo)} / {pct(to)} | "
                  f"{100 * (fo - fy):+.2f} | {ny:.0f} |")
        print()

    if "calibration" in only:
        print("## 7. Calibration when ratings are inflated (at the learner's own 0.90 target)\n")
        print("True recall at review is what the learner actually experiences; the target asks for 90%.\n")
        worlds = [
            ("honest, a learner the defaults describe", base_world),
            ("honest, forgets 2x slower", replace(base_world, k_true=2.0)),
            ("honest, forgets 2x faster", replace(base_world, k_true=0.5)),
            ("30% of lapses rated Hard", replace(base_world, optimistic_rate=0.3)),
            ("60% of lapses rated Hard", replace(base_world, optimistic_rate=0.6)),
            ("30% rated Hard and forgets 2x faster", replace(base_world, optimistic_rate=0.3, k_true=0.5)),
        ]
        variants = [("before 2026-09-28: may lengthen up to x2", "before"), ("off", "off"),
                    ("never lengthens (Yadora since 2026-09-28)", "yadora"), ("never past x1.25", "cap125"),
                    ("never past x1.5", "cap150"), ("3x slower to lengthen", "asym3")]
        if os.environ.get("YADORA_CAL_VARIANTS"):
            keep = set(os.environ["YADORA_CAL_VARIANTS"].split(","))
            variants = [v for v in variants if v[1] in keep]
        print("| learner | calibration | final quiz | average | weakest tenth | true recall at review | reviews |")
        print("|---|---|---|---|---|---|---|")
        for wname, w in worlds:
            for vname, v in variants:
                f, m, n, tl, rr = mean_full(Policy(cal=v), w, seeds)
                print(f"| {wname} | {vname} | {pct(f)} | {pct(m)} | {pct(tl)} | {pct(rr)} | {n:.0f} |")
        print()

    if "stability2" in only:
        # The paired confirmation of section 8: worlds from YADORA_STAB_WORLD (comma list), slopes from
        # YADORA_STAB_SLOPES, seeds from --seeds. Differences are Yadora-flat subtracted seed by seed.
        worlds = {
            "default": ("a learner the defaults describe", base_world),
            "slow": ("forgets 2x slower", replace(base_world, k_true=2.0)),
            "fast": ("forgets 2x faster", replace(base_world, k_true=0.5)),
            "inflated": ("30% of lapses rated Hard", replace(base_world, optimistic_rate=0.3)),
            "heavy": ("heavy load: 6 new/day, limit 30", replace(base_world, new_per_study_day=6, daily_limit=30)),
            "3years": ("three years", replace(base_world, days=3 * 365)),
        }
        keys = os.environ.get("YADORA_STAB_WORLD", "default").split(",")
        slopes = [float(x) for x in os.environ.get("YADORA_STAB_SLOPES", "0.02,0.03,0.05").split(",")]
        print("## 8b. Stability-adaptive target, paired seeds (at equal time)\n")
        for key in keys:
            wname, w = worlds[key]
            print(f"**{wname}** ({seeds} paired seeds; points vs the flat target, mean ± standard error)\n")
            print("| slope | final quiz | year average | weakest tenth |")
            print("|---|---|---|---|")
            for slope in slopes:
                d = paired_equal_time(Policy(stab_slope=slope), w, seeds)
                print(f"| {slope:+.2f} | {mean_se([x[0] for x in d])} | {mean_se([x[1] for x in d])} | "
                      f"{mean_se([x[2] for x in d])} |", flush=True)
            print(flush=True)

    if "stability" in only:
        print("## 8. Stability-adaptive target (at equal time)\n")
        for wname, w in [("a learner the defaults describe", base_world), ("forgets 2x faster", replace(base_world, k_true=0.5))]:
            fr, mr, nr, tr, _ = mean_full(Policy(), w, seeds)
            print(f"**{wname}** (reference {nr:.0f} reviews)\n")
            print("| target | final quiz at equal time | average at equal time | weakest tenth at equal time |")
            print("|---|---|---|---|")
            for name, slope in [("flat (Yadora)", 0.0), ("young topics lower, mature higher (+0.03)", 0.03),
                                ("young topics higher, mature lower (-0.03)", -0.03)]:
                f, m, tl = equal_time_full(Policy(stab_slope=slope), w, seeds, nr)
                print(f"| {name} | {pct(f)} | {pct(m)} | {pct(tl)} |")
            print()


if __name__ == "__main__":
    main()

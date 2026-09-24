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


@dataclass
class World:
    days: int = 365
    new_per_study_day: int = 3
    study_days_per_week: int = 6
    daily_limit: int = 50
    k_true: float = 1.0
    first_overconfident: float = 0.0   # share of first ratings one grade above the truth
    holiday: Optional[tuple] = None


def on_holiday(w: World, day: int) -> bool:
    return bool(w.holiday and w.holiday[0] <= day < w.holiday[0] + w.holiday[1])


def fuzz(ivl: float, base: float, tid: int, reviews: int, cap: Optional[float], first: bool, max_ivl: float) -> float:
    if base < ym.FUZZ_MIN_BASE_DAYS:
        return ivl
    f = 1.0 + ym.KotlinRandom(tid * 31 + reviews).next_double_range(-0.05, 0.05)
    out = min(max(ivl * f, ym.MIN_INTERVAL_DAYS), max_ivl)
    return min(out, cap) if (first and cap is not None) else out


def target(p: Policy, st: ym.State) -> float:
    return min(max(p.retention + p.diff_slope * (st.difficulty - 5.5) / 4.5, 0.75), 0.97)


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


def run(p: Policy, w: World, seed: int):
    """One Yadora twin under policy p. Returns (final quiz, mean knowledge, reviews)."""
    rng_c = random.Random(seed)
    rng = random.Random(seed * 7919 + 1)
    sched = ym.Fsrs6()
    mem = TrueMemory(ym.DEFAULT_WEIGHTS, w.k_true)
    topics: List[Topic] = []
    cal, evidence = 1.0, []
    reviews = 0
    know = []
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
                recalled = rng.random() < mem.r(t, day)
                g = pick(rng, SUCCESS_GRADES) if recalled else ym.AGAIN
                elapsed = day - t.last_day
                pred = sched.retrievability(elapsed, t.s)
                prev_ivl = t.model_due - (t.last_day + REVIEW_HOUR_FRACTION)
                if ym.is_calibration_evidence(elapsed, prev_ivl):
                    evidence.append((pred, recalled))
                    if len(evidence) > ym.CAL_WINDOW:
                        evidence.pop(0)
                new = sched.next_state(ym.State(t.s, t.d), elapsed, g)
                ivl, base = interval(sched, new, g, p, False, cal)
                ivl = fuzz(ivl, base, t.tid, t.reviews, p.first_cap, False, p.max_interval)
                t.s, t.d = new.stability, new.difficulty
                t.reviews += 1
                t.lapses += 1 if g == ym.AGAIN else 0
                t.state = mastery(new.stability, g == ym.AGAIN)
                t.model_due = day + REVIEW_HOUR_FRACTION + ivl
                t.due_day = math.floor(t.model_due)
                mem.update(t, day, g)
                reviews += 1
            if evidence:
                cal = ym.calibration_scale([e[0] for e in evidence], [e[1] for e in evidence], sched)
        know.append(knowledge(mem, topics, day))
    return knowledge(mem, topics, w.days - 1), statistics.fmean(know), reviews


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


def pct(x):
    return f"{100 * x:.2f}%"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--seeds", type=int, default=6)
    ap.add_argument("--only", help="comma list: order,relearn,cap,maxivl,adaptive")
    args = ap.parse_args()
    seeds = 2 if args.quick else args.seeds
    days = 180 if args.quick else 365
    only = set(args.only.split(",")) if args.only else {"order", "relearn", "cap", "maxivl", "adaptive"}

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


if __name__ == "__main__":
    main()

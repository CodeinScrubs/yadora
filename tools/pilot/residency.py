"""
The two-year residency exam, simulated: does following Yadora for two years leave the learner knowing more on
exam day than the same learner studying the same hours without it, and how close to "everything" does it get?

  python3 tools/pilot/residency.py --seeds 6  # ~30-40 minutes (4 seeds by default)
  python3 tools/pilot/residency.py --quick    # a smoke test (2 seeds, one year)

The learner studies 4 new topics on 6 days a week for two years (about 2,500 topics: chapters, lectures,
question-bank blocks) and sits the exam on the last day. Every twin gets the SAME review time as the Yadora
twin, day by day (a forgotten topic costs 1.5x a remembered one, as in simulate.py):

  random          reviews whatever it picks up
  oldest-first    the disciplined student: always the topic untouched for longest
  fixed ladder    a classic spaced-repetition planner: reviews after 1, 3, 7, 14, 30, 60, 120, 240, 365 days,
                  back to 1 after a lapse; spare time goes to the oldest topics
  plain FSRS-6    the same memory model with none of Yadora's product layer (no calibration, no first-study cap,
                  FSRS's own post-lapse interval, no interval ceiling, no daily limit): what another FSRS app
                  schedules. Compared at EQUAL TIME along its own retention sweep.

Yadora strategies, all within the app's settled rules (the exam date feeds nothing):

  default         target 0.90 throughout
  + final push    the last four weeks, Review ahead (weakest predicted recall first) up to 60 topics a day;
                  every other twin gets the same push, oldest first
  0.95 late       the learner moves the retention slider to 0.95 six months before the exam
  0.95 late + push

Reported for exam day: the average recall over every topic studied in the two years, the share of topics at
90%+ and at 80%+, and the recall of the weakest tenth.
"""
from __future__ import annotations

import argparse
import math
import os
import random
import statistics
import sys
from dataclasses import replace
from typing import List, Optional, Sequence

sys.path.insert(0, os.path.dirname(__file__))
import yadora_model as ym  # noqa: E402
from simulate import (  # noqa: E402
    SUCCESS_GRADES, Result, Scenario, Topic, TrueMemory, finish, knowledge, make_classes, pick, run_other,
    run_yadora,
)

LADDER = (1, 3, 7, 14, 30, 60, 120, 240, 365)
PUSH_DAYS, PUSH_CAPACITY = 28, 60


def run_ladder(sc: Scenario, classes, seed: int, daily_cost: Sequence[float]) -> Result:
    """The fixed-ladder planner, given the Yadora twin's review time each day."""
    mem = TrueMemory(sc.true_weights, sc.k_true)
    rng = random.Random(seed * 7919 + 3)
    topics: List[Topic] = []
    step: List[int] = []
    due: List[int] = []
    reviews, cost = 0, 0.0
    know: List[float] = []
    debt = 0.0  # a day's overspend comes off the next day (simulate.run_other explains why)
    for day in range(sc.days):
        for grade in classes[day]:
            t = Topic(len(topics), day, 0, 0, day)
            mem.seed(t, grade)
            topics.append(t)
            step.append(0)
            due.append(day + LADDER[0])
        budget = daily_cost[day] - debt
        if budget > 0:
            pool = [t for t in topics if t.last_day < day]
            owed = sorted((t for t in pool if due[t.tid] <= day), key=lambda t: (due[t.tid], t.tid))
            owed_ids = {t.tid for t in owed}
            spare = sorted((t for t in pool if t.tid not in owed_ids), key=lambda t: (t.last_day, t.tid))
            for t in owed + spare:
                if budget <= 0:
                    break
                recalled = rng.random() < mem.r(t, day)
                mem.update(t, day, pick(rng, SUCCESS_GRADES) if recalled else ym.AGAIN)
                step[t.tid] = min(step[t.tid] + 1, len(LADDER) - 1) if recalled else 0
                due[t.tid] = day + LADDER[step[t.tid]]
                c = 1.0 if recalled else sc.fail_cost
                budget -= c
                cost += c
                reviews += 1
        debt = max(0.0, -budget)
        know.append(knowledge(mem, topics, day))
    return finish(sc, mem, topics, know, reviews, cost, 0)


def run_plain_fsrs(sc: Scenario, classes, seed: int, retention: float) -> Result:
    """FSRS-6 with its published defaults and nothing else, every due topic every day."""
    sched = ym.Fsrs6(sc.sched_weights)
    mem = TrueMemory(sc.true_weights, sc.k_true)
    rng = random.Random(seed * 7919 + 4)
    topics: List[Topic] = []
    reviews, cost, max_day = 0, 0.0, 0
    know: List[float] = []
    for day in range(sc.days):
        for grade in classes[day]:
            t = Topic(len(topics), day, 0, 0, day)
            mem.seed(t, grade)
            st = sched.initial_state(grade)
            t.s, t.d = st.stability, st.difficulty
            t.due_day = day + max(1, round(sched.interval_days(st.stability, retention)))
            topics.append(t)
        today = 0
        for t in sorted((t for t in topics if t.due_day <= day and t.last_day < day), key=lambda t: (t.due_day, t.tid)):
            recalled = rng.random() < mem.r(t, day)
            grade = pick(rng, SUCCESS_GRADES) if recalled else ym.AGAIN
            new = sched.next_state(ym.State(t.s, t.d), day - t.last_day, grade)
            t.s, t.d = new.stability, new.difficulty
            t.due_day = day + max(1, round(sched.interval_days(new.stability, retention)))
            mem.update(t, day, grade)
            cost += 1.0 if recalled else sc.fail_cost
            reviews += 1
            today += 1
        max_day = max(max_day, today)
        know.append(knowledge(mem, topics, day))
    return finish(sc, mem, topics, know, reviews, cost, max_day)


def plain_fsrs_at_equal_time(sc: Scenario, classes, seed: int, budget_cost: float) -> Result:
    """Plain FSRS-6 interpolated along its own retention sweep to the Yadora twin's total review time."""
    pts = sorted((r.cost, r) for r in (run_plain_fsrs(sc, classes, seed, x) for x in (0.85, 0.88, 0.90, 0.92, 0.95)))
    lo, hi = pts[0], pts[1]
    for a, b in zip(pts, pts[1:]):
        if a[0] <= budget_cost <= b[0]:
            lo, hi = a, b
            break
    else:
        if budget_cost > pts[-1][0]:
            lo, hi = pts[-2], pts[-1]
    f = (budget_cost - lo[0]) / (hi[0] - lo[0]) if hi[0] != lo[0] else 0.0

    def mix(attr):
        return getattr(lo[1], attr) + f * (getattr(hi[1], attr) - getattr(lo[1], attr))

    return Result(
        final_quiz=mix("final_quiz"), first_half_quiz=mix("first_half_quiz"), mean_knowledge=mix("mean_knowledge"),
        reviews=round(mix("reviews")), cost=budget_cost, max_day_reviews=round(mix("max_day_reviews")),
        topics=lo[1].topics, share_at_90=mix("share_at_90"), share_at_80=mix("share_at_80"),
        weakest_tenth=mix("weakest_tenth"),
    )


def strategies(base: Scenario):
    late = (base.days - 182, 0.95)
    return [
        ("Yadora, default (0.90)", base),
        ("Yadora + final 4-week push", replace(base, sweep_days=PUSH_DAYS, sweep_capacity=PUSH_CAPACITY)),
        ("Yadora, target 0.95 for the last 6 months", replace(base, retention_from=late)),
        ("Yadora, 0.95 for the last 6 months + push", replace(base, retention_from=late, sweep_days=PUSH_DAYS,
                                                           sweep_capacity=PUSH_CAPACITY)),
    ]


def run_world(base: Scenario, seeds: int, plain: bool):
    rows = []
    for name, sc in strategies(base):
        per_twin = {}
        for s in range(seeds):
            seed = 3000 + s
            classes = make_classes(sc, seed)
            y, daily_cost = run_yadora(sc, classes, seed)
            twins = {
                "Yadora": y,
                "random": run_other(replace(sc, baseline="random"), classes, seed, daily_cost),
                "oldest-first": run_other(replace(sc, baseline="cycle"), classes, seed, daily_cost),
                "fixed ladder": run_ladder(sc, classes, seed, daily_cost),
            }
            if plain and not sc.sweep_days and not sc.retention_from:
                twins["plain FSRS-6"] = plain_fsrs_at_equal_time(sc, classes, seed, y.cost)
            for k, v in twins.items():
                per_twin.setdefault(k, []).append(v)
        rows.append((name, per_twin))
    return rows


def pct(xs):
    m = statistics.fmean(xs)
    sd = statistics.stdev(xs) if len(xs) > 1 else 0.0
    return f"{100 * m:.1f}% ±{100 * sd:.1f}"


def print_world(title: str, base: Scenario, rows, seeds: int):
    print(f"## {title}\n")
    print(f"{base.days} days, {base.new_per_study_day} new topics on {base.study_days_per_week} days a week, "
          f"daily limit {base.daily_limit}, k = {base.k_true}; {seeds} seeds; every twin gets the Yadora twin's "
          f"review time.\n")
    for name, per_twin in rows:
        y = per_twin["Yadora"]
        print(f"**{name}**: {statistics.fmean(r.reviews for r in y) / base.days:.1f} reviews a day on average, "
              f"{statistics.fmean(r.topics for r in y):.0f} topics\n")
        print("| twin | exam day: average recall | topics at 90%+ | topics at 80%+ | weakest tenth | "
              "first-year topics | average over the two years | Yadora wins |")
        print("|---|---|---|---|---|---|---|---|")
        for twin, rs in per_twin.items():
            wins = "" if twin == "Yadora" else f"{sum(1 for a, b in zip(y, rs) if a.final_quiz > b.final_quiz)}/{len(rs)}"
            print(f"| {twin} | {pct([r.final_quiz for r in rs])} | {pct([r.share_at_90 for r in rs])} | "
                  f"{pct([r.share_at_80 for r in rs])} | {pct([r.weakest_tenth for r in rs])} | "
                  f"{pct([r.first_half_quiz for r in rs])} | {pct([r.mean_knowledge for r in rs])} | {wins} |")
        print()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seeds", type=int, default=4)
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--only", help="comma list of worlds: default,fast,slow,heavy")
    args = ap.parse_args()
    seeds = 2 if args.quick else args.seeds
    days = 365 if args.quick else 730
    base = Scenario("residency", days=days, new_per_study_day=4)
    worlds = [
        ("default", "Two years, a learner the defaults describe", base, True),
        ("fast", "Two years, a learner who forgets 2x faster than the defaults assume", replace(base, k_true=0.5), True),
        ("slow", "Two years, a learner who forgets 2x slower", replace(base, k_true=2.0), True),
        ("heavy", "Two years, 6 new topics a day (limit 50)", replace(base, new_per_study_day=6), False),
    ]
    only = set(args.only.split(",")) if args.only else None
    for key, title, sc, plain in worlds:
        if only and key not in only:
            continue
        print_world(title, sc, run_world(sc, seeds, plain), seeds)
        sys.stdout.flush()


if __name__ == "__main__":
    main()

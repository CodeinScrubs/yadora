"""
The identical-twins question, simulated.

Two students with the SAME memory, the SAME classes (the same new topics on the same days) and the SAME
daily review time. One reviews what Yadora schedules. The other reviews too, but chooses topics without
a schedule. A year later both take one quiz on everything they studied. Who remembers more, by how much,
and does the answer survive a learner who forgets faster or slower than the model assumes, dishonest
ratings, missed days and a holiday?

  python3 tools/pilot/simulate.py                  # the standard scenario table (a few minutes)
  python3 tools/pilot/simulate.py --quick          # fewer seeds, for a smoke test
  python3 tools/pilot/simulate.py --weights w.json # true memory from a fitted weight set (analyze.py writes one)

WHAT THIS IS AND IS NOT. The "true memory" of both twins follows FSRS-6 -- a model fitted to hundreds of
millions of real reviews, but still a model. The simulation therefore answers "if memory behaves the way
the best available model says, what does scheduling buy?", not "what happened to real students". The
pilot data (analyze.py) is what tests whether the model describes Yadora's users; the sensitivity rows
below test whether the conclusion depends on that.

Modelling choices, stated so they can be argued with:
  * A learner who "forgets k times slower" experiences time dilated by 1/k: R_true(t) = R_model(t / k).
    k = 1 is the default-weights learner; the app's calibration exists to estimate exactly this k.
  * A review's outcome is drawn from the TRUE recall probability. A success is rated Hard / Good / Easy
    with probabilities 0.22 / 0.64 / 0.14 (py-fsrs simulator defaults); a first study is rated
    Hard / Medium / Easy with 0.25 / 0.55 / 0.20.
  * Both twins' memories update with the same FSRS-6 equations. The scheduler never sees the true state,
    only the ratings, exactly as in the app.
  * "Same time": each day the unscheduled twin spends the same review budget the Yadora twin spent. A
    review that finds the topic forgotten costs FAIL_COST units (relearning is slower), a success 1.
  * Reviews happen in the evening (0.8 of the way through the day); a topic is offered from 00:00 of the
    calendar day its due timestamp falls on, as the app does.
  * Understanding repair and "Important" are left out: they change which topics get extra time, which is
    a separate question from whether memory scheduling works.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import statistics
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

sys.path.insert(0, os.path.dirname(__file__))
import yadora_model as ym  # noqa: E402

REVIEW_HOUR_FRACTION = 0.8
SUCCESS_GRADES = ((ym.HARD, 0.22), (ym.GOOD, 0.64), (ym.EASY, 0.14))
FIRST_GRADES = ((ym.HARD, 0.25), (ym.GOOD, 0.55), (ym.EASY, 0.20))


def pick(rng: random.Random, table) -> int:
    x = rng.random()
    acc = 0.0
    for grade, p in table:
        acc += p
        if x < acc:
            return grade
    return table[-1][0]


@dataclass
class Scenario:
    name: str
    days: int = 365
    new_per_study_day: int = 3
    study_days_per_week: int = 6
    retention: float = 0.90
    daily_limit: int = 50
    k_true: float = 1.0            # learner forgets k times slower than the model assumes
    optimistic_rate: float = 0.0   # share of forgotten reviews reported as Hard (hindsight inflation)
    skip_day_rate: float = 0.0     # share of days the Yadora twin does no reviews at all
    holiday: Optional[tuple] = None  # (first_day, length): nobody reviews or studies
    fail_cost: float = 1.5
    calibration: bool = True
    baseline: str = "random"       # random | recent | cycle | cram | half-cram
    # Announced exam on the last day: for the final `sweep_days`, both twins review at `sweep_capacity` topics a
    # day. The Yadora twin does its due reviews, then reviews ahead, weakest predicted recall first (what
    # the Library's review-now allows, one topic at a time); the other twin reviews oldest-first.
    sweep_days: int = 0
    sweep_capacity: int = 0
    # The learner moves the retention slider on a given day: (day, new target). None = never.
    retention_from: Optional[tuple] = None
    true_weights: Sequence[float] = ym.DEFAULT_WEIGHTS
    sched_weights: Sequence[float] = ym.DEFAULT_WEIGHTS


@dataclass
class Topic:
    tid: int
    studied_day: int
    true_s: float
    true_d: float
    last_day: int
    # scheduler view (Yadora twin only)
    s: float = 0.0
    d: float = 0.0
    due_day: int = 0
    model_due: float = 0.0
    reviews: int = 0
    lapses: int = 0
    state: str = "Learning"


@dataclass
class Result:
    final_quiz: float                 # expected share of all topics recalled on the last day
    first_half_quiz: float            # ...of topics studied in the first half of the year
    mean_knowledge: float             # average over the year of the expected share recalled
    reviews: int
    cost: float
    max_day_reviews: int
    topics: int
    # How the final quiz is spread over topics: the share recalled at 90% or better, at 80% or better, and
    # the recall of the weakest tenth. An average can hide a tail of forgotten topics; these cannot.
    share_at_90: float = 0.0
    share_at_80: float = 0.0
    weakest_tenth: float = 0.0


class TrueMemory:
    """The learner's actual memory: FSRS-6 with time dilated by 1/k."""

    def __init__(self, weights, k: float):
        self.m = ym.Fsrs6(weights)
        self.k = k

    def r(self, topic: Topic, day: int) -> float:
        return self.m.retrievability((day - topic.last_day) / self.k, topic.true_s)

    def seed(self, topic: Topic, grade: int):
        st = self.m.initial_state(grade)
        topic.true_s, topic.true_d = st.stability, st.difficulty

    def update(self, topic: Topic, day: int, grade: int):
        # Recall strength at the review is read on the dilated clock (that IS the learner's forgetting), but
        # the same-day branch is chosen on the real calendar: it models what one night's sleep does, which
        # does not stretch with the learner. Dilating it too would turn a slow forgetter's next-day review
        # into a "same-day" one.
        m, st = self.m, ym.State(topic.true_s, topic.true_d)
        real = day - topic.last_day
        r = m.retrievability(real / self.k, st.stability)
        d = m.next_difficulty(st.difficulty, grade)
        if real < 1:
            s = m.short_term_stability(st.stability, grade)
        elif grade == ym.AGAIN:
            s = m.lapse_stability(st, r)
        else:
            s = m.recall_stability(st, r, grade)
        topic.true_s, topic.true_d = max(s, ym.S_MIN), d
        topic.last_day = day


def is_study_day(sc: Scenario, day: int) -> bool:
    if sc.holiday and sc.holiday[0] <= day < sc.holiday[0] + sc.holiday[1]:
        return False
    return day % 7 < sc.study_days_per_week


def on_holiday(sc: Scenario, day: int) -> bool:
    return bool(sc.holiday and sc.holiday[0] <= day < sc.holiday[0] + sc.holiday[1])


def target_on(sc: Scenario, day: int) -> float:
    """The retention target in force on `day` (the Settings slider, which the learner may move once)."""
    if sc.retention_from and day >= sc.retention_from[0]:
        return sc.retention_from[1]
    return sc.retention


def priority(t: Topic, day: int) -> float:
    """MedScheduler.priorityScore without high-yield (not simulated): lateness on the model's clock."""
    return max(day - t.model_due, 0.0) * 5.0


def priority_before_2026_09_24(t: Topic, day: int) -> float:
    """The score Yadora used until 2026-09-24, kept so experiments.py can show why it changed."""
    score = {"NeedsRelearn": 80.0, "Learning": 40.0, "Building": 20.0}.get(t.state, 0.0)
    score += min(t.lapses, 5) * 10.0
    return score + priority(t, day)


def mastery(s: float, forgot: bool) -> str:
    if forgot:
        return "NeedsRelearn"
    return "Learning" if s < 7 else ("Building" if s < 21 else "Strong")


def knowledge(mem: TrueMemory, topics: List[Topic], day: int) -> float:
    return statistics.fmean(mem.r(t, day) for t in topics) if topics else 0.0


def make_classes(sc: Scenario, seed: int) -> List[List[int]]:
    """The first-study grade of every topic introduced on each day. Both twins attend the same classes."""
    rng = random.Random(seed)
    return [[pick(rng, FIRST_GRADES) for _ in range(sc.new_per_study_day)] if is_study_day(sc, day) else []
            for day in range(sc.days)]


def run_yadora(sc: Scenario, classes: List[List[int]], seed: int):
    """The Yadora twin. Returns (Result, cost spent on reviews each day)."""
    sched = ym.Fsrs6(sc.sched_weights)
    mem = TrueMemory(sc.true_weights, sc.k_true)
    rng = random.Random(seed * 7919 + 1)
    topics: List[Topic] = []
    cal_scale = 1.0
    evidence: List[tuple] = []  # (predicted, recalled) on the scheduler's raw curve
    reviews, cost, max_day = 0, 0.0, 0
    daily_cost: List[float] = []
    know: List[float] = []

    for day in range(sc.days):
        # New topics are rated at once ("Save and rate now"): review #0 seeds from the rating and the first
        # interval is capped at five days.
        for grade in classes[day]:
            tid = len(topics)
            t = Topic(tid, day, 0, 0, day)
            mem.seed(t, grade)
            st = sched.initial_state(grade)
            t.s, t.d = st.stability, st.difficulty
            ivl, base = ym.memory_interval(sched, st, grade, target_on(sc, day), True, cal_scale)
            ivl = ym.fuzzed_interval(ivl, base, tid, 0, True)
            t.model_due = day + REVIEW_HOUR_FRACTION + ivl
            t.due_day = math.floor(t.model_due)
            t.reviews, t.state = 1, mastery(st.stability, False)
            topics.append(t)

        # Today's plan: due topics by priority, up to the daily limit.
        day_cost, day_reviews = 0.0, 0
        if not on_holiday(sc, day) and not (sc.skip_day_rate and rng.random() < sc.skip_day_rate):
            due = [t for t in topics if t.due_day <= day and t.last_day < day]
            due.sort(key=lambda t: (-priority(t, day), t.model_due, t.tid))
            todo = due[: sc.daily_limit]
            if sc.sweep_days and day >= sc.days - sc.sweep_days and len(todo) < sc.sweep_capacity:
                chosen = {t.tid for t in todo}
                ahead = [t for t in topics if t.tid not in chosen and t.last_day < day]
                ahead.sort(key=lambda t: (sched.retrievability(day - t.last_day, t.s), t.tid))
                todo = todo + ahead[: sc.sweep_capacity - len(todo)]
            for t in todo:
                recalled = rng.random() < mem.r(t, day)
                true_grade = pick(rng, SUCCESS_GRADES) if recalled else ym.AGAIN
                reported = true_grade
                if not recalled and sc.optimistic_rate and rng.random() < sc.optimistic_rate:
                    reported = ym.HARD
                elapsed = day - t.last_day
                predicted = sched.retrievability(elapsed, t.s)
                prev_interval = t.model_due - (t.last_day + REVIEW_HOUR_FRACTION)
                if sc.calibration and ym.is_calibration_evidence(elapsed, prev_interval):
                    evidence.append((predicted, reported != ym.AGAIN))
                    if len(evidence) > ym.CAL_WINDOW:
                        evidence.pop(0)
                new = sched.next_state(ym.State(t.s, t.d), elapsed, reported)
                ivl, base = ym.memory_interval(sched, new, reported, target_on(sc, day), False, cal_scale)
                ivl = ym.fuzzed_interval(ivl, base, t.tid, t.reviews, False)
                t.s, t.d = new.stability, new.difficulty
                t.reviews += 1
                t.lapses += 1 if reported == ym.AGAIN else 0
                t.state = mastery(new.stability, reported == ym.AGAIN)
                t.model_due = day + REVIEW_HOUR_FRACTION + ivl
                t.due_day = math.floor(t.model_due)
                mem.update(t, day, true_grade)
                day_cost += 1.0 if recalled else sc.fail_cost
                day_reviews += 1
            # The app refreshes the calibration at each session start; once a day is the same thing here.
            if sc.calibration and evidence:
                cal_scale = ym.calibration_scale([e[0] for e in evidence], [e[1] for e in evidence], sched)
        reviews += day_reviews
        cost += day_cost
        max_day = max(max_day, day_reviews)
        daily_cost.append(day_cost)
        know.append(knowledge(mem, topics, day))

    return finish(sc, mem, topics, know, reviews, cost, max_day), daily_cost


def run_other(sc: Scenario, classes: List[List[int]], seed: int, daily_cost: List[float]) -> Result:
    """The twin without a schedule, given exactly the review time the Yadora twin spent.

    random / recent / cycle spend each day's time on that day. cram saves ALL of it for the last four weeks
    (the familiar "review everything before the exam"), and half-cram spreads half through the year at
    random and saves half. Same total hours in every case.
    """
    mem = TrueMemory(sc.true_weights, sc.k_true)
    rng = random.Random(seed * 7919 + 2)
    topics: List[Topic] = []
    reviews, cost = 0, 0.0
    know: List[float] = []
    total = sum(daily_cost)
    cram_days = min(28, sc.days)
    budgets = list(daily_cost)
    order_kind = sc.baseline
    if sc.baseline == "cram":
        budgets = [0.0] * (sc.days - cram_days) + [total / cram_days] * cram_days
        order_kind = "cycle"
    elif sc.baseline == "half-cram":
        budgets = [c / 2 for c in daily_cost]
        for day in range(sc.days - cram_days, sc.days):
            budgets[day] += total / 2 / cram_days

    for day in range(sc.days):
        for grade in classes[day]:
            t = Topic(len(topics), day, 0, 0, day)
            mem.seed(t, grade)
            topics.append(t)
        budget = budgets[day]
        pool = [t for t in topics if t.last_day < day]
        if budget > 0 and pool:
            kind = order_kind
            if sc.baseline == "half-cram":
                kind = "cycle" if day >= sc.days - cram_days else "random"
            if sc.sweep_days and day >= sc.days - sc.sweep_days:
                kind = "cycle"  # the final push: everything, oldest first
            for t in choose_baseline(kind, pool, day, rng):
                if budget <= 0:
                    break
                recalled = rng.random() < mem.r(t, day)
                mem.update(t, day, pick(rng, SUCCESS_GRADES) if recalled else ym.AGAIN)
                c = 1.0 if recalled else sc.fail_cost
                budget -= c
                cost += c
                reviews += 1
        know.append(knowledge(mem, topics, day))
    return finish(sc, mem, topics, know, reviews, cost, 0)


def finish(sc, mem, topics, know, reviews, cost, max_day) -> Result:
    last = sc.days - 1
    first_half = [t for t in topics if t.studied_day < sc.days // 2]
    rs = sorted(mem.r(t, last) for t in topics) if topics else [0.0]
    tenth = rs[: max(1, len(rs) // 10)]
    return Result(
        final_quiz=knowledge(mem, topics, last),
        first_half_quiz=knowledge(mem, first_half, last),
        mean_knowledge=statistics.fmean(know),
        reviews=reviews, cost=cost, max_day_reviews=max_day, topics=len(topics),
        share_at_90=sum(1 for r in rs if r >= 0.9) / len(rs),
        share_at_80=sum(1 for r in rs if r >= 0.8) / len(rs),
        weakest_tenth=statistics.fmean(tenth),
    )


def run_pair(sc: Scenario, seed: int):
    """Both twins on identical classes. Returns (Yadora Result, other twin's Result)."""
    classes = make_classes(sc, seed)
    y, daily_cost = run_yadora(sc, classes, seed)
    return y, run_other(sc, classes, seed, daily_cost)


def choose_baseline(kind: str, pool: List[Topic], day: int, rng: random.Random) -> List[Topic]:
    """An ordering of candidate topics for one day; the caller stops when the day's time is spent."""
    if kind == "random":
        order = pool[:]
        rng.shuffle(order)
        return order
    if kind == "recent":
        # Reviews what was studied recently: weight 1 / (weeks since study + 1), sampled without replacement.
        keyed = [(-math.log(rng.random()) * ((day - t.studied_day) / 7.0 + 1.0), t) for t in pool]
        keyed.sort(key=lambda x: x[0])
        return [t for _, t in keyed]
    if kind == "cycle":
        # The disciplined no-app strategy: always the topic untouched for longest.
        return sorted(pool, key=lambda t: (t.last_day, t.tid))
    raise ValueError(kind)


def summarize(sc: Scenario, seeds: int):
    ys, bs = [], []
    for s in range(seeds):
        y, b = run_pair(sc, 1000 + s)
        ys.append(y)
        bs.append(b)

    def m(xs):
        return statistics.fmean(xs), (statistics.stdev(xs) if len(xs) > 1 else 0.0)

    gain = [y.final_quiz - b.final_quiz for y, b in zip(ys, bs)]
    cram_load = None
    if sc.baseline in ("cram", "half-cram"):
        share = 1.0 if sc.baseline == "cram" else 0.5
        cram_load = statistics.fmean(y.reviews * share / min(28, sc.days) for y in ys)
    return {
        "scenario": sc.name,
        "yadora_final": m([y.final_quiz for y in ys]),
        "baseline_final": m([b.final_quiz for b in bs]),
        "gain_points": m([100 * g for g in gain]),
        "yadora_first_half": m([y.first_half_quiz for y in ys]),
        "baseline_first_half": m([b.first_half_quiz for b in bs]),
        "yadora_mean_knowledge": m([y.mean_knowledge for y in ys]),
        "baseline_mean_knowledge": m([b.mean_knowledge for b in bs]),
        "reviews_per_topic": m([y.reviews / y.topics for y in ys]),
        "reviews_per_day": m([y.reviews / sc.days for y in ys]),
        "max_day_reviews": m([y.max_day_reviews for y in ys]),
        "yadora_wins": sum(1 for g in gain if g > 0),
        "cram_reviews_per_day": cram_load,
        "seeds": seeds,
    }


def standard_scenarios() -> List[Scenario]:
    base = dict()
    out = [
        Scenario("baseline: random review", **base),
        Scenario("baseline: recent-first review", baseline="recent", **base),
        Scenario("baseline: oldest-first cycle", baseline="cycle", **base),
        Scenario("baseline: saves all review time for a 4-week cram", baseline="cram", **base),
        Scenario("baseline: half spread, half crammed", baseline="half-cram", **base),
        Scenario("announced exam: both push 30 topics/day for the last 4 weeks (other: random before)",
                 sweep_days=28, sweep_capacity=30, **base),
        Scenario("announced exam: both push 30 topics/day for the last 4 weeks (other: oldest-first before)",
                 sweep_days=28, sweep_capacity=30, baseline="cycle", **base),
        Scenario("learner forgets 2x faster (k=0.5)", k_true=0.5, **base),
        Scenario("learner forgets 2x slower (k=2)", k_true=2.0, **base),
        Scenario("k=0.5, calibration off", k_true=0.5, calibration=False, **base),
        Scenario("30% of lapses rated Hard", optimistic_rate=0.3, **base),
        Scenario("60% of lapses rated Hard", optimistic_rate=0.6, **base),
        Scenario("skips 30% of days", skip_day_rate=0.3, **base),
        Scenario("3-week holiday at day 150", holiday=(150, 21), **base),
        Scenario("worst case: k=0.5, 30% optimistic, skips 30%", k_true=0.5, optimistic_rate=0.3,
                 skip_day_rate=0.3, baseline="cycle", **base),
        Scenario("heavy load: 6 new/day, limit 30", new_per_study_day=6, daily_limit=30, **base),
    ]
    return out


def retention_sweep(seeds: int) -> List[dict]:
    rows = []
    for r in (0.80, 0.85, 0.90, 0.93, 0.95, 0.97):
        sc = Scenario(f"retention {r:.2f}", retention=r)
        s = summarize(sc, seeds)
        s["retention"] = r
        rows.append(s)
    return rows


def fmt(pair, pct=True, digits=1):
    mean, sd = pair
    if pct:
        return f"{100 * mean:.{digits}f}% ±{100 * sd:.{digits}f}"
    return f"{mean:.{digits}f} ±{sd:.{digits}f}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seeds", type=int, default=8)
    ap.add_argument("--quick", action="store_true", help="3 seeds, 180 days")
    ap.add_argument("--weights", help="JSON file with a 21-weight list used as the TRUE memory")
    ap.add_argument("--json", help="also write the results here")
    args = ap.parse_args()
    seeds = 3 if args.quick else args.seeds
    scenarios = standard_scenarios()
    if args.weights:
        w = json.load(open(args.weights))
        w = w["weights"] if isinstance(w, dict) else w
        for sc in scenarios:
            sc.true_weights = w
    if args.quick:
        for sc in scenarios:
            sc.days = 180
            if sc.holiday:
                sc.holiday = (90, 21)

    results = [summarize(sc, seeds) for sc in scenarios]
    print(f"## Twins after {scenarios[0].days} days ({seeds} seeds each; mean ± sd)\n")
    print("| scenario | Yadora twin: final quiz | other twin: final quiz | gain (points) | Yadora wins | "
          "Yadora: first-half topics | other: first-half topics | Yadora: average over the year | "
          "other: average over the year | reviews/day | busiest day |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in results:
        if r["cram_reviews_per_day"]:
            r["scenario"] += f" (needs {r['cram_reviews_per_day']:.0f} topic reviews/day while cramming)"
        print(f"| {r['scenario']} | {fmt(r['yadora_final'])} | {fmt(r['baseline_final'])} | "
              f"{fmt(r['gain_points'], pct=False)} | {r['yadora_wins']}/{r['seeds']} | "
              f"{fmt(r['yadora_first_half'])} | {fmt(r['baseline_first_half'])} | "
              f"{fmt(r['yadora_mean_knowledge'])} | {fmt(r['baseline_mean_knowledge'])} | "
              f"{fmt(r['reviews_per_day'], pct=False)} | {r['max_day_reviews'][0]:.0f} |")

    sweep = retention_sweep(seeds) if not args.quick else []
    if sweep:
        print("\n## Retention target (Yadora twin vs random-review twin at equal time)\n")
        print("| target | Yadora final quiz | other twin | gain (points) | reviews/topic/year | reviews/day | "
              "quiz points per 100 reviews/topic |")
        print("|---|---|---|---|---|---|---|")
        for r in sweep:
            eff = 100 * r["yadora_final"][0] / r["reviews_per_topic"][0]
            print(f"| {r['retention']:.2f} | {fmt(r['yadora_final'])} | {fmt(r['baseline_final'])} | "
                  f"{fmt(r['gain_points'], pct=False)} | {fmt(r['reviews_per_topic'], pct=False)} | "
                  f"{fmt(r['reviews_per_day'], pct=False)} | {eff:.1f} |")
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"scenarios": results, "retention": sweep}, f, indent=2)


if __name__ == "__main__":
    main()

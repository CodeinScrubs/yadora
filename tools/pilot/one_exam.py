#!/usr/bin/env python3
"""
One exam, one year, a fixed daily time budget, a finite syllabus (docs/RESEARCH.md section 2.8).

simulate.py, residency.py and experiments.py give every learner a fixed number of new topics a day and ask whether
scheduling beats review without a schedule at equal time. This asks the question a student with ONE exam date asks
(the owner, 2026-10-03: a residency exam in a year, 7 hours of study a day, about 2,000 topics by the end, new topics
on some days and none on others): with a budget of B review-units a day, a syllabus of N topics and 365 days, how
should the day be split between new material and reviews, which retention target leaves the most on exam day, and
what is spare time worth?

Model. Everything that decides an interval is the app's own rule, transcribed in yadora_model.py:
  * each study day (6 of 7) has a budget of B "review units": a remembered review costs 1, a forgotten one 1.5,
    first-studying and rating a new topic costs c_new (default 3);
  * the day runs as the app runs it: due reviews by the app's priority score, then NEW topics with what is left (up
    to max_new a day) until the whole syllabus has been studied once, then spare time on Review ahead (weakest
    predicted recall first). --caps limits the share of the day due reviews may take BEFORE new topics (1.0 = reviews
    first); what the cap held back is reviewed after the new topics, with whatever is left. In the app that split is
    the daily limit, and doing the day's new material before "Review more anyway";
  * the retention target can differ before and after the syllabus is complete (r1, r2); r1 == r2 is a flat target;
  * true memory is FSRS-6 with time dilated by k (k < 1 forgets faster), and ratings are honest;
  * the exam score is the mean recall on the last day over ALL N topics, a topic never studied counting 0: what a
    perfectly calibrated candidate earns on a 4-option exam with negative marking, answering only when they know.

Run (standard library only; --workers processes in parallel):
  python3 tools/pilot/one_exam.py --selftest
  python3 tools/pilot/one_exam.py --grid --preset three --ks 1.0 --ns 1000,1600 --budgets 20,30,45 \\
      --caps 0.4,0.6,1.0 --seeds 3 --out grid.json
  python3 tools/pilot/one_exam.py --summarize grid.json

Limits, stated in section 2.8: the simulated memory is FSRS-6-shaped and the ratings honest; every topic costs the
same; no exam blueprint (some subjects weigh more), no rating inflation, no exam-aware skipping.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import statistics
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import simulate as sim  # noqa: E402
import yadora_model as ym  # noqa: E402


@dataclass(frozen=True)
class Cfg:
    n_topics: int
    budget: float                 # review units per study day
    c_new: float = 3.0            # cost of first-studying and rating one topic, in review units
    k_true: float = 1.0           # the learner forgets k times as slowly as the FSRS-6 defaults assume
    r1: float = 0.90              # target while the syllabus is not yet complete
    r2: float = 0.90              # target once every topic has been studied
    max_new: int = 10
    study_days: int = 6           # of every 7 days
    fail_cost: float = 1.5
    ahead: bool = True            # spare time goes to Review ahead
    cap_share: float = 1.0        # due reviews may take at most this share of the day BEFORE new topics (1.0 = reviews first)
    days: int = 365


def run(cfg: Cfg, seed: int) -> dict:
    sched = ym.Fsrs6()
    mem = sim.TrueMemory(ym.DEFAULT_WEIGHTS, cfg.k_true)
    rng = random.Random(seed * 7919 + 1)
    topics: list = []
    evidence: list = []
    state = {"cal": 1.0, "reviews": 0}
    debt = 0.0
    done_day = None

    def do_review(t, day: int, target: float) -> float:
        recalled = rng.random() < mem.r(t, day)
        true_grade = sim.pick(rng, sim.SUCCESS_GRADES) if recalled else ym.AGAIN
        reported = true_grade
        elapsed = day - t.last_day
        predicted = sched.retrievability(elapsed, t.s)
        prev_interval = t.model_due - (t.last_day + sim.REVIEW_HOUR_FRACTION)
        if ym.is_calibration_evidence(elapsed, prev_interval):
            evidence.append((predicted, reported != ym.AGAIN))
            if len(evidence) > ym.CAL_WINDOW:
                evidence.pop(0)
        new = sched.next_state(ym.State(t.s, t.d), elapsed, reported)
        ivl, base = ym.memory_interval(sched, new, reported, target, False, state["cal"])
        ivl = ym.fuzzed_interval(ivl, base, t.tid, t.reviews, False)
        t.s, t.d = new.stability, new.difficulty
        t.reviews += 1
        t.lapses += 1 if reported == ym.AGAIN else 0
        t.state = sim.mastery(new.stability, reported == ym.AGAIN)
        t.model_due = day + sim.REVIEW_HOUR_FRACTION + ivl
        t.due_day = math.floor(t.model_due)
        mem.update(t, day, true_grade)
        state["reviews"] += 1
        return 1.0 if recalled else cfg.fail_cost

    for day in range(cfg.days):
        study = (day % 7) < cfg.study_days
        budget = (cfg.budget if study else 0.0) - debt
        target = cfg.r2 if len(topics) >= cfg.n_topics else cfg.r1

        if study and budget > 0:
            # (a) due reviews, most urgent first (the app's own priority score), up to the cap
            due = [t for t in topics if t.due_day <= day and t.last_day < day]
            due.sort(key=lambda t: (-sim.priority(t, day, sched), t.model_due, t.tid))
            spent = 0.0
            cap = cfg.cap_share * cfg.budget
            for t in due:
                if budget <= 0 or spent >= cap:
                    break
                c = do_review(t, day, target)
                budget -= c
                spent += c

        if study and budget > 0 and len(topics) < cfg.n_topics:
            # (b) new topics with what is left of the day
            new_today = 0
            while budget > 0 and len(topics) < cfg.n_topics and new_today < cfg.max_new:
                grade = sim.pick(rng, sim.FIRST_GRADES)
                tid = len(topics)
                t = sim.Topic(tid, day, 0, 0, day)
                mem.seed(t, grade)
                st = sched.initial_state(grade)
                t.s, t.d = st.stability, st.difficulty
                ivl, base = ym.memory_interval(sched, st, grade, target, True, state["cal"])
                ivl = ym.fuzzed_interval(ivl, base, tid, 0, True)
                t.model_due = day + sim.REVIEW_HOUR_FRACTION + ivl
                t.due_day = math.floor(t.model_due)
                t.reviews, t.state = 1, sim.mastery(st.stability, False)
                topics.append(t)
                budget -= cfg.c_new
                new_today += 1
            if len(topics) >= cfg.n_topics and done_day is None:
                done_day = day

        if study and budget > 0:
            # (b2) the due reviews the cap held back, most urgent first
            due2 = [t for t in topics if t.due_day <= day and t.last_day < day]
            due2.sort(key=lambda t: (-sim.priority(t, day, sched), t.model_due, t.tid))
            for t in due2:
                if budget <= 0:
                    break
                budget -= do_review(t, day, target)

        if study and budget > 0 and cfg.ahead:
            # (c) spare time: Review ahead, weakest predicted recall first
            cand = [t for t in topics if t.last_day < day and t.due_day > day]
            cand.sort(key=lambda t: (sched.retrievability(day - t.last_day, t.s), t.tid))
            for t in cand:
                if budget <= 0:
                    break
                budget -= do_review(t, day, target)

        debt = max(0.0, -budget)
        if evidence:
            state["cal"] = ym.calibration_scale([e[0] for e in evidence], [e[1] for e in evidence], sched)

    last = cfg.days - 1
    rs = sorted(mem.r(t, last) for t in topics)
    tenth = rs[: max(1, len(rs) // 10)] if rs else [0.0]
    return {
        "score": sum(rs) / cfg.n_topics,                      # topics never studied count 0
        "coverage": len(topics) / cfg.n_topics,
        "mean_r_learned": statistics.fmean(rs) if rs else 0.0,
        "weakest_tenth": statistics.fmean(tenth),
        "share_at_90": (sum(1 for r in rs if r >= 0.9) / len(rs)) if rs else 0.0,
        "reviews_per_day": state["reviews"] / cfg.days,
        "done_day": done_day if done_day is not None else -1,
    }


def run_cfg(args):
    cfg, seed = args
    return cfg, seed, run(cfg, seed)


def selftest():
    """With no budget limit, 3 new topics on 6 days a week and no Review ahead, this is simulate.py's Yadora twin:
    RESEARCH.md section 2 reports a final quiz of 95.3% and about 6.0 reviews per topic in the year at 0.90."""
    cfg = Cfg(n_topics=939, budget=1e9, c_new=0.0, max_new=3, ahead=False)
    rows = [run(cfg, 1000 + s) for s in range(4)]
    print("selftest (target 0.90, the twin simulation's world, 4 seeds):")
    print(f"  one_exam.py      final quiz {100 * statistics.fmean(r['score'] for r in rows):.1f}%  reviews/topic "
          f"{statistics.fmean(r['reviews_per_day'] for r in rows) * 365 / 939:.1f}   (RESEARCH.md: 95.3%, ~6.0)")
    sc = sim.Scenario("check", days=365)
    ys = []
    for s in range(4):
        classes = sim.make_classes(sc, 1000 + s)
        y, _ = sim.run_yadora(sc, classes, 1000 + s)
        ys.append(y)
    print(f"  simulate.py      final quiz {100 * statistics.fmean(y.final_quiz for y in ys):.1f}%  reviews/topic "
          f"{statistics.fmean(y.reviews / y.topics for y in ys):.1f}   (same seeds)")


POLICIES = [
    # (label, r1, r2, ahead)
    ("flat 0.85", 0.85, 0.85, True),
    ("flat 0.90", 0.90, 0.90, True),
    ("flat 0.93", 0.93, 0.93, True),
    ("flat 0.95", 0.95, 0.95, True),
    ("flat 0.97", 0.97, 0.97, True),
    ("0.85 then 0.95", 0.85, 0.95, True),
    ("0.85 then 0.97", 0.85, 0.97, True),
    ("0.90 then 0.95", 0.90, 0.95, True),
    ("0.90 then 0.97", 0.90, 0.97, True),
    ("flat 0.90, no review-ahead", 0.90, 0.90, False),
    ("flat 0.95, no review-ahead", 0.95, 0.95, False),
]

PRESETS = {
    "v1": ["flat 0.85", "flat 0.90", "flat 0.93", "flat 0.95", "flat 0.97", "0.85 then 0.95", "0.90 then 0.95",
           "0.90 then 0.97", "flat 0.90, no review-ahead", "flat 0.95, no review-ahead"],
    "core": ["flat 0.85", "flat 0.90", "flat 0.95", "0.85 then 0.95", "0.90 then 0.95"],
    "three": ["flat 0.85", "flat 0.90", "flat 0.95"],
    "flat": ["flat 0.85", "flat 0.90", "flat 0.93", "flat 0.95", "flat 0.97"],
}


def grid(seeds: int, out: str, ks, ns, budgets, c_new: float, workers: int, only=None, caps=(1.0,)):
    jobs = []
    chosen = [p for p in POLICIES if only is None or p[0] in only]
    for k in ks:
        for n in ns:
            for b in budgets:
                for cap in caps:
                    for label, r1, r2, ahead in chosen:
                        cfg = Cfg(n_topics=n, budget=b, c_new=c_new, k_true=k, r1=r1, r2=r2, ahead=ahead, cap_share=cap)
                        for s in range(seeds):
                            jobs.append((cfg, 5000 + s))
    t0 = time.time()
    print(f"{len(jobs)} runs on {workers} workers ...", flush=True)
    results = {}
    with Pool(workers) as pool:
        for i, (cfg, seed, res) in enumerate(pool.imap_unordered(run_cfg, jobs, chunksize=2), 1):
            results.setdefault(cfg, []).append(res)
            if i % 50 == 0:
                print(f"  {i}/{len(jobs)}  {time.time() - t0:.0f}s", flush=True)
    rows = []
    for cfg, rs in results.items():
        label = next(l for l, r1, r2, a in POLICIES if (r1, r2, a) == (cfg.r1, cfg.r2, cfg.ahead))
        rows.append({
            "k": cfg.k_true, "n": cfg.n_topics, "budget": cfg.budget, "c_new": cfg.c_new, "policy": label,
            "cap_share": cfg.cap_share,
            "score": statistics.fmean(r["score"] for r in rs),
            "score_sd": statistics.stdev([r["score"] for r in rs]) if len(rs) > 1 else 0.0,
            "coverage": statistics.fmean(r["coverage"] for r in rs),
            "mean_r_learned": statistics.fmean(r["mean_r_learned"] for r in rs),
            "weakest_tenth": statistics.fmean(r["weakest_tenth"] for r in rs),
            "share_at_90": statistics.fmean(r["share_at_90"] for r in rs),
            "reviews_per_day": statistics.fmean(r["reviews_per_day"] for r in rs),
            "done_day": statistics.fmean(r["done_day"] for r in rs),
            "seeds": len(rs),
        })
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rows, f, indent=1)
    print(f"wrote {out} in {time.time() - t0:.0f}s", flush=True)


def summarize(path: str):
    """The grid as markdown tables, one per world (syllabus, budget, first-study cost, learner, review share)."""
    with open(path, encoding="utf-8-sig") as f:
        rows = json.load(f)
    order = [p[0] for p in POLICIES]
    by = defaultdict(list)
    for r in rows:
        by[(r["k"], r["n"], r["c_new"], r["budget"], r.get("cap_share", 1.0))].append(r)
    for (k, n, c_new, budget, cap), rs in sorted(by.items()):
        rs.sort(key=lambda r: order.index(r["policy"]) if r["policy"] in order else 99)
        best = max(rs, key=lambda r: r["score"])
        print(f"\n### {n} topics, {budget:g} review-units a day (first study {c_new:g}), learner k={k}; "
              f"reviews may take at most {cap:.0%} of the day before new topics")
        print("| policy | exam score (all topics) | coverage | recall of the studied | weakest tenth | topics at 90%+ "
              "| reviews a day | syllabus done on day |")
        print("|---|---|---|---|---|---|---|---|")
        for r in rs:
            mark = " (best)" if r is best else ""
            done = "never" if r["done_day"] < 0 else f"{r['done_day']:.0f}"
            print(f"| {r['policy']} | {100 * r['score']:.1f}% ±{100 * r['score_sd']:.1f}{mark} | {100 * r['coverage']:.0f}% | "
                  f"{100 * r['mean_r_learned']:.1f}% | {100 * r['weakest_tenth']:.1f}% | {100 * r['share_at_90']:.0f}% | "
                  f"{r['reviews_per_day']:.1f} | {done} |")


def main():
    for stream in (sys.stdout, sys.stderr):  # "±" on a cp1252 console (test_analyze.py checks analyze.py the same way)
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--selftest", action="store_true", help="reproduce simulate.py's Yadora twin")
    ap.add_argument("--grid", action="store_true", help="run a grid of worlds and policies")
    ap.add_argument("--summarize", metavar="GRID_JSON", help="print a grid's results as markdown tables")
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--out", default="grid.json")
    ap.add_argument("--ks", default="1.0", help="comma list of learner speeds (1 = the defaults, 0.6 forgets faster)")
    ap.add_argument("--ns", default="1000", help="comma list of syllabus sizes")
    ap.add_argument("--budgets", default="20,30,45", help="comma list of review-units a study day")
    ap.add_argument("--c-new", type=float, default=3.0, help="cost of a first study in review units")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    ap.add_argument("--preset", default="three", help="policy subset: " + ", ".join(PRESETS) + ", or all")
    ap.add_argument("--caps", default="1.0", help="comma list of review shares before new topics (1.0 = reviews first)")
    a = ap.parse_args()
    if a.selftest:
        selftest()
    if a.grid:
        only = None if a.preset == "all" else PRESETS[a.preset]
        grid(a.seeds, a.out, [float(x) for x in a.ks.split(",")], [int(x) for x in a.ns.split(",")],
             [float(x) for x in a.budgets.split(",")], a.c_new, a.workers, only,
             tuple(float(x) for x in a.caps.split(",")))
    if a.summarize:
        summarize(a.summarize)
    if not (a.selftest or a.grid or a.summarize):
        ap.print_help()


if __name__ == "__main__":
    main()

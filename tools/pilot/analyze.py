#!/usr/bin/env python3
"""
Yadora pilot analysis: read analytics exports, check them, measure the scheduler, write a report.

  python3 tools/pilot/analyze.py exports/ --out pilot_report
  python3 tools/pilot/analyze.py a.json b.json c.json --out pilot_report

Input: one or more files from Settings -> "Share research data" (or "Save research data to a file"), or
folders holding them. Several exports from the same participant are fine: the newest one is used, because
every export carries the whole history. Standard library only (Python 3.9+); nothing to install.

Output, in --out:
  report.md            findings, with every number behind them. Hand this (and summary.json) to a person or an AI.
  summary.json         the same numbers, machine-readable
  reviews.csv          one row per review log, joined with topic and participant context (UTF-8, opens in Excel)
  topics.csv           one row per topic
  participants.csv     one row per participant
  fitted_weights.json  a pooled refit of the first-study and curve-shape weights, when there is enough data;
                       python3 tools/pilot/simulate.py --weights pilot_report/fitted_weights.json reruns the
                       twin simulation with it

What it checks, in the order the report gives it:
  1. Integrity: every FSRS-6 review is replayed through an independent transcription of the scheduler
     (yadora_model.py, itself checked against py-fsrs). Elapsed days, the predicted recall and the scheduled
     interval must come out EXACTLY as the phone stored them. Any mismatch is a bug, not a statistic.
  2. Adherence: are reviews done near their dates, how big is the backlog, are first ratings given on the
     study day. A scheduling problem and a usage problem look alike in the numbers; this separates them.
  3. Calibration: does the model's predicted recall match what learners report? Pooled, per participant,
     per review number, per first rating, per elapsed time, per method.
  4. The first interval: observed recall at the first review, per first rating, against the default weights.
  5. Self-ratings against question scores, where learners entered them.
  6. Review method: does the next review find a topic better or worse than predicted, by how it was reviewed?
  7. A held-out pooled refit of a few weights, judged the way the app judges its personal model.
  8. Decision rules, fixed in advance in docs/PILOT.md, evaluated on the numbers above.

Read CLAUDE.md's "Settled decisions" before acting on anything here: several obvious-looking changes
(exam-date compression, the key-point cap, calendar-day due dates) were considered and rejected on purpose.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import glob
import json
import math
import os
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

sys.path.insert(0, os.path.dirname(__file__))
import yadora_model as ym  # noqa: E402

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None

DAY_MS = 86_400_000
MIN_EXPORT_VERSION = 6  # time zone first exported in v6; older files cannot be replayed exactly
RATINGS = ("Forgot", "Hard", "Good", "Easy")


# ---------------------------------------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------------------------------------

@dataclass
class Export:
    path: str
    data: dict
    participant: str
    exported_at: int
    tz: dt.tzinfo
    tz_name: str


def expand(paths: Sequence[str]) -> List[str]:
    out = []
    for p in paths:
        if os.path.isdir(p):
            out += sorted(glob.glob(os.path.join(p, "**", "*.json"), recursive=True))
        else:
            out.append(p)
    return out


def zone_of(d: dict) -> Tuple[dt.tzinfo, str]:
    env = d.get("environment") or {}
    name = env.get("timeZoneId")
    if name and ZoneInfo is not None:
        try:
            return ZoneInfo(name), name
        except Exception:
            pass
    off = env.get("utcOffsetMinutesAtExport")
    if off is not None:
        return dt.timezone(dt.timedelta(minutes=off)), f"UTC{off / 60:+.1f} (fixed; DST not modelled)"
    return dt.timezone.utc, "UTC (no zone in file)"


def load_exports(paths: Sequence[str], warn) -> List[Export]:
    newest: Dict[str, Export] = {}
    for path in expand(paths):
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
        except Exception as e:
            warn(f"{path}: not readable JSON ({e}); skipped")
            continue
        if not isinstance(d, dict) or "reviewLogs" not in d or "studyUnits" not in d:
            if isinstance(d, dict) and "backupVersion" in d:
                warn(f"{path}: this is a full BACKUP, not a research export (it contains titles and notes). "
                     f"Ask for Settings -> Share research data instead; skipped")
            else:
                warn(f"{path}: not a Yadora research export; skipped")
            continue
        version = d.get("exportVersion", 0)
        if version < MIN_EXPORT_VERSION:
            warn(f"{path}: export version {version} has no time zone; day counts may be off by one")
        pid = d.get("participantId") or ("file:" + os.path.splitext(os.path.basename(path))[0])
        tz, tz_name = zone_of(d)
        e = Export(path, d, pid, int(d.get("exportedAt", 0)), tz, tz_name)
        if pid in newest:
            older, keep = sorted([newest[pid], e], key=lambda x: x.exported_at)
            warn(f"{pid}: two exports ({os.path.basename(older.path)}, {os.path.basename(keep.path)}); "
                 f"using the newer, which holds the whole history")
            newest[pid] = keep
        else:
            newest[pid] = e
    return sorted(newest.values(), key=lambda e: e.participant)


# ---------------------------------------------------------------------------------------------------------
# Replay
# ---------------------------------------------------------------------------------------------------------

@dataclass
class Row:
    """One review log, with everything the analysis needs next to it."""
    participant: str
    unit_id: int
    log_id: int
    at: int
    local: dt.datetime
    log_type: str
    rating: str
    understanding: str
    review_number: int            # graded reviews before this one (0 = first study)
    elapsed_days: Optional[float]  # replayed, whole local calendar days since the previous graded event
    stored_elapsed: float
    predicted: Optional[float]     # stored retrievabilityAtReview (RECALL rows)
    replayed_predicted: Optional[float]
    calibration_scale: float
    desired_retention: float
    next_interval: float
    replayed_interval: Optional[float]
    previous_interval: float
    days_late: Optional[float]      # as exported: against the MEMORY date the previous review set
    days_late_effective: Optional[float]  # against the date that actually applied (memory or repair clock)
    deferrals: int
    first_grade: Optional[str]
    subject: str
    high_yield: bool
    notes_length: int
    methods: Tuple[str, ...]
    q_correct: int
    q_total: int
    session_kind: str
    scheduler_version: str
    parameter_set: int
    policy: str
    duration_ms: int
    lapses_before: int
    merged: bool
    decay: float = -ym.DEFAULT_WEIGHTS[20]   # the curve shape of this log's own weight set
    stability_before: Optional[float] = None
    difficulty_before: Optional[float] = None
    stability_after: Optional[float] = None
    mismatch: List[str] = field(default_factory=list)

    @property
    def is_recall(self) -> bool:
        return self.log_type == "RECALL"

    @property
    def success(self) -> Optional[bool]:
        return None if not self.is_recall else self.rating != "Forgot"

    @property
    def q_pct(self) -> Optional[float]:
        return self.q_correct / self.q_total if self.q_total > 0 and 0 <= self.q_correct <= self.q_total else None

    @property
    def calibrated(self) -> Optional[float]:
        """The prediction after the per-user scale, as the schedule effectively used it."""
        if self.predicted is None or not (0 < self.predicted <= 1):
            return None
        k = ym.safe_scale(self.calibration_scale if self.calibration_scale > 0 else 1.0)
        ratio = self.predicted ** (1.0 / self.decay) - 1.0
        return (1.0 + ratio / k) ** self.decay


def local_day(ms: int, tz) -> dt.date:
    return dt.datetime.fromtimestamp(ms / 1000, tz).date()


def calendar_days(a_ms: int, b_ms: int, tz) -> float:
    return float(max((local_day(b_ms, tz) - local_day(a_ms, tz)).days, 0))


def weight_sets(d: dict) -> Dict[int, Tuple[float, ...]]:
    sets = {0: ym.DEFAULT_WEIGHTS}
    for s in d.get("memoryParameterSets") or []:
        w = s.get("weights") or []
        if len(w) == 21:
            sets[int(s["id"])] = tuple(float(x) for x in w)
    return sets


def merged_units(d: dict) -> set:
    out = set()
    for e in d.get("eventLogs") or []:
        if e.get("type") == "MERGE":
            if e.get("unitId") is not None:
                out.add(int(e["unitId"]))
            for part in str(e.get("detail") or "").split(","):
                if part.strip().lstrip("-").isdigit():
                    out.add(int(part))
    return out


def replay_unit(logs: List[dict], unit: dict, weights: Tuple[float, ...], tz) -> List[dict]:
    """The canonical reconstruction, under one weight set: the first log seeds, a later FIRST_STUDY log is a
    re-encoding exposure (moves the clock, not the state), every other log is a graded recall.

    Returns per log: state before/after, elapsed days, predicted recall, graded count before, and the memory
    interval the rules give (before fuzz) plus whether it is a first study.
    """
    m = ym.Fsrs6(weights)
    out = []
    state = None
    last = None
    graded = 0
    for i, log in enumerate(logs):
        t = int(log["reviewedAt"])
        rating = log["memoryRating"]
        grade = ym.GRADE_OF.get(rating, ym.GOOD)
        if state is None:
            new = m.initial_state(grade)
            elapsed = calendar_days(int(unit.get("studiedAt", t)), t, tz)
            out.append(dict(before=None, after=new, elapsed=elapsed, predicted=None, graded_before=0, first=True, exposure=False))
            state, last, graded = new, t, 1
            continue
        if log.get("logType") == "FIRST_STUDY":
            out.append(dict(before=state, after=state, elapsed=calendar_days(last, t, tz), predicted=None,
                            graded_before=graded, first=False, exposure=True))
            last = t
            continue
        elapsed = calendar_days(last, t, tz)
        r = m.retrievability(math.floor(elapsed), state.stability)
        new = m.next_state(state, math.floor(elapsed), grade)
        out.append(dict(before=state, after=new, elapsed=elapsed, predicted=r, graded_before=graded, first=False, exposure=False))
        state, last, graded = new, t, graded + 1
    return out


def backs_off(policy: str) -> bool:
    """MedScheduler.backsOffRepairClock: YADORA-6 onward (and unstamped rows) double the repair deadline."""
    return policy not in ("YADORA-1", "YADORA-2", "YADORA-3", "YADORA-4", "YADORA-5")


def effective_due_dates(logs: List[dict]) -> List[Optional[int]]:
    """For each log, the date the topic was ACTUALLY due: the earlier of the memory date and the understanding
    repair deadline the previous review set. The export's scheduledForAt uses the memory date alone, so a
    topic the repair clock brought back after three days reads as reviewed "early" there."""
    out: List[Optional[int]] = [None] * len(logs)
    streak_history: List[Tuple[str, str]] = []
    for i, log in enumerate(logs):
        if i > 0:
            prev = logs[i - 1]
            prev_at = int(prev["reviewedAt"])
            memory_days = float(prev.get("nextIntervalDays", 0))
            due = prev_at + int(memory_days * DAY_MS)
            before_prev = streak_history[:-1]
            streak = 0
            for m, u in reversed(before_prev):
                if m != "Forgot" and u in ("Partial", "Confused"):
                    streak += 1
                else:
                    break
            und = prev.get("understandingRating", "")
            rating = prev.get("memoryRating", "")
            repair = None
            if und != "NotAsked" or rating == "Forgot":
                repair = ym.remediation_days(rating, und if und != "NotAsked" else "Partial",
                                             streak if backs_off(prev.get("schedulerPolicyVersion", "")) else 0)
            if repair is not None and (rating == "Forgot" or repair < memory_days):
                due = min(due, prev_at + int(repair * DAY_MS))
            out[i] = due
        streak_history.append((log.get("memoryRating", ""), log.get("understandingRating", "")))
    return out


def build_rows(e: Export) -> Tuple[List[Row], List[dict]]:
    d = e.data
    subjects = {str(k): v for k, v in (d.get("subjects") or {}).items()}
    units = {int(u["id"]): u for u in d["studyUnits"]}
    sets = weight_sets(d)
    merged = merged_units(d)
    by_unit: Dict[int, List[dict]] = defaultdict(list)
    for log in d["reviewLogs"]:
        by_unit[int(log["studyUnitId"])].append(log)
    rows: List[Row] = []
    for uid, logs in by_unit.items():
        logs.sort(key=lambda l: (int(l["reviewedAt"]), int(l["id"])))
        unit = units.get(uid, {})
        # One replay per weight set the topic's logs name: a log is reproduced on its OWN set.
        replays = {}
        for sid in {int(l.get("parameterSetId", 0)) for l in logs}:
            if sid in sets:
                replays[sid] = replay_unit(logs, unit, sets[sid], e.tz)
        first_grade = None
        lapses = 0
        effective_due = effective_due_dates(logs)
        for i, log in enumerate(logs):
            sid = int(log.get("parameterSetId", 0))
            rp = replays.get(sid, [None] * len(logs))[i]
            log_type = log.get("logType", "UNKNOWN")
            rating = log["memoryRating"]
            if i == 0:
                first_grade = {"Easy": "Easy", "Good": "Medium", "Hard": "Hard"}.get(rating, rating)
            pred = log.get("retrievabilityAtReview")
            pred = float(pred) if pred is not None and 0 <= float(pred) <= 1 and log_type == "RECALL" else None
            methods = tuple(log.get("reviewMethods") or [])
            if isinstance(log.get("reviewMethods"), str):  # tolerate a raw comma string
                methods = tuple(x for x in log["reviewMethods"].split(",") if x)
            row = Row(
                participant=e.participant, unit_id=uid, log_id=int(log["id"]), at=int(log["reviewedAt"]),
                local=dt.datetime.fromtimestamp(int(log["reviewedAt"]) / 1000, e.tz),
                log_type=log_type, rating=rating, understanding=log.get("understandingRating", ""),
                review_number=rp["graded_before"] if rp else i,
                elapsed_days=rp["elapsed"] if rp else None,
                stored_elapsed=float(log.get("elapsedDays", -1)),
                predicted=pred,
                replayed_predicted=rp["predicted"] if rp else None,
                calibration_scale=float(log.get("calibrationScaleAtReview", -1)),
                desired_retention=float(log.get("desiredRetentionAtReview", -1)),
                next_interval=float(log.get("nextIntervalDays", 0)),
                replayed_interval=None,
                previous_interval=float(log.get("previousIntervalDays", 0)),
                days_late=log.get("daysLate"),
                days_late_effective=(None if effective_due[i] is None or int(log.get("deferralsBeforeThisReview", 0) or 0) > 0
                                     else (int(log["reviewedAt"]) - effective_due[i]) / DAY_MS),
                deferrals=int(log.get("deferralsBeforeThisReview", 0) or 0),
                first_grade=first_grade,
                subject=subjects.get(str(unit.get("subjectId")), "") if unit.get("subjectId") is not None else "",
                high_yield=bool(unit.get("highYield", False)),
                notes_length=int(unit.get("notesLength", -1) if unit.get("notesLength") is not None else -1),
                methods=methods,
                q_correct=int(log.get("questionsCorrect", -1)), q_total=int(log.get("questionsTotal", -1)),
                session_kind=log.get("sessionKind") or "",
                scheduler_version=log.get("schedulerVersion", ""),
                parameter_set=sid,
                policy=log.get("schedulerPolicyVersion", ""),
                duration_ms=int(log.get("reviewDurationMs", -1)),
                lapses_before=lapses,
                merged=uid in merged,
                decay=-(sets.get(sid) or ym.DEFAULT_WEIGHTS)[20],
            )
            if rp:
                row.stability_before = rp["before"].stability if rp["before"] else None
                row.difficulty_before = rp["before"].difficulty if rp["before"] else None
                row.stability_after = rp["after"].stability
            check_row(row, log, rp, sets.get(sid))
            if log_type == "RECALL" and rating == "Forgot":
                lapses += 1
            rows.append(row)
    return rows, list(units.values())


def check_row(row: Row, log: dict, rp: Optional[dict], weights) -> None:
    """Is this FSRS-6 log exactly what the rules produce? Merged topics are skipped: a merge averages states
    on purpose, so replaying the combined history is not supposed to reproduce it."""
    if row.scheduler_version != "FSRS-6" or row.merged or rp is None or weights is None or rp["exposure"]:
        return
    m = ym.Fsrs6(weights)
    if row.stored_elapsed >= 0 and abs(rp["elapsed"] - row.stored_elapsed) > 1e-9 and not rp["first"]:
        row.mismatch.append(f"elapsed {row.stored_elapsed:g} stored vs {rp['elapsed']:g} replayed")
    if row.is_recall and row.predicted is not None and rp["predicted"] is not None:
        if abs(rp["predicted"] - row.predicted) > 1e-6 * max(1.0, row.predicted):
            row.mismatch.append(f"predicted recall {row.predicted:.6f} stored vs {rp['predicted']:.6f} replayed")
    grade = ym.GRADE_OF.get(row.rating, ym.GOOD)
    if row.desired_retention > 0:
        ivl, base = ym.memory_interval(m, rp["after"], grade, row.desired_retention, rp["first"],
                                       row.calibration_scale if row.calibration_scale > 0 else 1.0)
        ivl = ym.fuzzed_interval(ivl, base, row.unit_id, rp["graded_before"], rp["first"])
        row.replayed_interval = ivl
        if abs(ivl - row.next_interval) > 1e-6 * max(1.0, ivl):
            row.mismatch.append(f"interval {row.next_interval:.6f} stored vs {ivl:.6f} replayed")


# ---------------------------------------------------------------------------------------------------------
# Statistics helpers
# ---------------------------------------------------------------------------------------------------------

def mean(xs):
    xs = [x for x in xs if x is not None]
    return statistics.fmean(xs) if xs else None


def ci95(successes: int, n: int) -> Tuple[Optional[float], Optional[float]]:
    """Wilson interval for a proportion."""
    if n == 0:
        return None, None
    p = successes / n
    z = 1.96
    den = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / den
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return centre - half, centre + half


def fmt_p(x, digits=1):
    return "–" if x is None else f"{100 * x:.{digits}f}%"


def fmt(x, digits=3):
    return "–" if x is None or (isinstance(x, float) and math.isnan(x)) else f"{x:.{digits}f}"


def spearman(xs: List[float], ys: List[float]) -> Optional[float]:
    if len(xs) < 3:
        return None

    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
                j += 1
            for k in range(i, j + 1):
                r[order[k]] = (i + j) / 2 + 1
            i = j + 1
        return r

    rx, ry = ranks(xs), ranks(ys)
    mx, my = statistics.fmean(rx), statistics.fmean(ry)
    cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    vx = math.sqrt(sum((a - mx) ** 2 for a in rx))
    vy = math.sqrt(sum((b - my) ** 2 for b in ry))
    return cov / (vx * vy) if vx and vy else None


def calib_block(rows: List[Row], pred_attr="predicted") -> dict:
    pairs = [(getattr(r, pred_attr), r.success) for r in rows if getattr(r, pred_attr) is not None]
    n = len(pairs)
    if n == 0:
        return dict(n=0)
    succ = sum(1 for _, y in pairs if y)
    lo, hi = ci95(succ, n)
    return dict(
        n=n, observed=succ / n, observed_ci=(lo, hi), predicted=statistics.fmean(p for p, _ in pairs),
        log_loss=ym.log_loss(pairs), auc=ym.auc(pairs),
        rmse_bins=ym.rmse_bins([(getattr(r, pred_attr), r.success, r.elapsed_days or 0.0, max(r.review_number, 1),
                                 r.lapses_before) for r in rows if getattr(r, pred_attr) is not None]),
    )


def s0_mle(elapsed: List[float], recalled: List[bool], model: ym.Fsrs6) -> Optional[float]:
    """The first-study stability that best explains first-review outcomes (1-D maximum likelihood)."""
    if len(elapsed) < 10 or all(recalled) or not any(recalled):
        return None

    def nll(log_s):
        s = math.exp(log_s)
        tot = 0.0
        for t, y in zip(elapsed, recalled):
            p = min(max(model.retrievability(math.floor(t), s), 1e-6), 1 - 1e-6)
            tot -= math.log(p if y else 1 - p)
        return tot

    lo, hi = math.log(0.05), math.log(200.0)
    for _ in range(80):  # golden-section search; nll is unimodal in log S for this curve
        a = lo + (hi - lo) * 0.382
        b = lo + (hi - lo) * 0.618
        if nll(a) < nll(b):
            hi = b
        else:
            lo = a
    return math.exp((lo + hi) / 2)


# ---------------------------------------------------------------------------------------------------------
# Pooled refit (held out in time)
# ---------------------------------------------------------------------------------------------------------

FIT_INDICES = (1, 2, 3, 8, 20)   # S0(Hard), S0(Good), S0(Easy), recall-growth magnitude, curve shape
FIT_NAMES = ("S0 Hard (w1)", "S0 Medium/Good (w2)", "S0 Easy (w3)", "recall growth (w8)", "curve shape (w20)")


def histories(exports: List[Export]) -> List[Tuple[str, int, List[dict], dict, object]]:
    out = []
    for e in exports:
        units = {int(u["id"]): u for u in e.data["studyUnits"]}
        merged = merged_units(e.data)
        by_unit = defaultdict(list)
        for log in e.data["reviewLogs"]:
            by_unit[int(log["studyUnitId"])].append(log)
        for uid, logs in by_unit.items():
            if uid in merged:
                continue
            logs.sort(key=lambda l: (int(l["reviewedAt"]), int(l["id"])))
            out.append((e.participant, uid, logs, units.get(uid, {}), e.tz))
    return out


def split_times(exports: List[Export], frac=0.75) -> Dict[str, int]:
    """Per participant: reviews at or after this instant are held out."""
    cut = {}
    for e in exports:
        ts = sorted(int(l["reviewedAt"]) for l in e.data["reviewLogs"] if l.get("logType") == "RECALL")
        if ts:
            cut[e.participant] = ts[min(int(len(ts) * frac), len(ts) - 1)]
    return cut


def predictions(hist, weights) -> Tuple[List[Tuple[float, bool]], List[Tuple[float, bool]], List[str]]:
    """(train pairs, test pairs, participant per test pair) under one weight vector."""
    train, test, who = [], [], []
    for pid, uid, logs, unit, tz, cut in hist:
        rp = replay_unit(logs, unit, weights, tz)
        for log, r in zip(logs, rp):
            if r["predicted"] is None or log.get("logType") != "RECALL" or r["elapsed"] < 1:
                continue
            pair = (r["predicted"], log["memoryRating"] != "Forgot")
            if int(log["reviewedAt"]) >= cut:
                test.append(pair)
                who.append(pid)
            else:
                train.append(pair)
    return train, test, who


def nelder_mead(f, x0, step=0.25, iters=160):
    n = len(x0)
    pts = [list(x0)] + [[x0[j] + (step if j == i else 0.0) for j in range(n)] for i in range(n)]
    vals = [f(p) for p in pts]
    for _ in range(iters):
        order = sorted(range(n + 1), key=lambda i: vals[i])
        pts = [pts[i] for i in order]
        vals = [vals[i] for i in order]
        centroid = [sum(p[j] for p in pts[:-1]) / n for j in range(n)]
        refl = [centroid[j] + (centroid[j] - pts[-1][j]) for j in range(n)]
        fr = f(refl)
        if fr < vals[0]:
            exp_ = [centroid[j] + 2 * (centroid[j] - pts[-1][j]) for j in range(n)]
            fe = f(exp_)
            pts[-1], vals[-1] = (exp_, fe) if fe < fr else (refl, fr)
        elif fr < vals[-2]:
            pts[-1], vals[-1] = refl, fr
        else:
            con = [centroid[j] + 0.5 * (pts[-1][j] - centroid[j]) for j in range(n)]
            fc = f(con)
            if fc < vals[-1]:
                pts[-1], vals[-1] = con, fc
            else:
                for i in range(1, n + 1):
                    pts[i] = [pts[0][j] + 0.5 * (pts[i][j] - pts[0][j]) for j in range(n)]
                    vals[i] = f(pts[i])
        if max(vals) - min(vals) < 1e-7:
            break
    best = min(range(n + 1), key=lambda i: vals[i])
    return pts[best], vals[best]


def pooled_fit(exports: List[Export], min_train=300) -> dict:
    cuts = split_times(exports)
    hist = [(p, u, l, un, tz, cuts.get(p, 1 << 62)) for p, u, l, un, tz in histories(exports)]
    base = list(ym.DEFAULT_WEIGHTS)
    train0, test0, who = predictions(hist, tuple(base))
    result = dict(train_reviews=len(train0), test_reviews=len(test0), fitted=False)
    if len(train0) < min_train or len(test0) < 50:
        result["reason"] = f"needs >= {min_train} training and 50 held-out recall reviews"
        return result
    x0 = [math.log(base[i]) for i in FIT_INDICES]
    prior_weight = 2.0  # a mild pull toward the defaults, in log space, per parameter

    def weights_of(x):
        w = list(base)
        for i, v in zip(FIT_INDICES, x):
            w[i] = math.exp(v)
        return tuple(ym.clamp_weights(w))

    def objective(x):
        tr, _, _ = predictions(hist, weights_of(x))
        ll = ym.log_loss(tr) * len(tr)
        pen = prior_weight * sum((a - b) ** 2 for a, b in zip(x, x0))
        return (ll + pen) / len(tr)

    x, _ = nelder_mead(objective, x0)
    fitted = weights_of(x)
    _, test1, _ = predictions(hist, fitted)
    # Paired comparison on the held-out reviews: per-review log-loss difference.
    diffs = []
    for (p0, y), (p1, _) in zip(test0, test1):
        l0 = -math.log(min(max(p0 if y else 1 - p0, 1e-6), 1))
        l1 = -math.log(min(max(p1 if y else 1 - p1, 1e-6), 1))
        diffs.append(l0 - l1)
    md = statistics.fmean(diffs)
    sd = statistics.stdev(diffs) if len(diffs) > 1 else 0.0
    z = md / (sd / math.sqrt(len(diffs))) if sd > 0 else 0.0
    result.update(
        fitted=True,
        weights=list(fitted),
        changed={name: (base[i], fitted[i]) for name, i in zip(FIT_NAMES, FIT_INDICES)},
        test_log_loss_default=ym.log_loss(test0), test_log_loss_fitted=ym.log_loss(test1),
        test_auc_default=ym.auc(test0), test_auc_fitted=ym.auc(test1),
        z=z, better=z >= 2.33,
    )
    return result


# ---------------------------------------------------------------------------------------------------------
# The report
# ---------------------------------------------------------------------------------------------------------

def elapsed_bucket(t: Optional[float]) -> str:
    if t is None:
        return "?"
    for lo, hi, name in ((0, 1, "same day"), (1, 2, "1 d"), (2, 4, "2–3 d"), (4, 8, "4–7 d"), (8, 15, "8–14 d"),
                         (15, 31, "15–30 d"), (31, 61, "31–60 d"), (61, 10 ** 9, "61+ d")):
        if lo <= t < hi:
            return name
    return "?"


def late_bucket(x: Optional[float]) -> str:
    if x is None:
        return "unknown"
    if x < -1:
        return "early (>1 d before)"
    if x <= 1:
        return "on time (±1 d)"
    if x <= 3:
        return "1–3 d late"
    if x <= 7:
        return "3–7 d late"
    return "more than 7 d late"


def method_group(ms: Tuple[str, ...]) -> str:
    s = set(ms)
    if not s:
        return "not said"
    if s == {"Questions"}:
        return "Questions only"
    if s == {"Reading"}:
        return "Reading only"
    if s == {"Lecture"}:
        return "Lecture only"
    if "Questions" in s:
        return "Questions + other"
    return "other mix"


class Report:
    def __init__(self):
        self.lines: List[str] = []

    def h(self, text, level=2):
        self.lines += ["", "#" * level + " " + text, ""]

    def p(self, text=""):
        self.lines.append(text)

    def table(self, header: Sequence[str], rows: Sequence[Sequence]):
        self.lines.append("| " + " | ".join(header) + " |")
        self.lines.append("|" + "---|" * len(header))
        for r in rows:
            self.lines.append("| " + " | ".join(str(c) for c in r) + " |")
        self.lines.append("")

    def text(self):
        return "\n".join(self.lines).strip() + "\n"


def analyze(exports: List[Export], out_dir: str, warnings: List[str], fit: bool = True) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    all_rows: List[Row] = []
    topic_rows = []
    summary: dict = dict(participants={}, warnings=warnings)
    rep = Report()
    rep.p("# Yadora pilot report")
    rep.p(f"Generated {dt.datetime.now().strftime('%Y-%m-%d %H:%M')} from {len(exports)} participant export(s). "
          "Numbers are pooled over FSRS-6 recall reviews unless a section says otherwise. A recall review is the "
          "learner's answer to “How much did you still remember?” when they came back to a topic: Forgot = "
          "failure; Hard, Good, Easy = success.")
    if warnings:
        rep.h("Warnings while loading")
        for w in warnings:
            rep.p(f"- {w}")

    # ---- inventory -------------------------------------------------------------------------------------
    inv = []
    for e in exports:
        rows, units = build_rows(e)
        all_rows += rows
        d = e.data
        settings = d.get("settings") or {}
        issues = (d.get("consistency") or {}).get("issues") or []
        ts = [r.at for r in rows]
        span = (local_day(max(ts), e.tz) - local_day(min(ts), e.tz)).days + 1 if ts else 0
        active_days = len({r.local.date() for r in rows})
        export_day = dt.datetime.fromtimestamp(e.exported_at / 1000, e.tz).replace(hour=0, minute=0, second=0, microsecond=0)
        active_units = [u for u in units if not u.get("archived") and u.get("deletedAt") is None]
        overdue = [u for u in active_units if int(u.get("nextReviewAt", 0)) < export_day.timestamp() * 1000]
        overdue7 = [u for u in overdue if int(u["nextReviewAt"]) < export_day.timestamp() * 1000 - 7 * DAY_MS]
        events = Counter(ev.get("type") for ev in d.get("eventLogs") or [])
        info = dict(
            file=os.path.basename(e.path), export_version=d.get("exportVersion"), app=f"{d.get('appVersionName')} ({d.get('appVersionCode')})",
            device=f"{(d.get('device') or {}).get('manufacturer', '?')} {(d.get('device') or {}).get('model', '?')} / SDK {(d.get('device') or {}).get('sdkInt', '?')}",
            time_zone=e.tz_name, language=settings.get("language"), retention=settings.get("userDesiredRetention"),
            daily_limit=settings.get("dailyReviewLimit"), reminders=settings.get("dailyReminderEnabled"),
            topics=len(units), active_topics=len(active_units), logs=len(rows),
            first_studies=sum(1 for r in rows if r.log_type == "FIRST_STUDY"),
            recalls=sum(1 for r in rows if r.is_recall), span_days=span, active_days=active_days,
            overdue_at_export=len(overdue), overdue_7d_at_export=len(overdue7),
            consistency_issues=len(issues), issue_kinds=dict(Counter(i.get("kind") for i in issues)),
            events=dict(events), crash_log=bool(d.get("lastCrashLog")),
            active_parameter_set=(d.get("policy") or {}).get("activeParameterSetId", 0),
            personal_model_attempts=len(d.get("memoryParameterSets") or []),
        )
        summary["participants"][e.participant] = info
        inv.append(info)
        for u in units:
            topic_rows.append(dict(
                participant=e.participant, unit_id=u.get("id"),
                subject=(d.get("subjects") or {}).get(str(u.get("subjectId")), "") if u.get("subjectId") is not None else "",
                high_yield=u.get("highYield"), state=u.get("state"), reviews=u.get("reviewCount"), lapses=u.get("lapseCount"),
                stability=u.get("stability"), difficulty=u.get("difficulty"),
                studied=local_day(int(u.get("studiedAt", 0)), e.tz).isoformat() if u.get("studiedAt") else "",
                next_review=local_day(int(u.get("nextReviewAt", 0)), e.tz).isoformat() if u.get("nextReviewAt") else "",
                archived=u.get("archived"), deleted=u.get("deletedAt") is not None, memory_model=u.get("memoryModel"),
                parameter_set=u.get("parameterSetId", 0), notes_length=u.get("notesLength"), has_source=u.get("hasSource"),
                title_length=u.get("titleLength"), has_scope=u.get("hasRecallPrompt"),
            ))

    rep.h("1. Who and what")
    rep.table(
        ["participant", "app", "device", "zone", "lang", "target", "limit", "topics (active)", "first studies",
         "recall reviews", "days spanned", "active days", "overdue at export (>7 d)", "consistency issues"],
        [[pid, i["app"], i["device"], i["time_zone"], i["language"], i["retention"], i["daily_limit"],
          f"{i['topics']} ({i['active_topics']})", i["first_studies"], i["recalls"], i["span_days"], i["active_days"],
          f"{i['overdue_at_export']} ({i['overdue_7d_at_export']})", i["consistency_issues"]]
         for pid, i in summary["participants"].items()],
    )

    recalls = [r for r in all_rows if r.is_recall and r.scheduler_version == "FSRS-6"]
    recalls_pred = [r for r in recalls if r.predicted is not None]

    # ---- integrity -------------------------------------------------------------------------------------
    rep.h("2. Integrity: did every phone schedule exactly what the rules say?")
    checked = [r for r in all_rows if r.scheduler_version == "FSRS-6" and not r.merged and r.log_type in ("RECALL", "FIRST_STUDY")]
    bad = [r for r in checked if r.mismatch]
    issues_total = sum(i["consistency_issues"] for i in inv)
    summary["integrity"] = dict(checked=len(checked), mismatched=len(bad), consistency_issues=issues_total,
                                merged_skipped=sum(1 for r in all_rows if r.merged))
    rep.p(f"Replayed {len(checked)} FSRS-6 logs through tools/pilot/yadora_model.py (checked against py-fsrs 6.3.1): "
          f"elapsed days, predicted recall and the scheduled interval, fuzz included. "
          f"**{len(checked) - len(bad)} reproduced exactly, {len(bad)} did not.** "
          f"Export self-check issues: **{issues_total}**. "
          f"Topics that went through a merge are skipped ({summary['integrity']['merged_skipped']} logs): a merge "
          "averages states on purpose.")
    if bad:
        rep.p("Every mismatch is a bug report (or a time-zone change on that phone). The first ones:")
        rep.table(["participant", "topic", "log", "local time", "type", "what differs"],
                  [[r.participant, r.unit_id, r.log_id, r.local.strftime("%Y-%m-%d %H:%M"), r.log_type, "; ".join(r.mismatch)]
                   for r in bad[:25]])
    for pid, i in summary["participants"].items():
        if i["consistency_issues"]:
            rep.p(f"- {pid}: consistency issues {i['issue_kinds']}")
        if i["crash_log"]:
            rep.p(f"- {pid}: the export carries a crash log — read lastCrashLog in the file.")

    # ---- adherence -------------------------------------------------------------------------------------
    rep.h("3. Adherence: were reviews done, and near their dates?")
    lat = Counter(late_bucket(r.days_late_effective) for r in recalls if r.deferrals == 0)
    deferred = sum(1 for r in recalls if r.deferrals > 0)
    rep.p(f"Lateness of recall reviews against the date that actually applied — the memory date, or the earlier "
          f"understanding repair deadline the previous review set. Rows with a user deferral in between are left "
          f"out ({deferred}): their reconstructed date is not the real one. Early reviews come from Review ahead, a "
          f"Today card or the Library:")
    order = ["early (>1 d before)", "on time (±1 d)", "1–3 d late", "3–7 d late", "more than 7 d late", "unknown"]
    tot = sum(lat.values()) or 1
    rep.table(["timing", "reviews", "share"], [[k, lat.get(k, 0), fmt_p(lat.get(k, 0) / tot)] for k in order])
    first = [r for r in all_rows if r.log_type == "FIRST_STUDY" and r.review_number == 0]
    same_day = sum(1 for r in first if r.elapsed_days is not None and r.elapsed_days == 0)
    rep.p(f"First ratings given on the study day itself: **{fmt_p(same_day / len(first) if first else None)}** "
          f"of {len(first)}. The schedule counts from the rating, so a late first rating moves the whole schedule.")
    kinds = Counter(r.session_kind or "not recorded" for r in all_rows if r.log_type in ("RECALL", "FIRST_STUDY"))
    rep.p("Sessions that produced the logs: " + ", ".join(f"{k} {v}" for k, v in kinds.most_common()))
    per = []
    for pid, i in summary["participants"].items():
        rs = [r for r in recalls if r.participant == pid]
        lates = [r.days_late_effective for r in rs if r.days_late_effective is not None]
        ev = i["events"]
        per.append([pid, i["active_days"], i["span_days"], fmt_p(i["active_days"] / i["span_days"] if i["span_days"] else None),
                    fmt(statistics.median(lates), 1) if lates else "–", ev.get("PROCRASTINATE", 0) + ev.get("PROCRASTINATE_ALL", 0),
                    ev.get("REDISTRIBUTE", 0), ev.get("SNOOZE", 0), ev.get("NOTIF_SHOWN", 0), ev.get("MISSED_REMINDER_REPORT", 0)])
    rep.table(["participant", "active days", "days", "active share", "median days late", "not today", "spread out",
               "snoozes", "notifications shown", "missed-reminder reports"], per)
    summary["adherence"] = dict(lateness={k: lat.get(k, 0) for k in order}, first_rating_same_day=same_day,
                                first_ratings=len(first), session_kinds=dict(kinds))

    # ---- calibration -----------------------------------------------------------------------------------
    rep.h("4. Calibration: does predicted recall match reported recall?")
    groups = defaultdict(list)
    for r in recalls_pred:
        groups[(r.scheduler_version, r.parameter_set)].append(r)
    for (model, sid), rs in sorted(groups.items()):
        c = calib_block(rs)
        cc = calib_block(rs, "calibrated")
        rep.p(f"**{model}, weight set {sid}** ({'published defaults' if sid == 0 else 'a personal set'}): "
              f"{c['n']} reviews, reported recall {fmt_p(c['observed'])} (95% CI {fmt_p(c['observed_ci'][0])}–"
              f"{fmt_p(c['observed_ci'][1])}), mean predicted {fmt_p(c['predicted'])} raw / {fmt_p(cc.get('predicted'))} "
              f"after the per-user scale. Log loss {fmt(c['log_loss'])}, RMSE(bins) {fmt(c['rmse_bins'])}, AUC {fmt(c['auc'])}.")
        summary.setdefault("calibration", {})[f"{model}/{sid}"] = dict(raw=c, calibrated=cc)
    bins = [(0, .5), (.5, .7), (.7, .8), (.8, .85), (.85, .9), (.9, .95), (.95, 1.0001)]
    rows_b = []
    for lo, hi in bins:
        rs = [r for r in recalls_pred if lo <= r.predicted < hi]
        if rs:
            s = sum(1 for r in rs if r.success)
            l, u = ci95(s, len(rs))
            rows_b.append([f"{lo:.2f}–{min(hi, 1):.2f}", len(rs), fmt_p(mean(r.predicted for r in rs)), fmt_p(s / len(rs)),
                           f"{fmt_p(l)}–{fmt_p(u)}"])
    rep.p("By predicted recall (all weight sets pooled; read the per-set lines above when a personal set exists):")
    rep.table(["predicted", "reviews", "mean predicted", "reported", "95% CI"], rows_b)

    elapsed_order = ["same day", "1 d", "2–3 d", "4–7 d", "8–14 d", "15–30 d", "31–60 d", "61+ d", "?"]

    def by(label, keyf, rows):
        agg = defaultdict(list)
        for r in rows:
            agg[keyf(r)].append(r)
        out = []
        for k in sorted(agg, key=lambda x: (elapsed_order.index(x) if x in elapsed_order else 99, str(x))):
            rs = agg[k]
            s = sum(1 for r in rs if r.success)
            l, u = ci95(s, len(rs))
            out.append([k, len(rs), fmt_p(mean(r.predicted for r in rs)), fmt_p(s / len(rs)), f"{fmt_p(l)}–{fmt_p(u)}",
                        fmt(mean(r.elapsed_days for r in rs), 1)])
        rep.p(f"By {label}:")
        rep.table([label, "reviews", "mean predicted", "reported", "95% CI", "mean days since last"], out)
        return {str(k): dict(n=len(v), observed=sum(1 for r in v if r.success) / len(v),
                             predicted=mean(r.predicted for r in v)) for k, v in agg.items()}

    summary["by_review_number"] = by("review number", lambda r: str(min(r.review_number, 5)) + ("+" if r.review_number >= 5 else ""), recalls_pred)
    summary["by_elapsed"] = by("time since the previous review", lambda r: elapsed_bucket(r.elapsed_days), recalls_pred)
    summary["by_participant"] = by("participant", lambda r: r.participant, recalls_pred)
    summary["by_important"] = by("Important flag", lambda r: "Important" if r.high_yield else "normal", recalls_pred)
    summary["by_notes"] = by("notes written (topic size proxy)",
                             lambda r: "none" if r.notes_length <= 0 else ("1–200 chars" if r.notes_length <= 200 else "200+ chars"),
                             recalls_pred)
    summary["by_session"] = by("session kind", lambda r: r.session_kind or "not recorded", recalls_pred)

    # per-participant scale, as the app computes it
    rows_k = []
    m0 = ym.Fsrs6()
    for pid in summary["participants"]:
        ev = [r for r in recalls_pred if r.participant == pid and r.parameter_set == 0 and r.elapsed_days is not None
              and ym.is_calibration_evidence(r.elapsed_days, r.previous_interval)]
        ev = ev[-ym.CAL_WINDOW:]
        if ev:
            raw = ym.moment_scale([r.predicted for r in ev], [r.success for r in ev], m0)
            shr = ym.calibration_scale([r.predicted for r in ev], [r.success for r in ev], m0)
            rows_k.append([pid, len(ev), fmt(raw, 2), fmt(shr, 2)])
            summary["participants"][pid]["scale_raw"] = raw
            summary["participants"][pid]["scale_app"] = shr
    rep.p("Per-user interval scale (RecallCalibration; 1 = the defaults fit this learner; above 1 = remembers longer "
          "than predicted). 'raw' is the moment estimate on the evidence rows, 'app' what the app applies after shrinkage:")
    rep.table(["participant", "evidence reviews", "raw scale", "app scale"], rows_k)

    # ---- first interval ----------------------------------------------------------------------------------
    rep.h("5. The first interval: is the first review too early or too late?")
    firsts = [r for r in recalls_pred if r.review_number == 1]
    rows_f = []
    defaults = {"Hard": ym.DEFAULT_WEIGHTS[1], "Medium": ym.DEFAULT_WEIGHTS[2], "Easy": ym.DEFAULT_WEIGHTS[3]}
    summary["first_review"] = {}
    for g in ("Hard", "Medium", "Easy"):
        rs = [r for r in firsts if r.first_grade == g]
        if not rs:
            continue
        s = sum(1 for r in rs if r.success)
        l, u = ci95(s, len(rs))
        s0 = s0_mle([r.elapsed_days for r in rs], [r.success for r in rs], m0)
        rows_f.append([g, len(rs), fmt(mean(r.elapsed_days for r in rs), 1), fmt_p(mean(r.predicted for r in rs)),
                       fmt_p(s / len(rs)), f"{fmt_p(l)}–{fmt_p(u)}", fmt(defaults[g], 2), fmt(s0, 2) if s0 else "–"])
        summary["first_review"][g] = dict(n=len(rs), observed=s / len(rs), predicted=mean(r.predicted for r in rs),
                                          s0_default=defaults[g], s0_fitted=s0)
    rep.p("First review after the first study, by the difficulty given at the first rating. The first interval is "
          "capped at 5 days (POLICY). 'implied S0' is the first-study stability that best explains these outcomes on "
          "the FSRS-6 curve; compare it with the default:")
    rep.table(["first rating", "reviews", "mean days", "mean predicted", "reported", "95% CI", "default S0", "implied S0"], rows_f)

    # ---- self-ratings vs question scores -----------------------------------------------------------------
    rep.h("6. Self-ratings against question scores")
    scored = [r for r in recalls if r.q_pct is not None]
    summary["question_scores"] = dict(n=len(scored))
    if scored:
        rows_q = []
        for rating in RATINGS:
            rs = [r for r in scored if r.rating == rating]
            if rs:
                rows_q.append([rating, len(rs), fmt_p(mean(r.q_pct for r in rs)),
                               fmt_p(statistics.median(r.q_pct for r in rs)), sum(r.q_total for r in rs)])
        rho = spearman([RATINGS.index(r.rating) for r in scored], [r.q_pct for r in scored])
        summary["question_scores"].update(spearman=rho)
        over = sum(1 for r in scored if r.rating in ("Good", "Easy") and r.q_pct < 0.5)
        under = sum(1 for r in scored if r.rating == "Forgot" and r.q_pct >= 0.8)
        rep.p(f"{len(scored)} reviews carry a question score. Rank correlation between the memory rating and the share "
              f"answered right: **{fmt(rho, 2)}** (0 = unrelated, 1 = perfectly ordered). Good/Easy with under half right: "
              f"{over}; Forgot with 80%+ right: {under}.")
        rep.table(["rating", "reviews", "mean right", "median right", "questions answered"], rows_q)
        summary["question_scores"].update(over_rated=over, under_rated=under)
    else:
        rep.p("No review carries a question score yet.")

    # ---- review method -----------------------------------------------------------------------------------
    rep.h("7. Review method: what does the NEXT review find?")
    by_topic = defaultdict(list)
    for r in all_rows:
        if r.log_type in ("RECALL", "FIRST_STUDY") and r.scheduler_version == "FSRS-6":
            by_topic[(r.participant, r.unit_id)].append(r)
    resid = defaultdict(list)
    for rs in by_topic.values():
        rs.sort(key=lambda r: (r.at, r.log_id))
        for a, b in zip(rs, rs[1:]):
            if a.is_recall and b.is_recall and b.predicted is not None:
                resid[method_group(a.methods)].append((1.0 if b.success else 0.0) - b.predicted)
    rows_m = []
    for gname in ("Questions only", "Reading only", "Lecture only", "Questions + other", "other mix", "not said"):
        v = resid.get(gname)
        if v:
            mu = statistics.fmean(v)
            se = statistics.stdev(v) / math.sqrt(len(v)) if len(v) > 1 else float("nan")
            rows_m.append([gname, len(v), f"{mu:+.3f}", f"{mu - 1.96 * se:+.3f} to {mu + 1.96 * se:+.3f}" if len(v) > 1 else "–"])
    summary["method_residuals"] = {k: dict(n=len(v), mean=statistics.fmean(v)) for k, v in resid.items() if v}
    rep.p("For each review, how the NEXT review of the same topic turned out against its prediction (reported − predicted; "
          "positive = the topic held better than the model expected after that kind of review). Differences between "
          "rows, not the rows themselves, are what matter; they are observational (learners chose their method):")
    rep.table(["how the previous review was done", "next reviews", "mean residual", "95% CI"], rows_m)
    own = defaultdict(list)
    for r in recalls_pred:
        own[method_group(r.methods)].append(r)
    rep.p("And the review's own reported recall, by how it was done (a strict method can lower ratings by itself):")
    rep.table(["method", "reviews", "mean predicted", "reported"],
              [[k, len(v), fmt_p(mean(r.predicted for r in v)), fmt_p(sum(1 for r in v if r.success) / len(v))]
               for k, v in sorted(own.items())])

    # ---- understanding -----------------------------------------------------------------------------------
    rep.h("8. Understanding answers")
    und = Counter((r.rating, r.understanding) for r in recalls)
    rep.table(["memory rating"] + ["Clear", "Partial", "Confused", "NotAsked"],
              [[m] + [und.get((m, u), 0) for u in ("Clear", "Partial", "Confused", "NotAsked")] for m in RATINGS])
    partial_share = sum(v for (m, u), v in und.items() if u in ("Partial", "Confused") and m != "Forgot") / max(1, sum(
        v for (m, u), v in und.items() if m != "Forgot"))
    summary["understanding"] = dict(unrepaired_share=partial_share)
    rep.p(f"Share of successful reviews answered Partial or Confused: {fmt_p(partial_share)} (each schedules a repair).")

    # ---- pooled refit ------------------------------------------------------------------------------------
    rep.h("9. A pooled refit, held out in time")
    pf = pooled_fit(exports) if fit else dict(fitted=False, reason="skipped (--no-fit)")
    summary["pooled_fit"] = pf
    if pf.get("fitted"):
        rep.p(f"Fitted {', '.join(FIT_NAMES)} on each participant's earliest 75% of recall reviews "
              f"({pf['train_reviews']}), with a mild pull toward the defaults, and scored both weight sets on the "
              f"remaining 25% ({pf['test_reviews']}), predicted from the full history before each review.")
        rep.table(["weight", "default", "fitted"], [[k, fmt(a, 4), fmt(b, 4)] for k, (a, b) in pf["changed"].items()])
        rep.p(f"Held-out log loss: default {fmt(pf['test_log_loss_default'], 4)}, fitted {fmt(pf['test_log_loss_fitted'], 4)}; "
              f"AUC {fmt(pf['test_auc_default'], 3)} → {fmt(pf['test_auc_fitted'], 3)}; paired z = {fmt(pf['z'], 2)} "
              f"({'beats the defaults at the app’s own 1% bar' if pf['better'] else 'not better than the defaults at the app’s 1% bar'}).")
        with open(os.path.join(out_dir, "fitted_weights.json"), "w") as f:
            json.dump(dict(weights=pf["weights"], note="Pooled pilot refit of w1,w2,w3,w8,w20; the rest are the FSRS-6 defaults.",
                           held_out_z=pf["z"], train_reviews=pf["train_reviews"], test_reviews=pf["test_reviews"]), f, indent=2)
    else:
        rep.p(f"Not fitted: {pf.get('reason')}. ({pf.get('train_reviews', 0)} training / {pf.get('test_reviews', 0)} held-out reviews.)")
    rep.p("The app fits a full 21-weight personal set on the phone itself once a learner has 640+ reviews, and only "
          "adopts it if it predicts that learner's later reviews better (memoryParameterSets in each export lists every attempt).")

    # ---- decisions ---------------------------------------------------------------------------------------
    rep.h("10. Decision rules (fixed in advance in docs/PILOT.md)")
    decisions = decide(summary, recalls_pred)
    summary["decisions"] = decisions
    rep.table(["#", "question", "result", "verdict"], [[d["id"], d["question"], d["result"], d["verdict"]] for d in decisions])
    rep.p("Verdicts: **OK** = no change indicated; **LOOK** = a change is worth a simulation (tools/pilot/simulate.py) "
          "before it is argued; **BUG** = fix before reading anything else; **WAIT** = not enough data yet.")

    rep.h("Notes for whoever reads this next")
    rep.p("- The ratings are self-reports. Section 6 is the only check against something objective.")
    rep.p("- Two months of data cannot test long intervals: almost every review here is within ~60 days of the previous one.")
    rep.p("- Change nothing that CLAUDE.md lists as a settled decision without the owner; the reasons are written there.")
    rep.p("- Behaviour changes are simulated before they are argued: tools/pilot/simulate.py (add --weights fitted_weights.json).")

    # ---- files -------------------------------------------------------------------------------------------
    write_csvs(out_dir, all_rows, topic_rows, summary)
    with open(os.path.join(out_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write(rep.text())
    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False, default=lambda o: None if isinstance(o, float) and math.isnan(o) else str(o))
    return summary


def decide(summary: dict, recalls_pred: List[Row]) -> List[dict]:
    out = []

    def add(i, q, result, verdict):
        out.append(dict(id=i, question=q, result=result, verdict=verdict))

    integ = summary["integrity"]
    add("D1", "Did every phone schedule exactly what the rules say?",
        f"{integ['mismatched']} mismatches, {integ['consistency_issues']} self-check issues",
        "BUG" if integ["mismatched"] or integ["consistency_issues"] else "OK")

    cal = [v for k, v in (summary.get("calibration") or {}).items() if k.endswith("/0")]
    if cal and cal[0]["calibrated"].get("n", 0) >= 300:
        c = cal[0]["calibrated"]
        gap = c["observed"] - c["predicted"]
        add("D2", "Does reported recall match the (calibrated) prediction within 5 points?",
            f"reported {fmt_p(c['observed'])} vs predicted {fmt_p(c['predicted'])} (n={c['n']})",
            "OK" if abs(gap) <= 0.05 else "LOOK")
    else:
        add("D2", "Does reported recall match the (calibrated) prediction within 5 points?", "fewer than 300 reviews", "WAIT")

    for g in ("Hard", "Medium", "Easy"):
        fr = (summary.get("first_review") or {}).get(g)
        if fr and fr["n"] >= 60:
            gap = fr["observed"] - fr["predicted"]
            verdict = "LOOK" if gap < -0.07 or (gap > 0.05 and fr["observed"] > 0.95) else "OK"
            add(f"D3-{g}", f"First review after a {g} first rating: within −7/+5 points of prediction?",
                f"reported {fmt_p(fr['observed'])} vs predicted {fmt_p(fr['predicted'])} (n={fr['n']})", verdict)
        else:
            add(f"D3-{g}", f"First review after a {g} first rating: within −7/+5 points of prediction?",
                f"{fr['n'] if fr else 0} reviews (needs 60)", "WAIT")

    scales = [p.get("scale_raw") for p in summary["participants"].values() if p.get("scale_raw")]
    if len(scales) >= 3:
        low = sum(1 for s in scales if s < 0.8)
        high = sum(1 for s in scales if s > 1.25)
        verdict = "LOOK" if low > len(scales) / 2 or high > len(scales) / 2 else "OK"
        add("D4", "Do most learners forget systematically faster or slower than the defaults?",
            f"raw scales {', '.join(f'{s:.2f}' for s in scales)}", verdict)
    else:
        add("D4", "Do most learners forget systematically faster or slower than the defaults?",
            f"{len(scales)} participants with evidence (needs 3)", "WAIT")

    pf = summary.get("pooled_fit") or {}
    if pf.get("fitted"):
        add("D5", "Does a pooled refit predict held-out reviews better (z ≥ 2.33)?", f"z = {pf['z']:.2f}",
            "LOOK" if pf["better"] else "OK")
    else:
        add("D5", "Does a pooled refit predict held-out reviews better (z ≥ 2.33)?", pf.get("reason", "not run"), "WAIT")

    qs = summary.get("question_scores") or {}
    if qs.get("n", 0) >= 50 and qs.get("spearman") is not None:
        add("D6", "Do memory ratings follow question scores (rank correlation ≥ 0.3)?", f"ρ = {qs['spearman']:.2f} (n={qs['n']})",
            "OK" if qs["spearman"] >= 0.3 else "LOOK")
    else:
        add("D6", "Do memory ratings follow question scores (rank correlation ≥ 0.3)?", f"{qs.get('n', 0)} scored reviews (needs 50)", "WAIT")

    mr = summary.get("method_residuals") or {}
    q, rd = mr.get("Questions only"), mr.get("Reading only")
    if q and rd and q["n"] >= 100 and rd["n"] >= 100:
        diff = q["mean"] - rd["mean"]
        add("D7", "After a Questions review, does the next review go better than after a Reading one (by 5+ points)?",
            f"{diff:+.3f} (n={q['n']}/{rd['n']})", "LOOK" if abs(diff) >= 0.05 else "OK")
    else:
        add("D7", "After a Questions review, does the next review go better than after a Reading one (by 5+ points)?",
            f"{q['n'] if q else 0}/{rd['n'] if rd else 0} reviews (needs 100 each)", "WAIT")

    adh = summary.get("adherence") or {}
    late = adh.get("lateness") or {}
    total = sum(late.values()) or 0
    very_late = late.get("3–7 d late", 0) + late.get("more than 7 d late", 0)
    if total >= 100:
        add("D8", "Are fewer than 25% of reviews more than 3 days late?", f"{fmt_p(very_late / total)} of {total}",
            "OK" if very_late / total < 0.25 else "LOOK")
    else:
        add("D8", "Are fewer than 25% of reviews more than 3 days late?", f"{total} reviews (needs 100)", "WAIT")

    fs = adh.get("first_ratings", 0)
    if fs >= 50:
        share = adh.get("first_rating_same_day", 0) / fs
        add("D9", "Are at least 80% of first ratings given on the study day?", fmt_p(share), "OK" if share >= 0.8 else "LOOK")
    else:
        add("D9", "Are at least 80% of first ratings given on the study day?", f"{fs} first ratings (needs 50)", "WAIT")

    un = (summary.get("understanding") or {}).get("unrepaired_share")
    if un is not None and len(recalls_pred) >= 100:
        add("D10", "Are fewer than 40% of successful reviews answered Partial/Confused?", fmt_p(un), "OK" if un < 0.4 else "LOOK")
    else:
        add("D10", "Are fewer than 40% of successful reviews answered Partial/Confused?", "fewer than 100 reviews", "WAIT")
    return out


def write_csvs(out_dir: str, rows: List[Row], topics: List[dict], summary: dict) -> None:
    with open(os.path.join(out_dir, "reviews.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["participant", "topic", "log", "local_time", "local_date", "hour", "log_type", "rating", "success",
                    "understanding", "review_number", "first_rating", "elapsed_days", "predicted_recall",
                    "predicted_recall_calibrated", "calibration_scale", "desired_retention", "next_interval_days",
                    "previous_interval_days", "days_late_memory_date", "days_late_effective", "deferrals_before", "subject", "important", "notes_length",
                    "methods", "questions_right", "questions_total", "questions_share", "session_kind", "scheduler",
                    "weight_set", "policy", "lapses_before", "stability_before", "difficulty_before", "stability_after",
                    "merged_topic", "replay_mismatch"])
        for r in sorted(rows, key=lambda r: (r.participant, r.at, r.log_id)):
            w.writerow([r.participant, r.unit_id, r.log_id, r.local.strftime("%Y-%m-%d %H:%M"), r.local.date().isoformat(),
                        r.local.hour, r.log_type, r.rating, "" if r.success is None else int(r.success), r.understanding,
                        r.review_number, r.first_grade, r.elapsed_days, r.predicted, r.calibrated, r.calibration_scale,
                        r.desired_retention, r.next_interval, r.previous_interval, r.days_late, r.days_late_effective,
                        r.deferrals, r.subject,
                        int(r.high_yield), r.notes_length, "+".join(r.methods), r.q_correct if r.q_total > 0 else "",
                        r.q_total if r.q_total > 0 else "", r.q_pct, r.session_kind, r.scheduler_version, r.parameter_set,
                        r.policy, r.lapses_before, r.stability_before, r.difficulty_before, r.stability_after,
                        int(r.merged), "; ".join(r.mismatch)])
    if topics:
        with open(os.path.join(out_dir, "topics.csv"), "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=list(topics[0].keys()))
            w.writeheader()
            w.writerows(topics)
    with open(os.path.join(out_dir, "participants.csv"), "w", newline="", encoding="utf-8-sig") as f:
        keys = ["participant", "file", "app", "device", "time_zone", "language", "retention", "daily_limit", "reminders",
                "topics", "active_topics", "logs", "first_studies", "recalls", "span_days", "active_days",
                "overdue_at_export", "overdue_7d_at_export", "consistency_issues", "scale_raw", "scale_app"]
        w = csv.writer(f)
        w.writerow(keys)
        for pid, i in summary["participants"].items():
            w.writerow([pid] + [i.get(k) for k in keys[1:]])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="export files and/or folders holding them")
    ap.add_argument("--out", default="pilot_report", help="output folder (default: pilot_report)")
    ap.add_argument("--no-fit", action="store_true", help="skip the pooled refit (faster)")
    args = ap.parse_args(argv)
    warnings: List[str] = []
    exports = load_exports(args.inputs, warnings.append)
    if not exports:
        for w in warnings:
            print("warning:", w, file=sys.stderr)
        print("No research exports found.", file=sys.stderr)
        return 2
    summary = analyze(exports, args.out, warnings, fit=not args.no_fit)
    integ = summary["integrity"]
    print(f"{len(exports)} participant(s); {integ['checked']} logs replayed, {integ['mismatched']} mismatched; "
          f"{integ['consistency_issues']} self-check issues.")
    for d in summary["decisions"]:
        print(f"  {d['id']:7} {d['verdict']:5} {d['question']}  [{d['result']}]")
    print(f"Report: {os.path.join(args.out, 'report.md')}")
    return 1 if integ["mismatched"] or integ["consistency_issues"] else 0


if __name__ == "__main__":
    sys.exit(main())

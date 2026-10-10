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
     study day. A scheduling problem and a usage problem look alike in the numbers; this separates them. And
     the reminders: did each phone deliver one every day, on time (export v13+, REMINDER_FIRED events)? And the
     daily load: was the plan keeping up, or did a backlog grow (export v15+, DAILY_SNAPSHOT events)? Each phone's
     builds are dated (APP_VERSION), so a change can be told from an update.
  3. Calibration: does the model's predicted recall match what learners report? Pooled, per participant,
     per review number, per first rating, per elapsed time, per subject, per method; for each weight set also
     the Brier score, observed over expected, calibration-in-the-large and the calibration slope, with 95%
     intervals, and the gap with an interval that counts each topic's reviews as one cluster. Each learner's
     raw interval scale with its interval, and the spread between learners that the app's prior assumes.
  4. The first interval: observed recall at the first review, per first rating, against the default weights.
  5. Self-ratings against question scores, where learners entered them, by score band: the only objective
     check of generous rating.
  6. Review method: does the next review find a topic better or worse than predicted, by how it was reviewed?
  7. A held-out pooled refit of a few weights, judged the way the app judges its personal model.
  8. Decision rules, fixed in advance in docs/PILOT.md, evaluated on the numbers above.

Read CLAUDE.md's "Settled decisions" before acting on anything here: several obvious-looking changes
(exam-date compression, the key-point cap, calendar-day due dates) were considered and rejected on purpose.
"""
from __future__ import annotations

import argparse
import bisect
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
    # What this participant's OLDER exports held that this one does not: logs and topics gone in between (a topic
    # deleted for good after its 30 days in the trash, a restore, a reset). The analysis reads the newest file only.
    older_files: Tuple[str, ...] = ()
    missing_logs: int = 0
    missing_topics: int = 0


def expand(paths: Sequence[str]) -> List[str]:
    out = []
    for p in paths:
        if os.path.isdir(p):
            out += sorted(glob.glob(os.path.join(p, "**", "*.json"), recursive=True))
        else:
            out.append(p)
    return out


# Named zones this computer's Python could not load: no time-zone database (Windows Python without the tzdata package).
# Their reviews are counted at a fixed offset, wrong across a daylight-saving change, so a day count near midnight in the
# other season can differ from the phone's: D1 says so beside its mismatches (a production review, 2026-10-10).
ZONES_WITHOUT_DATABASE: set = set()


def load_zone(name: Optional[str]) -> Optional[dt.tzinfo]:
    """The named zone, or None (recorded in ZONES_WITHOUT_DATABASE) when this computer cannot load it."""
    if not name:
        return None
    if ZoneInfo is not None:
        try:
            return ZoneInfo(name)
        except Exception:
            pass
    ZONES_WITHOUT_DATABASE.add(name)
    return None


def zone_of(d: dict) -> Tuple[dt.tzinfo, str]:
    env = d.get("environment") or {}
    name = env.get("timeZoneId")
    zone = load_zone(name)
    if zone is not None:
        return zone, name
    off = env.get("utcOffsetMinutesAtExport")
    if off is not None:
        return dt.timezone(dt.timedelta(minutes=off)), f"UTC{off / 60:+.1f} (fixed; DST not modelled)"
    return dt.timezone.utc, "UTC (no zone in file)"


def _whole(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def export_problem(d: dict) -> Optional[str]:
    """Why a parsed export cannot be analysed, or None. One malformed file used to stop the whole batch with a
    TypeError (an outside audit, 2026-10-03: exportVersion "12" as text, reviewLogs or studyUnits null); now it is
    set aside with this reason and the other participants are analysed."""
    if "exportVersion" in d and not _whole(d["exportVersion"]):
        return f"exportVersion is {d['exportVersion']!r}, not a whole number"
    for key in ("reviewLogs", "studyUnits"):
        if not isinstance(d.get(key), list) or not all(isinstance(x, dict) for x in d[key]):
            return f"{key} is not a list of records"
    for key in ("eventLogs", "memoryParameterSets"):
        if d.get(key) is not None and (not isinstance(d[key], list) or not all(isinstance(x, dict) for x in d[key])):
            return f"{key} is not a list of records"
    if d.get("participantId") is not None and not isinstance(d["participantId"], str):
        return "participantId is not text"
    if "exportedAt" in d and not _whole(d["exportedAt"]):
        return "exportedAt is not a whole number"
    for u in d["studyUnits"]:
        if not _whole(u.get("id")):
            return "a topic has no whole-number id"
    for log in d["reviewLogs"]:
        if not all(_whole(log.get(k)) for k in ("id", "studyUnitId", "reviewedAt")) or not isinstance(log.get("memoryRating"), str):
            return "a review log lacks a whole-number id, topic or time, or a rating"
    return None


def load_exports(paths: Sequence[str], warn) -> List[Export]:
    by_participant: Dict[str, List[Export]] = defaultdict(list)
    for path in expand(paths):
        try:
            # utf-8-sig: a file opened and saved in Windows Notepad gains a byte-order mark, which json.load refuses.
            with open(path, encoding="utf-8-sig") as f:
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
        problem = export_problem(d)
        if problem:
            warn(f"{path}: {problem}; skipped, and the other files are analysed without it")
            continue
        version = d.get("exportVersion", 0)
        if version < MIN_EXPORT_VERSION:
            warn(f"{path}: export version {version} has no time zone; day counts may be off by one")
        pid = d.get("participantId") or ("file:" + os.path.splitext(os.path.basename(path))[0])
        tz, tz_name = zone_of(d)
        by_participant[pid].append(Export(path, d, pid, int(d.get("exportedAt", 0)), tz, tz_name))
    out = []
    for pid, files in by_participant.items():
        files.sort(key=lambda x: x.exported_at)
        keep, older = files[-1], files[:-1]
        if older:
            # Since 2026-10-03 the newer file is no longer assumed to hold everything (an outside audit): a topic
            # deleted for good, a restore or a reset between two exports takes logs with it, and reading only the
            # newest would quietly analyse the topics that survived. The loss is counted and reported; the files are
            # not merged, because a corrected log, an id reused after a reset or deleted data must not come back.
            kept_logs = {int(l["id"]) for l in keep.data["reviewLogs"]}
            kept_units = {int(u["id"]) for u in keep.data["studyUnits"]}
            gone_logs = {int(l["id"]) for e in older for l in e.data["reviewLogs"]} - kept_logs
            gone_units = {int(u["id"]) for e in older for u in e.data["studyUnits"]} - kept_units
            keep.older_files = tuple(os.path.basename(e.path) for e in older)
            keep.missing_logs, keep.missing_topics = len(gone_logs), len(gone_units)
            names = ", ".join(keep.older_files)
            if gone_logs or gone_units:
                warn(f"{pid}: {len(files)} exports; using the newest ({os.path.basename(keep.path)}). It lacks "
                     f"{len(gone_logs)} review logs and {len(gone_units)} topics that the older ({names}) had: deleted "
                     f"for good, a restore or a reset in between. Those topics are not analysed; if the learner deleted "
                     f"hard topics, the rest look better than the whole")
            else:
                warn(f"{pid}: {len(files)} exports; using the newest ({os.path.basename(keep.path)}), which still holds "
                     f"every log and topic of the older ({names})")
        out.append(keep)
    return sorted(out, key=lambda e: e.participant)


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
    recomputed: bool = False   # its prediction was recomputed by a rating correction, not made at the review
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


def tz_from(name: Optional[str], offset: Optional[str], fallback):
    """A zone from a TIME_ZONE event: its id, else its fixed offset ("+03:30"), else [fallback]."""
    zone = load_zone(name)
    if zone is not None:
        return zone
    if offset and ":" in offset:
        try:
            sign = -1 if offset.startswith("-") else 1
            h, m = offset.lstrip("+-").split(":")
            return dt.timezone(sign * dt.timedelta(hours=int(h), minutes=int(m)))
        except ValueError:
            pass
    return fallback


def zone_history(d: dict, export_tz) -> List[Tuple[int, object, str, Optional[str]]]:
    """TIME_ZONE events (export v15+), oldest first: (from when, zone, its id, the id it replaced). The phone records
    its zone whenever it differs from the last one seen: when the app comes to the front, and in its 6-hourly worker."""
    out = []
    for e in sorted((e for e in d.get("eventLogs") or [] if e.get("type") == "TIME_ZONE"), key=lambda e: int(e.get("at") or 0)):
        kv = parse_detail(e.get("detail"))
        out.append((int(e.get("at") or 0), tz_from(kv.get("zone"), kv.get("offset"), export_tz), kv.get("zone") or "?",
                    kv.get("previous")))
    return out


def zone_lookup(d: dict, export_tz):
    """The zone the phone was in at a moment, for counting a review's days the way the phone counted them: the latest
    TIME_ZONE record before it; before the first record, the zone that record replaced if it names one, else its own.
    Without records (exports before v15), the export's zone throughout, as before."""
    hist = zone_history(d, export_tz)
    if not hist:
        return lambda ms: export_tz
    times = [h[0] for h in hist]
    first_prev = hist[0][3]
    before_first = tz_from(first_prev, None, hist[0][1]) if first_prev and first_prev != "none" else hist[0][1]

    def at(ms: int):
        i = bisect.bisect_right(times, ms) - 1
        return hist[i][1] if i >= 0 else before_first
    return at


def stored_days(log: dict) -> Optional[float]:
    """MedScheduler.storedModelDays: the day count an FSRS-6 recall was scheduled with. The app's replays reuse it
    instead of counting again in today's zone (since 2026-10-04), and so does this replay's chain; the count is still
    checked on its own against the zone the phone was in (check_row)."""
    try:
        v = float(log.get("elapsedDays"))
    except (TypeError, ValueError):
        return None
    if (log.get("schedulerVersion") == "FSRS-6" and log.get("logType") == "RECALL" and math.isfinite(v) and v >= 0
            and v == math.floor(v)):
        return v
    return None


def weight_sets(d: dict) -> Dict[int, Tuple[float, ...]]:
    sets = {0: ym.DEFAULT_WEIGHTS}
    for s in d.get("memoryParameterSets") or []:
        w = s.get("weights") or []
        if len(w) == 21:
            sets[int(s["id"])] = tuple(float(x) for x in w)
    return sets


def set_activations(d: dict) -> Dict[int, int]:
    """When each weight set began scheduling (the app's MedScheduler.ParameterSet.activatedAt; 0 = the defaults).
    A log stamped with a set but reviewed before this was recomputed by a rating correction, not predicted."""
    out = {0: 0}
    for s in d.get("memoryParameterSets") or []:
        at = s.get("activatedAt") if s.get("activatedAt") is not None else s.get("createdAt")
        out[int(s["id"])] = int(at or 0)
    return out


def history_order(log: dict) -> Tuple[int, int]:
    """The order a topic's reviews happened in: saved order (the log id), as the app replays. A phone clock set back
    between two reviews gives the later one the earlier time, so sorting by time replays them in the wrong order."""
    return int(log.get("id", 0)), int(log["reviewedAt"])


def calibration_window(rows: List[Row], participant: str, parameter_set: int, activated_at: int) -> List[Row]:
    """The app's most recent eligible evidence, in saved order, independent of export/display clock order.

    Select the participant and live set before limiting: ids are local to one phone. Recomputed predictions
    and invalid/short/early observations never displace eligible ones. This does not change the activation
    boundary: a prediction dated before a set began scheduling is still excluded as in the app.
    """
    # The gap the app reads is the STORED one (ReviewLogDao.getRecentRecallLogsOnce): a merged topic's replay counts its
    # days again across both copies, and selecting by that recount left out reviews the app counts (a production review,
    # 2026-10-10). For every other topic the two are the same.
    eligible = [r for r in rows if r.participant == participant and r.parameter_set == parameter_set
                and r.is_recall and r.scheduler_version == "FSRS-6" and not r.recomputed
                and r.predicted is not None and 0 <= r.predicted <= 1
                and r.stored_elapsed is not None and r.at >= activated_at
                and ym.is_calibration_evidence(r.stored_elapsed, r.previous_interval)]
    return sorted(eligible, key=lambda r: (r.log_id, r.at))[-ym.CAL_WINDOW:]


CORRECTION_EVENTS = ("RATING_CORRECTED", "REVIEW_DATE_CORRECTED")


def corrections(d: dict) -> Dict[int, List[Tuple[int, int, Optional[int], bool]]]:
    """RATING_CORRECTED events (export v14+) per topic: (corrected log id, when, the topic's last log id when it
    replayed, whether the corrected log itself was recomputed). A correction replays the topic, so the stored prediction
    of every later log that existed then was recomputed, not made at the review. Since 2026-10-04 the event records that
    last id (`upto`), so saved order decides; an older event falls back to the clock, which misses the rewritten logs
    when it was set back between the reviews and the correction (an outside audit, 2026-10-04). REVIEW_DATE_CORRECTED
    (export v16) moves a review to another day, which recomputes that review's own prediction as well. A merge moves the
    corrected topic's logs to the survivor while the event keeps the old id, so each correction also applies to the
    topics MERGE events moved its logs to (a production review, 2026-10-10; on the survivor that can leave out a few of
    its own true predictions, never count a recomputed one). The app applies the same rule (RecomputedPredictions)."""
    survivor: Dict[int, int] = {}
    for e in d.get("eventLogs") or []:
        if e.get("type") == "MERGE" and e.get("unitId") is not None:
            for part in str(e.get("detail") or "").split(","):
                if part.strip().lstrip("-").isdigit() and int(part) != int(e["unitId"]):
                    survivor[int(part)] = int(e["unitId"])
    out: Dict[int, List[Tuple[int, int, Optional[int], bool]]] = defaultdict(list)
    for e in d.get("eventLogs") or []:
        if e.get("type") in CORRECTION_EVENTS and e.get("unitId") is not None:
            kv = parse_detail(e.get("detail"))
            log_id = as_int(kv.get("log"))
            if log_id is not None:
                fix = (log_id, int(e.get("at") or 0), as_int(kv.get("upto")), e.get("type") == "REVIEW_DATE_CORRECTED")
                owner, seen = int(e["unitId"]), set()
                while owner is not None and owner not in seen:
                    seen.add(owner)
                    out[owner].append(fix)
                    owner = survivor.get(owner)
    return out


def recomputed_by(log: dict, fixes: List[Tuple[int, int, Optional[int], bool]]) -> bool:
    """Did one of these corrections recompute this log's stored prediction? See `corrections`."""
    lid, at = int(log["id"]), int(log["reviewedAt"])
    return any((lid >= cid if inclusive else lid > cid) and (lid <= upto if upto is not None else at < cat)
               for cid, cat, upto, inclusive in fixes)


SAVED_LATER_H = 12  # a review saved this long after the time it happened at was logged for an earlier day


def review_time(d: dict) -> dict:
    """The learner's own rough minutes per review (studyMinutes, export v16; -1 = not given, NOT zero) and when reviews
    were saved (loggedAt) against when they happened (reviewedAt). Minutes are optional and the owner said they will often
    skip them, so coverage comes first: an estimate on a quarter of the reviews says nothing about the rest until the
    reviews with and without one are compared. Descriptive only; no decision rule reads it."""
    logs = [l for l in d.get("reviewLogs") or [] if l.get("logType") in ("RECALL", "FIRST_STUDY")]
    given = [l for l in logs if (as_int(l.get("studyMinutes")) or -1) > 0]

    def med(xs):
        return statistics.median(xs) if xs else None

    by_type = {t: [int(l["studyMinutes"]) for l in given if l.get("logType") == t] for t in ("FIRST_STUDY", "RECALL")}
    by_method: Dict[str, List[int]] = defaultdict(list)
    for l in given:
        if l.get("logType") == "RECALL":
            by_method[method_group(tuple(l.get("reviewMethods") or []))].append(int(l["studyMinutes"]))
    saved = [l for l in logs if (as_int(l.get("loggedAt")) or -1) > 0]
    later = [l for l in saved if int(l["loggedAt"]) - int(l["reviewedAt"]) >= SAVED_LATER_H * 3_600_000]
    moved = sum(1 for e in d.get("eventLogs") or [] if e.get("type") == "REVIEW_DATE_CORRECTED")
    return dict(logs=len(logs), given=len(given), first_study_median=med(by_type["FIRST_STUDY"]),
                first_studies_given=len(by_type["FIRST_STUDY"]), review_median=med(by_type["RECALL"]),
                reviews_given=len(by_type["RECALL"]),
                by_method={k: dict(n=len(v), median=med(v)) for k, v in sorted(by_method.items())},
                with_save_time=len(saved), saved_later=len(later), date_corrections=moved)


def important_at_review(log: dict, unit: dict) -> bool:
    """Whether the review was scheduled as Important: its own wasImportantAtReview (1 or 0; -1 = not recorded, older
    logs), as the app's replay reads it, else the topic's flag now."""
    flag = as_int(log.get("wasImportantAtReview"))
    return flag == 1 if flag is not None and flag >= 0 else bool(unit.get("highYield", False))


def merged_units(d: dict) -> set:
    """The topics whose history a merge combined: the MERGE events' survivors. Not the absorbed copies: a merge moves
    all their logs to the survivor, so a copy holds logs only after it was restored from the trash, and those are its
    own (a production review, 2026-10-10). The app reads them the same way (MedReviewRepository.mergedUnitIds)."""
    return {int(e["unitId"]) for e in d.get("eventLogs") or [] if e.get("type") == "MERGE" and e.get("unitId") is not None}


LATE_REMINDER_S = 600  # a reminder more than 10 minutes after its time is late (D11)
D11_MIN_DAYS = 14  # days of reminder data a phone needs before D11 judges it
# reminderHealth values that stop or delay reminders, as the export reads them
HEALTH_PROBLEMS = (
    ("notificationsAllowed", False, "notifications blocked"),
    ("reminderChannelOn", False, "reminder channel off"),
    ("exactAlarmsAllowed", False, "exact alarms not allowed"),
    ("batteryOptimizationIgnored", False, "battery optimization on"),
    ("backgroundRestricted", True, "background restricted"),
    ("powerSaveMode", True, "battery saver on"),
)


def parse_detail(text) -> Dict[str, str]:
    """An event's detail as key=value pairs (NOTIF_SHOWN, REMINDER_FIRED)."""
    return dict(part.split("=", 1) for part in str(text or "").split() if "=" in part)


def as_int(v) -> Optional[int]:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def reminder_delivery(d: dict, tz, exported_at: int) -> dict:
    """Did this phone's reminders come, and on time? Read from the REMINDER_FIRED events (export v13+): one per
    reminder alarm that reached the app, test reminders left out. Every day has at least one (the set time, the second
    slot or a snooze, even when nothing is due), so a day inside the observed span with reminders on and no fire at all
    is a reminder the phone never delivered. A NOTIF_SHOWN from the safety worker or the boot catch-up is a reminder
    that came only through a safety net. reminderHealth says what the phone allowed when the file was exported."""
    events = d.get("eventLogs") or []
    fires = []
    for e in events:
        if e.get("type") != "REMINDER_FIRED":
            continue
        kv = parse_detail(e.get("detail"))
        if kv.get("slot") == "test":
            continue
        fires.append(dict(at=int(e.get("at") or 0), late_s=as_int(kv.get("late_s")), exact=kv.get("exact"),
                          idle=kv.get("idle"), outcome=kv.get("outcome", "?")))
    shown = Counter(parse_detail(e.get("detail")).get("source", "?") for e in events if e.get("type") == "NOTIF_SHOWN")
    health = d.get("reminderHealth") or {}
    problems = [label for key, bad, label in HEALTH_PROBLEMS if health.get(key) is bad]
    bucket = health.get("standbyBucket")
    if isinstance(bucket, int) and bucket >= 40:
        problems.append(f"standby bucket {bucket} (rare or restricted)")
    out = dict(fires=len(fires), days=0, missed_days=[], late=0, known=0, median_late_s=None, p90_late_s=None,
               max_late_s=None, inexact=0, in_doze=0, outcomes={}, health_problems=problems,
               safety_net=shown.get("safety_worker", 0) + shown.get("boot_catchup", 0), shown_by_source=dict(shown),
               **reminder_funnel(d, tz))
    # The days judged start when this build was first seen on the phone, not at its first fire: counted from the first
    # fire, a phone whose reminders never came was never judged, and one silent for weeks before its first fire showed
    # none of those weeks (a production review, 2026-10-10). APP_VERSION (export v15+) is written at the first run of each
    # build and DAILY_SNAPSHOT on the first open of each day; the day the build was first seen is left out, since its
    # first reminder time may still be ahead then.
    seen = [int(e.get("at") or 0) for e in events if e.get("type") in ("APP_VERSION", "DAILY_SNAPSHOT")]
    seen = [x for x in seen if x > 0]
    if not fires and not seen:
        return out
    first_fire = min(f["at"] for f in fires) if fires else None
    first_seen = min(seen) if seen else None
    first_at = min(x for x in (first_fire, first_seen) if x is not None)
    if first_seen is not None and (first_fire is None or local_day(first_seen, tz) < local_day(first_fire, tz)):
        window_start = local_day(first_seen, tz) + dt.timedelta(days=1)
    else:
        window_start = local_day(first_fire, tz)
    off_days = set()

    def mark_off(a_ms: int, b_ms: int):
        day, end = local_day(a_ms, tz), local_day(b_ms, tz)
        while day <= end:
            off_days.add(day)
            day += dt.timedelta(days=1)

    # Days with reminders switched off are the learner's choice, not missed reminders.
    off_since = None
    for at, kind in sorted((int(e.get("at") or 0), e.get("type")) for e in events
                           if e.get("type") in ("REMINDERS_ON", "REMINDERS_OFF")):
        if kind == "REMINDERS_OFF" and off_since is None:
            off_since = at
        elif kind == "REMINDERS_ON":
            mark_off(off_since if off_since is not None else first_at, at)
            off_since = None
    if off_since is not None:
        mark_off(off_since, exported_at)
    # Reminders off when the file was written, and switched off before any switch was logged (a build before export
    # v13): nothing here was the phone's to deliver.
    switched = any(e.get("type") in ("REMINDERS_ON", "REMINDERS_OFF") for e in events)
    if not switched and (d.get("settings") or {}).get("dailyReminderEnabled") is False:
        return out
    fire_days = {local_day(f["at"], tz) for f in fires}
    day, export_day = window_start, local_day(exported_at, tz)
    observed = []
    while day < export_day:  # the export day itself is not over yet
        if day not in off_days:
            observed.append(day)
        day += dt.timedelta(days=1)
    lates = sorted(f["late_s"] for f in fires if f["late_s"] is not None)
    out.update(
        days=len(observed), missed_days=[x.isoformat() for x in observed if x not in fire_days],
        late=sum(1 for x in lates if x > LATE_REMINDER_S), known=len(lates),
        median_late_s=statistics.median(lates) if lates else None,
        p90_late_s=lates[min(len(lates) - 1, math.ceil(0.9 * len(lates)) - 1)] if lates else None,
        max_late_s=lates[-1] if lates else None,
        inexact=sum(1 for f in fires if f["exact"] == "0"), in_doze=sum(1 for f in fires if f["idle"] == "1"),
        outcomes=dict(Counter(f["outcome"] for f in fires)),
    )
    return out


REMINDER_OPEN_WINDOW_MS = 3 * 3_600_000  # a tap on the reminder or the alarm within 3 hours "opened" it


def reminder_funnel(d: dict, tz) -> dict:
    """What each posted reminder led to (export v14+): a tap on it or the alarm within three hours (APP_OPENED), and a
    review logged later the same local day. Since 2026-10-09 a tap on one of its topics' own notifications (from=topic)
    or on their group (from=topics) counts as a tap on the reminder. Test reminders are left out."""
    events = d.get("eventLogs") or []
    posted = sorted(int(e.get("at") or 0) for e in events if e.get("type") == "NOTIF_SHOWN"
                    and parse_detail(e.get("detail")).get("source") != "test")
    taps = sorted(int(e.get("at") or 0) for e in events if e.get("type") == "APP_OPENED"
                  and parse_detail(e.get("detail")).get("from") in ("notification", "alarm", "topic", "topics"))
    reviews = sorted(int(l["reviewedAt"]) for l in d.get("reviewLogs") or [])
    opened = sum(1 for t in posted if any(t <= a <= t + REMINDER_OPEN_WINDOW_MS for a in taps))
    same_day = sum(1 for t in posted if any(r >= t and local_day(r, tz) == local_day(t, tz) for r in reviews))
    return dict(posted=len(posted), opened=opened, reviewed_same_day=same_day,
                widget_opens=sum(1 for e in events if e.get("type") == "APP_OPENED"
                                 and parse_detail(e.get("detail")).get("from") == "widget"))


def daily_load(d: dict, tz) -> dict:
    """Was the plan keeping up? Read from the DAILY_SNAPSHOT events (export v15+): at most one per local day, written when
    the app first opened that day or by the 6-hourly safety worker (`source`, since 2026-10-04). Counts only. The review
    logs alone cannot rebuild this: the notification's "Not today" defers every due topic without naming them, and
    "Spread out" records only how many it moved. Two snapshots on one day (two writers at once) keep the first.

    The time of day a snapshot is taken varies with who writes it, and one taken after some of the day's reviews counts
    fewer overdue topics (`done` says how many came first). An outside audit (2026-10-04) built a phone that started every
    day 30 reviews behind and finished them all: sampled at night for a month and in the morning the next, the trend
    read +22.5 a month where there was none. So the trend is drawn through the snapshots taken before any review that
    day when there are at least TREND_MIN_DAYS of them, and through all of them otherwise, with the basis reported. A
    count a snapshot does not carry is left out, never read as 0; a day without a snapshot is missing, not a day off."""
    snaps: Dict[dt.date, dict] = {}
    for e in sorted((e for e in d.get("eventLogs") or [] if e.get("type") == "DAILY_SNAPSHOT"),
                    key=lambda e: int(e.get("at") or 0)):
        at = int(e.get("at") or 0)
        kv = parse_detail(e.get("detail"))
        counts = {k: as_int(v) for k, v in kv.items() if k != "source"}
        counts.update(at=at, source=kv.get("source"), hour=dt.datetime.fromtimestamp(at / 1000, tz).hour)
        snaps.setdefault(local_day(at, tz), counts)
    out = dict(days=len(snaps), span_days=0, days_without=0)
    if not snaps:
        return out
    days = sorted(snaps)

    def series(key, among=None):
        return [v for v in (snaps[x].get(key) for x in (among or days)) if v is not None]

    def stat(fn, xs):
        return fn(xs) if xs else None

    due, overdue, held, oldest = series("due"), series("overdue"), series("held"), series("oldest_overdue_days")
    before_reviews = [x for x in days if snaps[x].get("done") == 0]
    basis_days = before_reviews if len(before_reviews) >= TREND_MIN_DAYS else days
    # The backlog's trend: a least-squares line through the overdue counts against the calendar day, per 30 days.
    pts = [((x - days[0]).days, snaps[x]["overdue"]) for x in basis_days if snaps[x].get("overdue") is not None]
    slope = None
    if len(pts) >= 2:
        mx, my = statistics.fmean(p[0] for p in pts), statistics.fmean(p[1] for p in pts)
        sxx = sum((p[0] - mx) ** 2 for p in pts)
        slope = sum((p[0] - mx) * (p[1] - my) for p in pts) / sxx if sxx else None
    basis_overdue = [p[1] for p in pts]
    last = snaps[days[-1]]
    span = (days[-1] - days[0]).days + 1
    sources = Counter(snaps[x].get("source") or "not recorded" for x in days)
    out.update(
        days=len(days), first_day=days[0].isoformat(), last_day=days[-1].isoformat(), span_days=span,
        days_without=span - len(days),
        due_median=stat(statistics.median, due), due_max=stat(max, due),
        overdue_median=stat(statistics.median, overdue), overdue_max=stat(max, overdue),
        days_with_backlog=sum(1 for x in overdue if x > 0), oldest_overdue_max=stat(max, oldest),
        days_held=sum(1 for x in held if x > 0), held_max=stat(max, held),
        overdue_first30=mean(basis_overdue[:30]), overdue_last30=mean(basis_overdue[-30:]),
        overdue_trend_per_30d=None if slope is None else 30 * slope,
        trend_basis="before any review that day" if basis_days is before_reviews else "all snapshots, mixed times of day",
        trend_days=len(pts), before_reviews_days=len(before_reviews),
        median_hour=stat(statistics.median, [snaps[x]["hour"] for x in days]), sources=dict(sources),
        limits=sorted({s["limit"] for s in snaps.values() if s.get("limit") is not None}),
        active_last=last.get("active"), deferred_last=last.get("deferred"),
    )
    return out


D3_QUESTION = ("First review after a {g} first rating: not more than 7 points below prediction, and not more than 5 "
               "above it with over 95% recalled?")
TREND_MIN_DAYS = 14  # snapshots taken before any review that day, before the trend is drawn through them alone


def settings_timeline(d: dict, tz) -> List[dict]:
    """SETTINGS_CHANGED events (export v15+), oldest first: when the learner changed the daily limit, the retention
    target or the reminder time, and from what to what. A day's snapshot carries the limit, and every review its
    target, but not when either changed."""
    out = []
    for e in sorted((e for e in d.get("eventLogs") or [] if e.get("type") == "SETTINGS_CHANGED"),
                    key=lambda e: int(e.get("at") or 0)):
        kv = parse_detail(e.get("detail"))
        out.append(dict(day=local_day(int(e.get("at") or 0), tz).isoformat(), key=kv.get("key"), old=kv.get("old"),
                        new=kv.get("new")))
    return out


def app_versions(d: dict, tz) -> List[dict]:
    """When each build first ran on this phone, oldest first (APP_VERSION events, export v15+). previous = 0 is a fresh
    install, or the first build that recorded it."""
    out = []
    for e in sorted((e for e in d.get("eventLogs") or [] if e.get("type") == "APP_VERSION"),
                    key=lambda e: int(e.get("at") or 0)):
        kv = parse_detail(e.get("detail"))
        out.append(dict(day=local_day(int(e.get("at") or 0), tz).isoformat(), code=as_int(kv.get("code")),
                        name=kv.get("name"), previous=as_int(kv.get("previous"))))
    return out


def fmt_seconds(x) -> str:
    if x is None:
        return "–"
    return f"{x:.0f} s" if abs(x) < 120 else f"{x / 60:.0f} min"


def replay_unit(logs: List[dict], unit: dict, weights: Tuple[float, ...], tz, merged: bool = False) -> List[dict]:
    """The canonical reconstruction, under one weight set: the first log seeds, a later FIRST_STUDY log is a
    re-encoding exposure (moves the clock, not the state), every other log is a graded recall.

    [tz] is a zone, or a function from a time to the zone the phone was in then (zone_lookup). A recall's days are
    counted in that zone (`counted`), as the phone counted them; the chain itself follows the day count the review
    was scheduled with when it is stored (`stored_days`), as the app's own replays do, so one day miscounted is
    reported on its own row instead of moving every later one. A merged topic is always counted again: its stored
    counts run from its copies' own reviews.

    Returns per log: state before/after, elapsed days, the recount, predicted recall, graded count before, and the
    memory interval the rules give (before fuzz) plus whether it is a first study.
    """
    zone = tz if callable(tz) else (lambda ms: tz)
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
            elapsed = calendar_days(int(unit.get("studiedAt", t)), t, zone(t))
            out.append(dict(before=None, after=new, elapsed=elapsed, counted=elapsed, predicted=None, graded_before=0,
                            first=True, exposure=False))
            state, last, graded = new, t, 1
            continue
        if log.get("logType") == "FIRST_STUDY":
            counted = calendar_days(last, t, zone(t))
            out.append(dict(before=state, after=state, elapsed=counted, counted=counted, predicted=None,
                            graded_before=graded, first=False, exposure=True))
            # An exposure moves the clock to it, never back (MedScheduler.exposureClock): a merged copy rated for an
            # earlier day can be saved after a later review of the other copy.
            last = max(last, t)
            continue
        counted = calendar_days(last, t, zone(t))
        stored = None if merged else stored_days(log)
        elapsed = counted if stored is None else stored
        r = m.retrievability(math.floor(elapsed), state.stability)
        # Each review keeps the same-day rule of the policy it was scheduled under (YADORA-9: py-fsrs 6.3.2).
        new = m.next_state(state, math.floor(elapsed), grade, ym.floors_same_day_hard(log.get("schedulerPolicyVersion", "")))
        out.append(dict(before=state, after=new, elapsed=elapsed, counted=counted, predicted=r, graded_before=graded,
                        first=False, exposure=False))
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
            # The app keeps a deadline that beats the memory interval as finally scheduled (YADORA-7, the stored
            # nextIntervalDays); rows stamped YADORA-6 or older were compared before the ±5% fuzz, so inside that band
            # this reads a repair date the app had dropped (lateness only; nothing here is checked for exactness).
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
    activated = set_activations(d)
    merged = merged_units(d)
    fixed = corrections(d)
    zone_at = zone_lookup(d, e.tz)
    by_unit: Dict[int, List[dict]] = defaultdict(list)
    for log in d["reviewLogs"]:
        by_unit[int(log["studyUnitId"])].append(log)
    rows: List[Row] = []
    for uid, logs in by_unit.items():
        logs.sort(key=history_order)
        unit = units.get(uid, {})
        # One replay per weight set the topic's logs name: a log is reproduced on its OWN set.
        replays = {}
        for sid in {int(l.get("parameterSetId", 0)) for l in logs}:
            if sid in sets:
                replays[sid] = replay_unit(logs, unit, sets[sid], zone_at, merged=uid in merged)
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
                # Important as the review was scheduled (wasImportantAtReview, as the app's replay reads it), not as the
                # topic is now: a topic switched on later counted every earlier review as Important (2026-10-10).
                high_yield=important_at_review(log, unit),
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
                recomputed=int(log["reviewedAt"]) < activated.get(sid, 0) or recomputed_by(log, fixed.get(uid, [])),
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
    if row.stored_elapsed >= 0 and abs(rp["counted"] - row.stored_elapsed) > 1e-9 and not rp["first"]:
        row.mismatch.append(f"elapsed {row.stored_elapsed:g} stored vs {rp['counted']:g} counted")
    if row.is_recall and row.predicted is not None and rp["predicted"] is not None:
        if abs(rp["predicted"] - row.predicted) > 1e-6 * max(1.0, row.predicted):
            row.mismatch.append(f"predicted recall {row.predicted:.6f} stored vs {rp['predicted']:.6f} replayed")
    grade = ym.GRADE_OF.get(row.rating, ym.GOOD)
    if row.desired_retention > 0:
        ivl, base = ym.memory_interval(m, rp["after"], grade, row.desired_retention, rp["first"],
                                       row.calibration_scale if row.calibration_scale > 0 else 1.0)
        ivl = ym.fuzzed_interval(ivl, base, row.unit_id, rp["graded_before"], rp["first"], row.policy, row.scheduler_version)
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


def logistic_calibration(pairs: Sequence[Tuple[float, bool]]) -> dict:
    """The standard checks of a probability model (as for clinical prediction models): the Brier score, observed
    over expected (O/E, ideal 1), calibration-in-the-large (the intercept with logit(p) as an offset, ideal 0) and
    the calibration slope (logistic regression of the outcome on logit(p), ideal 1; below 1 = predictions too
    extreme), each with a 95% Wald interval. Descriptive only: no decision rule reads them. With predictions bunched
    near the target the slope's interval is wide, and it is reported as it is. Added 2026-09-28."""
    n = len(pairs)
    if n == 0:
        return {}
    ys = [1.0 if y else 0.0 for _, y in pairs]
    xs = [math.log(min(max(p, 1e-6), 1 - 1e-6) / (1 - min(max(p, 1e-6), 1 - 1e-6))) for p, _ in pairs]
    expected = statistics.fmean(p for p, _ in pairs)
    out = dict(brier=statistics.fmean((p - y) ** 2 for (p, _), y in zip(pairs, ys)),
               oe=statistics.fmean(ys) / expected if expected > 0 else None)
    if n < 50 or all(ys) or not any(ys):
        return out

    def sig(z):
        return 1.0 / (1.0 + math.exp(-z)) if z >= 0 else math.exp(z) / (1.0 + math.exp(z))

    a = 0.0  # calibration-in-the-large: logit P(y) = a + x (Newton on one parameter)
    for _ in range(50):
        mu = [sig(a + x) for x in xs]
        info = sum(m * (1 - m) for m in mu)
        step = sum(y - m for y, m in zip(ys, mu)) / info if info > 0 else 0.0
        a += step
        if abs(step) < 1e-10:
            break
    info = sum(m * (1 - m) for m in (sig(a + x) for x in xs))
    if info > 0 and math.isfinite(a):
        se = 1 / math.sqrt(info)
        out.update(citl=a, citl_ci=(a - 1.96 * se, a + 1.96 * se))

    al, be = 0.0, 1.0  # calibration slope: logit P(y) = al + be * x (Newton-Raphson on two parameters)
    ok = False
    for _ in range(100):
        mu = [sig(al + be * x) for x in xs]
        w = [m * (1 - m) for m in mu]
        g0 = sum(y - m for y, m in zip(ys, mu))
        g1 = sum((y - m) * x for y, m, x in zip(ys, mu, xs))
        h00, h01, h11 = sum(w), sum(wi * x for wi, x in zip(w, xs)), sum(wi * x * x for wi, x in zip(w, xs))
        det = h00 * h11 - h01 * h01
        if det <= 1e-12:
            break
        d_al, d_be = (h11 * g0 - h01 * g1) / det, (h00 * g1 - h01 * g0) / det
        al, be = al + d_al, be + d_be
        if not (math.isfinite(al) and math.isfinite(be)):
            break
        if abs(d_al) + abs(d_be) < 1e-10:
            ok = True
            break
    if ok:
        mu = [sig(al + be * x) for x in xs]
        w = [m * (1 - m) for m in mu]
        h00, h01, h11 = sum(w), sum(wi * x for wi, x in zip(w, xs)), sum(wi * x * x for wi, x in zip(w, xs))
        det = h00 * h11 - h01 * h01
        if det > 1e-12:
            se = math.sqrt(h00 / det)
            out.update(slope=be, slope_ci=(be - 1.96 * se, be + 1.96 * se))
    return out


WITHIN_MIN = 10  # next reviews after each method, per learner, before that learner's difference counts


METHOD_MIN_CLUSTERS = 20  # heuristic floor for a normal-approximation interval, not proof of adequate power


def _effective_cluster_count(counts):
    """Kish weight-concentration index, NOT a power calculation or an independent sample count."""
    counts = list(counts)
    total = sum(counts)
    return total * total / sum(x * x for x in counts) if total else 0.0


def within_participant_difference(by_person: Dict[str, Dict[str, List[Tuple[float, object]]]]) -> Optional[dict]:
    """Questions minus Reading residual, compared inside each learner and weighted by nq*nr/(nq+nr).

    Uncertainty clusters on topic INSIDE each learner; a topic present in both methods keeps its covariance.
    Repeating the same topic cannot create independent evidence. This interval is conditional on these learners,
    not a population/causal effect. Require 20 effective topic clusters overall and at least two effective
    topics per learner/method. Raw counts alone hide dominant topics/learners. Report the point estimate but
    withhold the interval when this guard fails. The floor is a diagnostic policy, not a power
    calculation. Cluster-normal intervals remain approximate and no interval repairs method-choice confounding.
    """
    num = den = var_num = mass_sq = 0.0
    people = nq_total = nr_total = clusters = 0
    estimable = True
    for groups in by_person.values():
        q, r = groups.get("Questions only", []), groups.get("Reading only", [])
        if len(q) < WITHIN_MIN or len(r) < WITHIN_MIN:
            continue
        mq, mr = statistics.fmean(x for x, _ in q), statistics.fmean(x for x, _ in r)
        w = len(q) * len(r) / (len(q) + len(r))
        num += w * (mq - mr)
        den += w
        qc = Counter(topic for _, topic in q)
        rc = Counter(topic for _, topic in r)
        eq = _effective_cluster_count(qc.values())
        er = _effective_cluster_count(rc.values())
        # Preserve the existing learner weights and point estimate. With only one influential
        # topic in either arm, centred residuals can give a false zero-variance contrast.
        estimable = estimable and eq + 1e-9 >= 2 and er + 1e-9 >= 2
        # Combine absolute arm weights on shared topics before measuring concentration.
        # Counting the arms separately overstates effective clusters when they share
        # the same influential topics; signed cancellation is not new evidence either.
        topic_mass = defaultdict(float)
        for topic, n in qc.items():
            topic_mass[topic] += n / len(q)
        for topic, n in rc.items():
            topic_mass[topic] += n / len(r)
        mass_sq += w * w * sum(m * m for m in topic_mass.values())
        scores = defaultdict(float)
        for x, topic in q:
            scores[topic] += (x - mq) / len(q)
        for x, topic in r:
            scores[topic] -= (x - mr) / len(r)
        k = len(scores)
        if k < 2:
            estimable = False
        else:
            var_num += w * w * k / (k - 1) * sum(x * x for x in scores.values())
        clusters += k
        people += 1
        nq_total += len(q)
        nr_total += len(r)
    if not people:
        return None
    diff = num / den
    # Absolute cluster masses sum to 2*den; each learner/topic occurs once, even
    # across both methods. The cap absorbs floating-point drift above the raw count.
    effective = min(float(clusters), 4 * den * den / mass_sq)
    se = math.sqrt(var_num) / den if estimable and effective + 1e-9 >= METHOD_MIN_CLUSTERS else None
    return dict(diff=diff, se=se, ci=None if se is None else (diff - 1.96 * se, diff + 1.96 * se),
                participants=people, clusters=clusters, effective_clusters=effective, n_questions=nq_total, n_reading=nr_total,
                uncertainty="topic-clustered, conditional on observed learners")


def method_comparison(rows: Sequence[Row]):
    """Use the next SAVED event, including exposure/legacy boundaries; never bridge or reorder them by the clock."""
    by_topic = defaultdict(list)
    for row in rows:
        by_topic[(row.participant, row.unit_id)].append(row)
    resid = defaultdict(list)
    by_person = defaultdict(lambda: defaultdict(list))
    by_group = defaultdict(list)
    for rs in by_topic.values():
        rs.sort(key=lambda r: r.log_id)
        for a, b in zip(rs, rs[1:]):
            if (a.is_recall and b.is_recall and a.scheduler_version == b.scheduler_version == "FSRS-6"
                    and b.predicted is not None and not b.recomputed):
                value = (1.0 if b.success else 0.0) - b.predicted
                group = method_group(a.methods)
                resid[group].append(value)
                by_person[a.participant][group].append((value, a.unit_id))
                by_group[group].append((b.predicted, bool(b.success), (a.participant, a.unit_id)))
    return resid, within_participant_difference(by_person), by_group


def clustered_gap(items: Sequence[Tuple[float, bool, object]]) -> Optional[dict]:
    """Reported minus predicted recall, with a 95% interval that counts each topic's reviews as one cluster (a
    cluster-robust variance). Reviews of one topic can move together, for instance a topic harder than its prediction
    failing several times in a row, and an interval that treats them as independent is then too narrow.
    design_effect is the clustered variance over the independent one (1 = the reviews behave as independent).
    Added 2026-10-03. Intervals also require 20 effective topic clusters (a weight-concentration
    guard, not a power calculation); many tiny topics must not conceal one dominant topic."""
    n = len(items)
    if n < 2:
        return None
    gaps = [(1.0 if y else 0.0) - p for p, y, _ in items]
    g = statistics.fmean(gaps)
    sums: Dict[object, float] = defaultdict(float)
    for (_, _, c), d in zip(items, gaps):
        sums[c] += d - g
    k = len(sums)
    effective = _effective_cluster_count(Counter(c for _, _, c in items).values())
    if k < 2:
        return dict(gap=g, gap_ci=None, design_effect=None, clusters=k, effective_clusters=effective)
    naive = sum((d - g) ** 2 for d in gaps) / (n * (n - 1))
    clustered = k / (k - 1) * sum(s * s for s in sums.values()) / (n * n)
    half = 1.96 * math.sqrt(clustered)
    ci = (g - half, g + half) if effective + 1e-9 >= METHOD_MIN_CLUSTERS else None
    return dict(gap=g, gap_ci=ci, design_effect=clustered / naive if naive > 0 else None,
                clusters=k, effective_clusters=effective)


def moment_scale_ci(predicted: Sequence[float], recalled: Sequence[bool], model: ym.Fsrs6) -> dict:
    """The raw interval scale (RecallCalibration's moment estimate) with a 95% interval, by the delta method on the
    moment equation sum R_i(k) = recalls: var(ln k) = sum R(1 - R) / (sum dR/dln k)^2 at the estimate. Near 90%
    predicted recall a review says little about k (about 19/n for var(ln k)), which is why the app shrinks the
    estimate by n / (n + 120): 120 is that 19 over an assumed spread between learners of 0.4 in ln k. Added 2026-10-03."""
    k = ym.moment_scale(predicted, recalled, model)
    num = den = 0.0
    for p in predicted:
        x = min(max(p, 1e-9), 1.0) ** (1.0 / model.decay) - 1.0
        r = (1.0 + x / k) ** model.decay
        num += r * (1.0 - r)
        den += -model.decay * (x / k) * (1.0 + x / k) ** (model.decay - 1.0)
    if den <= 0:
        return dict(scale=k, ci=None, se_log=None)
    se = math.sqrt(num) / den
    return dict(scale=k, ci=(k * math.exp(-1.96 * se), k * math.exp(1.96 * se)), se_log=se)


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
        clustered=clustered_gap([(getattr(r, pred_attr), r.success, (r.participant, r.unit_id))
                                 for r in rows if getattr(r, pred_attr) is not None]),
        **logistic_calibration(pairs),
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
        zone_at = zone_lookup(e.data, e.tz)
        by_unit = defaultdict(list)
        for log in e.data["reviewLogs"]:
            by_unit[int(log["studyUnitId"])].append(log)
        for uid, logs in by_unit.items():
            if uid in merged:
                continue
            logs.sort(key=history_order)
            out.append((e.participant, uid, logs, units.get(uid, {}), zone_at))
    return out


def first_rating_value(firsts: List["Row"]) -> dict:
    """The first reviews on the default FSRS-6 weights, scored twice: with the stored prediction, which starts from the
    first rating's own initial stability, and rating-blind, from the Medium one for everyone (same elapsed days). The
    paired difference in log loss says whether the immediate rating carries information about later recall."""
    s0 = {"Hard": ym.DEFAULT_WEIGHTS[1], "Medium": ym.DEFAULT_WEIGHTS[2], "Easy": ym.DEFAULT_WEIGHTS[3]}
    model = ym.Fsrs6()
    diffs, rated, blind = [], [], []
    for r in firsts:
        if r.first_grade not in s0 or r.elapsed_days is None or r.parameter_set != 0 or r.scheduler_version != "FSRS-6":
            continue
        p_rated = r.predicted
        p_blind = model.retrievability(r.elapsed_days, s0["Medium"])
        a, b = ym.log_loss([(p_rated, r.success)]), ym.log_loss([(p_blind, r.success)])
        rated.append(a)
        blind.append(b)
        diffs.append(b - a)
    n = len(diffs)
    if n < 2:
        return dict(n=n, ll_rated=None, ll_blind=None, z=None)
    sd = statistics.stdev(diffs)
    z = statistics.fmean(diffs) / (sd / math.sqrt(n)) if sd > 0 else 0.0
    return dict(n=n, ll_rated=statistics.fmean(rated), ll_blind=statistics.fmean(blind), z=z)


def split_review_ids(exports: List[Export], frac=0.75) -> Dict[str, int]:
    """Per participant: saved review ids at or after this boundary are held out.
    Wall time can run backwards; the fit must never train on a later saved review to predict an earlier one."""
    cut = {}
    for e in exports:
        ts = sorted(int(l["id"]) for l in e.data["reviewLogs"] if l.get("logType") == "RECALL")
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
            if int(log["id"]) >= cut:
                test.append(pair)
                who.append(pid)
            else:
                train.append(pair)
    return train, test, who


def base_target(d: dict) -> float:
    """The learner's own retention target, as the app's refit uses it for every topic (MedScheduler.effectiveRetention
    without Important, clamped by safeRetention to 0.70-0.99)."""
    try:
        target = float((d.get("settings") or {}).get("userDesiredRetention"))
    except (TypeError, ValueError):
        return 0.90
    return min(max(target, 0.70), 0.99) if math.isfinite(target) else 0.90


def pooled_lengthening(hist, weights, targets: Optional[Dict[str, float]] = None) -> float:
    """The app's never-lengthen check (Fsrs6Optimizer.lengthening) for a pooled set: every topic replayed to its last
    state under the set and under the published defaults, the next interval at the learner's target, inside the
    scheduler's 1-365 day bounds; the geometric mean of the ratios. Above 1 the set would schedule these learners'
    topics later than the defaults. As in the app, the comparison is with the defaults, not with the defaults times a
    learner's calibration (Fsrs6Optimizer.lengthening says why), and the first-study cap is not applied.

    [targets]: each learner's own target (base_target), the one the app checks every topic at
    (MedReviewRepository.refitPersonalModel). The target stored on a topic's last review (an Important topic's +0.03, or
    an older setting) used to stand in for it (a production review, 2026-10-10); it still does when none is given."""
    fitted, defaults = ym.Fsrs6(weights), ym.Fsrs6()
    logs_sum, n = 0.0, 0
    for participant, _, logs, unit, tz, _ in hist:
        a, b = replay_unit(logs, unit, tuple(weights), tz), replay_unit(logs, unit, ym.DEFAULT_WEIGHTS, tz)
        if not a:
            continue
        if targets is not None and participant in targets:
            target = targets[participant]
        else:
            target = float(logs[-1].get("desiredRetentionAtReview") or 0.9)
            target = target if 0.7 <= target <= 0.99 else 0.9

        def nxt(m, state):
            return min(max(m.interval_days(state.stability, target), ym.MIN_INTERVAL_DAYS), ym.MAX_INTERVAL_DAYS)

        logs_sum += math.log(nxt(fitted, a[-1]["after"]) / nxt(defaults, b[-1]["after"]))
        n += 1
    return math.exp(logs_sum / n) if n else 1.0


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
    cuts = split_review_ids(exports)
    hist = [(p, u, l, un, tz, cuts.get(p, 1 << 62)) for p, u, l, un, tz in histories(exports)]
    base = list(ym.DEFAULT_WEIGHTS)
    train0, test0, who = predictions(hist, tuple(base))
    result = dict(train_reviews=len(train0), test_reviews=len(test0), fitted=False, validation_order="saved_review_id")
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
    # The app adopts a set only if it ALSO keeps the first-rating grades in order and does not lengthen intervals
    # against the defaults (Fsrs6Optimizer.keepsGradeOrder, .lengthening). This fit used to be judged on z alone, and
    # an outside audit (2026-10-03) got "better" from a set with S0(Hard) 12.5 days over S0(Good) 0.14.
    order_ok = fitted[0] <= fitted[1] <= fitted[2] <= fitted[3]
    longer = pooled_lengthening(hist, fitted, {e.participant: base_target(e.data) for e in exports})
    result.update(
        fitted=True,
        weights=list(fitted),
        changed={name: (base[i], fitted[i]) for name, i in zip(FIT_NAMES, FIT_INDICES)},
        test_log_loss_default=ym.log_loss(test0), test_log_loss_fitted=ym.log_loss(test1),
        test_auc_default=ym.auc(test0), test_auc_fitted=ym.auc(test1),
        z=z, grade_order_ok=order_ok, lengthening=longer,
        better=z >= 2.33 and order_ok and longer <= 1.0,
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
        rd = reminder_delivery(d, e.tz, e.exported_at)
        summary.setdefault("reminders", {})[e.participant] = rd
        ld = daily_load(d, e.tz)
        summary.setdefault("load", {})[e.participant] = ld
        versions = app_versions(d, e.tz)
        summary.setdefault("app_versions", {})[e.participant] = versions
        zones = [dict(day=local_day(at, e.tz).isoformat(), zone=zid) for at, _, zid, _ in zone_history(d, e.tz)]
        summary.setdefault("time_zones", {})[e.participant] = zones
        changes = settings_timeline(d, e.tz)
        summary.setdefault("settings_changes", {})[e.participant] = changes
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
            reminder_days=rd["days"], days_without_reminder=len(rd["missed_days"]), reminder_fires=rd["fires"],
            late_reminders=rd["late"], safety_net_reminders=rd["safety_net"],
            reminder_health_problems="; ".join(rd["health_problems"]),
            # Counted from the events: corrections() lists one under every topic a merge moved its logs to.
            rating_corrections=sum(1 for e in d.get("eventLogs") or [] if e.get("type") == "RATING_CORRECTED"
                                   and e.get("unitId") is not None and as_int(parse_detail(e.get("detail")).get("log")) is not None),
            date_corrections=sum(1 for e in d.get("eventLogs") or [] if e.get("type") == "REVIEW_DATE_CORRECTED"
                                 and e.get("unitId") is not None and as_int(parse_detail(e.get("detail")).get("log")) is not None),
            minutes_given=review_time(d)["given"], minutes_median=review_time(d)["review_median"],
            saved_later=review_time(d)["saved_later"],
            opened_from_reminder=rd["opened"], reviewed_after_reminder=rd["reviewed_same_day"],
            older_exports=len(e.older_files), logs_missing_from_newest=e.missing_logs, topics_missing_from_newest=e.missing_topics,
            snapshot_days=ld["days"], median_overdue=ld.get("overdue_median"), max_overdue=ld.get("overdue_max"),
            days_held_back=ld.get("days_held"), overdue_trend_per_30d=ld.get("overdue_trend_per_30d"),
            app_builds="; ".join(f"{v['code']} ({v['name']}) from {v['day']}" for v in versions),
            time_zones="; ".join(f"{z['zone']} from {z['day']}" for z in zones),
            settings_changes="; ".join(f"{c['day']} {c['key']} {c['old']}→{c['new']}" for c in changes),
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
    for pid, i in summary["participants"].items():
        if i["logs_missing_from_newest"] or i["topics_missing_from_newest"]:
            rep.p(f"- {pid}: the newest export lacks {i['logs_missing_from_newest']} review logs and "
                  f"{i['topics_missing_from_newest']} topics that an older one had (deleted for good, a restore or a "
                  "reset). Everything below covers the topics that remain.")
        if i["app_builds"]:
            rep.p(f"- {pid}: builds on this phone, first run: {i['app_builds']}. A change in the numbers that starts on "
                  "one of these days may come from the update, not from the learner.")
        if len(summary["time_zones"][pid]) > 1:
            rep.p(f"- {pid}: time zones on this phone: {i['time_zones']}. A review's days are counted in the zone the "
                  "phone was in when it was made, as the phone counted them.")
        if i["settings_changes"]:
            rep.p(f"- {pid}: settings changed: {i['settings_changes']}.")

    recalls = [r for r in all_rows if r.is_recall and r.scheduler_version == "FSRS-6"]
    # As the app's calibration: a prediction counts only if it was made when the review happened. A rating
    # correction replays a topic's earlier rows on the weight set it is on now; those rows are left out here.
    recalls_pred = [r for r in recalls if r.predicted is not None and not r.recomputed]
    recomputed_left_out = sum(1 for r in recalls if r.predicted is not None and r.recomputed)

    # ---- integrity -------------------------------------------------------------------------------------
    rep.h("2. Integrity: did every phone schedule exactly what the rules say?")
    checked = [r for r in all_rows if r.scheduler_version == "FSRS-6" and not r.merged and r.log_type in ("RECALL", "FIRST_STUDY")]
    bad = [r for r in checked if r.mismatch]
    issues_total = sum(i["consistency_issues"] for i in inv)
    summary["integrity"] = dict(checked=len(checked), mismatched=len(bad), consistency_issues=issues_total,
                                merged_skipped=sum(1 for r in all_rows if r.merged))
    rep.p(f"Replayed {len(checked)} FSRS-6 logs through tools/pilot/yadora_model.py (checked against py-fsrs 6.3.2, and 6.3.1 for reviews stamped YADORA-8 or earlier): "
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
          f"out ({deferred}): their reconstructed date is not the real one. Early reviews come from Today's 'Next up' "
          f"list (Review ahead before 2026-10-09) or the Library:")
    order = ["early (>1 d before)", "on time (±1 d)", "1–3 d late", "3–7 d late", "more than 7 d late", "unknown"]
    tot = sum(lat.values()) or 1
    rep.table(["timing", "reviews", "share"], [[k, lat.get(k, 0), fmt_p(lat.get(k, 0) / tot)] for k in order])
    first = [r for r in all_rows if r.log_type == "FIRST_STUDY" and r.review_number == 0]
    same_day = sum(1 for r in first if r.elapsed_days is not None and r.elapsed_days == 0)
    rep.p(f"First ratings given on the study day itself: **{fmt_p(same_day / len(first) if first else None)}** "
          f"of {len(first)}. The schedule counts from the rating, so a late first rating moves the whole schedule.")
    kinds = Counter(r.session_kind or "not recorded" for r in all_rows if r.log_type in ("RECALL", "FIRST_STUDY"))
    rep.p("Where the logs came from (sessionKind; since 2026-10-09 one topic at a time from Today: PLAN today's share, "
          "EXTRA below its line, AHEAD 'Next up'; TOPIC anywhere else): " + ", ".join(f"{k} {v}" for k, v in kinds.most_common()))
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

    rem = summary.get("reminders") or {}
    if any(r["fires"] or r["days"] for r in rem.values()):
        rep.p("**Reminders: did each phone deliver them, on time?** Every reminder alarm that reached the app is logged "
              "(REMINDER_FIRED), test reminders apart, and every day with reminders on has at least one, even when nothing "
              f"is due. A day without one is a reminder the phone never delivered; late means more than "
              f"{LATE_REMINDER_S // 60} minutes after its time. Safety-net reminders came from the 6-hourly worker or the "
              "boot catch-up instead of the alarm. Health is what the phone allowed at export.")
        rep.table(["participant", "days", "days without a reminder", "alarms", "late", "lateness median / 90th / max",
                   "inexact", "in Doze", "safety net", "reminders posted / tapped / reviewed that day", "health at export"],
                  [[pid, r["days"], (f"{len(r['missed_days'])}: " + ", ".join(r["missed_days"][:6])
                                     + (" …" if len(r["missed_days"]) > 6 else "")) if r["missed_days"] else "0",
                    r["fires"], r["late"], " / ".join(fmt_seconds(r[k]) for k in ("median_late_s", "p90_late_s", "max_late_s")),
                    r["inexact"], r["in_doze"], r["safety_net"], f"{r['posted']} / {r['opened']} / {r['reviewed_same_day']}",
                    "; ".join(r["health_problems"]) or "OK"]
                   for pid, r in rem.items()])
    elif all((e.data.get("exportVersion") or 0) < 13 for e in exports):
        rep.p("**Reminders:** no delivery data. Reminder alarms are logged from export version 13; these files predate it.")
    else:
        rep.p("**Reminders:** no delivery data. No reminder alarm fired on these phones before the export: reminders "
              "off, or exported before the first one.")

    load = summary.get("load") or {}
    if any(l["days"] for l in load.values()):
        rep.p("**Daily load: was the plan keeping up?** One snapshot a day (DAILY_SNAPSHOT), taken when the app first "
              "opened that day or by its 6-hourly worker: what was due (first ratings included), how much of it was "
              "overdue (due before that day) and for how long, and on how many days the daily limit held reviews back. A "
              "snapshot taken after some of the day's reviews counts fewer overdue topics, so the trend is drawn through the "
              f"snapshots taken before any review that day when there are {TREND_MIN_DAYS} or more, and the table says "
              "which. A backlog that grows month after month is the first thing to ask the learner about (more new "
              "material than time, fewer study days, a limit set low); a count alone does not say which. Days without "
              "a snapshot are days the phone ran neither the app nor its 6-hourly worker: missing data, not days off.")
        rep.table(["participant", "days with a snapshot (of span)", "taken (median hour; by)", "due median / max",
                   "overdue median / max", "days with a backlog", "oldest overdue (days)", "days held back (most held)",
                   "mean overdue, first 30 → last 30", "trend per 30 days (drawn through)", "daily limit"],
                  [[pid, f"{l['days']} ({l['span_days']})",
                    f"{fmt(l['median_hour'], 0)}:00; " + ", ".join(f"{k} {v}" for k, v in sorted(l["sources"].items())),
                    f"{fmt(l['due_median'], 0)} / {l['due_max']}",
                    f"{fmt(l['overdue_median'], 0)} / {l['overdue_max']}", l["days_with_backlog"], l["oldest_overdue_max"],
                    f"{l['days_held']} ({l['held_max']})",
                    f"{fmt(l['overdue_first30'], 1)} → {fmt(l['overdue_last30'], 1)}",
                    f"{fmt(l['overdue_trend_per_30d'], 1)} ({l['trend_days']} days: {l['trend_basis']})",
                    ", ".join(str(x) for x in l["limits"]) or "–"]
                   for pid, l in load.items() if l["days"]])
    elif all((e.data.get("exportVersion") or 0) < 15 for e in exports):
        rep.p("**Daily load:** no snapshots. The day's load is logged from export version 15; these files predate it.")
    else:
        rep.p("**Daily load:** no snapshots in these files. One is written each day the app opens or its 6-hourly "
              "worker runs.")

    times = {e.participant: review_time(e.data) for e in exports}
    summary["review_time"] = times
    if any(t["given"] or t["with_save_time"] for t in times.values()):
        rep.p("**Review time: the learner's own minutes.** From export version 16 a rating can carry a rough estimate "
              "(10, 20, 30, 45 or 60+ minutes), optional and often skipped, so the coverage column comes first: before "
              "reading a median as the cost of a review, compare the reviews with an estimate to those without (topic, "
              "method, stage). It is what decides whether a syllabus fits the study time (RESEARCH.md §2.8). Saved later "
              f"counts reviews logged {SAVED_LATER_H}+ hours after the time they happened at (\"Reviewed: yesterday\"), and "
              "day corrections the logged reviews moved to another day from the topic's history.")
        rep.table(["participant", "reviews and first studies", "with minutes", "median: first study / review",
                   "review median by method (n)", "saved later", "day corrections"],
                  [[pid, t["logs"], f"{t['given']} ({fmt_p(t['given'] / t['logs'] if t['logs'] else None)})",
                    f"{fmt(t['first_study_median'], 0)} / {fmt(t['review_median'], 0)}",
                    "; ".join(f"{k} {fmt(v['median'], 0)} ({v['n']})" for k, v in t["by_method"].items()) or "–",
                    t["saved_later"], t["date_corrections"]]
                   for pid, t in times.items()])
    elif all((e.data.get("exportVersion") or 0) < 16 for e in exports):
        rep.p("**Review time:** no estimates. The learner's minutes are recorded from export version 16.")
    else:
        rep.p("**Review time:** no review in these files carries the learner's minutes.")

    # ---- calibration -----------------------------------------------------------------------------------
    rep.h("4. Calibration: does predicted recall match reported recall?")
    if recomputed_left_out:
        rep.p(f"{recomputed_left_out} recall reviews are left out: a rating correction recomputed their prediction (they "
              "came after a corrected review, or before the weight set they are stamped with began scheduling), so it "
              "was not made at the review.")
    groups = defaultdict(list)
    for r in recalls_pred:
        # A personal set's id is local to one phone: two learners' set 1 are different weights. Since 2026-10-03 each
        # personal set is its own group (an outside audit found two learners pooled under one "set 1"); the published
        # defaults are the same weights everywhere and stay pooled.
        groups[(r.scheduler_version, r.parameter_set, "" if r.parameter_set == 0 else r.participant)].append(r)
    for (model, sid, owner), rs in sorted(groups.items()):
        c = calib_block(rs)
        cc = calib_block(rs, "calibrated")
        whose = "published defaults" if sid == 0 else f"the personal set of {owner}"
        rep.p(f"**{model}, weight set {sid}** ({whose}): "
              f"{c['n']} reviews, reported recall {fmt_p(c['observed'])} (95% CI {fmt_p(c['observed_ci'][0])}–"
              f"{fmt_p(c['observed_ci'][1])}), mean predicted {fmt_p(c['predicted'])} raw / {fmt_p(cc.get('predicted'))} "
              f"after the per-user scale. Log loss {fmt(c['log_loss'])}, RMSE(bins) {fmt(c['rmse_bins'])}, AUC {fmt(c['auc'])}.")

        def ci(block, key):
            lo_hi = block.get(f"{key}_ci")
            return "–" if key not in block else f"{fmt(block[key], 2)} ({fmt(lo_hi[0], 2)} to {fmt(lo_hi[1], 2)})"

        rep.table(["predictions", "Brier", "O/E", "calibration-in-the-large (95% CI; ideal 0)",
                   "calibration slope (95% CI; ideal 1)"],
                  [[label, fmt(b.get("brier"), 4), fmt(b.get("oe"), 3), ci(b, "citl"), ci(b, "slope")]
                   for label, b in (("raw model", c), ("with the per-user scale", cc))])
        cl = cc.get("clustered") or {}
        if cl.get("gap_ci"):
            rep.p(f"Reported minus predicted (with the per-user scale): **{100 * cl['gap']:+.1f} points**, 95% CI "
                  f"{100 * cl['gap_ci'][0]:+.1f} to {100 * cl['gap_ci'][1]:+.1f} counting each topic's reviews as one "
                  f"cluster ({cl['clusters']} topics; design effect {fmt(cl['design_effect'], 2)}: 1 means the reviews "
                  "behave as independent, 2 means the review count overstates the evidence twofold). The intervals above "
                  "treat every review as independent.")
        elif cl:
            rep.p(f"Reported minus predicted: **{100 * cl['gap']:+.1f} points**; clustered interval withheld "
                  f"({cl['clusters']} topics; {cl['effective_clusters']:.1f} effective topic clusters, "
                  f"needs {METHOD_MIN_CLUSTERS}). D2 waits. Intervals above treat each review as independent.")
        summary.setdefault("calibration", {})[f"{model}/{sid}" + (f"@{owner}" if owner else "")] = dict(raw=c, calibrated=cc)
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
    # Different materials are forgotten at different rates (Sense et al. 2016; across medical disciplines D'Eon 2006),
    # so a per-subject calibration may one day beat a per-learner one. This is where the pilot would show it.
    summary["by_subject"] = by("subject", lambda r: r.subject or "(no subject)", recalls_pred)

    # per-participant scale, as the app computes it: on the published defaults (what D4 compares), and on the personal
    # set when one schedules the learner now (its own evidence since it began, on its own curve). It used to read the
    # defaults only and call that the app's figure (an outside audit, 2026-10-03).
    rows_k = []
    m0 = ym.Fsrs6()
    spread = []
    for e in exports:
        pid = e.participant
        active = int((e.data.get("policy") or {}).get("activeParameterSetId", 0) or 0)
        sets, starts = weight_sets(e.data), set_activations(e.data)
        for sid in sorted({0, active}):
            if sid not in sets:
                continue
            model = m0 if sid == 0 else ym.Fsrs6(sets[sid])
            ev = calibration_window(recalls_pred, pid, sid, starts.get(sid, 0))
            if not ev:
                continue
            est = moment_scale_ci([r.predicted for r in ev], [r.success for r in ev], model)
            raw = est["scale"]
            shr = ym.calibration_scale([r.predicted for r in ev], [r.success for r in ev], model)
            label = "defaults" if sid == 0 else f"personal set {sid}" + (" (in use)" if sid == active else "")
            rows_k.append([pid, label, len(ev), fmt(raw, 2),
                           f"{fmt(est['ci'][0], 2)}–{fmt(est['ci'][1], 2)}" if est["ci"] else "–", fmt(shr, 2)])
            if sid == 0:
                summary["participants"][pid]["scale_raw"] = raw
                summary["participants"][pid]["scale_raw_ci"] = est["ci"]
                summary["participants"][pid]["scale_app"] = shr
                if est["se_log"] and 0.25 < raw < 4.0:
                    spread.append((math.log(raw), est["se_log"], len(ev)))
            else:
                summary["participants"][pid]["scale_active_set"] = dict(set=sid, raw=raw, ci=est["ci"], app=shr, n=len(ev))
    rep.p("Per-user interval scale (RecallCalibration; 1 = the weights fit this learner; above 1 = remembers longer "
          "than predicted). 'raw' is the moment estimate on the evidence rows, 'app' what the app applies after shrinkage "
          "(never above 1 since 2026-09-28: generous ratings look exactly like slower forgetting, so only 'raw' shows "
          "a slower forgetter). The defaults' row is what D4 compares; a personal set's row is what the app uses while it "
          "schedules. Near 90% predicted recall one review says little about the scale, so expect wide intervals below a "
          "few hundred evidence reviews:")
    rep.table(["participant", "weights", "evidence reviews", "raw scale", "95% CI", "app scale"], rows_k)
    summary["scale_spread"] = None
    if len(spread) >= 3:
        between = statistics.variance([s[0] for s in spread])
        noise = statistics.fmean([s[1] ** 2 for s in spread])
        tau2 = max(0.0, between - noise)
        per_review = statistics.fmean([s[1] ** 2 * s[2] for s in spread])
        prior = per_review / tau2 if tau2 > 0 else None
        summary["scale_spread"] = dict(tau=math.sqrt(tau2), prior_reviews=prior, learners=len(spread))
        rep.p(f"Spread of the raw scale between learners beyond each one's own noise: τ = **{math.sqrt(tau2):.2f}** in "
              f"ln k, from {len(spread)} learners. The app's prior of {ym.CAL_PRIOR_REVIEWS} pseudo-reviews is the "
              "empirical-Bayes value for τ ≈ 0.4 at reviews near 90% predicted recall; the prior that would match these "
              "learners is " + (f"**{prior:.0f}** pseudo-reviews" if prior else "larger than any these data can support "
              "(no spread beyond noise)") + ". With this few learners τ is a rough guide, not a reason to change the "
              "constant (a population prior goes through D4 and D5).")

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
    fv = first_rating_value(firsts)
    summary["first_rating_value"] = fv
    if fv["n"]:
        rep.p(f"Does the first rating help? The same {fv['n']} first reviews predicted from the rating's own first-study "
              f"stability, then from the Medium one for everyone: log loss {fmt(fv['ll_rated'])} with the rating, "
              f"{fmt(fv['ll_blind'])} without it (paired z = {fmt(fv['z'], 2)}; positive = the rating helps). A rating "
              "given right after studying measures fluency more than memory; if it does not predict the first real review "
              "better, a pooled first-study prior and the first delayed review would serve as well (simulate before "
              "changing; CLAUDE.md, the first-study cap and prior).")

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
        # The objective check outside researchers asked for (2026-10-03, docs/RESEARCHER_PROMPT.md A3): from ratings
        # alone a learner who calls lapses Hard looks exactly like one who forgets slowly; the score tells them apart.
        bands = (("under 40%", 0.0, 0.4), ("40–59%", 0.4, 0.6), ("60–79%", 0.6, 0.8), ("80–100%", 0.8, 1.0001))
        rows_band, band_summary = [], {}
        for name, lo, hi in bands:
            rs = [r for r in scored if lo <= r.q_pct < hi]
            if not rs:
                continue
            counts = Counter(r.rating for r in rs)
            ok = sum(1 for r in rs if r.rating != "Forgot") / len(rs)
            rows_band.append([name, len(rs)] + [counts.get(x, 0) for x in RATINGS] + [fmt_p(ok)])
            band_summary[name] = dict(n=len(rs), counts={x: counts.get(x, 0) for x in RATINGS}, rated_success=ok)
        rep.p("The same reviews by the share answered right. Chance alone gets 20–25% of 4- or 5-option questions right, "
              "so a low band full of Hard, Good and Easy is what generous rating looks like; only these scores can tell it "
              "from slow forgetting:")
        rep.table(["share right", "reviews"] + list(RATINGS) + ["rated a success"], rows_band)
        per_q = []
        for pid in summary["participants"]:
            rs = [r for r in scored if r.participant == pid]
            if not rs:
                continue
            low = [r for r in rs if r.q_pct < 0.4]
            rho_p = spearman([RATINGS.index(r.rating) for r in rs], [r.q_pct for r in rs])
            per_q.append([pid, len(rs), fmt(rho_p, 2), len(low),
                          fmt_p(sum(1 for r in low if r.rating != "Forgot") / len(low)) if low else "–"])
        rep.table(["participant", "scored reviews", "rank correlation", "under 40% right", "of those rated a success"], per_q)
        summary["question_scores"].update(bands=band_summary)
    else:
        rep.p("No review carries a question score yet.")

    # ---- review method -----------------------------------------------------------------------------------
    rep.h("7. Review method: what does the NEXT review find?")
    resid, within, method_clusters = method_comparison(all_rows)
    rows_m = []
    for gname in ("Questions only", "Reading only", "Lecture only", "Questions + other", "other mix", "not said"):
        v = resid.get(gname)
        if v:
            mu = statistics.fmean(v)
            interval = clustered_gap(method_clusters[gname])
            ci = interval.get("gap_ci") if interval and interval["clusters"] >= METHOD_MIN_CLUSTERS else None
            rows_m.append([gname, len(v), f"{mu:+.3f}", f"{ci[0]:+.3f} to {ci[1]:+.3f}" if ci else "–"])
    summary["method_residuals"] = {k: dict(n=len(v), mean=statistics.fmean(v)) for k, v in resid.items() if v}
    summary["method_within_participant"] = within
    rep.p("For each review, how the NEXT review of the same topic turned out against its prediction (reported − predicted; "
          "positive = the topic held better than the model expected after that kind of review). Differences between "
          "rows, not the rows themselves, are what matter; they are observational (learners chose their method):")
    rep.table(["how the previous review was done", "next reviews", "mean residual", "topic-clustered 95% CI"], rows_m)
    wp = summary["method_within_participant"]
    if wp:
        interval = (f"95% CI {wp['ci'][0]:+.3f} to {wp['ci'][1]:+.3f}" if wp["ci"] else
                    f"interval withheld: fewer than {METHOD_MIN_CLUSTERS} effective topic clusters overall or fewer than two effective topics per learner/method")
        rep.p(f"Inside each learner (Questions minus Reading, at least {WITHIN_MIN} next reviews per method): "
              f"**{wp['diff']:+.3f}** ({interval}), {wp['participants']} learner(s), {wp['clusters']} topics, "
              f"{wp['effective_clusters']:.1f} effective topic clusters, {wp['n_questions']}/{wp['n_reading']} reviews. "
              "Topic-clustered and conditional on these learners. "
              "D7 reads this interval; no interval means WAIT. This is an observational association, not a causal "
              "method effect or a population-level confidence interval.")
    else:
        rep.p("No learner used both methods often enough yet. D7 waits.")
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
        rep.p(f"Fitted {', '.join(FIT_NAMES)} on each participant's earliest 75% of recall reviews in saved-id order "
              f"({pf['train_reviews']}), with a mild pull toward the defaults, and scored both weight sets on the "
              f"remaining 25% ({pf['test_reviews']}), predicted from the full history before each review.")
        rep.table(["weight", "default", "fitted"], [[k, fmt(a, 4), fmt(b, 4)] for k, (a, b) in pf["changed"].items()])
        rep.p(f"Held-out log loss: default {fmt(pf['test_log_loss_default'], 4)}, fitted {fmt(pf['test_log_loss_fitted'], 4)}; "
              f"AUC {fmt(pf['test_auc_default'], 3)} → {fmt(pf['test_auc_fitted'], 3)}; paired z = {fmt(pf['z'], 2)} "
              f"({'beats the defaults at the app’s own 1% bar' if pf['z'] >= 2.33 else 'not better than the defaults at the app’s 1% bar'}). "
              f"First-rating grades in order: {'yes' if pf['grade_order_ok'] else '**no**'}; next intervals against the "
              f"defaults: ×{fmt(pf['lengthening'], 3)} (the app refuses a set above 1). "
              + ("It meets every condition the app sets." if pf["better"] else "It does NOT meet every condition the app sets."))
        with open(os.path.join(out_dir, "fitted_weights.json"), "w", encoding="utf-8") as f:
            json.dump(dict(weights=pf["weights"], note="Pooled pilot refit of w1,w2,w3,w8,w20; the rest are the FSRS-6 defaults.",
                           held_out_z=pf["z"], grade_order_ok=pf["grade_order_ok"], lengthening=pf["lengthening"],
                           meets_app_conditions=pf["better"], train_reviews=pf["train_reviews"], test_reviews=pf["test_reviews"],
                           validation_order=pf["validation_order"]), f, indent=2)
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
    rep.p("- How often each decision rule fires by chance, and what it can miss, is in docs/PILOT.md (\"What each rule "
          "can detect\"). With a handful of learners a WAIT or an OK is not proof that nothing is wrong.")
    rep.p("- Two months of data cannot test long intervals: almost every review here is within ~60 days of the previous one.")
    rep.p("- Change nothing that CLAUDE.md lists as a settled decision without the owner; the reasons are written there.")
    rep.p("- Behaviour changes are simulated before they are argued: tools/pilot/simulate.py (add --weights fitted_weights.json).")

    # ---- files -------------------------------------------------------------------------------------------
    write_csvs(out_dir, all_rows, topic_rows, summary)
    with open(os.path.join(out_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write(rep.text())
    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        # Strict JSON: json.dump writes a float NaN or infinity as a bare token (its default= is never asked about floats),
        # which a strict reader, forecast_audit.py's included, refuses (a production review, 2026-10-10).
        json.dump(finite_json(summary), f, indent=2, ensure_ascii=False, allow_nan=False, default=str)
    return summary


def finite_json(o):
    """[o] with every NaN or infinite float replaced by None, for strict JSON."""
    if isinstance(o, float):
        return o if math.isfinite(o) else None
    if isinstance(o, dict):
        return {k: finite_json(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [finite_json(v) for v in o]
    return o


def decide(summary: dict, recalls_pred: List[Row]) -> List[dict]:
    out = []

    def add(i, q, result, verdict):
        out.append(dict(id=i, question=q, result=result, verdict=verdict))

    integ = summary["integrity"]
    zone_note = ""
    if integ["mismatched"] and ZONES_WITHOUT_DATABASE:
        zone_note = (f"; this computer has no time-zone database for {', '.join(sorted(ZONES_WITHOUT_DATABASE))}, so a day "
                     "count near midnight across a daylight-saving change can be this analysis's error, not the phone's: "
                     "install it (python -m pip install tzdata) and run again")
    add("D1", "Did every phone schedule exactly what the rules say?",
        f"{integ['mismatched']} mismatches, {integ['consistency_issues']} self-check issues{zone_note}",
        "BUG" if integ["mismatched"] or integ["consistency_issues"] else "OK")

    # Every group with enough reviews is judged: the published defaults pooled, and each learner's personal set on its
    # own. D2 used to read the defaults only, so a personal set 25 points off was never flagged (an outside audit,
    # 2026-10-03). Past 5 points a group is LOOK only when its topic-clustered 95% interval excludes 0 (amended
    # 2026-10-03, before any pilot data), otherwise WAIT.
    judged, notes = [], []
    for key, v in sorted((summary.get("calibration") or {}).items()):
        c = v["calibrated"]
        if c.get("n", 0) < 300:
            continue
        gap = c["observed"] - c["predicted"]
        ci_gap = (c.get("clustered") or {}).get("gap_ci")
        sure = ci_gap is not None and (ci_gap[0] > 0 or ci_gap[1] < 0)
        judged.append("WAIT" if ci_gap is None else ("OK" if abs(gap) <= 0.05 else ("LOOK" if sure else "WAIT")))
        notes.append(f"{key}: reported {fmt_p(c['observed'])} vs predicted {fmt_p(c['predicted'])} (n={c['n']}"
                     + (f"; clustered 95% CI of the gap {100 * ci_gap[0]:+.1f} to {100 * ci_gap[1]:+.1f} points)" if ci_gap else "; interval withheld: insufficient effective topic evidence)"))
    q2 = "Does reported recall match the (calibrated) prediction within 5 points?"
    if judged:
        add("D2", q2, "; ".join(notes), "LOOK" if "LOOK" in judged else ("WAIT" if "WAIT" in judged else "OK"))
    else:
        add("D2", q2, "no weight set with 300 reviews", "WAIT")

    for g in ("Hard", "Medium", "Easy"):
        fr = (summary.get("first_review") or {}).get(g)
        if fr and fr["n"] >= 60:
            gap = fr["observed"] - fr["predicted"]
            # Too late: more than 7 points below. Too early only when it was also nearly certain: more than 5 points above
            # AND over 95% recalled, a first review that early being close to wasted (the rule since 2026-09-24, written
            # out in full on 2026-10-04 after an outside audit found the question and PILOT.md gave only the band).
            verdict = "LOOK" if gap < -0.07 or (gap > 0.05 and fr["observed"] > 0.95) else "OK"
            add(f"D3-{g}", D3_QUESTION.format(g=g),
                f"reported {fmt_p(fr['observed'])} vs predicted {fmt_p(fr['predicted'])} (n={fr['n']})", verdict)
        else:
            add(f"D3-{g}", D3_QUESTION.format(g=g), f"{fr['n'] if fr else 0} reviews (needs 60)", "WAIT")

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
        add("D5", "Does a pooled refit predict held-out reviews better (z ≥ 2.33), with the app's conditions?",
            f"z = {pf['z']:.2f}; grades in order: {'yes' if pf['grade_order_ok'] else 'no'}; "
            f"lengthening ×{pf['lengthening']:.3f}", "LOOK" if pf["better"] else "OK")
    else:
        add("D5", "Does a pooled refit predict held-out reviews better (z ≥ 2.33), with the app's conditions?",
            pf.get("reason", "not run"), "WAIT")

    qs = summary.get("question_scores") or {}
    if qs.get("n", 0) >= 50 and qs.get("spearman") is not None:
        add("D6", "Do memory ratings follow question scores (rank correlation ≥ 0.3)?", f"ρ = {qs['spearman']:.2f} (n={qs['n']})",
            "OK" if qs["spearman"] >= 0.3 else "LOOK")
    else:
        add("D6", "Do memory ratings follow question scores (rank correlation ≥ 0.3)?", f"{qs.get('n', 0)} scored reviews (needs 50)", "WAIT")

    mr = summary.get("method_residuals") or {}
    q, rd = mr.get("Questions only"), mr.get("Reading only")
    wp = summary.get("method_within_participant")
    if q and rd and q["n"] >= 100 and rd["n"] >= 100 and wp:
        # Judged within each learner (2026-09-28, before any pilot data): the pooled gap is shown for comparison only.
        # Amended 2026-10-03, before any data: a 5-point difference must also have a 95% interval that excludes 0. With
        # 100 reviews after each method the threshold alone fired about one time in four on equal methods (PILOT.md).
        sure = wp["ci"] is not None and (wp["ci"][0] > 0 or wp["ci"][1] < 0)
        add("D7", "After a Questions review, does the next review go better than after a Reading one (by 5+ points)?",
            (f"{wp['diff']:+.3f} (topic-clustered 95% CI {wp['ci'][0]:+.3f} to {wp['ci'][1]:+.3f}) "
             if wp["ci"] else f"{wp['diff']:+.3f} (insufficient effective topic evidence for an interval) ") +
            f"within learners ({wp['participants']}); "
            f"pooled {q['mean'] - rd['mean']:+.3f} (n={q['n']}/{rd['n']})",
            "WAIT" if wp["ci"] is None else ("OK" if abs(wp["diff"]) < 0.05 else ("LOOK" if sure else "WAIT")))
    elif q and rd and q["n"] >= 100 and rd["n"] >= 100:
        add("D7", "After a Questions review, does the next review go better than after a Reading one (by 5+ points)?",
            f"no learner used both methods {WITHIN_MIN}+ times; pooled {q['mean'] - rd['mean']:+.3f} compares people, "
            "not methods", "WAIT")
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

    q11 = (f"Did every phone deliver a reminder on at least 95% of days, and at least 95% within "
           f"{LATE_REMINDER_S // 60} minutes of their time?")
    judged = {pid: r for pid, r in (summary.get("reminders") or {}).items() if r["days"] >= D11_MIN_DAYS}
    if judged:
        failing = []
        for pid, r in judged.items():
            covered = 1 - len(r["missed_days"]) / r["days"]
            on_time = 1 - r["late"] / r["known"] if r["known"] else 1.0
            if covered < 0.95 or on_time < 0.95:
                failing.append(f"{pid}: {fmt_p(covered)} of days, {fmt_p(on_time)} on time")
        add("D11", q11, "; ".join(failing) if failing else f"all {len(judged)} phones with {D11_MIN_DAYS}+ days",
            "LOOK" if failing else "OK")
    else:
        add("D11", q11, f"no phone with {D11_MIN_DAYS} days of reminder data (export v13+)", "WAIT")
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
                "overdue_at_export", "overdue_7d_at_export", "consistency_issues", "scale_raw", "scale_raw_ci", "scale_app",
                "reminder_days", "days_without_reminder", "reminder_fires", "late_reminders", "safety_net_reminders",
                "reminder_health_problems", "opened_from_reminder", "reviewed_after_reminder", "rating_corrections",
                "snapshot_days", "median_overdue", "max_overdue", "days_held_back", "overdue_trend_per_30d", "app_builds",
                "time_zones", "settings_changes", "date_corrections", "minutes_given", "minutes_median", "saved_later"]
        w = csv.writer(f)
        w.writerow(keys)
        for pid, i in summary["participants"].items():
            w.writerow([pid] + [i.get(k) for k in keys[1:]])


def main(argv=None):
    # The summary prints "≥", "−" and "ρ". A Windows console that is redirected or piped encodes stdout as cp1252,
    # which has none of them, and the run died at the end with UnicodeEncodeError (an outside audit, 2026-09-30).
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
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

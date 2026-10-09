"""
Yadora's memory model and scheduling rules, in plain Python (standard library only).

This is an independent transcription of the Kotlin sources, for ANALYSIS: replaying exported histories,
refitting weights, and simulating learners. It is checked against the same py-fsrs 6.3.1 golden vectors
the app is checked against (test_yadora_model.py), so a disagreement between the two points at a bug in
one of them, not at a matter of opinion.

Sources mirrored (keep in step when they change):
  app/src/main/java/com/example/domain/srs/Fsrs6.kt            -- FSRS-6 equations
  app/src/main/java/com/example/domain/srs/MedScheduler.kt      -- caps, relearn step, repair clock, fuzz
  app/src/main/java/com/example/domain/srs/RecallCalibration.kt -- the per-user interval scale

Deliberately NOT mirrored: FSRS-5 (frozen, only replays pre-2026-09 history; a pilot starts on FSRS-6).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable, List, Optional, Sequence, Tuple

DEFAULT_WEIGHTS: Tuple[float, ...] = (
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
    0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
)
DEFAULT_PARAMETER_SET_ID = "FSRS6-DEFAULT-21-PYFSRS-6.3.1"

# py-fsrs 6.3.1's parameter bounds (what the app's optimizer clamps to as well).
LOWER_BOUNDS = (0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0,
                0.001, 0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1)
UPPER_BOUNDS = (100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8,
                3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8)

S_MIN = 0.001
D_MIN, D_MAX = 1.0, 10.0

# Grades as FSRS numbers them; Yadora's rating names map onto them.
AGAIN, HARD, GOOD, EASY = 1, 2, 3, 4
GRADE_OF = {"Forgot": AGAIN, "Hard": HARD, "Good": GOOD, "Easy": EASY}

# MedScheduler policy constants (POLICY_VERSION YADORA-8).
MIN_INTERVAL_DAYS = 1.0
MAX_INTERVAL_DAYS = 365.0
RELEARN_STEP_DAYS = 1.0
FIRST_STUDY_MAX_DAYS = 5.0
FUZZ_MIN_BASE_DAYS = 3.0
POLICY_VERSION = "YADORA-8"
REPAIR_BACKOFF_FACTOR = 2.0

# RecallCalibration constants.
CAL_PRIOR_REVIEWS = 120
CAL_WINDOW = 600
CAL_MIN_ELAPSED_DAYS = 3.0
CAL_EARLY_FRACTION = 0.5
CAL_MIN_SCALE, CAL_MAX_SCALE = 0.5, 2.0   # the band a stored scale replays within (older builds stored up to 2)
CAL_MAX_ESTIMATE = 1.0                    # a NEW estimate never lengthens intervals (RecallCalibration, 2026-09-28)


@dataclass(frozen=True)
class State:
    stability: float
    difficulty: float


class Fsrs6:
    """FSRS-6 with one weight vector. Mirrors Fsrs6.kt line for line (same clamps, same branches)."""

    def __init__(self, weights: Sequence[float] = DEFAULT_WEIGHTS):
        if len(weights) != 21:
            raise ValueError(f"FSRS-6 needs 21 weights, got {len(weights)}")
        self.w = tuple(float(x) for x in weights)
        self.decay = -self.w[20]
        self.factor = 0.9 ** (1.0 / self.decay) - 1.0

    # --- forgetting curve -------------------------------------------------------------------------
    def retrievability(self, elapsed_days: float, stability: float) -> float:
        t = max(elapsed_days, 0.0)
        s = max(stability, S_MIN)
        return (1.0 + self.factor * t / s) ** self.decay

    def interval_days(self, stability: float, retention: float) -> float:
        s = max(stability, S_MIN)
        return (s / self.factor) * (retention ** (1.0 / self.decay) - 1.0)

    # --- state transitions ------------------------------------------------------------------------
    def _raw_initial_difficulty(self, grade: int) -> float:
        return self.w[4] - math.exp(self.w[5] * (grade - 1)) + 1.0

    def initial_state(self, grade: int) -> State:
        s0 = max(self.w[grade - 1], S_MIN)
        d0 = min(max(self._raw_initial_difficulty(grade), D_MIN), D_MAX)
        return State(s0, d0)

    def next_difficulty(self, d: float, grade: int) -> float:
        w = self.w
        delta = -w[6] * (grade - 3)
        damped = d + delta * (10.0 - d) / 9.0
        # Mean reversion targets the UNCLAMPED D0(Easy), exactly as py-fsrs does.
        out = w[7] * self._raw_initial_difficulty(EASY) + (1.0 - w[7]) * damped
        return min(max(out, D_MIN), D_MAX)

    def short_term_stability(self, s: float, grade: int) -> float:
        w = self.w
        s = max(s, S_MIN)
        inc = math.exp(w[17] * (grade - 3 + w[18])) * s ** (-w[19])
        if grade in (GOOD, EASY):
            inc = max(inc, 1.0)
        return max(s * inc, S_MIN)

    def recall_stability(self, st: State, r: float, grade: int) -> float:
        w = self.w
        hard = w[15] if grade == HARD else 1.0
        easy = w[16] if grade == EASY else 1.0
        inc = (math.exp(w[8]) * (11.0 - st.difficulty) * st.stability ** (-w[9])
               * (math.exp(w[10] * (1.0 - r)) - 1.0) * hard * easy)
        return st.stability * (1.0 + inc)

    def lapse_stability(self, st: State, r: float) -> float:
        w = self.w
        long_term = (w[11] * st.difficulty ** (-w[12]) * ((st.stability + 1.0) ** w[13] - 1.0)
                     * math.exp(w[14] * (1.0 - r)))
        short_term = st.stability / math.exp(w[17] * w[18])
        return max(min(long_term, short_term), S_MIN)

    def next_state(self, st: State, elapsed_days: float, grade: int) -> State:
        r = self.retrievability(elapsed_days, st.stability)
        d = self.next_difficulty(st.difficulty, grade)
        if elapsed_days < 1.0:
            s = self.short_term_stability(st.stability, grade)
        elif grade == AGAIN:
            s = self.lapse_stability(st, r)
        else:
            s = self.recall_stability(st, r, grade)
        return State(max(s, S_MIN), d)


# --- MedScheduler rules ---------------------------------------------------------------------------------

def safe_scale(scale: float) -> float:
    return min(max(scale, CAL_MIN_SCALE), CAL_MAX_SCALE) if math.isfinite(scale) else 1.0


def memory_interval(model: Fsrs6, new_state: State, grade: int, retention: float,
                    first_study: bool, calibration_scale: float = 1.0) -> Tuple[float, float]:
    """(interval, base interval) exactly as MedScheduler.reviewFsrs6 computes them, before fuzz."""
    if grade == AGAIN and not first_study:
        return RELEARN_STEP_DAYS, RELEARN_STEP_DAYS
    raw = model.interval_days(new_state.stability, retention) * safe_scale(calibration_scale)
    if first_study:
        raw = min(raw, FIRST_STUDY_MAX_DAYS)
    return min(max(raw, MIN_INTERVAL_DAYS), MAX_INTERVAL_DAYS), raw


def remediation_days(memory_rating: str, understanding: str, unrepaired_streak: int = 0) -> Optional[float]:
    """The understanding repair deadline (YADORA-6 backoff), before the "must beat the memory date" rule. Since
    YADORA-7 the app keeps it when it beats the memory interval as finally scheduled, fuzz included
    (MedScheduler.repairDays); YADORA-6 and older compared it with the interval before the fuzz."""
    if memory_rating == "Forgot":
        return 1.0
    if understanding == "Confused":
        base = 1.0
    elif understanding == "Partial":
        base = {"Hard": 2.0, "Good": 3.0, "Easy": 4.0}.get(memory_rating, 1.0)
    else:
        return None
    return base * REPAIR_BACKOFF_FACTOR ** min(max(unrepaired_streak, 0), 30)


class KotlinRandom:
    """kotlin.random.Random(seed: Long) -- XorWow -- so the app's interval fuzz can be reproduced exactly."""

    M32 = 0xFFFFFFFF

    def __init__(self, seed: int):
        seed &= 0xFFFFFFFFFFFFFFFF
        s1 = seed & self.M32
        s2 = (seed >> 32) & self.M32
        self.x, self.y, self.z, self.w = s1, s2, 0, 0
        self.v = (~s1) & self.M32
        self.addend = ((s1 << 10) ^ (s2 >> 4)) & self.M32
        for _ in range(64):
            self.next_int()

    def next_int(self) -> int:
        t = self.x
        t ^= t >> 2
        self.x, self.y, self.z = self.y, self.z, self.w
        v0 = self.v
        self.w = v0
        t = (t ^ (t << 1) ^ v0 ^ (v0 << 4)) & self.M32
        self.v = t
        self.addend = (self.addend + 362437) & self.M32
        return (t + self.addend) & self.M32

    def next_bits(self, n: int) -> int:
        return self.next_int() >> (32 - n)

    def next_double(self) -> float:
        hi = self.next_bits(26)
        lo = self.next_bits(27)
        return ((hi << 27) + lo) / float(1 << 53)

    def next_double_range(self, lo: float, hi: float) -> float:
        r = lo + self.next_double() * (hi - lo)
        return math.nextafter(hi, -math.inf) if r >= hi else r


def fuzzed_interval(interval: float, base_interval: float, unit_id: int, review_count: int,
                    first_study: bool, policy: str = POLICY_VERSION, model: str = "FSRS-6") -> float:
    """MedScheduler.fuzzedInterval, including the YADORA-8 FSRS-6 floor; old policies replay unchanged."""
    if base_interval < FUZZ_MIN_BASE_DAYS:
        return interval
    rng = KotlinRandom(unit_id * 31 + review_count)
    factor = 1.0 + rng.next_double_range(-0.05, 0.05)
    old_policy = policy in tuple(f"YADORA-{n}" for n in range(1, 8))
    minimum = FUZZ_MIN_BASE_DAYS if model == "FSRS-6" and not old_policy else MIN_INTERVAL_DAYS
    fuzzed = min(max(interval * factor, minimum), MAX_INTERVAL_DAYS)
    return min(fuzzed, FIRST_STUDY_MAX_DAYS) if first_study else fuzzed


# --- RecallCalibration ----------------------------------------------------------------------------------

def is_calibration_evidence(elapsed_days: float, previous_interval_days: float) -> bool:
    return elapsed_days >= CAL_MIN_ELAPSED_DAYS and elapsed_days >= CAL_EARLY_FRACTION * previous_interval_days


def moment_scale(predicted: Sequence[float], recalled: Sequence[bool], model: Fsrs6 = Fsrs6()) -> float:
    """The stability scale at which the predicted recall count equals the observed one (unshrunk)."""
    if not predicted:
        return 1.0
    ratios = [min(max(p, 1e-9), 1.0) ** (1.0 / model.decay) - 1.0 for p in predicted]
    observed = float(sum(1 for r in recalled if r))

    def total(k: float) -> float:
        return sum((1.0 + q / k) ** model.decay for q in ratios)

    lo_s, hi_s = 0.25, 4.0
    if total(hi_s) <= observed:
        return hi_s
    if total(lo_s) >= observed:
        return lo_s
    lo, hi = math.log(lo_s), math.log(hi_s)
    for _ in range(60):
        mid = (lo + hi) / 2
        if total(math.exp(mid)) < observed:
            lo = mid
        else:
            hi = mid
    return math.exp((lo + hi) / 2)


def calibration_scale(predicted: Sequence[float], recalled: Sequence[bool], model: Fsrs6 = Fsrs6()) -> float:
    """RecallCalibration.scale: the moment scale shrunk toward 1 in log space by n/(n+120), clamped to
    [CAL_MIN_SCALE, CAL_MAX_ESTIMATE]: it shortens intervals, never lengthens them (since 2026-09-28)."""
    n = len(predicted)
    if n == 0:
        return 1.0
    return min(calibration_scale_uncapped(predicted, recalled, model), CAL_MAX_ESTIMATE)


def calibration_scale_uncapped(predicted: Sequence[float], recalled: Sequence[bool], model: Fsrs6 = Fsrs6()) -> float:
    """The estimate as builds before 2026-09-28 applied it (clamped to 0.5-2): kept for experiments.py's comparison."""
    n = len(predicted)
    if n == 0:
        return 1.0
    raw = moment_scale(predicted, recalled, model)
    weight = n / (n + CAL_PRIOR_REVIEWS)
    return safe_scale(math.exp(math.log(raw) * weight))


# --- metrics --------------------------------------------------------------------------------------------

def log_loss(pairs: Iterable[Tuple[float, bool]]) -> float:
    total, n = 0.0, 0
    for p, y in pairs:
        p = min(max(p, 1e-6), 1 - 1e-6)
        total += -math.log(p if y else 1.0 - p)
        n += 1
    return total / n if n else float("nan")


def auc(pairs: Sequence[Tuple[float, bool]]) -> float:
    """Probability a random recalled review was predicted higher than a random forgotten one (ties = 1/2)."""
    pos = sorted(p for p, y in pairs if y)
    neg = sorted(p for p, y in pairs if not y)
    if not pos or not neg:
        return float("nan")
    # Rank-sum with ties handled by counting.
    import bisect
    wins = 0.0
    for p in pos:
        lo = bisect.bisect_left(neg, p)
        hi = bisect.bisect_right(neg, p)
        wins += lo + 0.5 * (hi - lo)
    return wins / (len(pos) * len(neg))


def rmse_bins(rows: Sequence[Tuple[float, bool, float, int, int]]) -> float:
    """srs-benchmark style RMSE(bins): rows are (predicted, recalled, elapsed_days, review_number, lapses).

    Reviews are grouped by (interval bucket, review-count bucket, lapse bucket); the squared gap between
    mean prediction and observed rate is averaged with bin sizes as weights. Buckets follow the benchmark's
    log-spaced idea at a coarser grain, because a pilot has thousands of reviews, not millions.
    """
    def bucket_t(t: float) -> int:
        return int(round(2.48 * 3.62 ** math.floor(math.log(max(t, 0.01)) / math.log(3.62)) * 100))

    def bucket_n(n: int) -> int:
        return int(round(1.99 * 1.65 ** math.floor(math.log(max(n, 1)) / math.log(1.65))))

    def bucket_l(l: int) -> int:
        return 0 if l == 0 else int(round(1.73 * 1.73 ** math.floor(math.log(l) / math.log(1.73))))

    bins = {}
    for p, y, t, n, l in rows:
        key = (bucket_t(t), bucket_n(n), bucket_l(l))
        s = bins.setdefault(key, [0.0, 0.0, 0])
        s[0] += p
        s[1] += 1.0 if y else 0.0
        s[2] += 1
    total_n = sum(v[2] for v in bins.values())
    if total_n == 0:
        return float("nan")
    se = sum(v[2] * ((v[0] - v[1]) / v[2]) ** 2 for v in bins.values())
    return math.sqrt(se / total_n)


def clamp_weights(w: Sequence[float]) -> List[float]:
    return [min(max(x, lo), hi) for x, lo, hi in zip(w, LOWER_BOUNDS, UPPER_BOUNDS)]

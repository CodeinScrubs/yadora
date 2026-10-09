# Generate FSRS-6 golden vectors from the REAL installed py-fsrs library.
# Not a re-derivation: these call the library's own methods.
#
#   python generate_fsrs6_goldens.py [output.json]
#
# app/src/test/resources/golden_fsrs6.json came from py-fsrs 6.3.1 (what reviews stamped YADORA-8 or earlier replay)
# and golden_fsrs6_632.json from py-fsrs 6.3.2 (live since YADORA-9); each file records the version that wrote it.
import json, io, sys
import fsrs
from fsrs.scheduler import Scheduler, DEFAULT_PARAMETERS
from fsrs.rating import Rating

try:
    import importlib.metadata as md
    ver = md.version("fsrs")
except Exception:
    ver = "unknown"

s = Scheduler()
print("py-fsrs version:", ver)
print("n params:", len(DEFAULT_PARAMETERS))
print("params:", list(DEFAULT_PARAMETERS))
print("DECAY:", s._DECAY, "FACTOR:", s._FACTOR)
print("unclamped D0(Easy):", s._initial_difficulty(rating=Rating.Easy, clamp=False))
print("clamped   D0(Easy):", s._initial_difficulty(rating=Rating.Easy, clamp=True))

rows = []
# Deliberately runs past 3650 days (10 years). Yadora once clamped stability there, which the
# reference does not do, and a sweep that stopped at 1000 could never have seen it.
stabs = [0.01, 0.5, 1.0, 2.3065, 5.0, 12.0, 68.9, 250.0, 1000.0, 4000.0, 20000.0]
diffs = [1.0, 2.1181, 3.5, 5.0, 7.25, 10.0]
elapsed = [0, 1, 2, 5, 13, 30, 100, 365]
grades = [Rating.Again, Rating.Hard, Rating.Good, Rating.Easy]

for S in stabs:
    for D in diffs:
        for g in grades:
            rows.append({
                "kind": "difficulty",
                "d": D, "g": int(g),
                "out": s._next_difficulty(difficulty=D, rating=g),
            })
            rows.append({
                "kind": "shortTerm",
                "s": S, "g": int(g),
                "out": s._short_term_stability(stability=S, rating=g),
            })
            for t in elapsed:
                R = (1 + s._FACTOR * t / S) ** s._DECAY
                if g == Rating.Again:
                    out = s._next_forget_stability(difficulty=D, stability=S, retrievability=R)
                else:
                    out = s._next_recall_stability(difficulty=D, stability=S, retrievability=R, rating=g)
                rows.append({
                    "kind": "longTerm",
                    "s": S, "d": D, "t": t, "g": int(g), "r": R, "out": out,
                })

# FULL-STATE transitions: the composed step, so branch selection and the final clamps are covered
# too. _next_stability is the library's own Again-vs-recall dispatch AND its stability clamp, so the
# only thing composed here is the same-day branch, which mirrors Scheduler.review_card's Review case.
full = []
for S in stabs:
    for D in diffs:
        for t in elapsed:
            R = (1 + s._FACTOR * t / S) ** s._DECAY
            for g in grades:
                if t < 1:
                    S2 = s._short_term_stability(stability=S, rating=g)
                else:
                    S2 = s._next_stability(difficulty=D, stability=S, retrievability=R, rating=g)
                D2 = s._next_difficulty(difficulty=D, rating=g)
                full.append({"s": S, "d": D, "t": t, "g": int(g), "outS": S2, "outD": D2})

# Initial states: the reference clamps initial stability to the MINIMUM only.
init = []
for g in grades:
    init.append({
        "g": int(g),
        "s": s._clamp_stability(stability=s.parameters[g - 1]),
        "d": s._initial_difficulty(rating=g, clamp=True),
    })

# Retrievability + interval inversion goldens
rt = []
for S in stabs:
    for t in elapsed:
        rt.append({"s": S, "t": t, "r": (1 + s._FACTOR * t / S) ** s._DECAY})
iv = []
for S in stabs:
    for r in [0.70, 0.80, 0.85, 0.90, 0.93, 0.95, 0.97, 0.99]:
        iv.append({"s": S, "req": r, "days": (S / s._FACTOR) * ((r ** (1 / s._DECAY)) - 1)})

out = sys.argv[1] if len(sys.argv) > 1 else "golden_fsrs6.json"
io.open(out, "w", encoding="utf-8").write(json.dumps({
    "pyFsrsVersion": ver,
    "parameters": list(DEFAULT_PARAMETERS),
    "decay": s._DECAY, "factor": s._FACTOR,
    "transitions": rows, "fullState": full, "initialStates": init,
 "retrievability": rt, "intervals": iv,
}, indent=1))
print("wrote", len(rows), "transitions,", len(full), "full-state,", len(init), "initial,", len(rt), "R,", len(iv), "interval")

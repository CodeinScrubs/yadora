"""Audit immutable REVIEW_FORECAST v1 events in research exports. Standard-library Python 3.9+.

  python tools/pilot/forecast_audit.py exports/ --out prospective_report

No app/database writes, refit, policy recommendation or reconstruction of missing forecasts. Ratings are a
subjective post-study proxy: these metrics do not measure objective recall, exam scores or causal benefit.
"""
import argparse
import csv
import json
import math
import os
import statistics
from collections import Counter, defaultdict

import analyze

RATINGS = {"Forgot": False, "Hard": True, "Good": True, "Easy": True}
CONTEXTS = {"FIRST_STUDY", "AHEAD", "USER_MOVED", "MEMORY_DUE", "UNDERSTANDING_DUE", "BOTH_DUE", "UNKNOWN"}


def parse(detail):
    """Do not silently accept duplicate keys or unversioned/new-version snapshots."""
    if not isinstance(detail, str):
        raise ValueError("missing snapshot detail")
    fields = {}
    for token in detail.split():
        key, sep, value = token.partition("=")
        if not sep or not key or not value or key in fields:
            raise ValueError("malformed or duplicate snapshot field")
        fields[key] = value
    if fields.get("v") != "1":
        raise ValueError("unsupported snapshot version")
    for key in ("log", "at", "set", "previousReview", "memoryDue", "repairDue", "effectiveDue", "deferredUntil"):
        fields[key] = int(fields[key])
    if fields["log"] <= 0 or fields["set"] < 0:
        raise ValueError("invalid identity")
    for key in ("p", "elapsed", "stability", "difficulty", "previousInterval", "retention", "scale"):
        fields[key] = float(fields[key])
        if not math.isfinite(fields[key]):
            raise ValueError("non-finite numeric field")
    if not (0 <= fields["p"] <= 1 and fields["elapsed"] >= 0 and fields["stability"] > 0
            and 1 <= fields["difficulty"] <= 10 and fields["previousInterval"] >= 0
            and 0.70 <= fields["retention"] <= 0.99 and 0.5 <= fields["scale"] <= 2):
        raise ValueError("numeric field outside its contract")
    if fields.get("memory") not in RATINGS or fields.get("understanding") not in {"NotAsked", "Confused", "Partial", "Clear"}:
        raise ValueError("invalid original answer")
    if fields.get("type") not in {"FIRST_STUDY", "RECALL"} or fields.get("model") != "FSRS-6":
        raise ValueError("unsupported log type or memory implementation")
    if fields.get("outcome") != "SUBJECTIVE_POST_STUDY" or fields.get("dueContext") not in CONTEXTS:
        raise ValueError("unknown outcome semantics or due context")
    for key in ("policy", "zone", "session"):
        if not fields.get(key):
            raise ValueError("missing provenance")
    return fields


def observations(export):
    grouped = defaultdict(list)
    issues, excluded = [], Counter()
    logs, ambiguous_logs = {}, set()
    for row in export.data["reviewLogs"]:
        log_id = int(row["id"])
        if log_id in ambiguous_logs:
            continue
        if log_id in logs:
            # SQLite ids are unique. A duplicated row in a modified export has no unambiguous join.
            del logs[log_id]
            ambiguous_logs.add(log_id)
            issues.append(f"{export.participant}: duplicate saved review log {log_id}")
        else:
            logs[log_id] = row
    for event in export.data.get("eventLogs") or []:
        if event.get("type") != "REVIEW_FORECAST":
            continue
        try:
            f = parse(event.get("detail"))
            if int(event["at"]) != f["at"]:
                raise ValueError("event timestamp disagrees with snapshot")
            grouped[f["log"]].append(f)
        except (ValueError, KeyError, TypeError, OverflowError) as error:
            issues.append(f"{export.participant}: event {event.get('id', '?')}: {error}")
    rows = []
    for log_id, snapshots in grouped.items():
        if len(snapshots) != 1:
            issues.append(f"{export.participant}: duplicate forecasts for log {log_id}")
            continue
        if log_id in ambiguous_logs:
            excluded["ambiguous_review_log"] += 1
            continue
        log = logs.get(log_id)
        if log is None:
            excluded["orphaned_after_purge_or_legacy_undo"] += 1
            continue  # retained event history can outlive a purged topic; never score it
        f = snapshots[0]
        if f["type"] == "FIRST_STUDY":
            excluded["first_study"] += 1
            continue
        if f["elapsed"] < 1:
            excluded["same_day"] += 1
            continue
        row = dict(f, participant=export.participant, topic=int(log["studyUnitId"]),
                   original_success=RATINGS[f["memory"]], current_memory=log["memoryRating"],
                   current_understanding=log.get("understandingRating", ""),
                   answer_changed=f["memory"] != log["memoryRating"] or f["understanding"] != log.get("understandingRating"))
        rows.append(row)
    excluded["missing_forecast"] = sum(1 for lid in logs if lid not in grouped)
    return rows, dict(excluded), issues


def metrics(rows):
    if not rows:
        return dict(n=0)
    pairs = [(r["p"], r["original_success"]) for r in rows]
    loss = analyze.ym.log_loss(pairs)
    brier = statistics.fmean((p - int(y)) ** 2 for p, y in pairs)
    gap = analyze.clustered_gap([(r["p"], r["original_success"], (r["participant"], r["topic"])) for r in rows])
    # Suppress an approximate cluster-normal interval when the number of topics is too small.
    if gap and gap["clusters"] < 20:
        gap["gap_ci"] = None
    return dict(n=len(rows), topics=len({(r["participant"], r["topic"]) for r in rows}),
                reported_success=statistics.fmean(int(y) for _, y in pairs),
                mean_forecast=statistics.fmean(p for p, _ in pairs), brier=brier, log_loss=loss,
                answer_changed=sum(r["answer_changed"] for r in rows), gap=gap)


def audit(exports):
    all_rows, people, issues = [], {}, []
    for export in exports:
        rows, excluded, errors = observations(export)
        all_rows.extend(rows)
        issues.extend(errors)
        people[export.participant] = dict(metrics=metrics(rows), excluded=excluded)
    groups = defaultdict(list)
    for row in all_rows:
        # Parameter set ids are phone-local. Never pool two learners' distinct "set 7" as one model.
        groups[(row["participant"], row["model"], row["set"], row["policy"], row["dueContext"])].append(row)
    summary = dict(version=1, semantics="subjective post-study rating, original answer, prospective raw forecast",
                   participants=people, issues=issues, groups=[dict(participant=k[0], model=k[1], set=k[2], policy=k[3],
                                                                 due_context=k[4], **metrics(v)) for k, v in sorted(groups.items())])
    return summary, sorted(all_rows, key=lambda r: (r["participant"], r["log"]))


def write(summary, rows, out):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as stream:
        json.dump(summary, stream, indent=2, ensure_ascii=False, allow_nan=False)
    with open(os.path.join(out, "forecasts.csv"), "w", newline="", encoding="utf-8-sig") as stream:
        fields = ["participant", "topic", "log", "at", "zone", "model", "set", "policy", "p", "elapsed", "stability",
                  "difficulty", "previousInterval", "memoryDue", "repairDue", "effectiveDue", "deferredUntil", "dueContext",
                  "memory", "understanding", "current_memory", "current_understanding", "answer_changed", "original_success"]
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    report = ["# Prospective forecast audit", "", "These outcomes are the ORIGINAL subjective post-study ratings. "
              "Forgot is 0; Hard/Good/Easy are 1 only as a proxy. Brier and log loss do not measure objective recall, "
              "educational benefit or exam performance. No universal passing score is applied.", "",
              "Missing forecasts are not reconstructed. First studies, same-day reviews, duplicate/malformed snapshots "
              "and orphaned events are excluded. Missing study days are not failures. Forecasts are joined by saved log id, "
              "including after merges and clock rollback. Current corrected answers appear beside the originals in the CSV.", "",
              "Intervals for the mean rating-minus-forecast gap cluster on topics, conditional on the observed learner. "
              "They are approximate and withheld below 20 topics. They do not account for a new learner population, "
              "method-choice confounding or repeated significance checks. Review times are selected by the policy/user.", "",
              "| Learner | Reviews | Topics | Predicted | Reported proxy | Brier | Log loss | Changed answers |",
              "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for pid, info in summary["participants"].items():
        m = info["metrics"]
        if m["n"]:
            report.append(f"| {pid} | {m['n']} | {m['topics']} | {m['mean_forecast']:.4f} | {m['reported_success']:.4f} | "
                          f"{m['brier']:.4f} | {m['log_loss']:.4f} | {m['answer_changed']} |")
        else:
            report.append(f"| {pid} | 0 | 0 | — | — | — | — | 0 |")
        report.extend(["", f"Excluded for {pid}: `{json.dumps(info['excluded'], sort_keys=True)}`", ""])
    report.extend(["", "Group results are in summary.json, separated by learner, memory model, weight set, policy and due context.",
                   "Malformed/duplicate snapshots: " + str(len(summary["issues"]))])
    report.extend("- " + error for error in summary["issues"])
    with open(os.path.join(out, "report.md"), "w", encoding="utf-8") as stream:
        stream.write("\n".join(report) + "\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+")
    parser.add_argument("--out", default="prospective_report")
    args = parser.parse_args(argv)
    warnings = []
    exports = analyze.load_exports(args.inputs, warnings.append)
    if not exports:
        print("No valid research exports. " + "; ".join(warnings))
        return 2
    summary, rows = audit(exports)
    summary["warnings"] = warnings
    write(summary, rows, args.out)
    print(f"{len(exports)} learner(s), {len(rows)} prospective delayed-review forecasts, {len(summary['issues'])} malformed/duplicate snapshots")
    print(os.path.join(args.out, "report.md"))
    return 1 if summary["issues"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

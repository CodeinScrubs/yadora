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
from collections import Counter, defaultdict, deque

import analyze

RATINGS = {"Forgot": False, "Hard": True, "Good": True, "Easy": True}
CONTEXTS = {"FIRST_STUDY", "AHEAD", "USER_MOVED", "MEMORY_DUE", "UNDERSTANDING_DUE", "BOTH_DUE", "UNKNOWN"}
RATING_DEFINITIONS = {"1", "2"}


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
    # Which meaning the rating had (2026-10-09): 2 = Forgot anchored to "most of it was gone on coming back"; absent = 1.
    fields["ratingDef"] = fields.get("ratingDef", "1")
    if fields["ratingDef"] not in RATING_DEFINITIONS:
        raise ValueError("unknown rating definition")
    fields["ratingDef"] = int(fields["ratingDef"])
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
                   answer_changed=f["memory"] != log["memoryRating"] or f["understanding"] != log.get("understandingRating"),
                   # The review's day was moved after this forecast (REVIEW_DATE_CORRECTED): the forecast keeps the
                   # original time and gap; the log now holds the corrected ones.
                   day_changed=f["at"] != int(log["reviewedAt"]), rating_def=f["ratingDef"])
        rows.append(row)
    excluded["missing_forecast"] = sum(1 for lid in logs if lid not in grouped)
    return rows, dict(excluded), issues


# Fixed diagnostics, not fitted on the export or a prescription for review scheduling.
BASELINE_WINDOW = 50
BASELINE_MIN_HISTORY = 20
MIN_EFFECTIVE_TOPICS = 20


def with_past_baseline(rows):
    """Counterfactual reference on retained, valid ORIGINAL observations, with no current/future outcome.

    Each learner/model/set/policy/session/due-context/rating-definition stream resets independently (the two rating
    definitions measure different things, so one never serves as the other's reference). Order is the saved log id,
    never the editable/rollback-prone wall clock. Laplace smoothing prevents certainty after all successes
    or failures. The fixed 50-review window and 20-review warm-up are diagnostics, not validated optima.
    """
    histories = defaultdict(lambda: deque(maxlen=BASELINE_WINDOW))
    result = []
    for row in sorted(rows, key=lambda r: (r["participant"], r["log"])):
        key = (row["participant"], row["model"], row["set"], row["policy"], row["session"], row["dueContext"],
               row.get("rating_def", 1))
        past = histories[key]
        probability = (sum(past) + 1.) / (len(past) + 2.) if len(past) >= BASELINE_MIN_HISTORY else None
        result.append(dict(row, baseline_p=probability, baseline_history_n=len(past)))
        past.append(int(row["original_success"]))
    return result


def baseline_comparison(rows):
    """Paired raw-model vs past-only reference scores on EXACTLY the same reviews.

    Topic-cluster sandwich SE for the review-weighted mean loss difference, conditional on this learner.
    Positive advantage means lower raw-FSRS log loss. The approximate 95% interval is withheld for sparse
    or concentrated topics; it is not a learning-effect interval, a causal test or a sequential test.
    """
    paired = [r for r in rows if r.get("baseline_p") is not None]
    out = dict(n=len(paired), warmup_excluded=len(rows) - len(paired),
               baseline="past 50 comparable original ratings, Laplace smoothing, 20-review warm-up",
               log_loss_advantage=None, log_loss_advantage_ci=None, effective_topics=0., topics=0)
    if not paired:
        return out
    model_pairs = [(r["p"], r["original_success"]) for r in paired]
    baseline_pairs = [(r["baseline_p"], r["original_success"]) for r in paired]
    differences = [analyze.ym.log_loss([b]) - analyze.ym.log_loss([m]) for b, m in zip(baseline_pairs, model_pairs)]
    average = statistics.fmean(differences)
    clusters = defaultdict(list)
    for row, delta in zip(paired, differences):
        clusters[(row["participant"], row["topic"])].append(delta)
    counts = [len(values) for values in clusters.values()]
    effective = len(paired) ** 2 / sum(n * n for n in counts)
    interval = None
    if len(clusters) > 1 and effective + 1e-9 >= MIN_EFFECTIVE_TOPICS:
        variance = len(clusters) / (len(clusters) - 1.) * sum(
            (sum(values) - len(values) * average) ** 2 for values in clusters.values()) / len(paired) ** 2
        se = math.sqrt(variance)
        interval = [average - 1.96 * se, average + 1.96 * se]
    out.update(topics=len(clusters), effective_topics=effective,
               raw_model_log_loss=analyze.ym.log_loss(model_pairs), baseline_log_loss=analyze.ym.log_loss(baseline_pairs),
               raw_model_brier=statistics.fmean((p - int(y)) ** 2 for p, y in model_pairs),
               baseline_brier=statistics.fmean((p - int(y)) ** 2 for p, y in baseline_pairs),
               log_loss_advantage=average, log_loss_advantage_ci=interval)
    return out


def metrics(rows):
    if not rows:
        return dict(n=0)
    pairs = [(r["p"], r["original_success"]) for r in rows]
    loss = analyze.ym.log_loss(pairs)
    brier = statistics.fmean((p - int(y)) ** 2 for p, y in pairs)
    gap = analyze.clustered_gap([(r["p"], r["original_success"], (r["participant"], r["topic"])) for r in rows])
    # Count weight concentration too: many one-review topics cannot hide one dominant topic.
    # Keep this guard in the standalone forecast tool as well as #27's shared gap helper.
    if gap:
        counts = Counter((r["participant"], r["topic"]) for r in rows)
        effective = len(rows) ** 2 / sum(n * n for n in counts.values())
        gap["effective_clusters"] = effective
        if effective + 1e-9 < 20:
            gap["gap_ci"] = None
    return dict(n=len(rows), topics=len({(r["participant"], r["topic"]) for r in rows}),
                baseline_comparison=baseline_comparison(rows),
                reported_success=statistics.fmean(int(y) for _, y in pairs),
                mean_forecast=statistics.fmean(p for p, _ in pairs), brier=brier, log_loss=loss,
                answer_changed=sum(r["answer_changed"] for r in rows),
                day_changed=sum(r.get("day_changed", False) for r in rows), gap=gap)


def audit(exports):
    all_rows, people, issues = [], {}, []
    for export in exports:
        rows, excluded, errors = observations(export)
        rows = with_past_baseline(rows)
        all_rows.extend(rows)
        issues.extend(errors)
        people[export.participant] = dict(metrics=metrics(rows), excluded=excluded)
    groups = defaultdict(list)
    for row in all_rows:
        # Parameter set ids are phone-local. Never pool two learners' distinct "set 7" as one model.
        # Two rating definitions measure different things (ratingDef): never pool them either.
        groups[(row["participant"], row["model"], row["set"], row["policy"], row["dueContext"], row["session"],
                row.get("rating_def", 1))].append(row)
    summary = dict(version=1, semantics="subjective post-study rating, original answer, prospective raw forecast",
                   participants=people, issues=issues, groups=[dict(participant=k[0], model=k[1], set=k[2], policy=k[3],
                                                                 due_context=k[4], session=k[5], rating_def=k[6], **metrics(v))
                                                            for k, v in sorted(groups.items())])
    return summary, sorted(all_rows, key=lambda r: (r["participant"], r["log"]))


def write(summary, rows, out):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as stream:
        json.dump(summary, stream, indent=2, ensure_ascii=False, allow_nan=False)
    with open(os.path.join(out, "forecasts.csv"), "w", newline="", encoding="utf-8-sig") as stream:
        fields = ["participant", "topic", "log", "at", "zone", "model", "set", "policy", "p", "elapsed", "stability",
                  "difficulty", "previousInterval", "memoryDue", "repairDue", "effectiveDue", "deferredUntil", "dueContext",
                  "session", "memory", "understanding", "current_memory", "current_understanding", "answer_changed", "original_success", "baseline_p", "baseline_history_n",
                  "rating_def", "day_changed"]
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
              "They are approximate and withheld below 20 effective topic clusters. Effective clusters measure weight concentration, not power or an independent sample count. They do not account for a new learner population, "
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
    report.extend(["", "## Past-only predictive baseline", "",
                   "The reference uses the last 50 retained valid original delayed-review ratings, with Laplace smoothing "
                   "and a 20-review warm-up. It resets for each learner/model/set/policy/session/due-context stream. "
                   "Its probability is computed before adding this review's outcome, in saved-id order even when the "
                   "clock moved backwards. These constants are fixed diagnostics, not a validated optimal predictor.", "",
                   "Raw FSRS and the reference are scored on exactly the same post-warm-up reviews. A positive log-loss "
                   "advantage favours raw FSRS. The approximate topic-cluster interval is conditional on the observed learner, "
                   "withheld below 20 effective topics and does not adjust for repeated checks or day-level dependence. "
                   "A low log loss can result from predicting mostly successful ratings without distinguishing weak topics. "
                   "It cannot prove that a review policy improves learning, saves study time or raises exam scores.", "",
                   "This baseline is reconstructed only from retained valid forecasts, not logged by the app. Purged/missing "
                   "history cannot be recovered; it is not presented as a historical deployed prediction. No model is replaced "
                   "and no LOOK/pass/fail threshold is inferred from the score.", "",
                   "| Learner | Paired reviews | Raw FSRS loss | Past-only loss | FSRS advantage | Approx. 95% topic interval | Effective topics |",
                   "|---|---:|---:|---:|---:|---|---:|"])
    for pid, info in summary["participants"].items():
        comparison = info["metrics"].get("baseline_comparison", {})
        if not comparison.get("n"):
            report.append(f"| {pid} | 0 | — | — | — | withheld | 0 |")
            continue
        ci = comparison["log_loss_advantage_ci"]
        interval = f"{ci[0]:+.5f} to {ci[1]:+.5f}" if ci else "withheld"
        report.append(f"| {pid} | {comparison['n']} | {comparison['raw_model_log_loss']:.5f} | "
                      f"{comparison['baseline_log_loss']:.5f} | {comparison['log_loss_advantage']:+.5f} | "
                      f"{interval} | {comparison['effective_topics']:.2f} |")
    report.extend(["", "Group results are in summary.json, separated by learner, memory model, weight set, policy, session and due context.",
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

"""Explicit denominators. Decode/detection/service failures are never dropped."""
from collections import Counter
from statistics import median

import numpy as np


def fraction(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "rate": numerator / denominator if denominator else None}


def _mode_summary(records, mode):
    live_known = [r for r in records if r["presentation_type"] == "live" and r["enrolled"]]
    live_unknown = [r for r in records if r["presentation_type"] == "live" and not r["enrolled"]]

    def recognition_rates(items):
        correct = sum(r[mode]["accepted"] and r[mode]["predicted_subject_id"] == r["subject_id"] for r in items)
        wrong = sum(r[mode]["accepted"] and r[mode]["predicted_subject_id"] != r["subject_id"] for r in items)
        return {"correct_identity": fraction(correct, len(items)),
                "not_correct_identity": fraction(len(items) - correct, len(items)),
                "wrong_identity": fraction(wrong, len(items))}

    result = {
        "known_live_all_inputs": recognition_rates(live_known),
        "unknown_live_false_accept_all_inputs": fraction(sum(r[mode]["accepted"] for r in live_unknown), len(live_unknown)),
        "reason_counts": dict(sorted(Counter(r[mode]["reason_code"] for r in records).items())),
    }
    if mode == "recognition_only":
        scorable = lambda r: r[mode]["reason_code"] in {"RECOGNIZED", "NOT_RECOGNIZED"}
        known_scored = [r for r in live_known if scorable(r)]
        unknown_scored = [r for r in live_unknown if scorable(r)]
        result["known_live_scored_only"] = recognition_rates(known_scored)
        result["unknown_live_false_accept_scored_only"] = fraction(sum(r[mode]["accepted"] for r in unknown_scored), len(unknown_scored))
        result["unscorable_inputs"] = sum(not scorable(r) for r in records)
    else:
        result["attack_false_accept_by_type"] = {
            kind: fraction(sum(r[mode]["accepted"] for r in records if r["presentation_type"] == kind),
                           sum(r["presentation_type"] == kind for r in records))
            for kind in ("print", "screen_photo", "replay")
        }
    return result


def summarize(records):
    pad = {}
    for kind in ("live", "print", "screen_photo", "replay"):
        items = [r for r in records if r["presentation_type"] == kind]
        scored = [r for r in items if r["liveness_only"]["reason_code"] in {"LIVE", "SPOOF_DETECTED"}]
        accepted = sum(r["liveness_only"]["accepted"] for r in items)
        pad[kind] = {
            "accepted_all_inputs": fraction(accepted, len(items)),
            "not_accepted_all_inputs": fraction(len(items) - accepted, len(items)),
            "accepted_scored_only": fraction(sum(r["liveness_only"]["accepted"] for r in scored), len(scored)),
            "rejected_scored_only": fraction(sum(not r["liveness_only"]["accepted"] for r in scored), len(scored)),
            "uncertain_count": sum(r["liveness_only"]["reason_code"] == "LIVENESS_UNCERTAIN" for r in items),
            "unscorable_count": len(items) - len(scored),
            "reason_counts": dict(sorted(Counter(r["liveness_only"]["reason_code"] for r in items).items())),
        }
    latency = {}
    for mode in ("recognition_only", "liveness_only", "combined"):
        times = [r[mode]["total_ms"] for r in records]
        latency[mode] = {"n": len(times), "p50_ms": median(times) if times else None,
                         "p95_ms": float(np.percentile(times, 95)) if times else None}
    return {
        "sample_count": len(records),
        "unit": "one image or explicitly selected video frame per attempt; no frame fusion",
        "unique_subjects": len({r["subject_id"] for r in records}),
        "unique_sessions": len({(r["subject_id"], r["session_id"]) for r in records}),
        "unique_clips": len({r["clip_id"] for r in records if r["clip_id"]}),
        "recognition_only": _mode_summary(records, "recognition_only"),
        "liveness_only": pad, "combined": _mode_summary(records, "combined"),
        "latency": latency,
    }


def report_metrics(records):
    result = summarize(records)
    result["by_condition"] = {c: summarize([r for r in records if r["condition"] == c])
                              for c in sorted({r["condition"] for r in records})}
    result["by_camera"] = {c: summarize([r for r in records if r["camera_id"] == c])
                           for c in sorted({r["camera_id"] for r in records})}
    return result

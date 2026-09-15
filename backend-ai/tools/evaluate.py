"""CLI for offline baseline evaluation; --help/--validate-only do not load models."""
import argparse
import json
from pathlib import Path
import sys
from time import perf_counter

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

# Windows PowerShell can expose CP1252 even though this CLI prints Vietnamese.
for console in (sys.stdout, sys.stderr):
    if hasattr(console, "reconfigure"):
        console.reconfigure(encoding="utf-8", errors="replace")

from evaluation.manifest import ManifestError, load_manifest


def write_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def parser():
    cli = argparse.ArgumentParser(description="Đánh giá AI offline: recognition, liveness, combined; không dùng MySQL/MQTT.")
    cli.add_argument("--manifest", type=Path, help="CSV manifest có nhãn")
    cli.add_argument("--dataset-root", type=Path, help="Thư mục gốc chứa ảnh/clip")
    cli.add_argument("--split", choices=("development", "calibration", "test"), default="development")
    cli.add_argument("--output", type=Path, help="Thư mục kết quả MỚI; không ghi đè")
    mode = cli.add_mutually_exclusive_group()
    mode.add_argument("--validate-only", action="store_true", help="Kiểm tra manifest/hash/chia tập; không tải model")
    mode.add_argument("--snapshot-only", action="store_true", help="Lưu môi trường/model; chưa đánh giá độ chính xác")
    mode.add_argument("--smoke-test", action="store_true", help="Model thật với ảnh xám tổng hợp; không đo accuracy")
    cli.add_argument("--model-root", type=Path, help="InsightFace root chứa models/buffalo_l; mặc định ~/.insightface")
    cli.add_argument("--liveness-dir", type=Path, help="Thư mục checkpoint MiniFASNet đã có trên máy")
    cli.add_argument("--allow-final-test", action="store_true", help="Xác nhận cấu hình đã chốt trước khi mở split test")
    return cli


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    if args.split == "test" and not args.allow_final_test and not args.validate_only:
        cli.error("Split test được giữ cho nghiệm thu. Dùng --allow-final-test khi cấu hình đã chốt.")
    special = args.snapshot_only or args.smoke_test
    if special and (args.manifest or args.dataset_root):
        cli.error("Snapshot/smoke-test không nhận manifest hoặc dữ liệu người thật.")
    if not special and (not args.manifest or not args.dataset_root):
        cli.error("Cần --manifest và --dataset-root, hoặc dùng --snapshot-only/--smoke-test.")
    try:
        samples = [] if special else load_manifest(args.manifest, args.dataset_root)
        if args.validate_only:
            issues = [{"sample_id": s.sample_id, "reason": s.media_error} for s in samples if s.media_error]
            print(json.dumps({"status": "valid" if not issues else "media_missing",
                              "rows": len(samples), "media_issues": issues}, ensure_ascii=False, indent=2))
            return 2 if issues else 0
        if not args.output:
            cli.error("Cần --output trỏ tới một thư mục mới.")
        if args.output.exists():
            raise ValueError("Thư mục output đã tồn tại; chọn tên run mới để giữ baseline cũ.")

        from evaluation.snapshot import baseline_snapshot
        from evaluation.runner import evaluate_samples
        from face_pipeline import FacePipeline
        # Empty data remains explicitly NOT evaluated; do not load models needlessly.
        face_app = checker = None
        startup_ms = 0.0
        has_data = any(s.split == "enrollment" for s in samples) and any(s.split == args.split for s in samples)
        if special or has_data:
            from model_runtime import load_models
            started = perf_counter()
            face_app, checker = load_models(args.model_root, args.liveness_dir)
            startup_ms = round((perf_counter() - started) * 1000, 2)
        snapshot = baseline_snapshot(face_app, checker, args.manifest, samples)
        snapshot["model_startup_ms"] = startup_ms
        snapshot["selected_split"] = args.split if not special else None
        snapshot["latency_policy"] = "No warm-up discarded. Combined first, diagnostics rerun separately; media decode counted in each mode, startup separate."
        pipeline = FacePipeline(face_app, checker)
        if args.smoke_test:
            import numpy as np
            response = pipeline.verify(np.full((240, 320, 3), 127, dtype=np.uint8), lambda: [])
            result = {"status": "smoke_test_only", "message": "Ảnh tổng hợp; chưa đánh giá độ chính xác.",
                      "smoke_reason": response["reasonCode"], "enrollment": [], "records": [], "metrics": None}
            exit_code = 0 if response["reasonCode"] == "NO_FACE" else 2
        elif args.snapshot_only:
            result = {"status": "not_evaluated", "message": "Đã lưu mức cơ sở môi trường; cần dữ liệu có nhãn.",
                      "enrollment": [], "records": [], "metrics": None}
            exit_code = 0 if all(snapshot["model_readiness"].values()) else 2
        else:
            result = evaluate_samples(pipeline, samples, args.dataset_root, args.split)
            exit_code = 0 if result["status"] in {"evaluated", "synthetic_smoke_test"} else 2
        # A run is immutable and created only after data validation/inference.
        args.output.mkdir(parents=True, exist_ok=False)
        write_json(args.output / "baseline_snapshot.json", snapshot)
        write_json(args.output / "enrollment_results.json", result.pop("enrollment"))
        records = result.pop("records")
        with (args.output / "results.jsonl").open("x", encoding="utf-8") as stream:
            for record in records:
                stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
        result["split"] = snapshot["selected_split"]
        result["accuracy_improvement_established"] = False
        result["notes"] = ["Các mode độc lập không được dùng để cấp quyền truy cập.",
                           "Không có mẫu ở một nhóm: rate=null, không phải 0% lỗi.",
                           "Ảnh/clip cùng buổi có thể tương quan; chưa tính khoảng tin cậy.",
                           "Latency offline không bao gồm HTTP/MySQL hoặc tổng thời gian ba mode."]
        write_json(args.output / "summary.json", result)
        with (args.output / "README.md").open("x", encoding="utf-8") as stream:
            stream.write("# Báo cáo AI offline\n\nTrạng thái: " + result["status"] + "\n\n"
                         + result["message"] + "\n\nXem summary.json cho số lỗi/mẫu số, tỷ lệ và p50/p95; "
                         "results.jsonl cho từng lượt; baseline_snapshot.json cho phiên bản/hash.\n")
        print(json.dumps({"status": result["status"], "output": str(args.output.resolve()),
                          "message": result["message"]}, ensure_ascii=False, indent=2))
        return exit_code
    except (ManifestError, OSError, ValueError) as exc:
        print("Không thể đánh giá: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

"""User-triggered webcam capture for a labeled offline AI dataset."""
import argparse
import csv
from datetime import datetime, timezone
from pathlib import Path
import re
import sys
import time
import uuid

import cv2

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))
from evaluation.manifest import REQUIRED

for console in (sys.stdout, sys.stderr):
    if hasattr(console, "reconfigure"):
        console.reconfigure(encoding="utf-8", errors="replace")

IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\Z")


def parser():
    cli = argparse.ArgumentParser(description="Chụp một số ảnh webcam đã khai báo trước; dùng được với OpenCV headless.")
    cli.add_argument("--dataset-root", type=Path, required=True)
    cli.add_argument("--manifest", type=Path, required=True)
    cli.add_argument("--subject-id", required=True, help="Mã nội bộ, ví dụ p01; không dùng họ tên")
    cli.add_argument("--session-id", required=True, help="Mỗi buổi chụp dùng mã mới")
    cli.add_argument("--split", choices=("enrollment", "development", "calibration", "test"), required=True)
    cli.add_argument("--presentation-type", choices=("live", "print", "screen_photo"), required=True)
    cli.add_argument("--camera-index", type=int, default=0)
    cli.add_argument("--camera-id", default="cam01")
    cli.add_argument("--condition", default="normal")
    cli.add_argument("--count", type=int, default=5, help="Số ảnh chụp, từ 1 đến 50")
    cli.add_argument("--warmup-seconds", type=float, default=3.0)
    cli.add_argument("--interval-seconds", type=float, default=1.0)
    cli.add_argument("--mirrored", action="store_true",
                     help="Ghi ảnh đã lật ngang; mặc định lưu đúng frame camera")
    return cli


def validate(args):
    for name in ("subject_id", "session_id", "camera_id"):
        if not IDENTIFIER.fullmatch(getattr(args, name)):
            raise ValueError(f"{name} chỉ dùng chữ ASCII, số, dấu chấm/gạch và dài tối đa 80")
    if args.split == "enrollment" and args.presentation_type != "live":
        raise ValueError("Enrollment chỉ chấp nhận presentation-type=live")
    if not args.condition.strip() or "\n" in args.condition or "\r" in args.condition:
        raise ValueError("condition không hợp lệ")
    if not 1 <= args.count <= 50 or not 0 <= args.warmup_seconds <= 30 or not 0 <= args.interval_seconds <= 30:
        raise ValueError("count phải 1..50; warmup/interval phải 0..30 giây")
    manifest = args.manifest.resolve()
    root = args.dataset_root.resolve()
    if not manifest.is_relative_to(root):
        raise ValueError("Manifest phải nằm trong dataset-root để dữ liệu được quản lý cùng nhau")
    return root, manifest


def load_existing(manifest):
    if not manifest.exists():
        return [], set()
    with manifest.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if set(reader.fieldnames or []) != REQUIRED:
            raise ValueError("Manifest hiện có không đúng schema của phase 2A")
        rows = list(reader)
    return rows, {row["sample_id"] for row in rows}


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        root, manifest = validate(args)
        existing, used_ids = load_existing(manifest)
        image_dir = root / "images"
        image_dir.mkdir(parents=True, exist_ok=True)
        capture = cv2.VideoCapture(args.camera_index)
        if not capture.isOpened():
            raise ValueError(f"Không mở được camera index {args.camera_index}")
        pending = []
        print(f"Camera khởi động. Bắt đầu chụp {args.count} ảnh sau {args.warmup_seconds:g} giây.")
        print("Chỉ thu khuôn mặt của người đã đồng ý tham gia. Nhấn Ctrl+C để hủy.")
        try:
            warmup_until = time.monotonic() + args.warmup_seconds
            while time.monotonic() < warmup_until:
                capture.read()
                time.sleep(0.05)
            for index in range(args.count):
                ok, frame = capture.read()
                if not ok:
                    raise ValueError("Không đọc được frame từ camera")
                sample_id = "s_" + uuid.uuid4().hex[:16]
                while sample_id in used_ids:
                    sample_id = "s_" + uuid.uuid4().hex[:16]
                used_ids.add(sample_id)
                filename = sample_id + ".jpg"
                target = image_dir / filename
                stored = cv2.flip(frame, 1) if args.mirrored else frame
                if not cv2.imwrite(str(target), stored, [cv2.IMWRITE_JPEG_QUALITY, 95]):
                    raise ValueError("Không ghi được ảnh")
                token = uuid.uuid4().hex[:16]
                pending.append({
                    "sample_id": sample_id, "relative_path": target.relative_to(root).as_posix(),
                    "subject_id": args.subject_id, "session_id": args.session_id,
                    "clip_id": "", "split": args.split,
                    "presentation_type": args.presentation_type, "camera_id": args.camera_id,
                    "condition": args.condition.strip(), "mirrored": str(args.mirrored).lower(),
                    "capture_source": "camera", "frame_index": "",
                    "source_id": "src_" + token, "attempt_id": "att_" + token,
                })
                print(f"Đã chụp {len(pending)} ảnh trong phiên này: {target.name}")
                if index + 1 < args.count:
                    time.sleep(args.interval_seconds)
        finally:
            capture.release()
        if pending:
            manifest.parent.mkdir(parents=True, exist_ok=True)
            with manifest.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=sorted(REQUIRED))
                writer.writeheader()
                writer.writerows(existing + pending)
        print(f"Kết thúc: thêm {len(pending)} ảnh. UTC {datetime.now(timezone.utc).isoformat()}")
        return 0
    except KeyboardInterrupt:
        print("Đã hủy phiên thu dữ liệu; ảnh của phiên chưa được thêm vào manifest.", file=sys.stderr)
        return 130
    except (OSError, ValueError) as exc:
        print("Không thể thu dữ liệu: " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

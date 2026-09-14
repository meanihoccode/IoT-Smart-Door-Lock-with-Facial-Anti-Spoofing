"""Manifest validation and leakage checks, without importing any model or DB driver."""
import csv
from dataclasses import dataclass, asdict
import hashlib
from pathlib import Path
import re

SPLITS = {"enrollment", "development", "calibration", "test"}
PRESENTATIONS = {"live", "print", "screen_photo", "replay"}
VIDEO_EXTENSIONS = {".mp4", ".avi", ".mov", ".mkv"}
REQUIRED = {"sample_id", "relative_path", "subject_id", "session_id", "clip_id",
            "split", "presentation_type", "camera_id", "condition", "mirrored",
            "capture_source", "frame_index", "source_id", "attempt_id"}
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\Z")


class ManifestError(ValueError):
    pass


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class Sample:
    sample_id: str
    relative_path: str
    subject_id: str
    session_id: str
    clip_id: str
    split: str
    presentation_type: str
    camera_id: str
    condition: str
    mirrored: str
    capture_source: str
    frame_index: int | None
    source_id: str
    attempt_id: str
    media_sha256: str | None = None
    media_error: str | None = None

    def metadata(self):
        return asdict(self)


def media_path(root, relative_path):
    relative = Path(relative_path)
    target = (root / relative).resolve()
    if relative.is_absolute() or not target.is_relative_to(root.resolve()):
        raise ManifestError("Media paths must stay inside the specified dataset root")
    return target


def load_manifest(path, dataset_root):
    root = Path(dataset_root).resolve()
    seen_ids, seen_attempts, hashes, sources, sessions, clips = set(), set(), {}, {}, {}, {}
    file_hash_cache = {}
    samples = []
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or not REQUIRED.issubset(reader.fieldnames):
            raise ManifestError("Missing CSV columns: " + ", ".join(sorted(REQUIRED - set(reader.fieldnames or []))))
        if len(reader.fieldnames) != len(set(reader.fieldnames)):
            raise ManifestError("Duplicate CSV column names")
        for row_number, raw in enumerate(reader, 2):
            if None in raw or any(raw.get(key) is None for key in REQUIRED):
                raise ManifestError(f"Malformed CSV row {row_number}")
            row = {key: raw[key].strip() for key in REQUIRED}
            for key in ("sample_id", "subject_id", "session_id", "camera_id", "source_id", "attempt_id"):
                if not IDENTIFIER.fullmatch(row[key]):
                    raise ManifestError(f"Row {row_number}: invalid {key}; use non-identifying ASCII codes")
            if row["clip_id"] and not IDENTIFIER.fullmatch(row["clip_id"]):
                raise ManifestError(f"Row {row_number}: invalid clip_id")
            if row["sample_id"] in seen_ids or row["attempt_id"] in seen_attempts:
                raise ManifestError("sample_id and attempt_id must be unique (one frame per attempt)")
            seen_ids.add(row["sample_id"])
            seen_attempts.add(row["attempt_id"])
            if row["split"] not in SPLITS or row["presentation_type"] not in PRESENTATIONS:
                raise ManifestError(f"Row {row_number}: unknown split or presentation_type")
            if row["mirrored"] not in {"true", "false"} or row["capture_source"] not in {"camera", "synthetic"}:
                raise ManifestError(f"Row {row_number}: mirrored=true/false, capture_source=camera/synthetic required")
            if not row["condition"] or not row["relative_path"]:
                raise ManifestError(f"Row {row_number}: condition and relative_path are required")
            target = media_path(root, row["relative_path"])
            extension = target.suffix.lower()
            frame_index = None
            if extension in VIDEO_EXTENSIONS:
                if not row["clip_id"] or not row["frame_index"].isdigit():
                    raise ManifestError("Video requires clip_id and a nonnegative explicit frame_index")
                frame_index = int(row["frame_index"])
            elif extension not in {".jpg", ".jpeg", ".png"} or row["frame_index"]:
                raise ManifestError("Use JPEG/PNG images or a supported video with explicit frame_index")
            if row["split"] == "enrollment" and row["presentation_type"] != "live":
                raise ManifestError("Enrollment must be labeled live")
            # Enforce session/source/clip disjointness across splits. source_id
            # connects transformed copies, which content hashes cannot detect.
            groups = [(sources, row["source_id"]),
                      (sessions, (row["subject_id"], row["session_id"]))]
            if row["clip_id"]:
                groups.append((clips, row["clip_id"]))
            for table, key in groups:
                if key in table and table[key] != row["split"]:
                    raise ManifestError(f"Split leakage in row {row_number}: source/session/clip crosses splits")
                table[key] = row["split"]
            if target not in file_hash_cache:
                try:
                    file_hash_cache[target] = (sha256_file(target), None)
                except OSError:
                    file_hash_cache[target] = (None, "MEDIA_UNREADABLE")
            digest, error = file_hash_cache[target]
            if digest:
                # Entire video must stay in one split; each selected frame must be unique.
                if digest in hashes and hashes[digest][0] != row["split"]:
                    raise ManifestError(f"Content-hash leakage in row {row_number}")
                split, frame_indices = hashes.setdefault(digest, (row["split"], set()))
                if frame_index in frame_indices:
                    raise ManifestError(f"Duplicate image/frame in row {row_number}")
                frame_indices.add(frame_index)
            row["frame_index"] = frame_index
            samples.append(Sample(**row, media_sha256=digest, media_error=error))
    gallery_subjects = [s.subject_id for s in samples if s.split == "enrollment"]
    if len(gallery_subjects) != len(set(gallery_subjects)):
        raise ManifestError("Phase 2A baseline requires exactly one enrollment image per subject")
    known = set(gallery_subjects)
    unknown_dev = {s.subject_id for s in samples if s.split in {"development", "calibration"} and s.subject_id not in known}
    unknown_test = {s.subject_id for s in samples if s.split == "test" and s.subject_id not in known}
    if unknown_dev & unknown_test:
        raise ManifestError("Unknown subjects in final test must be independent of development/calibration")
    return samples

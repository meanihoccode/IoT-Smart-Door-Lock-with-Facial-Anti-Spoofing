"""Reproducibility metadata; excludes raw media, embeddings, DB config and secrets."""
from datetime import datetime, timezone
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import platform
import subprocess

from baseline_config import BASELINE, CONFIG_PATH
from evaluation.manifest import sha256_file

BACKEND_DIR = Path(__file__).resolve().parents[1]
PACKAGES = ("fastapi", "uvicorn", "pydantic", "python-multipart", "opencv-python-headless",
            "numpy", "insightface", "onnxruntime", "torch", "torchvision", "Pillow")


def code_snapshot():
    paths = list(BACKEND_DIR.glob("*.py")) + list((BACKEND_DIR / "configs").glob("*.json"))
    paths.append(BACKEND_DIR / "requirements.txt")
    for directory in ("src", "evaluation", "tools", "tests"):
        paths.extend((BACKEND_DIR / directory).rglob("*.py"))
    hashes = {p.relative_to(BACKEND_DIR).as_posix(): sha256_file(p) for p in sorted(set(paths))}
    return {"files": hashes, "sha256": hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()}


def baseline_snapshot(face_app=None, liveness_checker=None, manifest=None, samples=()):
    versions = {}
    for package in PACKAGES:
        try:
            versions[package] = metadata.version(package)
        except metadata.PackageNotFoundError:
            versions[package] = None
    try:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=BACKEND_DIR,
                                           text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.SubprocessError):
        revision = None
    models = []
    if face_app is not None:
        for task, model in sorted(face_app.models.items()):
            file = Path(model.model_file)
            models.append({"task": task, "filename": file.name, "sha256": sha256_file(file),
                           "providers": model.session.get_providers()})
    if liveness_checker is not None and liveness_checker.is_ready:
        models.append({"task": "liveness", "filename": liveness_checker.model_path.name,
                       "sha256": sha256_file(liveness_checker.model_path),
                       "device": str(liveness_checker.device)})
    rows = [s.metadata() for s in samples]
    gallery_rows = [r for r in rows if r["split"] == "enrollment"]
    return {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "git_revision": revision, "source": code_snapshot(),
        "configuration": BASELINE, "configuration_sha256": sha256_file(CONFIG_PATH),
        "runtime": {"python": platform.python_version(), "platform": platform.platform(),
                    "machine": platform.machine(), "processor": platform.processor(),
                    "logical_cpu_count": os.cpu_count(), "packages": versions},
        "models": models,
        "model_readiness": {"face": face_app is not None,
                            "liveness": liveness_checker is not None and liveness_checker.is_ready},
        "manifest_sha256": sha256_file(manifest) if manifest else None,
        "gallery_source_sha256": hashlib.sha256(json.dumps(gallery_rows, sort_keys=True).encode()).hexdigest(),
        "gallery_strategy": BASELINE["gallery_strategy"], "gallery_subject_count": len(gallery_rows),
        "manifest_rows": rows,
    }

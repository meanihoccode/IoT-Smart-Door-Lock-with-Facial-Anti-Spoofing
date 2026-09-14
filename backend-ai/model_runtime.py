"""Explicit model loading for both FastAPI lifespan and offline commands."""
import logging
from pathlib import Path

from baseline_config import BASELINE

logger = logging.getLogger("face-auth")


def load_models(model_root=None, liveness_dir=None):
    from anti_spoofing import AntiSpoofingModel

    root = Path(model_root).resolve() if model_root else Path.home() / ".insightface"
    face_app = None
    try:
        # FaceAnalysis may download on a missing directory. Offline evaluation
        # must use existing assets and must not silently change its baseline.
        directory = root / "models" / BASELINE["face_model"]
        if not directory.is_dir() or not list(directory.glob("*.onnx")):
            raise FileNotFoundError("Local InsightFace model assets are missing")
        import insightface
        face_app = insightface.app.FaceAnalysis(name=BASELINE["face_model"], root=str(root))
        face_app.prepare(ctx_id=BASELINE["face_context_id"],
                         det_size=tuple(BASELINE["detection_size"]))
        if "recognition" not in face_app.models or "detection" not in face_app.models:
            raise RuntimeError("Both face detection and recognition models are required")
    except Exception:
        logger.exception("event=model_load_failed model=%s", BASELINE["face_model"])
        face_app = None
    checker = AntiSpoofingModel(liveness_dir or "resources/anti_spoof_models")
    return face_app, checker

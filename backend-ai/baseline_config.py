"""One versioned baseline shared by API, model loading and offline evaluation."""
import json
from pathlib import Path

CONFIG_PATH = Path(__file__).resolve().parent / "configs" / "baseline.json"
BASELINE = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))

# These describe algorithms, not tuning knobs. A new implementation must version them.
if (BASELINE["liveness_crop"] != "square_max_side_clipped_v1"
        or BASELINE["liveness_decision"] != "argmax_class_1"
        or BASELINE["gallery_strategy"] != "one_image_per_subject"
        or BASELINE["quality_gate"] != "disabled"
        or BASELINE["verification_face_selection"] != "largest_visible_area_then_center_v1"
        or BASELINE["enrollment_face_selection"] != "exactly_one"):
    raise ValueError("Baseline configuration does not describe the implemented pipeline")

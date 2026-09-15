import cv2
import numpy as np
from pathlib import Path
import torch
import torch.nn.functional as F
import warnings

# Ignore some torch warnings
warnings.filterwarnings('ignore')

from src.model_lib.MiniFASNet import MiniFASNetV2
from baseline_config import BASELINE


class LivenessModelUnavailableError(RuntimeError):
    """Raised when liveness inference cannot be performed safely."""


class AntiSpoofingModel:
    def __init__(self, model_dir="resources/anti_spoof_models"):
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model_loaded = False
        self.load_error = None
        self.model_name = BASELINE["liveness_model"]

        model_dir_path = Path(model_dir)
        if not model_dir_path.is_absolute():
            model_dir_path = Path(__file__).resolve().parent / model_dir_path
        self.model_path = model_dir_path / self.model_name

        if self.model_path.exists():
            try:
                self.model = MiniFASNetV2(conv6_kernel=(5, 5)).to(self.device)
                state_dict = torch.load(self.model_path, map_location=self.device, weights_only=True)
                new_state_dict = {k.replace('module.', ''): v for k, v in state_dict.items()}
                self.model.load_state_dict(new_state_dict)
                self.model.eval()
                self.model_loaded = True
                print(f"Anti-Spoofing Model loaded successfully from {self.model_path} onto {self.device}")
            except Exception as e:
                self.load_error = str(e)
                print(f"Failed to load Anti-Spoofing Model: {e}")
        else:
            self.load_error = f"Model file not found: {self.model_path}"
            print(f"Warning: Anti-spoofing model not found at {self.model_path}.")

    @property
    def is_ready(self):
        return self.model_loaded

    def predict(self, img, face_bbox):
        """
        img: OpenCV image (BGR)
        face_bbox: [x1, y1, x2, y2]
        Returns: is_real (boolean), confidence (float)
        """
        if not self.model_loaded:
            raise LivenessModelUnavailableError(
                self.load_error or "Anti-spoofing model is not loaded"
            )
            
        try:
            x1, y1, x2, y2 = [int(v) for v in face_bbox]
            
            # Khung chuẩn để đưa vào model MiniFASNet là cần margin
            w = x2 - x1
            h = y2 - y1
            # Theo thuật toán chuẩn của Silent-Face-Anti-Spoofing, crop khuôn mặt có padding (scale)
            scale = BASELINE["liveness_scale"]
            cx = x1 + w // 2
            cy = y1 + h // 2
            
            side = int(max(w, h) * scale / 2)
            
            x1_new = max(0, cx - side)
            y1_new = max(0, cy - side)
            x2_new = min(img.shape[1], cx + side)
            y2_new = min(img.shape[0], cy + side)
            
            cropped_face = img[y1_new:y2_new, x1_new:x2_new]
            
            # Resize về 80x80 cho MiniFASNetV2
            resized_face = cv2.resize(cropped_face, tuple(BASELINE["liveness_input_size"]))
            
            # Preprocess tensor (HWC to BCHW)
            tensor_img = torch.from_numpy(resized_face).permute(2, 0, 1).unsqueeze(0).float().to(self.device)
            
            with torch.no_grad():
                result = self.model.forward(tensor_img)
                score = F.softmax(result, dim=1).cpu().numpy()
            
            # Mảng output: 1 là real, 0 và 2 là spoof (print/replay)
            label = np.argmax(score)
            # Return the score of the real class consistently. Stage 2 will
            # calibrate a dedicated acceptance threshold for this score.
            live_score = score[0][1]

            return (label == 1), float(live_score)
            
        except Exception as e:
            raise LivenessModelUnavailableError(
                f"Liveness inference failed: {e}"
            ) from e

# Instantiate explicitly at API startup or CLI execution, never at import time.

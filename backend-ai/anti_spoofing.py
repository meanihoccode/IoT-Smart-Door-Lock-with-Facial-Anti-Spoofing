import cv2
import numpy as np
import os
import torch
import torch.nn.functional as F
import warnings

# Ignore some torch warnings
warnings.filterwarnings('ignore')

from src.model_lib.MiniFASNet import MiniFASNetV2

class AntiSpoofingModel:
    def __init__(self, model_dir="resources/anti_spoof_models"):
        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model_loaded = False
        
        model_path = os.path.join(model_dir, "2.7_80x80_MiniFASNetV2.pth")
        if os.path.exists(model_path):
            try:
                self.model = MiniFASNetV2(conv6_kernel=(5, 5)).to(self.device)
                state_dict = torch.load(model_path, map_location=self.device, weights_only=True)
                new_state_dict = {k.replace('module.', ''): v for k, v in state_dict.items()}
                self.model.load_state_dict(new_state_dict)
                self.model.eval()
                self.model_loaded = True
                print(f"Anti-Spoofing Model loaded successfully from {model_path} onto {self.device}")
            except Exception as e:
                print(f"Failed to load Anti-Spoofing Model: {e}")
        else:
            print(f"Warning: Anti-spoofing model not found at {model_path}.")

    def predict(self, img, face_bbox):
        """
        img: OpenCV image (BGR)
        face_bbox: [x1, y1, x2, y2]
        Returns: is_real (boolean), confidence (float)
        """
        if not self.model_loaded:
            print("WARNING: Using mock Liveness Detection because model is not loaded!")
            return True, 0.95
            
        try:
            x1, y1, x2, y2 = [int(v) for v in face_bbox]
            
            # Khung chuẩn để đưa vào model MiniFASNet là cần margin
            w = x2 - x1
            h = y2 - y1
            # Theo thuật toán chuẩn của Silent-Face-Anti-Spoofing, crop khuôn mặt có padding (scale)
            scale = 2.7
            cx = x1 + w // 2
            cy = y1 + h // 2
            
            side = int(max(w, h) * scale / 2)
            
            x1_new = max(0, cx - side)
            y1_new = max(0, cy - side)
            x2_new = min(img.shape[1], cx + side)
            y2_new = min(img.shape[0], cy + side)
            
            cropped_face = img[y1_new:y2_new, x1_new:x2_new]
            
            # Resize về 80x80 cho MiniFASNetV2
            resized_face = cv2.resize(cropped_face, (80, 80))
            
            # Preprocess tensor (HWC to BCHW)
            tensor_img = torch.from_numpy(resized_face).permute(2, 0, 1).unsqueeze(0).float().to(self.device)
            
            with torch.no_grad():
                result = self.model.forward(tensor_img)
                score = F.softmax(result, dim=1).cpu().numpy()
            
            # Mảng output: 1 là real, 0 và 2 là spoof (print/replay)
            label = np.argmax(score)
            confidence = score[0][label]
            
            return (label == 1), float(confidence)
            
        except Exception as e:
            print(f"Lỗi khi chạy mô hình liveness: {e}")
            return False, 0.0

anti_spoof_checker = AntiSpoofingModel()

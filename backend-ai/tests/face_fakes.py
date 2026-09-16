"""Model giả để kiểm tra luồng chọn mặt mà không cần camera hoặc tải model."""
from unittest.mock import Mock

import numpy as np

from face_pipeline import FACE_EMBEDDING_SIZE


class FakeFace:
    def __init__(self, value=1.0, bbox=(20, 20, 100, 100)):
        self.bbox = np.array(bbox, dtype=np.float32)
        self.kps = np.zeros((5, 2), dtype=np.float32)
        self.embedding = np.full(FACE_EMBEDDING_SIZE, value, dtype=np.float32)


class FakeFaceApp:
    """Giả lập riêng detector và ArcFace để đếm số lần mỗi model được gọi."""

    def __init__(self, faces):
        self.faces = faces
        self.det_model = Mock()
        boxes = np.array([list(face.bbox) + [0.99] for face in faces], dtype=np.float32)
        landmarks = np.array([face.kps for face in faces], dtype=np.float32)
        self.det_model.detect.return_value = (boxes.reshape(-1, 5), landmarks)
        self.models = {"recognition": Mock()}
        self.models["recognition"].get.side_effect = self.get_embedding
        self.get = Mock(side_effect=AssertionError("Không được tính đặc trưng cho mọi mặt"))

    def get_embedding(self, _image, selected_face):
        """Trả đặc trưng của mặt có box đã chọn; bắt lỗi nếu truyền nhầm box."""
        for face in self.faces:
            if np.array_equal(face.bbox, selected_face.bbox):
                selected_face.embedding = face.embedding
                return face.embedding
        raise AssertionError("Không tìm thấy mặt đã chọn trong dữ liệu test")

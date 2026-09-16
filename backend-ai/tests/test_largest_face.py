"""Kiểm tra cách chọn mặt ở các vị trí/kích thước khác nhau trong ảnh."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from face_fakes import FakeFace, FakeFaceApp
from face_pipeline import FacePipeline, PipelineError


class LargestFaceTests(unittest.TestCase):
    def setUp(self):
        self.image = np.zeros((120, 120, 3), dtype=np.uint8)

    def assert_selected(self, faces, expected):
        """Kiểm tra box, landmark và số mặt; bước chọn chưa được gọi ArcFace."""
        app = FakeFaceApp(faces)
        selected, count = FacePipeline(app, None).detect_largest(self.image)
        np.testing.assert_array_equal(expected.bbox, selected.bbox)
        np.testing.assert_array_equal(expected.kps, selected.kps)
        self.assertEqual(len(faces), count)
        app.det_model.detect.assert_called_once()
        app.models["recognition"].get.assert_not_called()

    def test_largest_area_wins_regardless_of_detection_order(self):
        wide_face = FakeFace(bbox=(0, 0, 100, 10))  # Rộng hơn nhưng diện tích chỉ 1000.
        large_face = FakeFace(bbox=(10, 10, 60, 60))  # Diện tích 2500.
        large_face.kps = np.full((5, 2), 30, dtype=np.float32)
        for faces in ([wide_face, large_face], [large_face, wide_face]):
            with self.subTest(first_box=faces[0].bbox):
                self.assert_selected(faces, large_face)

    def test_equal_area_prefers_face_nearest_image_center(self):
        edge_face = FakeFace(bbox=(0, 0, 20, 20))
        center_face = FakeFace(bbox=(50, 50, 70, 70))
        for faces in ([edge_face, center_face], [center_face, edge_face]):
            self.assert_selected(faces, center_face)

    def test_exact_tie_keeps_first_detected_face(self):
        left = FakeFace(bbox=(20, 50, 40, 70))
        right = FakeFace(bbox=(80, 50, 100, 70))
        self.assert_selected([left, right], left)
        self.assert_selected([right, left], right)

    def test_area_counts_only_visible_part_of_out_of_bounds_box(self):
        # Box vượt mỗi biên trông rất lớn nếu không giới hạn tọa độ.
        for bbox in ((-200, 0, 10, 120), (110, 0, 320, 120),
                     (0, -200, 120, 10), (0, 110, 120, 320)):
            with self.subTest(bbox=bbox):
                outside = FakeFace(bbox=bbox)
                inside = FakeFace(bbox=(30, 30, 90, 90))
                self.assert_selected([outside, inside], inside)

    def test_selected_face_preserves_original_box_and_landmarks(self):
        clipped_face = FakeFace(bbox=(-10, -10, 100, 100))
        self.assert_selected([clipped_face, FakeFace(bbox=(40, 40, 60, 60))], clipped_face)

    def test_invalid_box_rejects_attempt_without_trying_another_face(self):
        for bbox in ((0, 0, 0, 10), (20, 20, 10, 10), (130, 0, 140, 10),
                     (float("nan"), 0, 100, 100), (0, 0, float("inf"), 100)):
            with self.subTest(bbox=bbox):
                app = FakeFaceApp([FakeFace(bbox=bbox), FakeFace()])
                checker = Mock(is_ready=True)
                loader = Mock()
                result = FacePipeline(app, checker).verify(self.image, loader)
                self.assertEqual("INVALID_AI_RESPONSE", result["reasonCode"])
                self.assertEqual(2, result["faceCount"])
                app.models["recognition"].get.assert_not_called()
                checker.predict.assert_not_called()
                loader.assert_not_called()

    def test_missing_face_is_reported_before_embedding(self):
        app = FakeFaceApp([])
        with self.assertRaises(PipelineError) as raised:
            FacePipeline(app, None).detect_largest(self.image)
        self.assertEqual("NO_FACE", raised.exception.reason_code)
        app.models["recognition"].get.assert_not_called()


if __name__ == "__main__":
    unittest.main()

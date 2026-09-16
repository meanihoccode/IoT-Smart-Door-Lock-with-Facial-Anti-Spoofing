import asyncio
from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import cv2
import numpy as np
from fastapi import Request, UploadFile


BACKEND_AI_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_AI_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_AI_DIR))

import main
from anti_spoofing import AntiSpoofingModel, LivenessModelUnavailableError
from face_fakes import FakeFace, FakeFaceApp


class FakeLiveness:
    model_loaded = True

    @property
    def is_ready(self):
        return self.model_loaded

    def predict(self, _image, _bbox):
        return True, 0.9


class StageOneFaceAuthTests(unittest.TestCase):
    def setUp(self):
        # Runtime now loads only in FastAPI lifespan. Unit tests need no assets.
        self.checker_patch = patch.object(main, "anti_spoof_checker", FakeLiveness())
        self.checker_patch.start()
        self.addCleanup(self.checker_patch.stop)

    def make_upload(self):
        image = np.full((120, 120, 3), 127, dtype=np.uint8)
        encoded, buffer = cv2.imencode(".jpg", image)
        self.assertTrue(encoded)
        return UploadFile(filename="face.jpg", file=BytesIO(buffer.tobytes()))

    def request(self):
        return Request({"type": "http", "headers": []})

    def response_body(self, response):
        return json.loads(response.body.decode("utf-8"))

    def verify(self):
        return asyncio.run(main.verify_face(self.request(), self.make_upload()))

    def test_missing_liveness_model_fails_closed(self):
        with tempfile.TemporaryDirectory() as empty_model_dir:
            checker = AntiSpoofingModel(empty_model_dir)

            self.assertFalse(checker.is_ready)
            with self.assertRaises(LivenessModelUnavailableError):
                checker.predict(np.zeros((80, 80, 3), dtype=np.uint8), [1, 1, 20, 20])

    def test_unavailable_face_model_has_explicit_reason(self):
        with patch.object(main, "face_app", None):
            result = self.response_body(self.verify())

        self.assertEqual("error", result["status"])
        self.assertEqual("MODEL_UNAVAILABLE", result["reasonCode"])
        self.assertIn("requestId", result)

    def test_multiple_faces_recognizes_only_largest_face(self):
        small_face = FakeFace(-1.0, bbox=(40, 40, 60, 60))
        large_face = FakeFace(1.0)
        app = FakeFaceApp([small_face, large_face])
        known_faces = [
            {"id": 3, "username": "small", "embedding": small_face.embedding},
            {"id": 7, "username": "large", "embedding": large_face.embedding},
        ]
        with (
            patch.object(main, "face_app", app),
            patch.object(main.anti_spoof_checker, "predict", return_value=(True, 0.9)) as predict,
            patch.object(main, "load_known_faces", return_value=known_faces),
        ):
            result = self.response_body(self.verify())

        self.assertEqual("FACE_VERIFIED", result["reasonCode"])
        self.assertEqual(2, result["faceCount"])
        self.assertEqual(7, result["recognition"]["userId"])
        app.det_model.detect.assert_called_once()
        app.get.assert_not_called()
        app.models["recognition"].get.assert_called_once()
        np.testing.assert_array_equal(
            large_face.bbox, app.models["recognition"].get.call_args.args[1].bbox)
        predict.assert_called_once()
        np.testing.assert_array_equal(large_face.bbox, predict.call_args.args[1])

    def test_largest_spoof_does_not_fall_back_to_smaller_face(self):
        small_face = FakeFace(-1.0, bbox=(40, 40, 60, 60))
        large_face = FakeFace(1.0)
        app = FakeFaceApp([small_face, large_face])
        with (
            patch.object(main, "face_app", app),
            patch.object(main.anti_spoof_checker, "predict", return_value=(False, 0.02)) as predict,
            patch.object(main, "load_known_faces") as load_faces,
        ):
            result = self.response_body(self.verify())

        self.assertEqual("SPOOF_DETECTED", result["reasonCode"])
        self.assertEqual(2, result["faceCount"])
        self.assertEqual("NOT_RUN", result["recognition"]["status"])
        predict.assert_called_once()
        np.testing.assert_array_equal(large_face.bbox, predict.call_args.args[1])
        app.models["recognition"].get.assert_called_once()
        load_faces.assert_not_called()

    def test_unknown_largest_face_does_not_fall_back_to_known_smaller_face(self):
        small_face = FakeFace(-1.0, bbox=(40, 40, 60, 60))
        app = FakeFaceApp([small_face, FakeFace(1.0)])
        with (
            patch.object(main, "face_app", app),
            patch.object(main, "load_known_faces", return_value=[
                {"id": 3, "username": "small", "embedding": small_face.embedding},
            ]),
        ):
            result = self.response_body(self.verify())

        self.assertEqual("NOT_RECOGNIZED", result["reasonCode"])
        self.assertEqual(2, result["faceCount"])
        self.assertIsNone(result["recognition"]["userId"])
        app.models["recognition"].get.assert_called_once()

    def test_enrollment_multiple_faces_are_rejected_before_embedding(self):
        app = FakeFaceApp([FakeFace(), FakeFace(bbox=(40, 40, 60, 60))])
        with (
            patch.object(main, "face_app", app),
            patch.object(main.anti_spoof_checker, "predict") as predict,
        ):
            result = self.response_body(asyncio.run(
                main.extract_embedding(self.request(), self.make_upload())))

        self.assertEqual("MULTIPLE_FACES", result["reasonCode"])
        app.models["recognition"].get.assert_not_called()
        predict.assert_not_called()

    def test_no_face_has_explicit_reason(self):
        with (
            patch.object(main, "face_app", FakeFaceApp([])),
            patch.object(main.anti_spoof_checker, "model_loaded", True),
            patch.object(main.anti_spoof_checker, "predict") as predict,
        ):
            result = self.response_body(self.verify())

        self.assertEqual("NO_FACE", result["reasonCode"])
        predict.assert_not_called()

    def test_spoof_is_rejected_before_database_access(self):
        with (
            patch.object(main, "face_app", FakeFaceApp([FakeFace()])),
            patch.object(main.anti_spoof_checker, "model_loaded", True),
            patch.object(main.anti_spoof_checker, "predict", return_value=(False, 0.02)),
            patch.object(main, "load_known_faces") as load_faces,
        ):
            result = self.response_body(self.verify())

        self.assertEqual("SPOOF_DETECTED", result["reasonCode"])
        self.assertFalse(result["liveness"]["isReal"])
        load_faces.assert_not_called()

    def test_empty_gallery_is_different_from_database_failure(self):
        with (
            patch.object(main, "face_app", FakeFaceApp([FakeFace()])),
            patch.object(main.anti_spoof_checker, "model_loaded", True),
            patch.object(main.anti_spoof_checker, "predict", return_value=(True, 0.9)),
            patch.object(main, "load_known_faces", return_value=[]),
        ):
            no_enrollment = self.response_body(self.verify())

        with (
            patch.object(main, "face_app", FakeFaceApp([FakeFace()])),
            patch.object(main.anti_spoof_checker, "model_loaded", True),
            patch.object(main.anti_spoof_checker, "predict", return_value=(True, 0.9)),
            patch.object(
                main,
                "load_known_faces",
                side_effect=main.DatabaseUnavailableError("test database failure"),
            ),
        ):
            database_failure = self.response_body(self.verify())

        self.assertEqual("NO_ENROLLMENT", no_enrollment["reasonCode"])
        self.assertEqual("DB_UNAVAILABLE", database_failure["reasonCode"])

    def test_success_contains_complete_contract(self):
        known_embedding = np.full(main.FACE_EMBEDDING_SIZE, 1.0, dtype=np.float32)
        known_faces = [{
            "id": 7,
            "username": "test",
            "full_name": "Test User",
            "embedding": known_embedding,
        }]
        with (
            patch.object(main, "face_app", FakeFaceApp([FakeFace()])),
            patch.object(main.anti_spoof_checker, "model_loaded", True),
            patch.object(main.anti_spoof_checker, "predict", return_value=(True, 0.9)),
            patch.object(main, "load_known_faces", return_value=known_faces),
        ):
            result = self.response_body(self.verify())

        self.assertEqual("success", result["status"])
        self.assertEqual("FACE_VERIFIED", result["reasonCode"])
        self.assertTrue(result["liveness"]["isReal"])
        self.assertTrue(result["recognition"]["recognized"])
        self.assertEqual(7, result["recognition"]["userId"])
        self.assertIn("total", result["timingsMs"])

    def test_unmatched_face_reports_similarity_without_user_id(self):
        probe = FakeFace(1.0)
        different = np.concatenate((
            np.ones(main.FACE_EMBEDDING_SIZE // 2, dtype=np.float32),
            -np.ones(main.FACE_EMBEDDING_SIZE // 2, dtype=np.float32),
        ))
        known_faces = [{
            "id": 7,
            "username": "test",
            "full_name": "Test User",
            "embedding": different,
        }]
        with (
            patch.object(main, "face_app", FakeFaceApp([probe])),
            patch.object(main.anti_spoof_checker, "model_loaded", True),
            patch.object(main.anti_spoof_checker, "predict", return_value=(True, 0.9)),
            patch.object(main, "load_known_faces", return_value=known_faces),
        ):
            result = self.response_body(self.verify())

        self.assertEqual("NOT_RECOGNIZED", result["reasonCode"])
        self.assertFalse(result["recognition"]["recognized"])
        self.assertIsNone(result["recognition"]["userId"])


if __name__ == "__main__":
    unittest.main()

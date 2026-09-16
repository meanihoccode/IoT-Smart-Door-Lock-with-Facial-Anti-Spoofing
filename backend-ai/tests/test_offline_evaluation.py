import asyncio
import csv
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import cv2
import numpy as np
from fastapi import Request, UploadFile

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from evaluation.manifest import ManifestError, REQUIRED, Sample, load_manifest
from evaluation.metrics import report_metrics
from evaluation.runner import diagnostic, empty_mode, evaluate_samples, read_sample
from face_pipeline import FacePipeline, PipelineError, decode_image, validate_embedding
from face_fakes import FakeFace, FakeFaceApp


def gallery():
    return [{"id": 1, "username": "p01", "embedding": np.ones(512, dtype=np.float32)}]


def fake_pipeline(pad_real=True, faces=None):
    app = FakeFaceApp([FakeFace()] if faces is None else faces)
    checker = Mock()
    checker.is_ready = True
    checker.predict.return_value = (pad_real, 0.9 if pad_real else 0.02)
    return FacePipeline(app, checker)


class PipelineTests(unittest.TestCase):
    def test_recognition_diagnostic_survives_spoof_but_production_never_bypasses(self):
        pipeline = fake_pipeline(False)
        image = np.full((120, 120, 3), 127, np.uint8)
        loader = Mock(return_value=gallery())
        combined = pipeline.verify(image, loader)
        recognition = diagnostic(pipeline, image, gallery(), {1: "p01"}, "recognition_only")
        self.assertEqual("SPOOF_DETECTED", combined["reasonCode"])
        self.assertFalse(combined["recognition"]["recognized"])
        loader.assert_not_called()
        self.assertTrue(recognition["accepted"])
        self.assertEqual("p01", recognition["predicted_subject_id"])

    def test_api_and_offline_combined_return_same_contract(self):
        import main
        pipeline = fake_pipeline()
        ok, buffer = cv2.imencode(".jpg", np.full((120, 120, 3), 127, np.uint8))
        self.assertTrue(ok)
        image = decode_image(buffer.tobytes(), "face.jpg")
        expected = pipeline.verify(image, lambda: gallery(), "same-request")
        request = Request({"type": "http", "headers": [(b"x-request-id", b"same-request")]})
        with (patch.object(main, "face_app", pipeline.face_app),
              patch.object(main, "anti_spoof_checker", pipeline.liveness_checker),
              patch.object(main, "load_known_faces", return_value=gallery())):
            response = asyncio.run(main.verify_face(request, UploadFile(filename="face.jpg", file=BytesIO(buffer.tobytes()))))
        actual = json.loads(response.body)
        actual.pop("timingsMs")
        expected.pop("timingsMs")
        self.assertEqual(expected, actual)

    def test_enrollment_api_and_offline_use_same_embedding(self):
        import main
        pipeline = fake_pipeline()
        _, buffer = cv2.imencode(".jpg", np.full((120, 120, 3), 100, np.uint8))
        with patch.object(main, "face_app", pipeline.face_app):
            result = asyncio.run(main.extract_embedding(Request({"type": "http", "headers": []}),
                                 UploadFile(filename="face.jpg", file=BytesIO(buffer.tobytes()))))
        body = json.loads(result.body)
        self.assertEqual("EMBEDDING_EXTRACTED", body["reasonCode"])
        self.assertEqual(pipeline.extract(decode_image(buffer.tobytes(), "face.jpg")).tolist(), body["embedding"])

    def test_bad_probe_embedding_does_not_prevent_independent_pad_measurement(self):
        face = FakeFace()
        face.embedding = np.zeros(512, np.float32)
        pipeline = fake_pipeline(faces=[face])
        image = np.zeros((120, 120, 3), np.uint8)
        self.assertEqual("INVALID_AI_RESPONSE", pipeline.verify(image, lambda: gallery())["reasonCode"])
        self.assertEqual("LIVE", diagnostic(pipeline, image, gallery(), {1: "p01"}, "liveness_only")["reason_code"])

    def test_diagnostics_select_same_largest_face_as_production(self):
        small_face = FakeFace(-1.0, bbox=(40, 40, 60, 60))
        large_face = FakeFace(1.0)
        pipeline = fake_pipeline(faces=[small_face, large_face])
        image = np.zeros((120, 120, 3), np.uint8)
        combined = pipeline.verify(image, gallery)
        recognition = diagnostic(pipeline, image, gallery(), {1: "p01"}, "recognition_only")

        self.assertEqual("FACE_VERIFIED", combined["reasonCode"])
        self.assertEqual(2, combined["faceCount"])
        self.assertEqual("p01", recognition["predicted_subject_id"])
        self.assertEqual(2, recognition["face_count"])

        # Chẩn đoán liveness chỉ cần box, không phải chạy ArcFace.
        pipeline.face_app.models["recognition"].get.reset_mock()
        pipeline.liveness_checker.predict.reset_mock()
        liveness = diagnostic(pipeline, image, gallery(), {1: "p01"}, "liveness_only")
        self.assertEqual("LIVE", liveness["reason_code"])
        self.assertEqual(2, liveness["face_count"])
        pipeline.face_app.models["recognition"].get.assert_not_called()
        pipeline.liveness_checker.predict.assert_called_once()
        np.testing.assert_array_equal(
            large_face.bbox, pipeline.liveness_checker.predict.call_args.args[1])

    def test_unavailable_pad_allows_only_offline_recognition_measurement(self):
        pipeline = fake_pipeline()
        pipeline.liveness_checker.is_ready = False
        image = np.zeros((120, 120, 3), np.uint8)
        self.assertEqual("MODEL_UNAVAILABLE", pipeline.verify(image, lambda: gallery())["reasonCode"])
        self.assertTrue(diagnostic(pipeline, image, gallery(), {1: "p01"}, "recognition_only")["accepted"])

    def test_invalid_model_scores_fail_closed(self):
        image = np.zeros((120, 120, 3), np.uint8)
        for score in (float("nan"), float("inf"), -0.01, 1.01):
            with self.subTest(score=score):
                pipeline = fake_pipeline()
                pipeline.liveness_checker.predict.return_value = (True, score)
                loader = Mock(return_value=gallery())
                self.assertEqual("MODEL_UNAVAILABLE", pipeline.verify(image, loader)["reasonCode"])
                loader.assert_not_called()

    def test_inference_exception_and_corrupt_template_fail_closed(self):
        pipeline = fake_pipeline()
        image = np.zeros((120, 120, 3), np.uint8)
        pipeline.liveness_checker.predict.side_effect = RuntimeError("inference unavailable")
        self.assertEqual("MODEL_UNAVAILABLE", pipeline.verify(image, lambda: gallery())["reasonCode"])
        pipeline.liveness_checker.predict.side_effect = None
        corrupted = gallery()
        corrupted[0]["embedding"] = [0.0] * 512
        self.assertEqual("INVALID_TEMPLATE", pipeline.verify(image, lambda: corrupted)["reasonCode"])

    def test_bad_embeddings_and_images_rejected(self):
        for embedding in ([1] * 511, [0] * 512, [float("nan")] * 512, [float("inf")] * 512):
            with self.subTest(embedding_length=len(embedding)), self.assertRaises(PipelineError):
                validate_embedding(embedding)
        with self.assertRaises(PipelineError):
            decode_image(b"not an image", "probe.jpg")

    def test_importing_api_does_not_initialize_models(self):
        command = [sys.executable, "-B", "-c",
                   "import sys; import main; assert main.face_app is None; assert 'insightface' not in sys.modules; assert 'torch' not in sys.modules"]
        result = subprocess.run(command, cwd=BACKEND_DIR, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.root / "manifest.csv"

    def row(self, index=1, **changes):
        row = dict(sample_id=f"sample{index}", relative_path=f"image{index}.png", subject_id="p01",
                   session_id=f"session{index}", clip_id="", split="development", presentation_type="live",
                   camera_id="cam01", condition="normal", mirrored="false", capture_source="synthetic",
                   frame_index="", source_id=f"source{index}", attempt_id=f"attempt{index}")
        row.update(changes)
        return row

    def write(self, rows):
        with self.manifest.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=sorted(REQUIRED))
            writer.writeheader()
            writer.writerows(rows)

    def test_missing_media_is_retained_for_failure_accounting(self):
        self.write([self.row()])
        samples = load_manifest(self.manifest, self.root)
        self.assertEqual(1, len(samples))
        self.assertEqual("MEDIA_UNREADABLE", samples[0].media_error)

    def test_duplicate_bytes_cross_split_rejected(self):
        (self.root / "image1.png").write_bytes(b"same image")
        (self.root / "image2.png").write_bytes(b"same image")
        self.write([self.row(1, split="enrollment"), self.row(2)])
        with self.assertRaisesRegex(ManifestError, "Content-hash leakage"):
            load_manifest(self.manifest, self.root)

    def test_same_session_or_source_cross_split_rejected(self):
        for shared in (dict(session_id="shared"), dict(source_id="shared"), dict(clip_id="shared")):
            with self.subTest(shared=shared):
                self.write([self.row(1, split="enrollment", **shared), self.row(2, **shared)])
                with self.assertRaisesRegex(ManifestError, "Split leakage"):
                    load_manifest(self.manifest, self.root)

    def test_final_unknown_subjects_independent(self):
        self.write([self.row(1), self.row(2, split="test")])
        with self.assertRaisesRegex(ManifestError, "Unknown subjects"):
            load_manifest(self.manifest, self.root)

    def test_duplicate_enrollment_subject_rejected(self):
        self.write([self.row(1, split="enrollment"), self.row(2, split="enrollment")])
        with self.assertRaisesRegex(ManifestError, "one enrollment image"):
            load_manifest(self.manifest, self.root)

    def test_path_escape_rejected(self):
        self.write([self.row(relative_path="../outside.png")])
        with self.assertRaisesRegex(ManifestError, "inside"):
            load_manifest(self.manifest, self.root)

    def test_video_requires_explicit_frame(self):
        self.write([self.row(relative_path="video.mp4", clip_id="clip01")])
        with self.assertRaisesRegex(ManifestError, "explicit frame_index"):
            load_manifest(self.manifest, self.root)

    def test_failed_enrollment_does_not_relabel_known_probes_as_unknown(self):
        self.write([self.row(1, split="enrollment"), self.row(2)])
        result = evaluate_samples(fake_pipeline(), load_manifest(self.manifest, self.root), self.root, "development")
        self.assertEqual("invalid_gallery", result["status"])
        self.assertEqual([], result["records"])
        self.assertIsNone(result["metrics"])

    def test_real_runner_retains_missing_probe_with_all_mode_failures(self):
        _, buffer = cv2.imencode(".png", np.full((120, 120, 3), 127, np.uint8))
        (self.root / "image1.png").write_bytes(buffer.tobytes())
        self.write([self.row(1, split="enrollment"), self.row(2)])
        result = evaluate_samples(fake_pipeline(), load_manifest(self.manifest, self.root), self.root, "development")
        self.assertEqual(1, result["metrics"]["sample_count"])
        self.assertEqual(1.0, result["metrics"]["combined"]["known_live_all_inputs"]["not_correct_identity"]["rate"])
        self.assertEqual("INVALID_IMAGE", result["records"][0]["combined"]["reason_code"])
        self.assertEqual("synthetic_smoke_test", result["status"])

    def test_empty_manifest_is_not_evaluated(self):
        self.write([])
        result = evaluate_samples(fake_pipeline(), load_manifest(self.manifest, self.root), self.root, "development")
        self.assertEqual("not_evaluated", result["status"])
        self.assertIsNone(result["metrics"])


class MetricTests(unittest.TestCase):
    def record(self, subject, enrolled, presentation, rec_accepted, pad_accepted, reason=None):
        rec = empty_mode(reason or ("RECOGNIZED" if rec_accepted else "NOT_RECOGNIZED"))
        rec.update(accepted=rec_accepted, predicted_subject_id="p01" if rec_accepted else None)
        pad = empty_mode(reason or ("LIVE" if pad_accepted else "SPOOF_DETECTED"))
        pad["accepted"] = pad_accepted
        combined = empty_mode(reason or ("FACE_VERIFIED" if rec_accepted and pad_accepted else "SPOOF_DETECTED"))
        combined.update(accepted=rec_accepted and pad_accepted,
                        predicted_subject_id="p01" if rec_accepted and pad_accepted else None)
        return dict(subject_id=subject, enrolled=enrolled, presentation_type=presentation,
                    session_id="s01", clip_id="", condition="normal", camera_id="cam01",
                    recognition_only=rec, liveness_only=pad, combined=combined)

    def test_known_denominators_and_gate_failure_are_not_hidden(self):
        rows = [self.record("p01", True, "live", True, True),
                self.record("p01", True, "live", True, False),
                self.record("p01", True, "live", False, False, "NO_FACE"),
                self.record("p02", False, "live", True, True),
                self.record("p02", False, "live", False, True),
                self.record("p02", False, "live", False, False, "INVALID_IMAGE"),
                self.record("p01", True, "print", True, True),
                self.record("p01", True, "replay", True, False)]
        summary = report_metrics(rows)
        self.assertEqual(2 / 3, summary["recognition_only"]["known_live_all_inputs"]["correct_identity"]["rate"])
        self.assertEqual(1 / 3, summary["combined"]["known_live_all_inputs"]["correct_identity"]["rate"])
        self.assertEqual(1 / 3, summary["recognition_only"]["unknown_live_false_accept_all_inputs"]["rate"])
        self.assertEqual(1 / 2, summary["recognition_only"]["unknown_live_false_accept_scored_only"]["rate"])
        self.assertEqual(2, summary["liveness_only"]["live"]["unscorable_count"])
        self.assertEqual(1, summary["combined"]["attack_false_accept_by_type"]["print"]["numerator"])
        self.assertEqual(0, summary["combined"]["attack_false_accept_by_type"]["replay"]["numerator"])
        self.assertIsNone(summary["combined"]["attack_false_accept_by_type"]["screen_photo"]["rate"])

    def test_wrong_enrolled_identity_is_failure_not_success(self):
        summary = report_metrics([self.record("p03", True, "live", True, True)])
        self.assertEqual(1.0, summary["combined"]["known_live_all_inputs"]["wrong_identity"]["rate"])
        self.assertEqual(0.0, summary["combined"]["known_live_all_inputs"]["correct_identity"]["rate"])

    def test_no_samples_is_null_rate_not_perfect_accuracy(self):
        summary = report_metrics([])
        self.assertIsNone(summary["combined"]["known_live_all_inputs"]["correct_identity"]["rate"])
        self.assertIsNone(summary["latency"]["combined"]["p95_ms"])


if __name__ == "__main__":
    unittest.main()

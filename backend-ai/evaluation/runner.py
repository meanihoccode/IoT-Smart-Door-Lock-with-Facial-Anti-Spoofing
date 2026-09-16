"""Offline diagnostics call the same detection, PAD, matcher and verify as the API."""
from pathlib import Path
from time import perf_counter

import cv2

from face_pipeline import (PipelineError, decode_image, elapsed_ms,
                           MAX_UPLOAD_BYTES)
from evaluation.manifest import media_path, sha256_file
from evaluation.metrics import report_metrics


def read_sample(sample, dataset_root):
    path = media_path(Path(dataset_root), sample.relative_path)
    # Do not evaluate changed assets against a fingerprint recorded earlier.
    try:
        if sample.media_error or not sample.media_sha256:
            raise PipelineError("INVALID_IMAGE")
        if sha256_file(path) != sample.media_sha256:
            raise ValueError("Dataset media changed after manifest validation")
        if sample.frame_index is None:
            with path.open("rb") as stream:
                return decode_image(stream.read(MAX_UPLOAD_BYTES + 1), path.name)
        capture = cv2.VideoCapture(str(path))
        try:
            if not capture.isOpened():
                raise PipelineError("INVALID_IMAGE")
            # Decode sequentially instead of trusting codec-dependent seek rounding.
            image = None
            for _ in range(sample.frame_index + 1):
                ok, image = capture.read()
                if not ok:
                    raise PipelineError("INVALID_IMAGE")
            return image
        finally:
            capture.release()
    except (OSError, cv2.error) as exc:
        raise PipelineError("INVALID_IMAGE") from exc


def empty_mode(reason_code, total_ms=0.0, face_count=0):
    return {"accepted": False, "reason_code": reason_code, "face_count": face_count,
            "predicted_subject_id": None, "similarity": None, "live_score": None,
            "total_ms": total_ms, "timings_ms": {}}


def diagnostic(pipeline, image, gallery, id_to_subject, mode):
    """Independent modes exist only here, never as an API option."""
    started = perf_counter()
    result = empty_mode("INTERNAL_ERROR")
    try:
        tick = perf_counter()
        try:
            # Cùng quy tắc chọn mặt với API; face_count vẫn là tổng số mặt phát hiện.
            face, face_count = pipeline.detect_largest(image)
            result["face_count"] = face_count
            if mode == "recognition_only":
                embedding = pipeline.get_embedding(image, face)
        finally:
            result["timings_ms"]["detectionAndEmbedding"] = elapsed_ms(tick)
        tick = perf_counter()
        if mode == "recognition_only":
            match, reason = pipeline.match(embedding, gallery)
            result.update(accepted=match["recognized"],
                          reason_code="RECOGNIZED" if match["recognized"] else reason,
                          predicted_subject_id=id_to_subject.get(match["userId"]),
                          similarity=match["similarity"])
            result["timings_ms"]["matching"] = elapsed_ms(tick)
        else:
            pad = pipeline.check_liveness(image, face)
            result.update(accepted=pad["isReal"],
                          reason_code="LIVE" if pad["isReal"] else "SPOOF_DETECTED",
                          live_score=pad["liveScore"])
            result["timings_ms"]["liveness"] = elapsed_ms(tick)
    except PipelineError as exc:
        result.update(reason_code=exc.reason_code, face_count=max(result["face_count"], exc.face_count))
    except Exception:
        result["reason_code"] = "INTERNAL_ERROR"
    result["total_ms"] = elapsed_ms(started)
    return result


def evaluate_samples(pipeline, samples, dataset_root, split):
    enrollment = sorted((s for s in samples if s.split == "enrollment"), key=lambda s: s.subject_id)
    probes = [s for s in samples if s.split == split]
    if not enrollment or not probes:
        return {"status": "not_evaluated", "message": "Cần ảnh enrollment và ảnh probe thuộc split đã chọn.",
                "enrollment": [], "records": [], "metrics": None}
    gallery, enrollment_results = [], []
    for user_id, sample in enumerate(enrollment, 1):
        started = perf_counter()
        reason = "EMBEDDING_EXTRACTED"
        try:
            embedding = pipeline.extract(read_sample(sample, dataset_root))
            gallery.append({"id": user_id, "username": sample.subject_id,
                            "embedding": embedding})
        except PipelineError as exc:
            reason = exc.reason_code
        except ValueError:
            raise  # Media changed since the snapshot: invalidate this run.
        except Exception:
            reason = "INTERNAL_ERROR"
        enrollment_results.append({"sample_id": sample.sample_id, "subject_id": sample.subject_id,
                                   "reason_code": reason, "total_ms": elapsed_ms(started)})
    if len(gallery) != len(enrollment):
        # Never turn a failed known identity into an apparently unknown probe.
        return {"status": "invalid_gallery", "message": "Có ảnh đăng ký lỗi; sửa gallery trước khi đo probe.",
                "enrollment": enrollment_results, "records": [], "metrics": None}
    id_to_subject = {entry["id"]: entry["username"] for entry in gallery}
    known_subjects = set(id_to_subject.values())
    records = []
    for sample in probes:
        record = sample.metadata()
        record["enrolled"] = sample.subject_id in known_subjects
        record["image_width"] = record["image_height"] = None
        tick = perf_counter()
        try:
            image = read_sample(sample, dataset_root)
            decode_ms = elapsed_ms(tick)
            record["image_height"], record["image_width"] = image.shape[:2]
            # Production combined path runs first, without diagnostic prewarming.
            response = pipeline.verify(image, lambda: gallery, sample.sample_id)
            combined = empty_mode(response["reasonCode"], face_count=response["faceCount"])
            combined.update(accepted=response["status"] == "success",
                            predicted_subject_id=id_to_subject.get(response["recognition"]["userId"]),
                            similarity=response["recognition"]["similarity"],
                            live_score=response["liveness"]["liveScore"],
                            total_ms=response["timingsMs"]["total"],
                            timings_ms=dict(response["timingsMs"]))
            # The API names the gallery-load timing 'database'. Offline it is an in-memory lookup.
            combined["timings_ms"]["galleryLoad"] = combined["timings_ms"].pop("database", 0.0)
            record["combined"] = combined
            for mode in ("recognition_only", "liveness_only"):
                record[mode] = diagnostic(pipeline, image, gallery, id_to_subject, mode)
            for mode in ("recognition_only", "liveness_only", "combined"):
                record[mode]["timings_ms"]["mediaReadAndDecode"] = decode_ms
                record[mode]["total_ms"] = round(record[mode]["total_ms"] + decode_ms, 2)
        except PipelineError as exc:
            for mode in ("recognition_only", "liveness_only", "combined"):
                record[mode] = empty_mode(exc.reason_code, elapsed_ms(tick), exc.face_count)
        records.append(record)
    return {"status": "evaluated" if all(s.capture_source == "camera" for s in samples if s.split in {"enrollment", split}) else "synthetic_smoke_test",
            "message": "Đã đo dữ liệu có nhãn; chưa phải kết luận tăng độ chính xác.",
            "enrollment": enrollment_results, "records": records, "metrics": report_metrics(records)}

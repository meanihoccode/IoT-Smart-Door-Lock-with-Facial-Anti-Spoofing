"""Shared image inference. No HTTP, database, MQTT, model downloads or import-time loading."""
from dataclasses import dataclass
from time import perf_counter
from uuid import uuid4

import cv2
import numpy as np

from baseline_config import BASELINE

FACE_MODEL_NAME = BASELINE["face_model"]
FACE_EMBEDDING_SIZE = BASELINE["embedding_size"]
RECOGNITION_THRESHOLD = BASELINE["recognition_threshold"]
MAX_UPLOAD_BYTES = BASELINE["max_upload_bytes"]

REASON_MESSAGES = {
    "FACE_VERIFIED": "Xác thực khuôn mặt thành công.",
    "NO_ENROLLMENT": "Hệ thống chưa có khuôn mặt đã đăng ký.",
    "NO_FACE": "Không tìm thấy khuôn mặt. Hãy nhìn thẳng vào camera và thử lại.",
    "MULTIPLE_FACES": "Phát hiện nhiều khuôn mặt. Chỉ một người được đứng trước camera.",
    "LOW_QUALITY": "Ảnh chưa đủ rõ. Hãy giữ yên và bảo đảm khuôn mặt đủ sáng.",
    "LIVENESS_UNCERTAIN": "Chưa xác định được khuôn mặt thật. Hãy thử lại trong điều kiện sáng hơn.",
    "SPOOF_DETECTED": "Phát hiện dấu hiệu giả mạo. Từ chối truy cập.",
    "NOT_RECOGNIZED": "Khuôn mặt chưa được nhận diện hoặc chưa đăng ký.",
    "MODEL_UNAVAILABLE": "Dịch vụ AI chưa sẵn sàng. Vui lòng thử lại sau.",
    "DB_UNAVAILABLE": "Không thể đọc dữ liệu khuôn mặt đã đăng ký.",
    "INVALID_AI_RESPONSE": "Kết quả AI không hợp lệ.",
    "INVALID_TEMPLATE": "Dữ liệu khuôn mặt đã đăng ký không hợp lệ.",
    "INVALID_IMAGE": "File ảnh không hợp lệ hoặc không thể đọc.",
    "INTERNAL_ERROR": "Dịch vụ AI gặp lỗi trong quá trình xử lý.",
    "EMBEDDING_EXTRACTED": "Đã trích xuất đặc trưng khuôn mặt.",
}


class PipelineError(RuntimeError):
    def __init__(self, reason_code, face_count=0):
        super().__init__(reason_code)
        self.reason_code = reason_code
        self.face_count = face_count


class DatabaseUnavailableError(PipelineError):
    def __init__(self, _detail=None):
        super().__init__("DB_UNAVAILABLE")


class InvalidTemplateError(PipelineError):
    def __init__(self, _detail=None):
        super().__init__("INVALID_TEMPLATE")


@dataclass
class DetectedFace:
    """Giữ dữ liệu của một mặt để detector, ArcFace và anti-spoofing dùng chung."""

    bbox: np.ndarray  # Khung mặt [x1, y1, x2, y2] trong ảnh gốc.
    kps: np.ndarray | None  # Các điểm mắt, mũi, miệng để ArcFace căn chỉnh mặt.
    det_score: float  # Độ tin cậy do detector trả về, không phải điểm nhận diện.
    embedding: np.ndarray | None = None  # Vector đặc trưng, chỉ tính sau khi chọn mặt.


def elapsed_ms(started_at):
    return round((perf_counter() - started_at) * 1000, 2)


def decode_image(contents, filename):
    if (not str(filename).lower().endswith((".jpg", ".jpeg", ".png"))
            or not contents or len(contents) > MAX_UPLOAD_BYTES):
        raise PipelineError("INVALID_IMAGE")
    try:
        image = cv2.imdecode(np.frombuffer(contents, np.uint8), cv2.IMREAD_COLOR)
    except cv2.error as exc:
        raise PipelineError("INVALID_IMAGE") from exc
    if image is None:
        raise PipelineError("INVALID_IMAGE")
    return image


def validate_embedding(value, reason_code="INVALID_AI_RESPONSE"):
    try:
        embedding = np.asarray(value, dtype=np.float32)
        norm = np.linalg.norm(embedding)
        if (embedding.shape != (FACE_EMBEDDING_SIZE,)
                or not np.all(np.isfinite(embedding)) or not np.isfinite(norm) or norm == 0):
            raise ValueError("invalid embedding")
        return embedding
    except (TypeError, ValueError, OverflowError) as exc:
        raise PipelineError(reason_code, face_count=1) from exc


def compute_cosine_similarity(embedding_a, embedding_b):
    norm_a = np.linalg.norm(embedding_a)
    norm_b = np.linalg.norm(embedding_b)
    if norm_a == 0 or norm_b == 0:
        return 0.0
    similarity = float(np.dot(embedding_a, embedding_b) / (norm_a * norm_b))
    if not np.isfinite(similarity):
        raise InvalidTemplateError()
    return float(np.clip(similarity, -1.0, 1.0))


def verification_response(request_id=None):
    return {
        "status": "error", "requestId": request_id or str(uuid4()),
        "reasonCode": "INTERNAL_ERROR", "message": REASON_MESSAGES["INTERNAL_ERROR"],
        "faceCount": 0,  # Tổng số mặt phát hiện, kể cả những mặt không được chọn.
        "liveness": {"status": "NOT_RUN", "isReal": None, "liveScore": None},
        "recognition": {
            "status": "NOT_RUN", "recognized": False, "userId": None,
            "username": None, "similarity": None,
            "threshold": RECOGNITION_THRESHOLD, "gallerySize": 0,
        },
        "models": {"faceRecognition": FACE_MODEL_NAME, "antiSpoofing": BASELINE["liveness_model"]},
        "timingsMs": {},
    }


def finish_response(result, reason_code, started_at):
    result["reasonCode"] = reason_code
    result["status"] = "success" if reason_code == "FACE_VERIFIED" else "error"
    result["message"] = REASON_MESSAGES[reason_code]
    result["timingsMs"]["total"] = elapsed_ms(started_at)
    return result


class FacePipeline:
    def __init__(self, face_app, liveness_checker):
        self.face_app = face_app  # Chứa detector tìm mặt và ArcFace lấy đặc trưng.
        self.liveness_checker = liveness_checker  # MiniFASNet kiểm tra mặt thật/giả.

    @property
    def liveness_ready(self):
        return self.liveness_checker is not None and self.liveness_checker.is_ready

    def detect_faces(self, image):
        """Phát hiện vị trí các mặt một lần, chưa tính đặc trưng nhận diện."""
        if self.face_app is None:
            raise PipelineError("MODEL_UNAVAILABLE")

        # max_num=0 lấy tất cả box để tự chọn theo diện tích và đếm đúng số mặt.
        # Không dùng FaceAnalysis.get(): hàm đó chạy các model trên mọi mặt.
        # boxes: mỗi hàng gồm [x1, y1, x2, y2, độ tin cậy].
        # landmarks: các điểm mắt/mũi/miệng tương ứng với từng hàng của boxes.
        boxes, landmarks = self.face_app.det_model.detect(image, max_num=0, metric="default")
        faces = []
        for index, box in enumerate(boxes):
            faces.append(DetectedFace(
                bbox=box[:4],
                kps=landmarks[index] if landmarks is not None else None,
                det_score=float(box[4]),
            ))
        if not faces:
            raise PipelineError("NO_FACE")
        return faces

    def detect_one(self, image):
        """Dùng khi đăng ký: yêu cầu đúng một mặt để tránh lưu nhầm người."""
        faces = self.detect_faces(image)
        if len(faces) != 1:
            raise PipelineError("MULTIPLE_FACES", len(faces))
        return faces[0]

    def detect_largest(self, image):
        """Trả về (mặt lớn nhất, tổng số mặt); nếu bằng diện tích thì ưu tiên gần tâm ảnh."""
        faces = self.detect_faces(image)
        face_count = len(faces)
        image_height, image_width = image.shape[:2]

        selected_face = None  # Mặt tốt nhất tìm được trong vòng lặp.
        largest_area = -1.0  # Diện tích phần box nằm trong ảnh, tính bằng pixel vuông.
        nearest_center_distance = float("inf")  # Khoảng cách bình phương tới tâm ảnh.

        for face in faces:
            if face.bbox.shape != (4,) or not np.all(np.isfinite(face.bbox)):
                raise PipelineError("INVALID_AI_RESPONSE", face_count)

            # Chỉ giới hạn tọa độ để tính diện tích nhìn thấy. Giữ box/landmark gốc
            # trên face để các model vẫn crop và căn chỉnh cùng khuôn mặt đã chọn.
            x1, y1, x2, y2 = face.bbox.astype(float)
            x1, x2 = np.clip([x1, x2], 0, image_width)
            y1, y2 = np.clip([y1, y2], 0, image_height)
            box_width = x2 - x1
            box_height = y2 - y1
            if box_width <= 0 or box_height <= 0:
                raise PipelineError("INVALID_AI_RESPONSE", face_count)

            area = box_width * box_height
            center_x = (x1 + x2) / 2
            center_y = (y1 + y2) / 2
            center_distance = ((center_x - image_width / 2) ** 2
                               + (center_y - image_height / 2) ** 2)

            # Diện tích là tiêu chí chính; khoảng cách chỉ dùng khi bằng diện tích.
            # Nếu cả hai tiêu chí bằng nhau, giữ mặt xuất hiện trước trong kết quả detector.
            if (area > largest_area
                    or (area == largest_area and center_distance < nearest_center_distance)):
                selected_face = face
                largest_area = area
                nearest_center_distance = center_distance

        return selected_face, face_count

    def get_embedding(self, image, face):
        """Chạy ArcFace đúng một lần cho mặt đã chọn và kiểm tra vector trả về."""
        recognition_model = self.face_app.models["recognition"]
        embedding = recognition_model.get(image, face)
        return validate_embedding(embedding)

    def extract(self, image):
        """Lấy đặc trưng để đăng ký, chỉ sau khi xác nhận ảnh có đúng một mặt."""
        face = self.detect_one(image)
        return self.get_embedding(image, face)

    def check_liveness(self, image, face):
        if not self.liveness_ready:
            raise PipelineError("MODEL_UNAVAILABLE", 1)
        try:
            is_real, live_score = self.liveness_checker.predict(image, face.bbox)
            if not isinstance(is_real, (bool, np.bool_)):
                raise ValueError("invalid liveness flag")
            if not np.isfinite(live_score) or not 0 <= live_score <= 1:
                raise ValueError("invalid liveness score")
        except Exception as exc:
            raise PipelineError("MODEL_UNAVAILABLE", 1) from exc
        return {"status": "PASSED" if is_real else "FAILED",
                "isReal": bool(is_real), "liveScore": float(live_score)}

    def match(self, embedding, gallery):
        recognition = verification_response()["recognition"]
        recognition["gallerySize"] = len(gallery)
        if not gallery:
            return recognition, "NO_ENROLLMENT"
        best_match, best_similarity = None, -1.0
        for candidate in gallery:
            candidate_embedding = validate_embedding(candidate["embedding"], "INVALID_TEMPLATE")
            score = compute_cosine_similarity(embedding, candidate_embedding)
            if score > best_similarity:
                best_match, best_similarity = candidate, score
        recognition.update(status="NOT_MATCHED", similarity=best_similarity)
        if best_match is None or best_similarity < RECOGNITION_THRESHOLD:
            return recognition, "NOT_RECOGNIZED"
        recognition.update(status="MATCHED", recognized=True,
                           userId=best_match["id"], username=best_match["username"])
        return recognition, "FACE_VERIFIED"

    def verify(self, image, gallery_loader, request_id=None):
        """Xác thực mặt lớn nhất; chỉ so gallery sau khi mặt này vượt anti-spoofing.

        image là ảnh BGR từ camera; gallery_loader đọc danh sách người đã đăng ký;
        request_id giúp đối chiếu kết quả giữa AI, backend-core và log.
        """
        started = perf_counter()
        result = verification_response(request_id)
        reason = "INTERNAL_ERROR"
        try:
            if self.face_app is None or not self.liveness_ready:
                raise PipelineError("MODEL_UNAVAILABLE")
            tick = perf_counter()
            try:
                face, face_count = self.detect_largest(image)
                result["faceCount"] = face_count
                embedding = self.get_embedding(image, face)
            finally:
                result["timingsMs"]["detectionAndEmbedding"] = elapsed_ms(tick)
            tick = perf_counter()
            try:
                # Dùng đúng mặt đã lấy embedding; không thử mặt khác nếu mặt này thất bại.
                result["liveness"] = self.check_liveness(image, face)
            except PipelineError:
                result["liveness"]["status"] = "UNAVAILABLE"
                raise
            finally:
                result["timingsMs"]["liveness"] = elapsed_ms(tick)
            if not result["liveness"]["isReal"]:
                raise PipelineError("SPOOF_DETECTED", 1)
            tick = perf_counter()
            try:
                gallery = gallery_loader()
            finally:
                result["timingsMs"]["database"] = elapsed_ms(tick)
            tick = perf_counter()
            try:
                result["recognition"], reason = self.match(embedding, gallery)
            finally:
                result["timingsMs"]["matching"] = elapsed_ms(tick)
        except PipelineError as exc:
            result["faceCount"] = max(result["faceCount"], exc.face_count)
            reason = exc.reason_code
        except Exception:
            # Do not serialize exceptions, images, templates or DB configuration.
            reason = "INTERNAL_ERROR"
        return finish_response(result, reason, started)

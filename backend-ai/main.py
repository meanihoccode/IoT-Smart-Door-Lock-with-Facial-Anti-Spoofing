from fastapi import FastAPI, File, Request, UploadFile
from fastapi.responses import JSONResponse
import cv2
import numpy as np
import insightface
from anti_spoofing import anti_spoof_checker, LivenessModelUnavailableError
import mysql.connector
import json
import logging
import os
from pathlib import Path
from time import perf_counter
from uuid import uuid4
from dotenv import load_dotenv


ENV_FILE = Path(__file__).resolve().parent.parent / ".env"
load_dotenv(ENV_FILE)

FACE_MODEL_NAME = "buffalo_l"
FACE_EMBEDDING_SIZE = 512
RECOGNITION_THRESHOLD = 0.5
MAX_UPLOAD_BYTES = 10 * 1024 * 1024

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

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("face-auth")


class DatabaseUnavailableError(RuntimeError):
    pass


class InvalidTemplateError(RuntimeError):
    pass


def get_db_connection():
    return mysql.connector.connect(
        host=os.getenv("DB_HOST", "localhost"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", ""),
        database=os.getenv("DB_NAME", "btl_iot"),
        connection_timeout=3,
    )


def check_database():
    connection = None
    try:
        connection = get_db_connection()
        connection.ping(reconnect=False, attempts=1, delay=0)
        return True
    except mysql.connector.Error:
        return False
    finally:
        if connection is not None and connection.is_connected():
            connection.close()


def load_known_faces():
    connection = None
    cursor = None
    try:
        connection = get_db_connection()
        cursor = connection.cursor(dictionary=True)
        cursor.execute(
            "SELECT id, username, full_name, face_embedding "
            "FROM users WHERE face_embedding IS NOT NULL"
        )
        users = cursor.fetchall()
    except mysql.connector.Error as exc:
        raise DatabaseUnavailableError("Could not load enrolled faces") from exc
    finally:
        if cursor is not None:
            cursor.close()
        if connection is not None and connection.is_connected():
            connection.close()

    known_faces = []
    invalid_count = 0
    for user in users:
        try:
            embedding = np.asarray(json.loads(user["face_embedding"]), dtype=np.float32)
            if (
                embedding.shape != (FACE_EMBEDDING_SIZE,)
                or not np.all(np.isfinite(embedding))
                or np.linalg.norm(embedding) == 0
            ):
                raise ValueError("invalid embedding")
            known_faces.append(
                {
                    "id": user["id"],
                    "username": user["username"],
                    "full_name": user["full_name"],
                    "embedding": embedding,
                }
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            invalid_count += 1

    if invalid_count:
        logger.error(
            "event=invalid_face_templates invalid_count=%s gallery_rows=%s",
            invalid_count,
            len(users),
        )
        raise InvalidTemplateError(f"Found {invalid_count} invalid face templates")
    return known_faces


def compute_cosine_similarity(embedding_a, embedding_b):
    norm_a = np.linalg.norm(embedding_a)
    norm_b = np.linalg.norm(embedding_b)
    if norm_a == 0 or norm_b == 0:
        return 0.0
    similarity = float(np.dot(embedding_a, embedding_b) / (norm_a * norm_b))
    return float(np.clip(similarity, -1.0, 1.0))


def request_id_from(request):
    supplied_id = request.headers.get("x-request-id", "") if request else ""
    supplied_id = supplied_id.strip()
    if supplied_id and len(supplied_id) <= 64 and all(
        character.isalnum() or character in "-_." for character in supplied_id
    ):
        return supplied_id
    return str(uuid4())


def elapsed_ms(started_at):
    return round((perf_counter() - started_at) * 1000, 2)


def build_verification_response(
    request_id,
    status,
    reason_code,
    started_at,
    *,
    face_count=0,
    liveness_status="NOT_RUN",
    is_real=None,
    live_score=None,
    recognition_status="NOT_RUN",
    recognized=False,
    user_id=None,
    username=None,
    similarity=None,
    gallery_size=0,
    timings=None,
):
    result = {
        "status": status,
        "requestId": request_id,
        "reasonCode": reason_code,
        "message": REASON_MESSAGES[reason_code],
        "faceCount": face_count,
        "liveness": {
            "status": liveness_status,
            "isReal": is_real,
            "liveScore": live_score,
        },
        "recognition": {
            "status": recognition_status,
            "recognized": recognized,
            "userId": user_id,
            "username": username,
            "similarity": similarity,
            "threshold": RECOGNITION_THRESHOLD,
            "gallerySize": gallery_size,
        },
        "models": {
            "faceRecognition": FACE_MODEL_NAME,
            "antiSpoofing": anti_spoof_checker.model_name,
        },
        "timingsMs": dict(timings or {}),
    }
    result["timingsMs"]["total"] = elapsed_ms(started_at)
    logger.info(
        "event=face_verification request_id=%s reason=%s status=%s "
        "face_count=%s quality_score=%s live_score=%s similarity=%s "
        "gallery_size=%s face_model=%s liveness_model=%s "
        "recognition_threshold=%s timings_ms=%s",
        request_id,
        reason_code,
        status,
        face_count,
        None,
        live_score,
        similarity,
        gallery_size,
        FACE_MODEL_NAME,
        anti_spoof_checker.model_name,
        RECOGNITION_THRESHOLD,
        json.dumps(result["timingsMs"], separators=(",", ":")),
    )
    return result


app = FastAPI(title="Face Recognition API", description="AI Backend for Smart Lock")

logger.info("event=model_loading model=%s", FACE_MODEL_NAME)
try:
    face_app = insightface.app.FaceAnalysis(name=FACE_MODEL_NAME)
    face_app.prepare(ctx_id=-1, det_size=(640, 640))
    face_model_error = None
except Exception as exc:
    logger.exception("event=model_load_failed model=%s", FACE_MODEL_NAME)
    face_app = None
    face_model_error = str(exc)


@app.get("/")
def read_root():
    return {
        "status": "ok",
        "message": "AI Backend process is running",
        "readyUrl": "/ready",
    }


@app.get("/ready")
def readiness():
    dependencies = {
        "faceModel": face_app is not None,
        "livenessModel": anti_spoof_checker.is_ready,
        "database": check_database(),
    }
    ready = all(dependencies.values())
    return JSONResponse(
        status_code=200 if ready else 503,
        content={
            "status": "ready" if ready else "not_ready",
            "dependencies": dependencies,
            "models": {
                "faceRecognition": FACE_MODEL_NAME,
                "antiSpoofing": anti_spoof_checker.model_name,
            },
        },
    )


@app.post("/api/verify-face")
async def verify_face(request: Request, file: UploadFile = File(...)):
    started_at = perf_counter()
    request_id = request_id_from(request)
    timings = {}

    try:
        filename = (file.filename or "").lower()
        if not filename.endswith((".jpg", ".jpeg", ".png")):
            return JSONResponse(
                build_verification_response(
                    request_id, "error", "INVALID_IMAGE", started_at, timings=timings
                )
            )

        decode_started = perf_counter()
        contents = await file.read()
        if not contents or len(contents) > MAX_UPLOAD_BYTES:
            return JSONResponse(
                build_verification_response(
                    request_id, "error", "INVALID_IMAGE", started_at, timings=timings
                )
            )
        image = cv2.imdecode(np.frombuffer(contents, np.uint8), cv2.IMREAD_COLOR)
        timings["decode"] = elapsed_ms(decode_started)
        if image is None:
            return JSONResponse(
                build_verification_response(
                    request_id, "error", "INVALID_IMAGE", started_at, timings=timings
                )
            )

        if face_app is None or not anti_spoof_checker.is_ready:
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "MODEL_UNAVAILABLE",
                    started_at,
                    timings=timings,
                )
            )

        detection_started = perf_counter()
        faces = face_app.get(image)
        timings["detectionAndEmbedding"] = elapsed_ms(detection_started)
        face_count = len(faces)
        if face_count == 0:
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "NO_FACE",
                    started_at,
                    face_count=0,
                    timings=timings,
                )
            )
        if face_count > 1:
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "MULTIPLE_FACES",
                    started_at,
                    face_count=face_count,
                    timings=timings,
                )
            )

        target_face = faces[0]
        embedding = np.asarray(target_face.embedding, dtype=np.float32)
        if (
            embedding.shape != (FACE_EMBEDDING_SIZE,)
            or not np.all(np.isfinite(embedding))
            or np.linalg.norm(embedding) == 0
        ):
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "INVALID_AI_RESPONSE",
                    started_at,
                    face_count=1,
                    timings=timings,
                )
            )

        liveness_started = perf_counter()
        try:
            is_real, live_score = anti_spoof_checker.predict(image, target_face.bbox)
        except LivenessModelUnavailableError:
            logger.exception("event=liveness_inference_failed request_id=%s", request_id)
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "MODEL_UNAVAILABLE",
                    started_at,
                    face_count=1,
                    liveness_status="UNAVAILABLE",
                    timings=timings,
                )
            )
        timings["liveness"] = elapsed_ms(liveness_started)
        if not is_real:
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "SPOOF_DETECTED",
                    started_at,
                    face_count=1,
                    liveness_status="FAILED",
                    is_real=False,
                    live_score=live_score,
                    timings=timings,
                )
            )

        database_started = perf_counter()
        try:
            known_faces = load_known_faces()
        except DatabaseUnavailableError:
            logger.exception("event=database_unavailable request_id=%s", request_id)
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "DB_UNAVAILABLE",
                    started_at,
                    face_count=1,
                    liveness_status="PASSED",
                    is_real=True,
                    live_score=live_score,
                    timings=timings,
                )
            )
        except InvalidTemplateError:
            logger.exception("event=invalid_templates request_id=%s", request_id)
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "INVALID_TEMPLATE",
                    started_at,
                    face_count=1,
                    liveness_status="PASSED",
                    is_real=True,
                    live_score=live_score,
                    timings=timings,
                )
            )
        timings["database"] = elapsed_ms(database_started)

        gallery_size = len(known_faces)
        if gallery_size == 0:
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "NO_ENROLLMENT",
                    started_at,
                    face_count=1,
                    liveness_status="PASSED",
                    is_real=True,
                    live_score=live_score,
                    gallery_size=0,
                    timings=timings,
                )
            )

        matching_started = perf_counter()
        best_match = None
        best_similarity = -1.0
        for known_face in known_faces:
            similarity = compute_cosine_similarity(embedding, known_face["embedding"])
            if similarity > best_similarity:
                best_similarity = similarity
                best_match = known_face
        timings["matching"] = elapsed_ms(matching_started)

        if best_match is None or best_similarity < RECOGNITION_THRESHOLD:
            return JSONResponse(
                build_verification_response(
                    request_id,
                    "error",
                    "NOT_RECOGNIZED",
                    started_at,
                    face_count=1,
                    liveness_status="PASSED",
                    is_real=True,
                    live_score=live_score,
                    recognition_status="NOT_MATCHED",
                    similarity=best_similarity,
                    gallery_size=gallery_size,
                    timings=timings,
                )
            )

        return JSONResponse(
            build_verification_response(
                request_id,
                "success",
                "FACE_VERIFIED",
                started_at,
                face_count=1,
                liveness_status="PASSED",
                is_real=True,
                live_score=live_score,
                recognition_status="MATCHED",
                recognized=True,
                user_id=best_match["id"],
                username=best_match["username"],
                similarity=best_similarity,
                gallery_size=gallery_size,
                timings=timings,
            )
        )
    except Exception:
        logger.exception("event=face_verification_failed request_id=%s", request_id)
        return JSONResponse(
            build_verification_response(
                request_id, "error", "INTERNAL_ERROR", started_at, timings=timings
            )
        )


@app.post("/api/extract-embedding")
async def extract_embedding(request: Request, file: UploadFile = File(...)):
    """Extract one 512-dimensional face template for enrollment."""
    started_at = perf_counter()
    request_id = request_id_from(request)
    filename = (file.filename or "").lower()
    if not filename.endswith((".jpg", ".jpeg", ".png")):
        return JSONResponse(
            {
                "status": "error",
                "requestId": request_id,
                "reasonCode": "INVALID_IMAGE",
                "message": REASON_MESSAGES["INVALID_IMAGE"],
            }
        )

    try:
        contents = await file.read()
        if not contents or len(contents) > MAX_UPLOAD_BYTES:
            raise ValueError("invalid upload size")
        image = cv2.imdecode(np.frombuffer(contents, np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("image decode failed")
        if face_app is None:
            return JSONResponse(
                {
                    "status": "error",
                    "requestId": request_id,
                    "reasonCode": "MODEL_UNAVAILABLE",
                    "message": REASON_MESSAGES["MODEL_UNAVAILABLE"],
                }
            )

        faces = face_app.get(image)
        if not faces:
            reason_code = "NO_FACE"
        elif len(faces) > 1:
            reason_code = "MULTIPLE_FACES"
        else:
            embedding = np.asarray(faces[0].embedding, dtype=np.float32)
            if (
                embedding.shape != (FACE_EMBEDDING_SIZE,)
                or not np.all(np.isfinite(embedding))
                or np.linalg.norm(embedding) == 0
            ):
                reason_code = "INVALID_AI_RESPONSE"
            else:
                logger.info(
                    "event=embedding_extracted request_id=%s face_count=1 total_ms=%s",
                    request_id,
                    elapsed_ms(started_at),
                )
                return JSONResponse(
                    {
                        "status": "success",
                        "requestId": request_id,
                        "reasonCode": "EMBEDDING_EXTRACTED",
                        "message": REASON_MESSAGES["EMBEDDING_EXTRACTED"],
                        "faceCount": 1,
                        "embedding": embedding.tolist(),
                        "model": FACE_MODEL_NAME,
                        "totalMs": elapsed_ms(started_at),
                    }
                )
    except ValueError:
        reason_code = "INVALID_IMAGE"
    except Exception:
        logger.exception("event=embedding_extraction_failed request_id=%s", request_id)
        reason_code = "INTERNAL_ERROR"

    logger.info(
        "event=embedding_rejected request_id=%s reason=%s total_ms=%s",
        request_id,
        reason_code,
        elapsed_ms(started_at),
    )
    return JSONResponse(
        {
            "status": "error",
            "requestId": request_id,
            "reasonCode": reason_code,
            "message": REASON_MESSAGES[reason_code],
        }
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

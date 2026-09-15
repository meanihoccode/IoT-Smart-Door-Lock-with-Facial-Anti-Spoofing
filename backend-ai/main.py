"""HTTP/DB adapter for the shared AI pipeline. Response contract stays phase-1 compatible."""
from contextlib import asynccontextmanager
import json
import logging
import os
from pathlib import Path
from time import perf_counter
from uuid import uuid4

from dotenv import load_dotenv
from fastapi import FastAPI, File, Request, UploadFile
from fastapi.responses import JSONResponse
import mysql.connector

from face_pipeline import (
    FACE_MODEL_NAME, FACE_EMBEDDING_SIZE, RECOGNITION_THRESHOLD,
    MAX_UPLOAD_BYTES, REASON_MESSAGES, DatabaseUnavailableError,
    InvalidTemplateError, PipelineError, FacePipeline, decode_image,
    validate_embedding, compute_cosine_similarity, elapsed_ms,
    verification_response, finish_response,
)
from model_runtime import load_models
from settings import database_config

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s %(message)s"
)
logger = logging.getLogger("face-auth")

face_app = None
anti_spoof_checker = None


@asynccontextmanager
async def lifespan(_app):
    global face_app, anti_spoof_checker
    face_app, anti_spoof_checker = load_models()
    yield


app = FastAPI(
    title="Face Recognition API",
    description="AI Backend for Smart Lock",
    lifespan=lifespan
)


def get_db_connection():
    return mysql.connector.connect(**database_config())


def check_database():
    connection = None
    try:
        connection = get_db_connection()
        connection.ping(reconnect=False, attempts=1, delay=0)
        return True
    except mysql.connector.Error:
        return False
    finally:
        if connection is not None:
            connection.close()


def load_known_faces():
    connection = cursor = None
    try:
        connection = get_db_connection()
        cursor = connection.cursor(dictionary=True)
        cursor.execute("SELECT id, username, full_name, face_embedding "
                       "FROM users WHERE face_embedding IS NOT NULL")
        users = cursor.fetchall()
    except mysql.connector.Error as exc:
        raise DatabaseUnavailableError() from exc
    finally:
        if cursor is not None:
            cursor.close()
        if connection is not None:
            connection.close()
    known_faces = []
    for user in users:
        try:
            embedding = validate_embedding(json.loads(user["face_embedding"]), "INVALID_TEMPLATE")
        except (ValueError, TypeError, PipelineError) as exc:
            raise InvalidTemplateError() from exc
        known_faces.append({"id": user["id"], "username": user["username"],
                            "full_name": user["full_name"], "embedding": embedding})
    return known_faces


def request_id_from(request):
    supplied = request.headers.get("x-request-id", "").strip()
    if supplied and len(supplied) <= 64 and all(c.isalnum() or c in "-_." for c in supplied):
        return supplied
    return str(uuid4())


def log_verification(result):
    logger.info(
        "event=face_verification request_id=%s reason=%s status=%s face_count=%s "
        "quality_score=None live_score=%s similarity=%s gallery_size=%s "
        "face_model=%s liveness_model=%s recognition_threshold=%s timings_ms=%s",
        result["requestId"], result["reasonCode"], result["status"], result["faceCount"],
        result["liveness"]["liveScore"], result["recognition"]["similarity"],
        result["recognition"]["gallerySize"], result["models"]["faceRecognition"],
        result["models"]["antiSpoofing"], result["recognition"]["threshold"],
        json.dumps(result["timingsMs"], separators=(",", ":")),
    )


@app.get("/")
def read_root():
    return {"status": "ok", "message": "AI Backend process is running", "readyUrl": "/ready"}


@app.get("/ready")
def readiness():
    dependencies = {"faceModel": face_app is not None,
                    "livenessModel": FacePipeline(face_app, anti_spoof_checker).liveness_ready,
                    "database": check_database()}
    return JSONResponse(status_code=200 if all(dependencies.values()) else 503,
                        content={"status": "ready" if all(dependencies.values()) else "not_ready",
                                 "dependencies": dependencies,
                                 "models": verification_response()["models"]})


@app.post("/api/verify-face")
async def verify_face(request: Request, file: UploadFile = File(...)):
    started = perf_counter()
    request_id = request_id_from(request)
    result = verification_response(request_id)
    decode_started = perf_counter()
    try:
        contents = await file.read(MAX_UPLOAD_BYTES + 1)
        image = decode_image(contents, file.filename)
        decode_ms = elapsed_ms(decode_started)
        result = FacePipeline(face_app, anti_spoof_checker).verify(image, load_known_faces, request_id)
        result["timingsMs"]["decode"] = decode_ms
    except PipelineError as exc:
        finish_response(result, exc.reason_code, started)
        result["timingsMs"]["decode"] = elapsed_ms(decode_started)
    except Exception:
        logger.exception("event=face_verification_failed request_id=%s", request_id)
        finish_response(result, "INTERNAL_ERROR", started)
    result["timingsMs"]["total"] = elapsed_ms(started)
    log_verification(result)
    return JSONResponse(result)


@app.post("/api/extract-embedding")
async def extract_embedding(request: Request, file: UploadFile = File(...)):
    started = perf_counter()
    request_id = request_id_from(request)
    result = {"status": "error", "requestId": request_id}
    try:
        contents = await file.read(MAX_UPLOAD_BYTES + 1)
        image = decode_image(contents, file.filename)
        embedding = FacePipeline(face_app, anti_spoof_checker).extract(image)
        reason = "EMBEDDING_EXTRACTED"
        result.update(status="success", faceCount=1, embedding=embedding.tolist(),
                      model=FACE_MODEL_NAME, totalMs=elapsed_ms(started))
    except PipelineError as exc:
        reason = exc.reason_code
    except Exception:
        logger.exception("event=embedding_extraction_failed request_id=%s", request_id)
        reason = "INTERNAL_ERROR"
    result.update(reasonCode=reason, message=REASON_MESSAGES[reason])
    logger.info("event=embedding_result request_id=%s reason=%s total_ms=%s",
                request_id, reason, elapsed_ms(started))
    return JSONResponse(result)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

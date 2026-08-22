from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
import cv2
import numpy as np
import io
from PIL import Image
import insightface
from anti_spoofing import anti_spoof_checker
import mysql.connector
import json

def get_db_connection():
    return mysql.connector.connect(
        host="localhost",
        user="root",
        password="1234",
        database="btl_iot"
    )

def load_known_faces():
    try:
        conn = get_db_connection()
        cursor = conn.cursor(dictionary=True)
        cursor.execute("SELECT id, username, full_name, face_embedding FROM users WHERE face_embedding IS NOT NULL")
        users = cursor.fetchall()
        
        known = []
        for u in users:
            try:
                emb_list = json.loads(u['face_embedding'])
                emb_array = np.array(emb_list, dtype=np.float32)
                known.append({
                    "id": u['id'],
                    "username": u['username'],
                    "full_name": u['full_name'],
                    "embedding": emb_array
                })
            except Exception as e:
                pass
        cursor.close()
        conn.close()
        return known
    except Exception as e:
        print(f"DB Error: {e}")
        return []

def compute_cosine_similarity(emb1, emb2):
    dot = np.dot(emb1, emb2)
    norm1 = np.linalg.norm(emb1)
    norm2 = np.linalg.norm(emb2)
    if norm1 == 0 or norm2 == 0: return 0.0
    return dot / (norm1 * norm2)

app = FastAPI(title="Face Recognition API", description="AI Backend for Smart Lock")

# Initialize InsightFace (This automatically downloads models if not present)
print("Loading InsightFace models (RetinaFace + ArcFace)...")
try:
    face_app = insightface.app.FaceAnalysis(name='buffalo_l')
    # Use ctx_id=0 for GPU, -1 for CPU
    face_app.prepare(ctx_id=-1, det_size=(640, 640)) 
except Exception as e:
    print(f"Warning: Could not initialize InsightFace: {e}")
    face_app = None


@app.get("/")
def read_root():
    return {"status": "ok", "message": "AI Backend is running"}

@app.post("/api/verify-face")
async def verify_face(file: UploadFile = File(...)):
    if not file.filename.endswith(('.jpg', '.jpeg', '.png')):
        raise HTTPException(status_code=400, detail="Invalid image format")
        
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        
        if img is None:
            raise HTTPException(status_code=400, detail="Could not read image")

        if face_app is None:
            return JSONResponse({"status": "error", "message": "AI Models not loaded. Please install insightface and onnxruntime."})

        # Step 1: Face Detection & Feature Extraction (RetinaFace + ArcFace)
        faces = face_app.get(img)
        
        if len(faces) == 0:
            return JSONResponse({"status": "error", "message": "Không tìm thấy khuôn mặt trong ảnh!"})
            
        # We assume the largest face or the first one is the target
        target_face = faces[0] 
        bbox = target_face.bbox # [x1, y1, x2, y2]
        embedding = target_face.embedding # 512-d vector

        # Step 2: Liveness Detection (Silent-Face-Anti-Spoofing)
        is_real, spoof_score = anti_spoof_checker.predict(img, bbox)
        
        if not is_real:
            return JSONResponse({
                "status": "error",
                "message": "Phát hiện giả mạo! Liveness check failed.",
                "is_real": False
            })

        # Step 3: Face Recognition (Cosine Similarity)
        known_faces = load_known_faces()
        
        recognized = False
        user_id = None
        user_name = None
        best_similarity = -1.0
        threshold = 0.5  # Cosine similarity threshold for InsightFace

        for known in known_faces:
            sim = compute_cosine_similarity(embedding, known['embedding'])
            if sim > best_similarity:
                best_similarity = sim
                if sim >= threshold:
                    recognized = True
                    user_id = known['id']
                    user_name = known['username']

        return JSONResponse({
            "status": "success",
            "is_real": True,
            "spoof_score": float(spoof_score),
            "face_detected": True,
            "recognized": recognized,
            "user_id": user_id,
            "username": user_name,
            "similarity": float(best_similarity),
            "message": f"Welcome {user_name}" if recognized else "Face verified but not recognized"
        })
        
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Internal server error: {str(e)}")

@app.post("/api/extract-embedding")
async def extract_embedding(file: UploadFile = File(...)):
    """API dùng để đăng ký khuôn mặt mới, trả về mảng 512 số"""
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        
        if img is None:
            raise HTTPException(status_code=400, detail="Could not read image")
            
        if face_app is None:
            return JSONResponse({"status": "error", "message": "AI Models not loaded."})

        faces = face_app.get(img)
        if len(faces) == 0:
            return JSONResponse({"status": "error", "message": "Không tìm thấy khuôn mặt trong ảnh!"})
            
        target_face = faces[0] 
        embedding = target_face.embedding.tolist() # Convert numpy array to python list

        return JSONResponse({
            "status": "success",
            "embedding": embedding
        })
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Internal server error: {str(e)}")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

# AI service: private network only (no published port). Models are mounted read-only, never downloaded.
FROM python:3.11.9-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
# insightface builds a Cython extension at install time.
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.lock.txt .
# insightface pulls in the GUI opencv-python, which shadows the headless build and needs X11 libraries.
# Keep only the headless build (same cv2 API) so the image needs no display stack.
RUN pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.lock.txt \
    && pip uninstall -y opencv-python \
    && pip install --force-reinstall --no-deps opencv-python-headless==5.0.0.93 \
    && python -c "import cv2, insightface, onnxruntime, torch" \
    && apt-get purge -y build-essential && apt-get autoremove -y

# src/anti_spoof_predict.py resolves ./resources relative to the working directory, so keep WORKDIR=/app.
COPY . .
RUN useradd --system --uid 10001 app
USER app

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/ready', timeout=4).status == 200 else 1)"
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]

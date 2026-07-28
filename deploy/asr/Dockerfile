FROM python:3.11-slim-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    ca-certificates \
    wget \
    bzip2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --upgrade pip \
    && pip install --no-cache-dir --retries 5 --timeout 120 -r requirements.txt

# SenseVoice small (offline final)
ARG ASR_MODEL_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
ARG ASR_MODEL_MIRROR=https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
RUN mkdir -p /models \
    && (wget --tries=5 --timeout=120 -O /tmp/asr-model.tar.bz2 "${ASR_MODEL_MIRROR}" \
        || wget --tries=5 --timeout=120 -O /tmp/asr-model.tar.bz2 "${ASR_MODEL_URL}") \
    && tar -tjf /tmp/asr-model.tar.bz2 >/dev/null \
    && tar -xjf /tmp/asr-model.tar.bz2 -C /models \
    && rm /tmp/asr-model.tar.bz2 \
    && mv /models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 /models/sense-voice

# Streaming Zipformer bilingual zh-en (partial / endpoint)
ARG ASR_STREAM_MODEL_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
ARG ASR_STREAM_MODEL_MIRROR=https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
RUN (wget --tries=5 --timeout=180 -O /tmp/asr-stream.tar.bz2 "${ASR_STREAM_MODEL_MIRROR}" \
        || wget --tries=5 --timeout=180 -O /tmp/asr-stream.tar.bz2 "${ASR_STREAM_MODEL_URL}") \
    && tar -tjf /tmp/asr-stream.tar.bz2 >/dev/null \
    && tar -xjf /tmp/asr-stream.tar.bz2 -C /models \
    && rm /tmp/asr-stream.tar.bz2 \
    && mv /models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20 /models/zipformer-zh

ENV ASR_MODEL_DIR=/models/sense-voice
ENV ASR_STREAM_MODEL_DIR=/models/zipformer-zh
ENV ASR_STREAM_ENABLED=true
ENV ASR_THREADS=2
ENV ASR_STREAM_THREADS=2
ENV ASR_PORT=8080
ENV ASR_LANGUAGE=zh

COPY server.py .

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health')" || exit 1

CMD ["python", "server.py"]

"""
Minimal ASR HTTP service for LianYu (sherpa-onnx SenseVoice).
POST /transcribe — multipart audio (wav/webm/ogg) → { "text": "..." }
GET /health — readiness probe
"""
from __future__ import annotations

import io
import os
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import sherpa_onnx
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse

MODEL_DIR = Path(os.environ.get("ASR_MODEL_DIR", "/models/sense-voice"))
PORT = int(os.environ.get("ASR_PORT", "8080"))
MAX_BYTES = int(os.environ.get("ASR_MAX_BYTES", str(8 * 1024 * 1024)))

app = FastAPI(title="LianYu ASR", version="1.0.0")
_recognizer: sherpa_onnx.OfflineRecognizer | None = None


def _find_model_file() -> str:
    candidates = list(MODEL_DIR.rglob("model.onnx"))
    if not candidates:
        candidates = list(MODEL_DIR.rglob("*.onnx"))
    if not candidates:
        raise FileNotFoundError(f"No onnx model under {MODEL_DIR}")
    # Prefer smallest path depth named model.onnx
    candidates.sort(key=lambda p: (p.name != "model.onnx", len(p.parts)))
    return str(candidates[0])


def _load_recognizer() -> sherpa_onnx.OfflineRecognizer:
    global _recognizer
    if _recognizer is not None:
        return _recognizer
    model_path = _find_model_file()
    tokens_path = MODEL_DIR / "tokens.txt"
    if not tokens_path.is_file():
        found = list(MODEL_DIR.rglob("tokens.txt"))
        if not found:
            raise FileNotFoundError(f"tokens.txt not found under {MODEL_DIR}")
        tokens_path = found[0]
    _recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model_path),
        tokens=str(tokens_path),
        num_threads=int(os.environ.get("ASR_THREADS", "2")),
        use_itn=True,
        debug=False,
    )
    return _recognizer


def _to_wav_path(raw: bytes, suffix: str) -> str:
    suffix = (suffix or ".webm").lower()
    if suffix not in {".wav", ".webm", ".ogg", ".opus", ".mp3", ".m4a"}:
        suffix = ".webm"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as src:
        src.write(raw)
        src_path = src.name
    if suffix == ".wav":
        return src_path
    dst = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    dst.close()
    try:
        subprocess.run(
            [
                "ffmpeg", "-y", "-i", src_path,
                "-ar", "16000", "-ac", "1", "-f", "wav", dst.name,
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    finally:
        try:
            os.unlink(src_path)
        except OSError:
            pass
    return dst.name


def _read_wav(path: str) -> tuple[np.ndarray, int]:
    import wave

    with wave.open(path, "rb") as wf:
        if wf.getnchannels() != 1 or wf.getsampwidth() != 2:
            raise HTTPException(status_code=400, detail="audio must be mono 16-bit PCM after conversion")
        sample_rate = wf.getframerate()
        frames = wf.readframes(wf.getnframes())
    samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    return samples, sample_rate


@app.on_event("startup")
def startup() -> None:
    _load_recognizer()


@app.get("/health")
def health() -> JSONResponse:
    try:
        _load_recognizer()
        return JSONResponse({"status": "ok"})
    except Exception as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)) -> JSONResponse:
    if not file.filename:
        raise HTTPException(status_code=400, detail="missing file")
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty audio")
    if len(raw) > MAX_BYTES:
        raise HTTPException(status_code=400, detail="audio too large")

    suffix = Path(file.filename).suffix.lower()
    wav_path = None
    try:
        wav_path = _to_wav_path(raw, suffix)
        samples, sample_rate = _read_wav(wav_path)
        if samples.size == 0:
            return JSONResponse({"text": ""})
        recognizer = _load_recognizer()
        stream = recognizer.create_stream()
        stream.accept_waveform(sample_rate, samples)
        recognizer.decode_stream(stream)
        text = (stream.result.text or "").strip()
        return JSONResponse({"text": text})
    except HTTPException:
        raise
    except subprocess.CalledProcessError as exc:
        raise HTTPException(status_code=400, detail="unsupported or corrupt audio") from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail="asr failed") from exc
    finally:
        if wav_path:
            try:
                os.unlink(wav_path)
            except OSError:
                pass


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=PORT)

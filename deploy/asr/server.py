"""
LianYu ASR service (sherpa-onnx).

- POST /transcribe — multipart audio → SenseVoice offline final { "text": "..." }
- POST /transcribe/pcm — raw int16 LE PCM @ 16k mono → SenseVoice final
- WS /stream — feed PCM frames → Zipformer online partial / endpoint final
- GET /health — readiness (both engines when streaming enabled)
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import sherpa_onnx
from fastapi import FastAPI, File, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

SENSE_DIR = Path(os.environ.get("ASR_MODEL_DIR", "/models/sense-voice"))
ZIPFORMER_DIR = Path(os.environ.get("ASR_STREAM_MODEL_DIR", "/models/zipformer-zh"))
PORT = int(os.environ.get("ASR_PORT", "8080"))
MAX_BYTES = int(os.environ.get("ASR_MAX_BYTES", str(8 * 1024 * 1024)))
SAMPLE_RATE = 16000
STREAM_ENABLED = os.environ.get("ASR_STREAM_ENABLED", "true").lower() not in {"0", "false", "no"}

app = FastAPI(title="LianYu ASR", version="2.0.0")
_offline: sherpa_onnx.OfflineRecognizer | None = None
_online: sherpa_onnx.OnlineRecognizer | None = None


def _find_under(root: Path, name: str) -> Path | None:
    if not root.is_dir():
        return None
    direct = root / name
    if direct.is_file():
        return direct
    found = list(root.rglob(name))
    return found[0] if found else None


def _find_model_onnx(root: Path) -> str:
    candidates = list(root.rglob("model.onnx"))
    if not candidates:
        candidates = list(root.rglob("*.onnx"))
    if not candidates:
        raise FileNotFoundError(f"No onnx model under {root}")
    candidates.sort(key=lambda p: (p.name != "model.onnx", len(p.parts)))
    return str(candidates[0])


def _load_offline() -> sherpa_onnx.OfflineRecognizer:
    global _offline
    if _offline is not None:
        return _offline
    model_path = _find_model_onnx(SENSE_DIR)
    tokens_path = _find_under(SENSE_DIR, "tokens.txt")
    if tokens_path is None:
        raise FileNotFoundError(f"tokens.txt not found under {SENSE_DIR}")
    _offline = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model_path),
        tokens=str(tokens_path),
        num_threads=int(os.environ.get("ASR_THREADS", "2")),
        language=os.environ.get("ASR_LANGUAGE", "zh"),
        use_itn=True,
        debug=False,
    )
    return _offline


def _pick_encoder(root: Path) -> Path:
    for name in (
        "encoder-epoch-99-avg-1.int8.onnx",
        "encoder-epoch-99-avg-1.onnx",
        "encoder.int8.onnx",
        "encoder.onnx",
    ):
        p = _find_under(root, name)
        if p is not None:
            return p
    encoders = list(root.rglob("encoder*.onnx"))
    if not encoders:
        raise FileNotFoundError(f"No encoder*.onnx under {root}")
    encoders.sort(key=lambda p: ("int8" not in p.name, len(str(p))))
    return encoders[0]


def _pick_decoder(root: Path) -> Path:
    for name in ("decoder-epoch-99-avg-1.onnx", "decoder.onnx"):
        p = _find_under(root, name)
        if p is not None:
            return p
    found = list(root.rglob("decoder*.onnx"))
    if not found:
        raise FileNotFoundError(f"No decoder*.onnx under {root}")
    return found[0]


def _pick_joiner(root: Path) -> Path:
    for name in (
        "joiner-epoch-99-avg-1.int8.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "joiner.int8.onnx",
        "joiner.onnx",
    ):
        p = _find_under(root, name)
        if p is not None:
            return p
    found = list(root.rglob("joiner*.onnx"))
    if not found:
        raise FileNotFoundError(f"No joiner*.onnx under {root}")
    found.sort(key=lambda p: ("int8" not in p.name, len(str(p))))
    return found[0]


def _load_online() -> sherpa_onnx.OnlineRecognizer:
    global _online
    if _online is not None:
        return _online
    tokens = _find_under(ZIPFORMER_DIR, "tokens.txt")
    if tokens is None:
        raise FileNotFoundError(f"tokens.txt not found under {ZIPFORMER_DIR}")
    encoder = _pick_encoder(ZIPFORMER_DIR)
    decoder = _pick_decoder(ZIPFORMER_DIR)
    joiner = _pick_joiner(ZIPFORMER_DIR)
    _online = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(tokens),
        encoder=str(encoder),
        decoder=str(decoder),
        joiner=str(joiner),
        num_threads=int(os.environ.get("ASR_STREAM_THREADS", os.environ.get("ASR_THREADS", "2"))),
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method="greedy_search",
        enable_endpoint_detection=True,
        rule1_min_trailing_silence=float(os.environ.get("ASR_EP_RULE1", "2.4")),
        rule2_min_trailing_silence=float(os.environ.get("ASR_EP_RULE2", "1.2")),
        rule3_min_utterance_length=float(os.environ.get("ASR_EP_RULE3", "20")),
        provider="cpu",
    )
    return _online


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


def _pcm16_to_float(raw: bytes) -> np.ndarray:
    if len(raw) < 2:
        return np.zeros(0, dtype=np.float32)
    if len(raw) % 2 == 1:
        raw = raw[:-1]
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


def _sense_transcribe(samples: np.ndarray, sample_rate: int) -> str:
    if samples.size == 0:
        return ""
    recognizer = _load_offline()
    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples)
    recognizer.decode_stream(stream)
    return (stream.result.text or "").strip()


@app.on_event("startup")
def startup() -> None:
    _load_offline()
    if STREAM_ENABLED:
        try:
            _load_online()
        except Exception as exc:
            # Allow HTTP final-only boot if stream model missing in local smoke.
            print(f"[asr] streaming Zipformer not loaded: {exc}")


@app.get("/health")
def health() -> JSONResponse:
    try:
        _load_offline()
        stream_ok = False
        stream_err = None
        if STREAM_ENABLED:
            try:
                _load_online()
                stream_ok = True
            except Exception as exc:
                stream_err = str(exc)
        body = {
            "status": "ok" if (not STREAM_ENABLED or stream_ok) else "degraded",
            "senseVoice": True,
            "zipformer": stream_ok,
        }
        if stream_err:
            body["zipformerError"] = stream_err
        if STREAM_ENABLED and not stream_ok:
            raise HTTPException(status_code=503, detail=body)
        return JSONResponse(body)
    except HTTPException:
        raise
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
        text = _sense_transcribe(samples, sample_rate)
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


@app.post("/transcribe/pcm")
async def transcribe_pcm(file: UploadFile = File(...)) -> JSONResponse:
    """SenseVoice final on raw int16 LE PCM @ 16 kHz mono (no ffmpeg)."""
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty audio")
    if len(raw) > MAX_BYTES:
        raise HTTPException(status_code=400, detail="audio too large")
    try:
        samples = _pcm16_to_float(raw)
        text = _sense_transcribe(samples, SAMPLE_RATE)
        return JSONResponse({"text": text})
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail="asr failed") from exc


@app.websocket("/stream")
async def stream_asr(ws: WebSocket) -> None:
    await ws.accept()
    if not STREAM_ENABLED:
        await ws.send_text(json.dumps({"type": "error", "message": "streaming disabled"}))
        await ws.close()
        return
    try:
        recognizer = _load_online()
    except Exception:
        await ws.send_text(json.dumps({"type": "error", "message": "streaming model unavailable"}))
        await ws.close()
        return

    stream = recognizer.create_stream()
    last_text = ""
    pcm_buf = bytearray()
    max_session_bytes = MAX_BYTES * 2

    async def emit(event: dict) -> None:
        await ws.send_text(json.dumps(event, ensure_ascii=False))

    try:
        await emit({"type": "ready", "sampleRate": SAMPLE_RATE})
        while True:
            msg = await ws.receive()
            if msg.get("type") == "websocket.disconnect":
                break
            if "text" in msg and msg["text"] is not None:
                try:
                    ctrl = json.loads(msg["text"])
                except json.JSONDecodeError:
                    continue
                kind = ctrl.get("type")
                if kind in {"end", "finish", "flush"}:
                    # Tail padding so Zipformer finishes the last chunk.
                    pad = np.zeros(int(0.4 * SAMPLE_RATE), dtype=np.float32)
                    stream.accept_waveform(SAMPLE_RATE, pad)
                    stream.input_finished()
                    while recognizer.is_ready(stream):
                        recognizer.decode_stream(stream)
                    text = (recognizer.get_result(stream) or "").strip()
                    if text:
                        await emit({"type": "final", "text": text})
                    await emit({"type": "closed"})
                    break
                if kind == "reset":
                    stream = recognizer.create_stream()
                    last_text = ""
                    pcm_buf.clear()
                    await emit({"type": "reset"})
                continue

            data = msg.get("bytes")
            if not data:
                continue
            if len(pcm_buf) + len(data) > max_session_bytes:
                await emit({"type": "error", "message": "session audio too large"})
                break
            pcm_buf.extend(data)
            samples = _pcm16_to_float(bytes(data))
            if samples.size == 0:
                continue
            stream.accept_waveform(SAMPLE_RATE, samples)
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
            text = (recognizer.get_result(stream) or "").strip()
            if text and text != last_text:
                last_text = text
                await emit({"type": "partial", "text": text})
            if recognizer.is_endpoint(stream):
                final_text = last_text
                await emit({"type": "final", "text": final_text})
                # Reset for next utterance; keep connection.
                recognizer.reset(stream)
                last_text = ""
                # Keep a short PCM window for optional SenseVoice re-decode upstream.
                pcm_buf.clear()
                await emit({"type": "endpoint"})
    except WebSocketDisconnect:
        return
    except Exception:
        try:
            await emit({"type": "error", "message": "stream failed"})
        except Exception:
            pass
    finally:
        try:
            await ws.close()
        except Exception:
            pass


if __name__ == "__main__":
    import uvicorn

    # websockets needed for /stream
    uvicorn.run(app, host="0.0.0.0", port=PORT)

"""
YuNian ASR service (sherpa-onnx).

- POST /transcribe — multipart audio → SenseVoice offline final { "text": "..." }
- POST /transcribe/pcm — raw int16 LE PCM @ 16k mono → SenseVoice final
- WS /stream — feed PCM frames → Zipformer online partial / endpoint final
- GET /health — readiness (both engines when streaming enabled)
"""
from __future__ import annotations

import asyncio
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

app = FastAPI(title="YuNian ASR", version="2.0.0")
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
    """Prefer int8 SenseVoice (~229MB) over fp32 (~895MB)."""
    for name in ("model.int8.onnx", "model.onnx"):
        found = list(root.rglob(name))
        if found:
            found.sort(key=lambda p: len(p.parts))
            return str(found[0])
    candidates = list(root.rglob("*.onnx"))
    if not candidates:
        raise FileNotFoundError(f"No onnx model under {root}")
    candidates.sort(key=lambda p: ("int8" not in p.name, len(p.parts)))
    return str(candidates[0])


def _load_offline() -> sherpa_onnx.OfflineRecognizer:
    global _offline
    if _offline is not None:
        return _offline
    model_path = _find_model_onnx(SENSE_DIR)
    tokens_path = _find_under(SENSE_DIR, "tokens.txt")
    if tokens_path is None:
        raise FileNotFoundError(f"tokens.txt not found under {SENSE_DIR}")
    threads = int(os.environ.get("ASR_THREADS", "2"))
    _offline = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model_path),
        tokens=str(tokens_path),
        num_threads=threads,
        language=os.environ.get("ASR_LANGUAGE", "zh"),
        use_itn=True,
        debug=False,
    )
    print(f"[asr] SenseVoice model={model_path} threads={threads}", flush=True)
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
    for name in (
        "decoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "decoder.int8.onnx",
        "decoder.onnx",
    ):
        p = _find_under(root, name)
        if p is not None:
            return p
    found = list(root.rglob("decoder*.onnx"))
    if not found:
        raise FileNotFoundError(f"No decoder*.onnx under {root}")
    found.sort(key=lambda p: ("int8" not in p.name, len(str(p))))
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
    stream_threads = int(os.environ.get("ASR_STREAM_THREADS", os.environ.get("ASR_THREADS", "2")))
    _online = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(tokens),
        encoder=str(encoder),
        decoder=str(decoder),
        joiner=str(joiner),
        num_threads=stream_threads,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method="greedy_search",
        enable_endpoint_detection=True,
        rule1_min_trailing_silence=float(os.environ.get("ASR_EP_RULE1", "2.4")),
        rule2_min_trailing_silence=float(os.environ.get("ASR_EP_RULE2", "1.2")),
        rule3_min_utterance_length=float(os.environ.get("ASR_EP_RULE3", "20")),
        provider="cpu",
    )
    print(
        f"[asr] Zipformer encoder={encoder} decoder={decoder} joiner={joiner} "
        f"threads={stream_threads}",
        flush=True,
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


# ── 二次解码（final re-pass）──
# 流式 Zipformer 是 greedy transducer：没有语言模型、会重复吐字、结尾常丢音。
# 同一句音频再交给离线 SenseVoice 解一遍，精度高得多（非自回归，几十到一两百毫秒）。
# 语音通话真正送给模型的是**这句定稿文本**，所以这里值得多花这点时间。
FINAL_REPASS = os.environ.get("ASR_FINAL_REPASS", "true").lower() not in {"0", "false", "no"}
UTT_MAX_SECONDS = float(os.environ.get("ASR_UTT_MAX_SECONDS", "30"))


def _sense_final(pcm: bytes) -> str:
    """把一整句 PCM 用 SenseVoice 重解；失败返回空串（绝不影响主链路）。"""
    if not pcm:
        return ""
    try:
        return _sense_transcribe(_pcm16_to_float(pcm), SAMPLE_RATE).strip()
    except Exception as exc:  # noqa: BLE001
        print(f"[asr] sense repass failed: {exc}", flush=True)
        return ""


def _pick_final(zip_text: str, sense_text: str) -> str:
    """二次解码结果优先，流式结果只兜底。

    为什么不是"取更长的那句"：Zipformer 的重复吐字（如「赫尔赫尔」）会让它更长，
    按长度取胜劣币驱逐良币。只在 SenseVoice 明显可疑（空/极短/不到流式一半）时才回退。
    """
    z = (zip_text or "").strip()
    s = (sense_text or "").strip()
    if not s:
        return z
    if not z:
        return s
    if len(s) < 2 or len(s) * 2 < len(z):
        return z
    return s


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
    # 当前这一句的音频：commit / endpoint 时用来做 SenseVoice 二次解码。
    # 和 pcm_buf 分开是因为 pcm_buf 是"整个会话"的上限控制，utt_pcm 只留这一句。
    utt_pcm = bytearray()
    utt_max_bytes = int(UTT_MAX_SECONDS * SAMPLE_RATE * 2)
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
                if kind == "commit":
                    # 「现在这句说完了，请给我定稿」——由客户端 VAD 触发。
                    #
                    # 为什么需要它：客户端 VAD（750ms 静音）总比引擎自己的端点规则
                    # （rule2=1.2s）先到。原先客户端一到就直接拿**未定稿的中间结果**
                    # 去提问，尾巴上的字常常还没解出来，就成了「什么赫赫尔」那种残句。
                    # 现在改成：客户端说完了 → 这里立刻收尾定稿 → 再回 endpoint。
                    pad = np.zeros(int(0.3 * SAMPLE_RATE), dtype=np.float32)
                    stream.accept_waveform(SAMPLE_RATE, pad)
                    while recognizer.is_ready(stream):
                        recognizer.decode_stream(stream)
                    zip_text = (recognizer.get_result(stream) or "").strip() or last_text
                    sense_text = await asyncio.to_thread(_sense_final, bytes(utt_pcm)) if FINAL_REPASS else ""
                    final_text = _pick_final(zip_text, sense_text)
                    print(
                        f"[asr] commit zip={zip_text!r} sense={sense_text!r} pick={final_text!r}",
                        flush=True,
                    )
                    if final_text:
                        await emit({
                            "type": "final",
                            "text": final_text,
                            "zipformer": zip_text,
                            "sense": sense_text,
                        })
                    recognizer.reset(stream)
                    last_text = ""
                    pcm_buf.clear()
                    utt_pcm.clear()
                    await emit({"type": "endpoint"})
                    continue
                if kind == "reset":
                    stream = recognizer.create_stream()
                    last_text = ""
                    pcm_buf.clear()
                    utt_pcm.clear()
                    await emit({"type": "reset"})
                continue

            data = msg.get("bytes")
            if not data:
                continue
            if len(pcm_buf) + len(data) > max_session_bytes:
                await emit({"type": "error", "message": "session audio too large"})
                break
            pcm_buf.extend(data)
            if len(utt_pcm) < utt_max_bytes:
                utt_pcm.extend(data)
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
                # 引擎自己判到端点了：同样先二次解码再定稿（与 commit 一致的取值口径）
                zip_text = (recognizer.get_result(stream) or "").strip() or last_text
                sense_text = await asyncio.to_thread(_sense_final, bytes(utt_pcm)) if FINAL_REPASS else ""
                final_text = _pick_final(zip_text, sense_text)
                print(
                    f"[asr] endpoint zip={zip_text!r} sense={sense_text!r} pick={final_text!r}",
                    flush=True,
                )
                if final_text:
                    await emit({
                        "type": "final",
                        "text": final_text,
                        "zipformer": zip_text,
                        "sense": sense_text,
                    })
                # Reset for next utterance; keep connection.
                recognizer.reset(stream)
                last_text = ""
                pcm_buf.clear()
                utt_pcm.clear()
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

"""FireRedASR2S HTTP service for gomob voice-message transcription."""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
import wave
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse


def env_bool(key: str, default: bool) -> bool:
    value = os.getenv(key)
    if value is None or value == "":
        return default
    return value.lower() in {"1", "true", "yes", "on"}


def env_int(key: str, default: int) -> int:
    value = os.getenv(key)
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def env_float(key: str, default: float) -> float:
    value = os.getenv(key)
    if not value:
        return default
    try:
        return float(value)
    except ValueError:
        return default


def require_dir(key: str, default: str | None = None) -> str:
    value = os.getenv(key) or default
    if not value:
        raise RuntimeError(f"{key} 未配置")
    path = Path(value).expanduser().resolve()
    if not path.is_dir():
        raise RuntimeError(f"{key} 指向的目录不存在: {path}")
    return str(path)


def wav_duration_seconds(path: Path) -> float:
    with wave.open(str(path), "rb") as audio:
        frames = audio.getnframes()
        rate = audio.getframerate()
        return frames / float(rate)


def convert_to_wav16k(input_path: Path, output_path: Path) -> None:
    ffmpeg = shutil.which(os.getenv("GOMOB_FFMPEG_BIN", "ffmpeg"))
    if not ffmpeg:
        try:
            import imageio_ffmpeg

            ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        except Exception:
            ffmpeg = None
    if not ffmpeg:
        raise RuntimeError("ffmpeg 未安装，无法把 App 录音转成 FireRedASR2S 要求的 16kHz 单声道 wav")
    cmd = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(input_path),
        "-ar",
        "16000",
        "-ac",
        "1",
        "-acodec",
        "pcm_s16le",
        "-f",
        "wav",
        str(output_path),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "ffmpeg 转码失败").strip()
        raise RuntimeError(detail)


def build_firered_system() -> Any:
    repo_dir = os.getenv("GOMOB_FIRERED_ASR2S_REPO")
    if repo_dir:
        sys.path.insert(0, str(Path(repo_dir).expanduser().resolve()))

    from fireredasr2s import FireRedAsr2System, FireRedAsr2SystemConfig
    from fireredasr2s.fireredasr2 import FireRedAsr2Config
    from fireredasr2s.fireredlid import FireRedLidConfig
    from fireredasr2s.fireredpunc import FireRedPuncConfig
    from fireredasr2s.fireredvad import FireRedVadConfig

    asr_type = os.getenv("GOMOB_FIRERED_ASR_TYPE", "aed")
    if asr_type not in {"aed", "llm"}:
        raise RuntimeError("GOMOB_FIRERED_ASR_TYPE 只支持 aed 或 llm")

    default_root = os.getenv("GOMOB_FIRERED_MODEL_ROOT", "pretrained_models")
    vad_dir = require_dir("GOMOB_FIRERED_VAD_DIR", f"{default_root}/FireRedVAD/VAD")
    lid_dir = require_dir("GOMOB_FIRERED_LID_DIR", f"{default_root}/FireRedLID")
    punc_dir = require_dir("GOMOB_FIRERED_PUNC_DIR", f"{default_root}/FireRedPunc")
    asr_default = "FireRedASR2-AED" if asr_type == "aed" else "FireRedASR2-LLM"
    asr_dir = require_dir("GOMOB_FIRERED_ASR_MODEL_DIR", f"{default_root}/{asr_default}")

    vad_config = FireRedVadConfig(
        use_gpu=env_bool("GOMOB_FIRERED_VAD_USE_GPU", False),
        smooth_window_size=env_int("GOMOB_FIRERED_VAD_SMOOTH_WINDOW", 5),
        speech_threshold=env_float("GOMOB_FIRERED_VAD_SPEECH_THRESHOLD", 0.4),
        min_speech_frame=env_int("GOMOB_FIRERED_VAD_MIN_SPEECH_FRAME", 20),
        max_speech_frame=env_int("GOMOB_FIRERED_VAD_MAX_SPEECH_FRAME", 6000),
        min_silence_frame=env_int("GOMOB_FIRERED_VAD_MIN_SILENCE_FRAME", 20),
        merge_silence_frame=env_int("GOMOB_FIRERED_VAD_MERGE_SILENCE_FRAME", 0),
        extend_speech_frame=env_int("GOMOB_FIRERED_VAD_EXTEND_SPEECH_FRAME", 0),
        chunk_max_frame=env_int("GOMOB_FIRERED_VAD_CHUNK_MAX_FRAME", 30000),
    )
    lid_config = FireRedLidConfig(
        use_gpu=env_bool("GOMOB_FIRERED_LID_USE_GPU", True),
        use_half=env_bool("GOMOB_FIRERED_LID_USE_HALF", False),
    )
    asr_config = FireRedAsr2Config(
        use_gpu=env_bool("GOMOB_FIRERED_ASR_USE_GPU", True),
        use_half=env_bool("GOMOB_FIRERED_ASR_USE_HALF", False),
        beam_size=env_int("GOMOB_FIRERED_ASR_BEAM_SIZE", 3),
        nbest=1,
        decode_max_len=0,
        softmax_smoothing=env_float("GOMOB_FIRERED_ASR_SOFTMAX_SMOOTHING", 1.25),
        aed_length_penalty=env_float("GOMOB_FIRERED_ASR_AED_LENGTH_PENALTY", 0.6),
        eos_penalty=env_float("GOMOB_FIRERED_ASR_EOS_PENALTY", 1.0),
        return_timestamp=True,
    )
    punc_config = FireRedPuncConfig(
        use_gpu=env_bool("GOMOB_FIRERED_PUNC_USE_GPU", True),
        sentence_max_length=env_int("GOMOB_FIRERED_PUNC_SENTENCE_MAX_LENGTH", -1),
    )

    config = FireRedAsr2SystemConfig(
        vad_dir,
        lid_dir,
        asr_type,
        asr_dir,
        punc_dir,
        vad_config,
        lid_config,
        asr_config,
        punc_config,
        asr_batch_size=1,
        punc_batch_size=1,
        enable_vad=env_bool("GOMOB_FIRERED_ENABLE_VAD", True),
        enable_lid=env_bool("GOMOB_FIRERED_ENABLE_LID", True),
        enable_punc=env_bool("GOMOB_FIRERED_ENABLE_PUNC", True),
    )
    return FireRedAsr2System(config)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.asr_system = build_firered_system()
    app.state.asr_lock = asyncio.Lock()
    yield


app = FastAPI(title="gomob FireRedASR2 service", version="0.1.0", lifespan=lifespan)


@app.get("/healthz")
def healthz() -> dict[str, Any]:
    return {
        "ok": True,
        "engine": "fireredasr2",
        "model": os.getenv("GOMOB_FIRERED_ASR_TYPE", "aed"),
    }


@app.post("/v1/asr/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    engine: str = Form("fireredasr2"),
    model: str = Form("FireRedASR2-AED"),
    language: str = Form("zh"),
    asset_id: str = Form(""),
    object_key: str = Form(""),
) -> JSONResponse:
    if engine and engine != "fireredasr2":
        raise HTTPException(status_code=400, detail="当前 ASR 服务只支持 fireredasr2")

    max_seconds = env_float("GOMOB_ASR_MAX_AUDIO_SECONDS", 60.0)
    utterance_id = f"asset_{asset_id or 'unknown'}_{uuid.uuid4().hex[:10]}"

    with tempfile.TemporaryDirectory(prefix="gomob-asr-") as tmp:
        tmp_dir = Path(tmp)
        raw_path = tmp_dir / "input.bin"
        wav_path = tmp_dir / "input.wav"
        raw_path.write_bytes(await file.read())

        try:
            convert_to_wav16k(raw_path, wav_path)
            duration = wav_duration_seconds(wav_path)
            if duration > max_seconds:
                raise HTTPException(
                    status_code=400,
                    detail=f"音频过长: {duration:.1f}s，当前模型限制 {max_seconds:.1f}s",
                )
            async with app.state.asr_lock:
                result = await asyncio.to_thread(app.state.asr_system.process, str(wav_path), utterance_id)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    sentences = result.get("sentences") or []
    confidence = average_confidence(sentences)
    text = (result.get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=422, detail="ASR 未识别出有效文本")

    response = {
        "text": text,
        "normalized_text": text,
        "segments": normalize_segments(sentences, result.get("words") or []),
        "confidence": confidence,
        "engine": "fireredasr2",
        "model": model or "FireRedASR2-AED",
        "language": detected_language(sentences) or language or "zh",
        "meta": {
            "asset_id": asset_id,
            "object_key": object_key,
            "dur_s": result.get("dur_s"),
            "vad_segments_ms": result.get("vad_segments_ms") or [],
        },
    }
    return JSONResponse(response)


def average_confidence(sentences: list[dict[str, Any]]) -> float | None:
    values = [
        float(item["asr_confidence"])
        for item in sentences
        if item.get("asr_confidence") is not None
    ]
    if not values:
        return None
    return round(sum(values) / len(values), 4)


def detected_language(sentences: list[dict[str, Any]]) -> str | None:
    for item in sentences:
        lang = item.get("lang")
        if lang:
            return str(lang)
    return None


def normalize_segments(sentences: list[dict[str, Any]], words: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "start_ms": int(item.get("start_ms", 0)),
            "end_ms": int(item.get("end_ms", 0)),
            "text": item.get("text", ""),
            "confidence": item.get("asr_confidence"),
            "language": item.get("lang"),
            "words": words_in_sentence(words, item),
        }
        for item in sentences
    ]


def words_in_sentence(words: list[dict[str, Any]], sentence: dict[str, Any]) -> list[dict[str, Any]]:
    start = int(sentence.get("start_ms", 0))
    end = int(sentence.get("end_ms", 0))
    return [
        {
            "start_ms": int(word.get("start_ms", 0)),
            "end_ms": int(word.get("end_ms", 0)),
            "text": word.get("text", ""),
        }
        for word in words
        if start <= int(word.get("start_ms", 0)) <= end
    ]


def main() -> None:
    import uvicorn

    host = os.getenv("GOMOB_ASR_HOST", "0.0.0.0")
    port = env_int("GOMOB_ASR_PORT", 18091)
    uvicorn.run("app:app", host=host, port=port, reload=False)


if __name__ == "__main__":
    main()

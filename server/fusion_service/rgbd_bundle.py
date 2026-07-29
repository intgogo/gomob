"""schema_version=2 原始 RGBD bundle 的唯一服务端契约。"""
from __future__ import annotations

import hashlib
import io
import json
import re
import zipfile
from typing import List, Optional

import numpy as np
from PIL import Image

from fusion_core import Intrinsic, RgbdFrame
from vin_calibration import CALIBRATION_SIZE, align_raw_rgbd, parse_calibration


SCHEMA_VERSION = 2
CALIBRATION_NAME = "calibration.bin"
CALIBRATION_FORMAT = "vin_creator_v3"
DEPTH_ENCODING = "vin_creator_disparity_u16"
MAX_SYNC_DELTA_US = 15_000
DEVICE_ID = re.compile(r"^[A-Z0-9_-]+$")
SESSION_KEY = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
MAX_SHOTS = 32
MAX_RGB_PNG_BYTES = 32 * 1024 * 1024

MANIFEST_KEYS = {"schema_version", "session_key", "calibration", "source", "shots"}
CALIBRATION_KEYS = {
    "format", "depth_device_id", "color_device_id", "depth_profile", "color_profile", "sha256",
}
SOURCE_KEYS = {"depth_width", "depth_height", "depth_encoding", "color_width", "color_height"}
SHOT_KEYS = {"index", "rgb", "depth", "conf", "color_timestamp_us", "depth_timestamp_us"}


def _required(obj: dict, key: str, kind: type):
    if key not in obj or type(obj[key]) is not kind:
        raise ValueError(f"manifest.{key} 缺失或类型错误")
    return obj[key]


def _exact_keys(obj: dict, expected: set[str], field: str) -> None:
    actual = set(obj)
    if actual != expected:
        raise ValueError(f"{field} 字段集合不符合唯一契约：missing={sorted(expected - actual)} extra={sorted(actual - expected)}")


def _normalize_device_id(value: str, field: str) -> str:
    normalized = value.strip().upper()
    if not DEVICE_ID.fullmatch(normalized) or ".." in normalized:
        raise ValueError(f"{field} 非法：{value!r}")
    return normalized


def _strict_zip(data: bytes) -> tuple[zipfile.ZipFile, set[str]]:
    try:
        archive = zipfile.ZipFile(io.BytesIO(data), "r")
    except zipfile.BadZipFile as exc:
        raise ValueError("bundle 不是合法 zip") from exc
    infos = archive.infolist()
    names = [item.filename for item in infos]
    if len(names) != len(set(names)):
        archive.close()
        raise ValueError("zip 含重复 entry")
    if any(item.is_dir() or "/" in item.filename or "\\" in item.filename or item.filename in ("", ".", "..") for item in infos):
        archive.close()
        raise ValueError("zip entry 必须全部位于根目录")
    if names.count(CALIBRATION_NAME) != 1:
        archive.close()
        raise ValueError("zip 根目录必须恰好存在一个 calibration.bin")
    return archive, set(names)


def unpack(data: bytes) -> List[RgbdFrame]:
    """校验并把原始 disparity/RGB 投影成 Depth 网格上的 RgbdFrame。"""
    archive, names = _strict_zip(data)
    with archive as z:
        if "manifest.json" not in names:
            raise ValueError("缺少 manifest.json")
        if z.getinfo("manifest.json").file_size > 2 * 1024 * 1024:
            raise ValueError("manifest.json 过大")
        try:
            manifest = json.loads(z.read("manifest.json"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ValueError("manifest.json 不是合法 UTF-8 JSON") from exc
        if type(manifest) is not dict:
            raise ValueError("manifest.json 根节点不是对象")
        _exact_keys(manifest, MANIFEST_KEYS, "manifest")
        if _required(manifest, "schema_version", int) != SCHEMA_VERSION:
            raise ValueError(f"只接受 schema_version={SCHEMA_VERSION}")
        session_key = _required(manifest, "session_key", str).strip()
        if not SESSION_KEY.fullmatch(session_key):
            raise ValueError("session_key 非法")
        cal_meta = _required(manifest, "calibration", dict)
        source = _required(manifest, "source", dict)
        shots = _required(manifest, "shots", list)
        _exact_keys(cal_meta, CALIBRATION_KEYS, "manifest.calibration")
        _exact_keys(source, SOURCE_KEYS, "manifest.source")
        if not shots or len(shots) > MAX_SHOTS:
            raise ValueError(f"shots 数量必须在 1..{MAX_SHOTS}")

        if cal_meta.get("format") != CALIBRATION_FORMAT:
            raise ValueError("仅支持 vin_creator_v3 标定")
        depth_id = _normalize_device_id(_required(cal_meta, "depth_device_id", str), "depth_device_id")
        _normalize_device_id(_required(cal_meta, "color_device_id", str), "color_device_id")
        expected_sha = _required(cal_meta, "sha256", str).lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
            raise ValueError("calibration.sha256 非法")
        if z.getinfo(CALIBRATION_NAME).file_size != CALIBRATION_SIZE:
            raise ValueError(f"calibration.bin 大小必须为 {CALIBRATION_SIZE} bytes")
        cal_blob = z.read(CALIBRATION_NAME)
        actual_sha = hashlib.sha256(cal_blob).hexdigest()
        if actual_sha != expected_sha:
            raise ValueError(f"calibration.bin SHA-256 不匹配：got={actual_sha} want={expected_sha}")
        calibration = parse_calibration(cal_blob, actual_sha)
        if calibration.depth_device_id != depth_id:
            raise ValueError(f"BIN Depth 序列号 {calibration.depth_device_id} 与 manifest {depth_id} 不一致")

        dw = int(_required(source, "depth_width", int))
        dh = int(_required(source, "depth_height", int))
        cw = int(_required(source, "color_width", int))
        ch = int(_required(source, "color_height", int))
        if (dw, dh) != (640, 128) or (cw, ch) != (4160, 832):
            raise ValueError("vin_creator_v3 仅接受 RS-D550 640x128 mode25 + HLSD8 4160x832")
        if source.get("depth_encoding") != DEPTH_ENCODING:
            raise ValueError("depth_encoding 不是 VINCreator 原始 disparity u16")
        if cal_meta.get("depth_profile") != f"{dw}x{dh}_mode25":
            raise ValueError("calibration.depth_profile 与 source 尺寸不一致")
        if cal_meta.get("color_profile") != f"{cw}x{ch}":
            raise ValueError("calibration.color_profile 与 source 尺寸不一致")
        fx, fy, cx, cy = calibration.depth_intrinsics(dw, dh)
        intr = Intrinsic(dw, dh, fx, fy, cx, cy)

        expected_names = {"manifest.json", CALIBRATION_NAME}
        frames: List[RgbdFrame] = []
        for expected_index, shot in enumerate(shots):
            if type(shot) is not dict:
                raise ValueError(f"shots[{expected_index}] 不是对象")
            _exact_keys(shot, SHOT_KEYS, f"manifest.shots[{expected_index}]")
            if _required(shot, "index", int) != expected_index:
                raise ValueError("shots.index 必须从 0 连续递增")
            rgb_name = _required(shot, "rgb", str)
            depth_name = _required(shot, "depth", str)
            conf_name = shot["conf"]
            if rgb_name != f"rgb_{expected_index}.png" or depth_name != f"depth_{expected_index}.u16":
                raise ValueError(f"帧 {expected_index} 文件名不符合唯一契约")
            if conf_name is not None and conf_name != f"conf_{expected_index}.u8":
                raise ValueError(f"帧 {expected_index} confidence 文件名不符合唯一契约")
            expected_names.update((rgb_name, depth_name))
            if conf_name is not None:
                expected_names.add(conf_name)
            color_ts = _required(shot, "color_timestamp_us", int)
            depth_ts = _required(shot, "depth_timestamp_us", int)
            if color_ts <= 0 or depth_ts <= 0 or abs(color_ts - depth_ts) > MAX_SYNC_DELTA_US:
                raise ValueError(f"帧 {expected_index} RGB/Depth 时间差超过 {MAX_SYNC_DELTA_US}us")
            missing = [name for name in (rgb_name, depth_name, conf_name) if name is not None and name not in names]
            if missing:
                raise ValueError(f"帧 {expected_index} 缺少文件：{missing}")
            expected_depth_bytes = dw * dh * 2
            if z.getinfo(depth_name).file_size != expected_depth_bytes:
                raise ValueError(f"帧 {expected_index} depth 字节数错误")
            depth_bytes = z.read(depth_name)
            raw = np.frombuffer(depth_bytes, dtype="<u2").reshape(dh, dw)
            try:
                if z.getinfo(rgb_name).file_size > MAX_RGB_PNG_BYTES:
                    raise ValueError(f"帧 {expected_index} RGB PNG 过大")
                rgb_bytes = z.read(rgb_name)
                with Image.open(io.BytesIO(rgb_bytes)) as encoded:
                    if encoded.format != "PNG":
                        raise ValueError(f"帧 {expected_index} RGB 实际编码不是 PNG")
                    if encoded.size != (cw, ch):
                        raise ValueError(f"帧 {expected_index} RGB 实际尺寸 {encoded.size} != {(cw, ch)}")
                    image = encoded.convert("RGB")
                    image.load()
            except Exception as exc:
                if isinstance(exc, ValueError):
                    raise
                raise ValueError(f"帧 {expected_index} RGB PNG 解码失败") from exc
            rgb = np.asarray(image, dtype=np.uint8)
            conf: Optional[np.ndarray] = None
            if conf_name is not None:
                expected_conf_bytes = dw * dh
                if z.getinfo(conf_name).file_size != expected_conf_bytes:
                    raise ValueError(f"帧 {expected_index} confidence 字节数错误")
                conf_bytes = z.read(conf_name)
                conf = np.frombuffer(conf_bytes, dtype=np.uint8).reshape(dh, dw).copy()
            color, depth_mm, aligned_conf = align_raw_rgbd(rgb, raw, calibration, conf)
            frames.append(RgbdFrame(color=color, depth_mm=depth_mm, intr=intr, conf=aligned_conf, mask=None))
        unexpected = names - expected_names
        if unexpected:
            raise ValueError(f"zip 含契约外 entry：{sorted(unexpected)}")
        return frames

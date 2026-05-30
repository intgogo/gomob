#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any


BX_COMMANDS = {
    0x0000: "OpenDevice",
    0x0001: "CloseDevice",
    0x0002: "ResetComponent",
    0x0003: "KeepAlive",
    0x0004: "GetProperty",
    0x0005: "SetProperty",
    0x0006: "OpenStream",
    0x0007: "CloseStream",
    0x000A: "InitUploadFile",
    0x000B: "WriteUploadFile",
    0x000C: "FinishUploadFile",
    0x000D: "DownloadFileChunk",
    0x000E: "StartUsbStreamOrFilePull",
    0x000F: "StopFilePull",
    0x0010: "InitDownloadFile",
    0x0011: "FinishDownloadFile",
}

BX_PROPERTIES = {
    0x0000: "Property0/设备状态探测",
    0x0006: "HostTime/系统时间同步",
    0x0015: "StreamStatus/设备流状态",
    0x0017: "LogMode",
    0x0018: "UserGpio",
    0x0028: "Property40/原厂初始化探测",
    0x002A: "Property42/原厂初始化探测",
    0x002B: "RgbAeBoundary",
    0x002C: "RgbGainBoundary",
    0x002D: "ITofLtc1706Voltage",
    0x0030: "StreamFlagMode/设备流标志",
}

COMPANION_SELECTOR25 = {
    (0x01, 0x02, 0x00): "HV3/Sonix 停止或默认流模式",
    (0x01, 0x02, 0x02): "HV3/Sonix Light IR / 散斑流",
    (0x01, 0x02, 0x03): "HV3/Sonix depth 1280 档",
    (0x01, 0x02, 0x08): "HV3/Sonix depth 640 档",
    (0x01, 0x02, 0x0C): "HV3/Sonix depth 320 档",
    (0x01, 0x0A, None): "HV3/Sonix 初始化/状态命令 0x0a",
    (0x01, 0x15, None): "HV3/Sonix 初始化/状态命令 0x15",
    (0x01, 0x19, None): "HV3/Sonix 初始化/状态命令 0x19",
}

DEFAULT_INPUTS = [
    "native/berxel/host/assets/iHawkP100R3_color_master_xu5_init.json",
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json",
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json",
    ".dev/berxel-host-sdk/vendor-capture/hawk-color-read-control.tsv",
]


@dataclass
class XuRecord:
    source: str
    source_kind: str
    index: int
    frame: str
    t_seconds: str
    bus: str
    device: str
    selector: int | None
    unit: int | None
    interface: int | None
    w_value: int | None
    w_index: int | None
    w_length: int | None
    data: bytes


def parse_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    text = str(value).strip()
    if not text:
        return None
    try:
        return int(text, 0)
    except ValueError:
        return int(float(text))


def clean_hex(text: str) -> str:
    return re.sub(r"[^0-9a-fA-F]", "", text)


def bytes_from_hex(text: str) -> bytes:
    cleaned = clean_hex(text)
    if len(cleaned) % 2:
        cleaned = cleaned[:-1]
    return bytes.fromhex(cleaned)


def display_path(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def le16(data: bytes, offset: int) -> int | None:
    if offset + 2 > len(data):
        return None
    return int.from_bytes(data[offset : offset + 2], "little")


def le32(data: bytes, offset: int) -> int | None:
    if offset + 4 > len(data):
        return None
    return int.from_bytes(data[offset : offset + 4], "little")


def hex_preview(data: bytes, limit: int = 64) -> str:
    text = data[:limit].hex()
    if len(data) > limit:
        return text + "..."
    return text


def load_json_records(path: Path, root: Path) -> list[XuRecord]:
    obj = json.loads(path.read_text(encoding="utf-8"))
    rows = obj.get("init_set_cur") or obj.get("set_cur") or []
    source = display_path(path, root)
    records: list[XuRecord] = []
    for idx, row in enumerate(rows):
        data = bytes_from_hex(row.get("data_hex", ""))
        w_value = parse_int(row.get("wValue") or row.get("w_value"))
        selector = parse_int(row.get("selector"))
        if selector is None and w_value is not None:
            selector = (w_value >> 8) & 0xFF
        w_index = parse_int(row.get("wIndex") or row.get("w_index"))
        unit = parse_int(row.get("unit"))
        interface = parse_int(row.get("interface"))
        if unit is None and w_index is not None:
            unit = (w_index >> 8) & 0xFF
            interface = w_index & 0xFF
        records.append(
            XuRecord(
                source=source,
                source_kind="json",
                index=idx,
                frame=str(row.get("frame", "")),
                t_seconds=str(row.get("t_seconds", "")),
                bus="",
                device="",
                selector=selector,
                unit=unit,
                interface=interface,
                w_value=w_value,
                w_index=w_index,
                w_length=parse_int(row.get("wLength") or row.get("w_length")),
                data=data,
            )
        )
    return records


def load_tsv_records(path: Path, root: Path) -> list[XuRecord]:
    source = display_path(path, root)
    records: list[XuRecord] = []
    for idx, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines()):
        parts = line.split("\t")
        if len(parts) < 12:
            continue
        data_hex = parts[-1].strip()
        if not data_hex or not re.fullmatch(r"[0-9a-fA-F]+", data_hex):
            continue
        w_value = parse_int(parts[8])
        w_index = parse_int(parts[9])
        w_length = parse_int(parts[10])
        selector = (w_value >> 8) & 0xFF if w_value is not None else None
        unit = (w_index >> 8) & 0xFF if w_index is not None else None
        interface = w_index & 0xFF if w_index is not None else None
        if unit not in (3, 5):
            continue
        records.append(
            XuRecord(
                source=source,
                source_kind="tsv",
                index=idx,
                frame=parts[0],
                t_seconds=parts[1],
                bus=parts[2],
                device=parts[3],
                selector=selector,
                unit=unit,
                interface=interface,
                w_value=w_value,
                w_index=w_index,
                w_length=w_length,
                data=bytes_from_hex(data_hex),
            )
        )
    return records


def bx_property_text(cmd: int, payload: bytes) -> tuple[str, str, dict[str, Any]]:
    prop = le16(payload, 0)
    if prop is None:
        return "", "", {}
    value = payload[2:]
    prop_name = BX_PROPERTIES.get(prop, f"Property0x{prop:04x}")
    detail: dict[str, Any] = {"property_id": prop, "property_name": prop_name}
    if cmd == 0x0004:
        return prop_name, f"GET {prop_name}(0x{prop:04x})", detail
    if prop == 0x0006 and len(value) >= 8:
        sec = le32(value, 0)
        usec = le32(value, 4)
        detail.update({"host_sec": sec, "host_usec": usec})
        return prop_name, f"SET {prop_name}(0x0006) sec={sec} usec={usec}", detail
    if prop in (0x002B, 0x002C) and len(value) >= 3:
        channel = value[0]
        lo = value[1] | (value[2] << 8) if len(value) >= 3 else None
        hi = value[3] | (value[4] << 8) if len(value) >= 5 else None
        detail.update({"channel": channel, "min": lo, "max": hi})
        return prop_name, f"SET {prop_name}(0x{prop:04x}) channel={channel} min={lo} max={hi}", detail
    if prop == 0x0030 and len(value) >= 2:
        flag = le16(value, 0)
        detail["flag"] = flag
        return prop_name, f"SET {prop_name}(0x0030) flag=0x{flag:04x}", detail
    if value:
        detail["value_hex"] = value.hex()
        return prop_name, f"SET {prop_name}(0x{prop:04x}) value={value.hex()}", detail
    return prop_name, f"SET {prop_name}(0x{prop:04x})", detail


def decode_bx(data: bytes) -> dict[str, Any]:
    declared_len = le16(data, 2) or 0
    cmd = le16(data, 4) or 0
    subcmd = le16(data, 6) or 0
    payload = data[8 : 8 + declared_len]
    command = BX_COMMANDS.get(cmd, f"Command0x{cmd:04x}")
    text = f"BX {command} cmd=0x{cmd:04x} sub=0x{subcmd:04x} len={declared_len}"
    details: dict[str, Any] = {
        "protocol": "BX",
        "declared_len": declared_len,
        "cmd": cmd,
        "subcmd": subcmd,
        "command": command,
        "payload_hex": payload.hex(),
    }
    property_name = ""
    if cmd in (0x0004, 0x0005):
        property_name, text, prop_details = bx_property_text(cmd, payload)
        details.update(prop_details)
        text = f"BX {command}: {text}"
    elif cmd == 0x0000:
        text = "BX OpenDevice"
    elif cmd == 0x0001:
        text = "BX CloseDevice"
    elif cmd == 0x0002:
        text = "BX ResetComponent"
    elif cmd == 0x0003 and declared_len >= 2:
        keepalive = le16(payload, 0)
        details["keepalive"] = keepalive
        text = f"BX KeepAlive value={keepalive}"
    elif cmd == 0x0006 and declared_len >= 8:
        stream = le16(payload, 0)
        width = le16(payload, 2)
        height = le16(payload, 4)
        fps = le16(payload, 6)
        aux0 = le16(payload, 8) if declared_len >= 10 else None
        aux1 = le16(payload, 10) if declared_len >= 12 else None
        details.update({"stream": stream, "width": width, "height": height, "fps": fps, "aux0": aux0, "aux1": aux1})
        text = f"BX OpenStream stream={stream} {width}x{height}@{fps} aux={aux0},{aux1}"
    elif cmd == 0x0007 and declared_len >= 2:
        stream = le16(payload, 0)
        details["stream"] = stream
        text = f"BX CloseStream stream={stream}"
    elif cmd == 0x000D and declared_len >= 10:
        file_type = le16(payload, 0)
        offset = le32(payload, 2)
        size = le32(payload, 6)
        details.update({"file_type": file_type, "offset": offset, "size": size})
        text = f"BX DownloadFileChunk file={file_type} offset={offset} size={size}"
    elif cmd == 0x000D and declared_len >= 6:
        file_type = le16(payload, 0)
        offset = le16(payload, 2)
        size = le16(payload, 4)
        details.update({"file_type": file_type, "offset": offset, "size": size})
        text = f"BX DownloadFileChunk file={file_type} offset={offset} size={size}"
    elif cmd in (0x000E,):
        first = le16(payload, 0) if declared_len >= 2 else None
        second = le16(payload, 2) if declared_len >= 4 else None
        details.update({"arg0": first, "arg1": second})
        text = f"BX {command} arg0={first} arg1={second}"
    elif cmd in (0x0010, 0x0011, 0x000F) and declared_len >= 2:
        file_type = le16(payload, 0)
        details["file_type"] = file_type
        text = f"BX {command} file={file_type}"
    return {
        **details,
        "family": "master_xu5_bx",
        "name": command,
        "property_name": property_name,
        "decoded": text,
    }


def decode_companion(data: bytes, selector: int | None) -> dict[str, Any]:
    family = "companion_xu3_raw"
    text = f"RAW len={len(data)} first={hex_preview(data, 16)}"
    name = "Raw"
    details: dict[str, Any] = {"protocol": "RAW"}
    if selector == 25 and len(data) >= 2:
        key3 = (data[0], data[1], data[2] if len(data) >= 3 else None)
        key2 = (data[0], data[1], None)
        label = COMPANION_SELECTOR25.get(key3) or COMPANION_SELECTOR25.get(key2)
        opcode = (data[0] << 8) | data[1]
        arg = data[2] if len(data) >= 3 else None
        name = f"Selector25_0x{opcode:04x}"
        details.update({"opcode": opcode, "arg0": arg})
        text = label or f"Selector25 raw opcode=0x{opcode:04x} arg0={arg}"
    elif selector == 30 and len(data) >= 8:
        word0 = le16(data, 0)
        word1 = le16(data, 2)
        page = le32(data, 4)
        flag = data[8] if len(data) >= 9 else None
        name = f"Selector30_0x{word0:04x}"
        details.update({"word0": word0, "word1": word1, "page": page, "flag": flag})
        text = f"Selector30 配置页 word0=0x{word0:04x} word1=0x{word1:04x} page=0x{page:08x} flag={flag}"
    return {
        **details,
        "family": family,
        "name": name,
        "property_name": "",
        "decoded": text,
    }


def decode_record(record: XuRecord) -> dict[str, Any]:
    data = record.data
    trimmed = data[: record.w_length] if record.w_length else data
    if len(trimmed) >= 8 and trimmed[:2] == b"BX":
        decoded = decode_bx(trimmed)
    else:
        decoded = decode_companion(trimmed, record.selector)
    normalized = normalized_key(trimmed, decoded)
    return {
        "source": record.source,
        "source_kind": record.source_kind,
        "index": record.index,
        "frame": record.frame,
        "t_seconds": record.t_seconds,
        "bus": record.bus,
        "device": record.device,
        "unit": record.unit,
        "selector": record.selector,
        "interface": record.interface,
        "w_value": f"0x{record.w_value:04x}" if record.w_value is not None else "",
        "w_index": f"0x{record.w_index:04x}" if record.w_index is not None else "",
        "w_length": record.w_length or len(trimmed),
        "data_hex": trimmed.hex(),
        "prefix_hex": hex_preview(trimmed, 32),
        "normalized_key": normalized,
        **decoded,
    }


def normalized_key(data: bytes, decoded: dict[str, Any]) -> str:
    if decoded.get("protocol") == "BX":
        cmd = decoded.get("cmd")
        prop = decoded.get("property_id")
        if cmd == 0x0005 and prop == 0x0006:
            return "BX:cmd=0005:prop=0006:host-time"
        if cmd == 0x000D:
            return f"BX:cmd=000d:file={decoded.get('file_type')}:chunk-size={decoded.get('size')}"
        if prop is not None:
            return f"BX:cmd={cmd:04x}:prop={prop:04x}:payload={data[8:].hex()}"
        return f"BX:cmd={cmd:04x}:sub={decoded.get('subcmd'):04x}:payload={data[8:].hex()}"
    return f"RAW:selector={decoded.get('name')}:{data[:16].hex()}:len={len(data)}"


def collect_records(paths: list[Path], root: Path) -> list[XuRecord]:
    records: list[XuRecord] = []
    for path in paths:
        if not path.exists():
            continue
        if path.suffix == ".json":
            records.extend(load_json_records(path, root))
        elif path.suffix == ".tsv":
            records.extend(load_tsv_records(path, root))
    return records


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fieldnames = [
        "source",
        "source_kind",
        "index",
        "frame",
        "t_seconds",
        "bus",
        "device",
        "unit",
        "selector",
        "interface",
        "w_value",
        "w_index",
        "w_length",
        "family",
        "protocol",
        "name",
        "cmd",
        "subcmd",
        "declared_len",
        "property_id",
        "property_name",
        "stream",
        "width",
        "height",
        "fps",
        "file_type",
        "offset",
        "size",
        "decoded",
        "normalized_key",
        "prefix_hex",
        "data_hex",
    ]
    with path.open("w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def unique_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: dict[str, dict[str, Any]] = {}
    counts = Counter(row["normalized_key"] for row in rows)
    for row in rows:
        key = row["normalized_key"]
        if key not in seen:
            copy = dict(row)
            copy["count"] = counts[key]
            seen[key] = copy
    return list(seen.values())


def write_unique_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    uniq = unique_rows(rows)
    if not uniq:
        path.write_text("", encoding="utf-8")
        return
    fieldnames = [
        "count",
        "family",
        "protocol",
        "unit",
        "selector",
        "name",
        "cmd",
        "subcmd",
        "declared_len",
        "property_id",
        "property_name",
        "stream",
        "width",
        "height",
        "fps",
        "file_type",
        "size",
        "decoded",
        "normalized_key",
        "prefix_hex",
    ]
    with path.open("w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(uniq)


def markdown_table(headers: list[str], rows: list[list[Any]]) -> str:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join(["---"] * len(headers)) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(str(item) for item in row) + " |")
    return "\n".join(lines)


def write_markdown(path: Path, rows: list[dict[str, Any]], input_paths: list[Path], root: Path) -> None:
    command_counts = Counter((row.get("family"), row.get("name"), row.get("cmd")) for row in rows)
    prop_counts = Counter((row.get("property_id"), row.get("property_name"), row.get("name")) for row in rows if row.get("property_id") not in ("", None))
    stream_rows = [
        row
        for row in unique_rows(rows)
        if row.get("name") == "OpenStream" or row.get("decoded", "").startswith("HV3/Sonix depth")
    ]
    bx_table = []
    for cmd, name in sorted(BX_COMMANDS.items()):
        bx_table.append([f"0x{cmd:04x}", name, bx_semantics(cmd)])
    prop_table = []
    for (prop, prop_name, cmd_name), count in sorted(prop_counts.items(), key=lambda item: (int(item[0][0]), item[0][2])):
        prop_table.append([count, f"0x{int(prop):04x}", prop_name, cmd_name])
    command_table = []
    def command_sort_key(item: tuple[tuple[Any, Any, Any], int]) -> tuple[str, int, str]:
        family, name, cmd = item[0]
        cmd_order = int(cmd) if isinstance(cmd, int) else -1
        return (str(family), cmd_order, str(name))

    for (family, name, cmd), count in sorted(command_counts.items(), key=command_sort_key):
        cmd_text = f"0x{int(cmd):04x}" if isinstance(cmd, int) else ""
        command_table.append([count, family, cmd_text, name])
    stream_table = []
    for row in stream_rows:
        stream_table.append(
            [
                row.get("count", ""),
                row.get("family", ""),
                row.get("decoded", ""),
                row.get("prefix_hex", ""),
            ]
        )
    candidate_streams = [
        ["vendor 已抓到", "stream=1 640x400@15", make_bx_open_stream_hex(1, 640, 400, 15)],
        ["待实测", "stream=1 640x400@30", make_bx_open_stream_hex(1, 640, 400, 30)],
        ["待实测", "stream=1 1280x800@30", make_bx_open_stream_hex(1, 1280, 800, 30)],
        ["待实测", "stream=1 1920x1080@30", make_bx_open_stream_hex(1, 1920, 1080, 30)],
    ]

    source_lines = []
    for input_path in input_paths:
        label = display_path(input_path, root)
        source_lines.append(f"- `{label}`")

    text = "\n".join(
        [
            "# Berxel 原厂 XU 命令反编译",
            "",
            "## 输入",
            "",
            *source_lines,
            "",
            "## BX 包格式",
            "",
            "`libBerxelUvcDriver.so` 的 `berxel::BerxelHostProtocol::berxelFillupCmd` 反汇编确认包头为：",
            "",
            "```text",
            "uint16 magic = 0x5842  # bytes: 42 58, ASCII \"BX\"",
            "uint16 payload_len",
            "uint16 cmd",
            "uint16 subcmd",
            "uint8  payload[payload_len]",
            "```",
            "",
            "## BX 命令号",
            "",
            markdown_table(["cmd", "名称", "语义"], bx_table),
            "",
            "## 本次样本命令统计",
            "",
            markdown_table(["次数", "family", "cmd", "名称"], command_table),
            "",
            "## 本次样本属性号",
            "",
            markdown_table(["次数", "property", "名称", "方向"], prop_table) if prop_table else "未观察到属性命令。",
            "",
            "## 流模式命令",
            "",
            markdown_table(["次数", "family", "解码", "payload 前缀"], stream_table) if stream_table else "未观察到流模式命令。",
            "",
            "## COLOR OpenStream 候选 payload",
            "",
            "下面三条高分辨率 payload 是按已反编译出的 `cmd=0x0006` 结构合成，必须实机验证后才能写入正式初始化表。",
            "",
            markdown_table(["状态", "模式", "64B payload"], candidate_streams),
            "",
            "## 关键结论",
            "",
            "- COLOR 的私有分辨率命令是 master XU5 的 `BX OpenStream(cmd=0x0006)`，payload 中直接写 `stream,width,height,fps`。",
            "- 现有原厂 color 抓包只捕获到 `stream=1 640x400@15`，所以只切 UVC frame index 会继续出 640x400 JPEG。",
            "- companion XU3 selector 25 的 `01 02 08` 与原厂 HV3/Sonix 反汇编吻合：depth 640 档；同逻辑推导 `01 02 03` 是 depth 1280 档，`01 02 0c` 是 depth 320 档。",
            "- `cmd=0x000d DownloadFileChunk` 是原厂按 54 字节块读取参数/文件数据的命令，抓包里的长序列不是重复初始化，而是在连续拉取文件块。",
            "",
        ]
    )
    path.write_text(text, encoding="utf-8")


def bx_semantics(cmd: int) -> str:
    return {
        0x0000: "打开 master 设备会话，无 payload",
        0x0001: "关闭 master 设备会话，无 payload",
        0x0002: "重置组件，原厂封装未携带组件号",
        0x0003: "保活，payload 为 uint16 计数/状态",
        0x0004: "读取属性，payload 为 uint16 property_id",
        0x0005: "写属性，payload 为 uint16 property_id + value",
        0x0006: "打开流，payload 为 stream,width,height,fps,aux0,aux1",
        0x0007: "关闭流，payload 为 uint16 stream",
        0x000A: "初始化上传文件",
        0x000B: "写上传文件块",
        0x000C: "结束上传文件",
        0x000D: "读取/下载文件块，payload 为 file_type,offset,size",
        0x000E: "启动 USB stream 或文件拉取",
        0x000F: "停止文件拉取",
        0x0010: "初始化下载文件，payload 为 uint16 file_type",
        0x0011: "结束下载文件，payload 为 uint16 file_type",
    }.get(cmd, "")


def make_bx_open_stream_hex(stream: int, width: int, height: int, fps: int, aux0: int = 0, aux1: int = 0) -> str:
    payload = b"".join(
        [
            stream.to_bytes(2, "little"),
            width.to_bytes(2, "little"),
            height.to_bytes(2, "little"),
            fps.to_bytes(2, "little"),
            aux0.to_bytes(2, "little"),
            aux1.to_bytes(2, "little"),
        ]
    )
    packet = b"BX" + len(payload).to_bytes(2, "little") + (0x0006).to_bytes(2, "little") + (0).to_bytes(2, "little") + payload
    return packet.ljust(64, b"\0").hex()


def main() -> None:
    parser = argparse.ArgumentParser(description="Decode Berxel vendor XU payloads.")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--out", type=Path, default=Path(".dev/berxel-host-sdk/xu-decode"))
    parser.add_argument("inputs", nargs="*", type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    out_dir = args.out if args.out.is_absolute() else root / args.out
    out_dir.mkdir(parents=True, exist_ok=True)

    input_paths = args.inputs or [Path(item) for item in DEFAULT_INPUTS]
    input_paths = [path if path.is_absolute() else root / path for path in input_paths]
    records = collect_records(input_paths, root)
    rows = [decode_record(record) for record in records]

    write_csv(out_dir / "xu_commands.csv", rows)
    write_unique_csv(out_dir / "xu_unique_commands.csv", rows)
    write_markdown(out_dir / "xu_opcode_summary.md", rows, input_paths, root)

    print(f"decoded_records={len(rows)}")
    print(f"unique_normalized={len(unique_rows(rows))}")
    print(f"out_dir={out_dir}")


if __name__ == "__main__":
    main()

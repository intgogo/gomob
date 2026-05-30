#!/usr/bin/env python3
"""
从 Linux SDK companion USB trace 抽 Sonix XU init 序列。

每条 SET_CUR 落进 JSON：{ "t": float, "selector": int, "wLen": int, "data_hex": str }
GET_CUR 也记下来（验证用）。

用法：
  python3 scripts/extract_sonix_init_sequence.py \
      .dev/m1.6.2-usb-trace/bus8-companion.pcap \
      native/berxel/iHawkP100R3_init_sequence.json
"""
import json
import subprocess
import sys


def run_tshark(pcap):
    # 抓所有 class interface control transfer
    cmd = [
        "tshark", "-r", pcap,
        "-Y", "usb.bmRequestType == 0x21 || usb.bmRequestType == 0xa1",
        "-T", "fields",
        "-e", "frame.number",
        "-e", "frame.time_relative",
        "-e", "usb.bmRequestType",
        "-e", "usb.setup.bRequest",
        "-e", "usb.setup.wValue",
        "-e", "usb.setup.wLength",
        "-e", "usb.data_fragment",
        "-e", "usb.capdata",
    ]
    out = subprocess.check_output(cmd, text=True)
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    pcap, dst = sys.argv[1], sys.argv[2]

    txt = run_tshark(pcap)
    entries = []
    for line in txt.strip().split("\n"):
        cols = line.split("\t")
        if len(cols) < 6:
            continue
        frame_no, t, bmRT, bRequest, wValue, wLen = cols[:6]
        data_frag = cols[6] if len(cols) > 6 else ""
        capdata = cols[7] if len(cols) > 7 else ""
        if not wValue or not wLen:
            # GET_CUR 完成包：data 在 capdata
            entries.append({
                "frame": int(frame_no),
                "t": float(t),
                "kind": "GET_CUR_data" if bmRT == "0x000000a1" else "?",
                "data_hex": capdata.replace(":", "").lower(),
            })
            continue
        wVal = int(wValue, 16)
        selector = (wVal >> 8) & 0xff
        kind = "SET_CUR" if bmRT == "0x00000021" else "GET_CUR"
        entry = {
            "frame": int(frame_no),
            "t": float(t),
            "kind": kind,
            "selector": selector,
            "wValue": wVal,
            "wLength": int(wLen),
            "data_hex": (data_frag or capdata).replace(":", "").lower(),
        }
        entries.append(entry)

    # 抽 set_cur 类型 + 标准 UVC probe/commit (selector 0x01/0x02 on wIndex=1)
    init = [
        e for e in entries
        if e.get("kind") == "SET_CUR" and e.get("selector") in (0x19, 0x1e)
    ]

    out = {
        "source": pcap,
        "count": len(init),
        "selectors": sorted(set(e["selector"] for e in init)),
        "init_set_cur": [
            {
                "selector": e["selector"],
                "wLength": e["wLength"],
                "data_hex": e["data_hex"],
                "t_seconds": e["t"],
                "frame": e["frame"],
            }
            for e in init
        ],
    }
    with open(dst, "w") as f:
        json.dump(out, f, indent=2)
    print(f"wrote {dst}: {len(init)} init SET_CUR entries, selectors={out['selectors']}")


if __name__ == "__main__":
    main()

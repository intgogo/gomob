#!/usr/bin/env python3
"""
从 Linux SDK master USB trace 抽 Berxel 'BX' header XU 5 init 序列。

每条 SET_CUR 都是 selector 0x01 / wIndex 0x0500 / wLen 64，payload 前两字节 'BX'。
默认抽前 20 条用作 firmware 进入 ready 的种子序列。

用法：
  python3 scripts/extract_master_xu5_init.py \
      .dev/m1.6.2-usb-trace/bus7-master.pcap \
      core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json \
      [count]
"""
import json
import subprocess
import sys


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    pcap, dst = sys.argv[1], sys.argv[2]
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 20

    set_cmd = [
        "tshark", "-r", pcap,
        "-Y", "usb.bmRequestType == 0x21",
        "-T", "fields",
        "-e", "frame.number",
        "-e", "frame.time_relative",
        "-e", "usb.setup.wValue",
        "-e", "usb.setup.wIndex",
        "-e", "usb.setup.wLength",
        "-e", "usb.data_fragment",
    ]
    set_out = subprocess.check_output(set_cmd, text=True)

    set_entries = []
    for line in set_out.strip().split("\n"):
        cols = line.split("\t")
        if len(cols) < 5:
            continue
        frame_no, t, wValue, wIndex, wLen = cols[:5]
        data_frag = cols[5] if len(cols) > 5 else ""
        if not wValue or not wIndex:
            continue
        wVal = int(wValue, 16) if wValue.startswith("0x") else int(wValue)
        wIdx = int(wIndex, 16) if wIndex.startswith("0x") else int(wIndex)
        data_hex = data_frag.replace(":", "").lower()
        # master XU5 固定走 unit=5/interface=0，payload 是 Berxel "BX" 头。
        if wIdx != 0x0500 or int(wLen) != 64 or not data_hex.startswith("4258"):
            continue
        selector = (wVal >> 8) & 0xff
        unit = (wIdx >> 8) & 0xff
        iface = wIdx & 0xff
        set_entries.append({
            "frame": int(frame_no),
            "t": float(t),
            "selector": selector,
            "unit": unit,
            "interface": iface,
            "wValue": wVal,
            "wIndex": wIdx,
            "wLength": int(wLen),
            "data_hex": data_hex,
        })

    # 抽 GET_CUR readback：bRequest=0x81, wValue 必带 selector
    get_cmd = [
        "tshark", "-r", pcap,
        "-Y", "usb.bmRequestType == 0xa1 && usb.capdata",
        "-T", "fields",
        "-e", "frame.number",
        "-e", "frame.time_relative",
        "-e", "usb.setup.wValue",
        "-e", "usb.setup.wIndex",
        "-e", "usb.capdata",
    ]
    get_out = subprocess.check_output(get_cmd, text=True)
    get_entries = []
    for line in get_out.strip().split("\n"):
        cols = line.split("\t")
        if len(cols) < 5 or not cols[4]:
            continue
        get_entries.append({
            "frame": int(cols[0]),
            "t": float(cols[1]),
            "data_hex": cols[4].replace(":", "").lower(),
        })

    take = set_entries[:count]
    out = {
        "source": pcap,
        "total_set_cur": len(set_entries),
        "total_get_cur": len(get_entries),
        "init_set_cur_count": len(take),
        "init_set_cur": [
            {
                "selector": e["selector"],
                "unit": e["unit"],
                "interface": e["interface"],
                "wValue": e["wValue"],
                "wIndex": e["wIndex"],
                "wLength": e["wLength"],
                "data_hex": e["data_hex"],
                "t_seconds": e["t"],
                "frame": e["frame"],
            }
            for e in take
        ],
    }
    with open(dst, "w") as f:
        json.dump(out, f, indent=2)
    print(
        f"wrote {dst}: {len(take)} init SET_CUR entries "
        f"(unit={take[0]['unit']} selector={take[0]['selector']:#04x} wLen={take[0]['wLength']})"
    )


if __name__ == "__main__":
    main()

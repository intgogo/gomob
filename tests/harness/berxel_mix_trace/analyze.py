#!/usr/bin/env python3
# 解码原厂 MIX SET_CUR 序列:tshark 抓的 (wValue, wIndex, data_fragment) → BX 帧(magic/len/cmd/reg/value)。
# master XU5 = wIndex 0x0500;companion = 0x0300。输出按时间序的命令链,定位 MIX 开流的命令/顺序。
import sys

# BX cmd code(byte4-5)
CMD = {0x0000: "OpenDevice/Probe", 0x0005: "SetProperty", 0x0006: "OpenStream/HostTime",
       0x000d: "DownloadFileChunk", 0x000e: "StartUsbStream", 0x0010: "InitDownloadFile",
       0x0011: "FinishDownloadFile"}
# SetProperty(cmd=0x05) 的 reg(byte8-9)
REG = {0x0000: "DeviceStatus", 0x0006: "HostTime", 0x0015: "StreamStatus", 0x0017: "LogMode",
       0x0028: "InitProbe", 0x002a: "InitProbe2", 0x0030: "StreamFlagMode"}


def hx(s):
    return s.replace(":", "").replace(" ", "").strip().lower()


def le16(b, i):
    return b[i] | (b[i + 1] << 8) if i + 1 < len(b) else -1


def decode(data_hex):
    b = bytes.fromhex(data_hex) if data_hex else b""
    if len(b) < 6 or b[0] != 0x42 or b[1] != 0x58:
        return f"(non-BX {data_hex[:24]})"
    ln = le16(b, 2)
    cmd = le16(b, 4)
    name = CMD.get(cmd, f"cmd0x{cmd:04x}")
    if cmd == 0x0005:  # SetProperty: reg@8, value@10
        reg = le16(b, 8)
        val = le16(b, 10)
        return f"SetProperty {REG.get(reg, f'reg0x{reg:04x}')}(0x{reg:04x}) = 0x{val:04x} (b10={b[10]:02x} b11={b[11]:02x})"
    if cmd == 0x0006:  # OpenStream(stream_type@8,w@10,h@12,fps@14) 或 HostTime
        st = le16(b, 8)
        if st in (1, 2, 4):
            return f"OpenStream type={st}({'COLOR' if st==1 else 'DEPTH' if st==2 else 'IR'}) {le16(b,10)}x{le16(b,12)}@{le16(b,14)}"
        return f"cmd0x0006(HostTime/其它) data={data_hex[:32]}"
    if cmd == 0x000e:
        return f"StartUsbStream arg0=0x{le16(b,8):04x} arg1=0x{le16(b,10):04x}"
    return f"{name}(0x{cmd:04x}) len={ln} data={data_hex[8:32]}"


def main():
    raw = sys.argv[1] if len(sys.argv) > 1 else "setcur_raw.txt"
    master, companion, other = [], [], []
    for line in open(raw, errors="ignore"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        wv, wi, data = parts[0], parts[1], hx(parts[2])
        if not data:
            continue
        try:
            wi_i = int(wi, 16) if wi.startswith("0x") else int(wi)
        except ValueError:
            wi_i = -1
        dec = decode(data)
        row = f"  wValue={wv} wIndex={wi}  {dec}"
        if wi_i == 0x0500:
            master.append(row)
        elif wi_i == 0x0300:
            companion.append(row)
        else:
            other.append(row)

    print("=" * 70)
    print(f"原厂 MIX master XU5(wIndex=0x0500)命令序列  共 {len(master)} 条:")
    print("=" * 70)
    for r in master:
        print(r)
    print()
    print("=" * 70)
    print(f"companion(wIndex=0x0300)命令序列  共 {len(companion)} 条:")
    print("=" * 70)
    for r in companion:
        print(r)
    if other:
        print(f"\n其它 wIndex {len(other)} 条(略)")

    print("\n── 关键对照点(我们 setup_dual vs 原厂)──")
    print("找:① StreamFlagMode 写的是 0x02(MIX)吗;② OpenStream COLOR/DEPTH 各几条、在 StreamFlagMode 前还是后;")
    print("    ③ StreamStatus(0x0015) 写几次、值;④ StartUsbStream 几条、args;⑤ 有没有我们没发的命令。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

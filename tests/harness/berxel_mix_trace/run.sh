#!/usr/bin/env bash
# 抓原厂 MIX(color+depth)开流的 USB 命令序列 —— definitive 锁定我们 setup_dual 缺的那口气。
# 流程:① 编原厂 MIX reader;② tcpdump 抓 usbmon0(全总线);③ 跑 reader(setStreamFlagMode(MIX)+startStreams(C|D));
#       ④ tshark 解析 master XU5(wIndex=0x0500)+ companion(wIndex=0x0300)的 SET_CUR(0x21/01)payload;
#       ⑤ Python 解码 BX 帧(magic/len/cmd/reg)→ 输出原厂 MIX 命令序列,供 diff。
set -uo pipefail
PROJ="$(cd "$(dirname "$0")/../../.." && pwd)"; cd "$PROJ"
SDK="${VENDOR_SDK_DIR:-$PROJ/.dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190}"
OUT="${OUTPUT_DIR:-$PROJ/.dev/berxel_mix_trace}"; mkdir -p "$OUT"
BIN="$OUT/vendor_mix_read"; PCAP="$OUT/mix.pcap"

echo "== 0. 前置 =="
lsusb -d 0603:001f >/dev/null 2>&1 || { echo "❌ master 0603:001f 不在服务器,请把相机接回服务器"; exit 2; }
lsusb -d 3558:1012 >/dev/null 2>&1 || { echo "❌ companion 3558:1012 不在"; exit 2; }
[ -f "$SDK/libs/libBerxelHawk.so" ] || { echo "❌ vendor SDK 缺: $SDK"; exit 2; }
command -v tshark >/dev/null || { echo "❌ 缺 tshark"; exit 2; }
ls /sys/kernel/debug/usb/usbmon/ >/dev/null 2>&1 || { modprobe usbmon 2>/dev/null; mount -t debugfs none /sys/kernel/debug 2>/dev/null; }
echo "✅ 设备在线 + 工具就绪"

echo "== 1. 编原厂 MIX reader =="
g++ -std=c++17 -O2 -I"$SDK/Include" tests/harness/berxel_mix_trace/vendor_mix_read.cpp \
    -L"$SDK/libs" -lBerxelHawk -Wl,-rpath,"$SDK/libs" -o "$BIN" || { echo "❌ 编译失败"; exit 3; }
echo "✅ 编译通过"

echo "== 2. 起 usbmon 抓包(全总线)=="
tcpdump -i usbmon0 -w "$PCAP" >/dev/null 2>&1 &
TCPD=$!
sleep 1

echo "== 3. 跑原厂 MIX reader(setStreamFlagMode(MIX)+startStreams(COLOR|DEPTH))=="
LD_LIBRARY_PATH="$SDK/libs" timeout 25s "$BIN" "${VW:-640}" "${VH:-400}" "${VFPS:-30}" 20 \
    > "$OUT/reader.log" 2>&1 || true
sleep 1
kill "$TCPD" 2>/dev/null; wait "$TCPD" 2>/dev/null
echo "--- reader 关键行 ---"; grep -E "MARK|rc=|RESULT" "$OUT/reader.log" | head -20

echo "== 4. tshark 解析 master XU5(0x0500)+ companion(0x0300)SET_CUR payload =="
# bmRequestType=0x21(class/iface/OUT) bRequest=1(SET_CUR);usb.data_fragment 是 64B payload
tshark -r "$PCAP" -Y 'usb.bmRequestType == 0x21 && usb.setup.bRequest == 1' \
    -T fields -e usb.setup.wValue -e usb.setup.wIndex -e usb.data_fragment \
    2>/dev/null > "$OUT/setcur_raw.txt"
echo "捕获 SET_CUR 条数: $(wc -l < "$OUT/setcur_raw.txt")"

echo "== 5. 解码 BX 序列 =="
python3 tests/harness/berxel_mix_trace/analyze.py "$OUT/setcur_raw.txt" "$OUT/reader.log" | tee "$OUT/mix_sequence.txt"
echo "→ 完整序列: $OUT/mix_sequence.txt  /  pcap: $PCAP"

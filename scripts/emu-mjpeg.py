#!/usr/bin/env python3
"""emu-mjpeg.py — 把 adb screencap 循环成 MJPEG / HTTP 静态页给浏览器看。

用法：
    ./scripts/emu-mjpeg.py [--port 8088] [--device emulator-5556] [--fps 2]

打开 http://<服务器IP>:8088/ 看实时屏幕（带刷新按钮，默认 2fps）。
emulator 的 headless 模式 adb 100% 稳定，避免 GUI 渲染问题。
"""

import argparse
import http.server
import os
import socketserver
import subprocess
import sys
import threading
import time

ADB = os.environ.get("ADB", "/opt/android-sdk/platform-tools/adb")


class Frame:
    def __init__(self):
        self.data = b""
        self.lock = threading.Lock()
        self.ts = 0


def grab(device: str, frame: Frame, fps: float):
    interval = 1.0 / max(fps, 0.5)
    while True:
        try:
            out = subprocess.run(
                [ADB, "-s", device, "exec-out", "screencap", "-p"],
                capture_output=True, timeout=5,
            )
            if out.returncode == 0 and out.stdout:
                with frame.lock:
                    frame.data = out.stdout
                    frame.ts = time.time()
        except subprocess.TimeoutExpired:
            pass
        except Exception as e:
            print(f"grab error: {e}", file=sys.stderr)
        time.sleep(interval)


def make_handler(frame: Frame, fps: float):
    refresh_ms = int(1000 / max(fps, 0.5))

    HTML = (
        "<!doctype html><html><head><meta charset=utf-8>"
        "<title>gomob emu</title>"
        "<style>"
        "body{margin:0;background:#222;color:#ccc;font-family:sans-serif;text-align:center}"
        "img{max-height:100vh;max-width:100vw;display:block;margin:0 auto}"
        ".bar{position:fixed;top:0;left:0;right:0;background:#000a;padding:6px;font-size:12px;z-index:10}"
        "</style></head><body>"
        "<div class=bar>gomob emulator (auto-refresh) — 关刷新按 stop"
        " | <a href='/screen.png?t=' style='color:#5af'>full size</a></div>"
        f"<img id=s src='/screen.png?t=0' onerror='setTimeout(load,500)'>"
        "<script>"
        f"let on=true;let ms={refresh_ms};"
        "function load(){if(!on)return;document.getElementById('s').src='/screen.png?t='+Date.now();}"
        "document.getElementById('s').onload=()=>setTimeout(load,ms);"
        "window.stop=()=>{on=false};"
        "</script></body></html>"
    )

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if self.path.startswith("/screen.png"):
                with frame.lock:
                    data = frame.data
                if not data:
                    self.send_response(503); self.end_headers(); return
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            # /
            html = HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
    return H


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8088)
    ap.add_argument("--device", default="emulator-5556")
    ap.add_argument("--fps", type=float, default=2.0)
    args = ap.parse_args()

    frame = Frame()
    t = threading.Thread(target=grab, args=(args.device, frame, args.fps), daemon=True)
    t.start()

    H = make_handler(frame, args.fps)

    class TServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    srv = TServer(("0.0.0.0", args.port), H)
    print(f"serving on http://0.0.0.0:{args.port}/  device={args.device}  fps={args.fps}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

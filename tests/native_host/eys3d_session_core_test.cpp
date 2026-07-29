// eYs3D 会话引擎单测 — 喂 IF2/IF1 整帧 → poll metric depthMm / color,含背压 / serial / 状态机。
#include "eys3d/portable/eys3d_session_core.h"

#include <cstdio>

using namespace gomob::eys3d;
using gomob::camera::CameraFrame;
using gomob::camera::CameraStreamKind;
using gomob::camera::SessionState;
using gomob::camera::StreamProfile;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-46s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
void PutLe16(std::vector<uint8_t>& b, uint16_t v) {
  b.push_back(static_cast<uint8_t>(v & 0xFF));
  b.push_back(static_cast<uint8_t>(v >> 8));
}

SessionCoreConfig MakeCfg(uint16_t dw, uint16_t dh, size_t maxq = 8) {
  SessionCoreConfig c;
  c.color = StreamProfile{1280, 256, 5, StreamProfile::Format::kMjpeg, "color"};
  c.depth = StreamProfile{dw, dh, 5, StreamProfile::Format::kDepthU16, "depth"};
  c.depth_router = DepthRouterConfig{dw, dh, 0, false};
  c.max_queue = maxq;
  return c;
}
}  // namespace

int main() {
  // 深度路由 + 取帧 + serial 递增。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(2, 2));
    Check("未设度量 → depth 未就绪", !core.depth_ready());
    core.SetZdTable(ZdTable::FromMillimeters({0, 1000, 500, 250}));
    Check("设 ZD 表 → depth 就绪", core.depth_ready());
    core.MarkStreaming();
    Check("state=streaming", core.state() == SessionState::kStreaming);

    std::vector<uint8_t> raw;
    PutLe16(raw, 0); PutLe16(raw, 1); PutLe16(raw, 3); PutLe16(raw, 2);
    core.OnRawDepthFrame(raw.data(), raw.size(), 111);
    core.OnRawDepthFrame(raw.data(), raw.size(), 222);

    CameraFrame f;
    int n = core.Poll(&f, 0);
    Check("poll1 出 depthMm 2x2", n == 1 && f.kind == CameraStreamKind::kDepthMm &&
                                  f.width == 2 && f.height == 2 && f.size == 8);
    const uint16_t* mm = reinterpret_cast<const uint16_t*>(f.data);
    Check("depthMm 查表 [0,1000,250,500]", mm[0] == 0 && mm[1] == 1000 && mm[2] == 250 && mm[3] == 500);
    Check("serial0 host_ns=111", f.serial == 0 && f.host_ns == 111);
    n = core.Poll(&f, 0);
    Check("poll2 serial1 host_ns=222", n == 1 && f.serial == 1 && f.host_ns == 222);
    n = core.Poll(&f, 0);
    Check("poll3 空(timeout0)", n == 0);
    auto st = core.stats();
    Check("stats depth_frames=2", st.depth_frames == 2);
  }

  // 路径无关 metric 深度入口(软件 stereo 汇流)。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(2, 1));
    uint16_t mm[2] = {1234, 5678};
    core.OnDepthMmFrame(mm, 2, 1, 99);
    CameraFrame f;
    int n = core.Poll(&f, 0);
    const uint16_t* out = reinterpret_cast<const uint16_t*>(f.data);
    Check("OnDepthMmFrame 入队 depthMm",
          n == 1 && f.kind == CameraStreamKind::kDepthMm && f.size == 4 &&
              out[0] == 1234 && out[1] == 5678 && f.host_ns == 99);
    Check("OnDepthMmFrame 计 depth_frames", core.stats().depth_frames == 1);
  }

  // 最新帧快照(JNI consume-once)。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(2, 1));
    uint16_t a[2] = {100, 200}, b[2] = {300, 400};
    core.OnDepthMmFrame(a, 2, 1, 1);
    core.OnDepthMmFrame(b, 2, 1, 2);  // 更新最新
    uint16_t dst[8] = {0};
    int64_t meta[4] = {0};
    int n = core.SnapshotLatestDepthMm(dst, 8, meta);
    Check("快照取最新 depth(=b)", n == 4 && dst[0] == 300 && dst[1] == 400 && meta[0] == 2 && meta[1] == 1);
    Check("consume-once 第二次 0", core.SnapshotLatestDepthMm(dst, 8, meta) == 0);
    core.OnDepthMmFrame(a, 2, 1, 3);
    Check("新帧后快照恢复", core.SnapshotLatestDepthMm(dst, 8, meta) == 4 && dst[0] == 100);
    Check("cap 不足 → -1", core.SnapshotLatestDepthMm(dst, 1, meta) == 0);  // 已 consume,无新帧返0
    core.OnDepthMmFrame(a, 2, 1, 4);
    Check("cap 不足(有新帧) → -1", core.SnapshotLatestDepthMm(dst, 1, meta) == -1);
    // color 快照
    std::vector<uint8_t> col = {1, 2, 3};
    core.OnColorFrame(col.data(), col.size(), 9);
    std::vector<uint8_t> out;
    Check("color 快照", core.SnapshotLatestColor(&out, meta) && out.size() == 3 && out[0] == 1);
    Check("color consume-once", !core.SnapshotLatestColor(&out, meta));
  }

  // color passthrough + 独立 serial。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(2, 2));
    std::vector<uint8_t> color = {0xAA, 0xBB, 0xCC};
    core.OnColorFrame(color.data(), color.size(), 5);
    CameraFrame f;
    int n = core.Poll(&f, 0);
    Check("poll color passthrough", n == 1 && f.kind == CameraStreamKind::kColor &&
                                    f.size == 3 && f.data[0] == 0xAA && f.serial == 0);
    Check("color stats", core.stats().color_frames == 1);
  }

  // 背压:max_queue=2,推 5 帧深度,只留最新 2,dropped 计数。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(1, 1, /*maxq=*/2));
    core.SetZdTable(ZdTable::FromMillimeters({0, 10, 20, 30, 40, 50, 60}));
    for (uint16_t d = 1; d <= 5; ++d) {
      std::vector<uint8_t> raw; PutLe16(raw, d);
      core.OnRawDepthFrame(raw.data(), raw.size(), d);
    }
    CameraFrame f;
    int got = 0;
    while (core.Poll(&f, 0) == 1) ++got;
    Check("背压后队列剩 2", got == 2);
    Check("dropped 计数>0", core.stats().dropped >= 3);
  }

  // 路由失败(尺寸不符)→ 不入队 + dropped。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(4, 4));
    core.SetZdTable(ZdTable::FromMillimeters({0, 1}));
    std::vector<uint8_t> tiny(4, 0);  // 期望 4*4*2=32B
    core.OnRawDepthFrame(tiny.data(), tiny.size(), 1);
    CameraFrame f;
    Check("路由失败不入队", core.Poll(&f, 0) == 0);
    Check("路由失败计 dropped", core.stats().dropped == 1);
  }

  // 错误态 → Poll 返回 -1。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(2, 2));
    core.MarkError("test error");
    CameraFrame f;
    uint16_t dst[4] = {0};
    int64_t meta[4] = {0};
    Check("错误态 Poll=-1", core.Poll(&f, 0) == -1);
    Check("错误态 depth 快照=-1", core.SnapshotLatestDepthMm(dst, 4, meta) == -1);
    Check("last_error 记录", core.last_error() == "test error" && core.state() == SessionState::kError);
  }

  // arming 装配(走协议默认 USB2)。
  {
    Eys3dSessionCore core;
    auto arm = core.BuildArming(DefaultRsd550Usb2());
    Check("arming 3 步(VideoModeReg+Interleave+IR)", arm.size() == 3);
    Check("arming[0] 写 reg 0xF0", arm.size() == 3 && arm[0].data.size() == 4 &&
                                   arm[0].data[0] == 0x20 && arm[0].data[1] == 0xF0);
  }

  // 回调式。
  {
    Eys3dSessionCore core;
    core.Configure(MakeCfg(1, 1));
    core.SetGeometric(614.6f, 49.98f);
    int cb_count = 0; CameraStreamKind last_kind = CameraStreamKind::kColor;
    core.SetOnFrame([&](const CameraFrame& fr) { ++cb_count; last_kind = fr.kind; });
    std::vector<uint8_t> raw; PutLe16(raw, 512);
    core.OnRawDepthFrame(raw.data(), raw.size(), 1);
    Check("回调触发 depthMm", cb_count == 1 && last_kind == CameraStreamKind::kDepthMm);
  }

  std::printf("eys3d_session_core_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}

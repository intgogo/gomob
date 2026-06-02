// native/camera 抽象层 smoke 测试 — 验证头自洽、接口可实现、再导出可用。
#include "camera/camera_device.h"
#include "camera/camera_registry.h"
#include "camera/camera_session.h"

#include <cstdio>
#include <memory>

using namespace gomob::camera;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-44s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}

// mock 实现:确保 ICameraSession/ICameraDriver 是可实现的良构抽象。
class MockSession : public ICameraSession {
 public:
  bool start(const SessionCallbacks&) override { st_ = SessionState::kStreaming; return true; }
  int poll(CameraFrame* out, uint32_t) override {
    if (out) { out->kind = CameraStreamKind::kDepthMm; out->width = 640; out->height = 128; }
    return 1;
  }
  bool set_controls(const DepthControls&) override { return true; }
  void stop() override { st_ = SessionState::kStopped; }
  void join() override {}
  SessionState state() const override { return st_; }
  SessionStats stats() const override { return {}; }
 private:
  SessionState st_ = SessionState::kIdle;
};

class MockDriver : public ICameraDriver {
 public:
  CameraCapabilities capabilities() const override {
    CameraCapabilities c;
    c.vendor = "eYs3D"; c.model = "RS-D550";
    c.has_ir = true; c.depth_is_metric_onchip = true;
    c.depth_profiles.push_back(StreamProfile{640, 128, 5, StreamProfile::Format::kDepthU16, "640x128@5"});
    return c;
  }
  std::vector<UsbId> match_usb_ids() const override { return {UsbId{0x3438, 0x0206}}; }
  std::unique_ptr<ICameraSession> open_host(UsbContext&, const SessionConfig&) override {
    return std::make_unique<MockSession>();
  }
  std::unique_ptr<ICameraSession> open_fd(const std::vector<int>&, const SessionConfig&) override {
    return std::make_unique<MockSession>();
  }
};

// 参数化 mock(给注册表测多 driver 分发)。
class ParamMockDriver : public ICameraDriver {
 public:
  ParamMockDriver(const char* vendor, const char* model, UsbId id)
      : vendor_(vendor), model_(model), id_(id) {}
  CameraCapabilities capabilities() const override {
    CameraCapabilities c; c.vendor = vendor_; c.model = model_; return c;
  }
  std::vector<UsbId> match_usb_ids() const override { return {id_}; }
  std::unique_ptr<ICameraSession> open_host(UsbContext&, const SessionConfig&) override {
    return std::make_unique<MockSession>();
  }
  std::unique_ptr<ICameraSession> open_fd(const std::vector<int>&, const SessionConfig&) override {
    return std::make_unique<MockSession>();
  }
 private:
  const char* vendor_; const char* model_; UsbId id_;
};
}  // namespace

int main() {
  MockDriver drv;
  auto caps = drv.capabilities();
  Check("capabilities vendor/model", caps.vendor == "eYs3D" && caps.model == "RS-D550");
  Check("depth_is_metric_onchip", caps.depth_is_metric_onchip);
  Check("depth_profiles 1 档", caps.depth_profiles.size() == 1 && caps.depth_profiles[0].width == 640);

  auto ids = drv.match_usb_ids();
  Check("match 0x3438:0x0206", ids.size() == 1 && ids[0].vid == 0x3438 && ids[0].pid == 0x0206);

  // open_fd → session → poll
  auto sess = drv.open_fd({3}, SessionConfig{});
  Check("open_fd 出 session", sess != nullptr);
  SessionCallbacks cb;
  Check("session start", sess->start(cb));
  Check("session state streaming", sess->state() == SessionState::kStreaming);
  CameraFrame f;
  int n = sess->poll(&f, 100);
  Check("poll 出 depthMm 640x128", n > 0 && f.kind == CameraStreamKind::kDepthMm && f.width == 640);
  sess->stop();
  Check("session stopped", sess->state() == SessionState::kStopped);

  // 再导出件可用(类型存在即可)
  UsbId reexport{0x0603, 0x001f};
  Check("re-export UsbId 可用", reexport.vid == 0x0603);

  // ---- 注册表分发(自动识别两相机)----
  {
    CameraRegistry reg;
    reg.Register(std::make_shared<ParamMockDriver>("eYs3D", "RS-D550", UsbId{0x3438, 0x0206}));
    reg.Register(std::make_shared<ParamMockDriver>("Berxel", "iHawkP100R3", UsbId{0x0603, 0x001f}));
    Check("注册 2 driver", reg.size() == 2);

    ICameraDriver* e = reg.MatchByUsbId(UsbId{0x3438, 0x0206});
    Check("0x3438:0x0206 → eYs3D", e != nullptr && e->capabilities().vendor == "eYs3D");
    ICameraDriver* b = reg.MatchByUsbId(UsbId{0x0603, 0x001f});
    Check("0x0603:0x001f → Berxel", b != nullptr && b->capabilities().model == "iHawkP100R3");
    Check("未知 id → nullptr", reg.MatchByUsbId(UsbId{0x1234, 0x5678}) == nullptr);
    Check("Knows 认领集", reg.Knows(UsbId{0x3438, 0x0206}) && !reg.Knows(UsbId{0xdead, 0xbeef}));
    Check("all_known_ids 并集=2", reg.all_known_ids().size() == 2);

    // 分发出会话可用。
    auto sess2 = e->open_fd({7}, SessionConfig{});
    Check("分发 driver 出会话", sess2 != nullptr);
  }

  std::printf("camera_abstraction_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}

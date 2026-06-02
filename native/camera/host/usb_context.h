// host(Linux libusb)USB 上下文 — gomob::camera::UsbContext 的具体定义。
//
// camera_session.h 仅前置声明 class UsbContext(让 ICameraDriver::open_host 签名跨平台);
// 这里在 host 层给出 libusb-backed 定义。Berxel/eYs3D 等 host driver 共用。
// Android 路径走 open_fd 不需要它。
#pragma once

#include <libusb-1.0/libusb.h>

#include <cstdint>

namespace gomob::camera {

// 用 libusb【默认 context】(nullptr),与 proven eys3d_replay_stream 全程一致:
// open/handle_events 都用默认 context,会话线程无需持有 context 指针即可 handle_events(nullptr)。
// 默认 context 是 refcounted 的,init/exit 可多次配对。
class UsbContext {
 public:
  UsbContext() { ok_ = (libusb_init(nullptr) == 0); }
  ~UsbContext() {
    if (ok_) libusb_exit(nullptr);
  }
  UsbContext(const UsbContext&) = delete;
  UsbContext& operator=(const UsbContext&) = delete;

  bool valid() const { return ok_; }

  // 按 vid/pid 打开第一个匹配设备,返回 handle(调用方负责 libusb_close);不存在 → nullptr。
  libusb_device_handle* open(uint16_t vid, uint16_t pid) const {
    return libusb_open_device_with_vid_pid(nullptr, vid, pid);
  }

 private:
  bool ok_ = false;
};

}  // namespace gomob::camera

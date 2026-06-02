// eYs3D / Etron host(Linux libusb)UVC 设备实现 — gomob::camera::IUvcDevice。
//
// 镜像 native/berxel/host 的 libusb 思路:auto-detach kernel driver + claim + 标准 UVC class control。
// host 取流(eys3d_replay_stream)与 host 会话(Eys3dHostSession)共用同一份,避免重复实现。
// Android 路径用各自的 fd-based IUvcDevice,不在此。
//
// Etron XU/UVC 控制传输映射(反汇编 + usbmon 实证,见
// docs/agent-memory/finding_rsd550_open_sequence_decoded_2026-06-01.md):
//   SET_CUR  bmReqType=0x21 bReq=0x01;GET_CUR 0xA1/0x81;GET_DEF 0xA1/0x87。
//   XU 写 wValue=0x0300 wIndex=0x0400;VS PROBE/COMMIT wValue=0x0100/0x0200 wIndex=接口号。
#pragma once

#include <libusb-1.0/libusb.h>

#include <cstdint>
#include <cstdio>
#include <vector>

#include "camera/camera_device.h"  // gomob::camera::IUvcDevice

namespace gomob::eys3d::host {

class Eys3dUsbDevice : public gomob::camera::IUvcDevice {
 public:
  explicit Eys3dUsbDevice(libusb_device_handle* handle) : handle_(handle) {
    if (handle_) libusb_set_auto_detach_kernel_driver(handle_, 1);
  }
  ~Eys3dUsbDevice() override {
    for (int iface : claimed_) libusb_release_interface(handle_, iface);
    if (handle_) libusb_close(handle_);
  }

  Eys3dUsbDevice(const Eys3dUsbDevice&) = delete;
  Eys3dUsbDevice& operator=(const Eys3dUsbDevice&) = delete;

  // 从 uvcvideo 抢占接口(IF0=XU 通道 / IF1=color / IF2=depth 都要 claim)。
  bool claim(int iface) {
    if (!handle_) return false;
    if (libusb_kernel_driver_active(handle_, iface) == 1)
      libusb_detach_kernel_driver(handle_, iface);
    const int rc = libusb_claim_interface(handle_, iface);
    if (rc != 0) {
      std::fprintf(stderr, "[eys3d] claim iface %d rc=%d %s\n", iface, rc, libusb_error_name(rc));
      return false;
    }
    claimed_.push_back(iface);
    return true;
  }

  libusb_device_handle* handle() const { return handle_; }
  int clear_halt(uint8_t ep) { return handle_ ? libusb_clear_halt(handle_, ep) : -1; }

  int control_transfer(uint8_t t, uint8_t r, uint16_t v, uint16_t i, uint8_t* d, uint16_t l,
                       uint32_t to) override {
    return handle_ ? libusb_control_transfer(handle_, t, r, v, i, d, l, to)
                   : LIBUSB_ERROR_NO_DEVICE;
  }
  int uvc_set_cur(uint16_t v, uint16_t i, uint8_t* d, uint16_t l, uint32_t to) override {
    return control_transfer(0x21, 0x01, v, i, d, l, to);
  }
  int uvc_get_cur(uint16_t v, uint16_t i, uint8_t* d, uint16_t l, uint32_t to) override {
    return control_transfer(0xA1, 0x81, v, i, d, l, to);
  }
  int uvc_get_def(uint16_t v, uint16_t i, uint8_t* d, uint16_t l, uint32_t to) override {
    return control_transfer(0xA1, 0x87, v, i, d, l, to);
  }
  int bulk_in(uint8_t ep, uint8_t* d, int l, int* a, uint32_t to) override {
    return handle_ ? libusb_bulk_transfer(handle_, ep, d, l, a, to) : LIBUSB_ERROR_NO_DEVICE;
  }

 private:
  libusb_device_handle* handle_ = nullptr;
  std::vector<int> claimed_;
};

}  // namespace gomob::eys3d::host

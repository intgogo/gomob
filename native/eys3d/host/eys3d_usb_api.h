// eYs3D 取流的 libusb 函数表（后端可切换）。
//
// 背景：Android 真机上自研"现代 libusb wrap_sys_device" bulk 取流只吐 ~2 帧对即停，而 VINCreator/eSPDI 用
//   saki4510t fork 的 libusb100（导出 libusb_get_device_with_fd）能连续出流（同机直插、纯 bulk 设备）。
//   但 gomob_native 同时被 Berxel 用现代 libusb-1.0+wrap_sys_device，一个 .so 不能链两个同名符号 libusb，
//   且两个 libusb 共存有符号互位风险。故 eYs3D 会话侧用本函数表：Android 经 dlopen(libusb100, RTLD_LOCAL)+dlsym 填表，
//   host 直接填真实 libusb_* 指针。流循环/设备封装只经本表调用，Berxel 路径完全不动。
//
// 注：libusb_fill_bulk/interrupt_transfer 是 libusb.h 里的 inline（只写 transfer 结构体字段，不调符号），
//   且 libusb_transfer ABI 跨 libusb-1.0 各 fork 稳定，故 fill_* 直接复用现代头，无需进本表。
#pragma once

#include <libusb-1.0/libusb.h>

namespace gomob::eys3d {

struct UsbApi {
  int (*init)(libusb_context**) = nullptr;
  // saki4510t fork：带 usbfs 根路径的 init（"/dev/bus/usb"），跳过被 SELinux 拒的目录自动枚举。host 无此变体（=nullptr）。
  int (*init2)(libusb_context**, const char* usbfs) = nullptr;
  void (*exit)(libusb_context*) = nullptr;
  // saki4510t fork 专属：从 Java 拿到的 usbfs fd + vid/pid/busnum/devaddr 重建 libusb_device（替代 wrap_sys_device）。
  // host 路径不用它（用 wrap_sys_device，见 open_android 标志），置 nullptr。
  libusb_device* (*get_device_with_fd)(libusb_context*, int vid, int pid, const char* serial,
                                       int fd, int busnum, int devaddr) = nullptr;
  int (*wrap_sys_device)(libusb_context*, intptr_t fd, libusb_device_handle**) = nullptr;
  int (*open)(libusb_device*, libusb_device_handle**) = nullptr;
  void (*close)(libusb_device_handle*) = nullptr;
  int (*claim_interface)(libusb_device_handle*, int) = nullptr;
  int (*release_interface)(libusb_device_handle*, int) = nullptr;
  int (*control_transfer)(libusb_device_handle*, uint8_t, uint8_t, uint16_t, uint16_t,
                          unsigned char*, uint16_t, unsigned int) = nullptr;
  libusb_transfer* (*alloc_transfer)(int) = nullptr;
  int (*submit_transfer)(libusb_transfer*) = nullptr;
  int (*cancel_transfer)(libusb_transfer*) = nullptr;
  void (*free_transfer)(libusb_transfer*) = nullptr;
  int (*handle_events_timeout)(libusb_context*, struct timeval*) = nullptr;
  // ★ 阻塞事件收割(libuvc/Etron SDK 事件线程用的就是这个,见反汇编 _uvc_handle_events + libuvc init.c:89)。
  //   saki4510t libusb100 后端下:带 timeout 的 poll 对 bulk URB 漏投递完成(中断 IN 正常、bulk IN 0 完成),
  //   阻塞 handle_events(_completed) 才正常投递 bulk 完成 → 这是"手动 2 帧停 vs libuvc 连续"的真因.
  int (*handle_events)(libusb_context*) = nullptr;
  int (*handle_events_completed)(libusb_context*, int*) = nullptr;
  const char* (*error_name)(int) = nullptr;
  int (*clear_halt)(libusb_device_handle*, unsigned char) = nullptr;
  int (*reset_device)(libusb_device_handle*) = nullptr;
  int (*set_auto_detach_kernel_driver)(libusb_device_handle*, int) = nullptr;
  int (*kernel_driver_active)(libusb_device_handle*, int) = nullptr;
  int (*detach_kernel_driver)(libusb_device_handle*, int) = nullptr;
  int (*bulk_transfer)(libusb_device_handle*, unsigned char, unsigned char*, int, int*, unsigned int) = nullptr;
};

// 进程级单例（eYs3D 单会话）。流循环/设备封装通过它调用。
UsbApi& Eys3dUsb();

// host：填真实链接的 libusb_* 指针（直接调）。
void Eys3dUsbSetHostApi();

}  // namespace gomob::eys3d

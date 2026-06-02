// eYs3D RS-D550 传输无关取流主循环 — host(libusb vid/pid open)与 Android(libusb_wrap_sys_device(fd))共调。
//
// 把开流序列回放 + 双端点异步多 URB + 单线程 FID 组装 + 喂 Eys3dSessionCore 整段抽成自由函数,
// 两个会话壳(Eys3dHostSession / Eys3dFdSession)只负责【拿到 libusb_device_handle*】,流逻辑零重复。
// 唯一差异是 handle 来源:host 用 ctx.open(vid,pid);Android 用 libusb_wrap_sys_device(fd)。
//
// ★ 开流序列当前用 ProvenWrongModePlan(1280×480 错模式,实证能出流但深度退化为列恒定垃圾);
//   mode25 正确开流值(reg 0xF0/PROBE 分辨率/depthType36 线编码)待真机锁定换 Mode25Usb2Plan。
//   见 docs/architecture/13-eys3d-driver.md §2bis、TODO M6.5。
#pragma once
#include <cstdint>
#include <vector>

#include "camera/camera_session.h"               // gomob::camera::SessionConfig
#include "eys3d/portable/eys3d_protocol.h"        // ArmConfig
#include "eys3d/portable/eys3d_session_core.h"    // Eys3dSessionCore

struct libusb_device_handle;  // 前置声明:头不拉 libusb,壳可保持 libusb-free
struct libusb_context;

namespace gomob::eys3d::host {

// 单条 VS 流的开流 + 取流参数。
struct Eys3dStreamPlan {
  uint8_t endpoint = 0;          // 0x81 color / 0x82 depth
  int vs_interface = 0;          // 1 / 2
  int urb_size = 0;              // = dwMaxPayloadTransferSize(一 URB 一 payload)
  int frame_bytes = 0;           // 组装出的整帧字节
  std::vector<uint8_t> probe_zero;  // 26B PROBE 零负载(探询)
  std::vector<uint8_t> probe_neg;   // 26B PROBE/COMMIT 协商负载(带 maxFrame/maxPayload)
};

// 完整开流计划(arming 寄存器 + 双流 PROBE/COMMIT)。
struct Eys3dOpenPlan {
  ArmConfig arm;
  Eys3dStreamPlan color;
  Eys3dStreamPlan depth;
};

// proven 但【错配置】计划(videoMode=0x02=14bit + 1280×480 YUYV color + depth 1228800B)。
// 仅证流通(实测能出流),深度是列恒定垃圾。保留作回归/对比,不应作生产默认。
Eys3dOpenPlan ProvenWrongModePlan();

// ★ mode25 正确计划(ROSIE4/USB2,本设备 PID 0x0206):videoMode=36(0x24,离线锁定)+
//   color 1280×256 MJPEG@5 + depth 640×128@5 + interleave off。生产默认。
// VS bFrameIndex 由 scripts/eys3d-parse-descriptor.py 解析真机 lsusb -v 自动填(默认最佳估值 color=2/depth=4)。
// 【device-gated 未定项】:depth 传输帧是否含状态行(depth_status_rows,影响 frame_bytes)。
//   videoMode=36 与分辨率是离线确定的;帧索引/状态行是流协商细节,真机 lsusb 一抓即定。
Eys3dOpenPlan Mode25Usb2Plan(uint8_t color_frame_index = 2, uint8_t depth_frame_index = 4,
                             uint16_t depth_status_rows = 0);

// 传输无关取流主循环:reset + claim IF0/1/2 + 回放开流序列 + 双端点异步 32 URB + 单线程 FID 组装 → 喂 core。
// handle 须已 open(host:ctx.open(vid,pid);Android:libusb_wrap_sys_device(fd))。
// ctx = handle 所属 libusb context(handle_events 必须在此 context 上;host 默认 context 传 nullptr,
//       Android 具名 context 传该指针)。否则具名 context 的 URB 不被服务 → 0 帧。
// 停止信号取 core.stop_requested();core 出 MarkStreaming/MarkStopped/MarkError + depthMm/color 帧。
// core 须在调用前已 Configure + 注入度量(ZdTable/几何)。
void RunEys3dStreamLoop(libusb_context* ctx, libusb_device_handle* handle, const Eys3dOpenPlan& plan,
                        const gomob::camera::SessionConfig& cfg, Eys3dSessionCore& core);

}  // namespace gomob::eys3d::host

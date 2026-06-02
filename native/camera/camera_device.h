// 厂商无关相机 I/O 层(native/camera)。
//
// 设计见 docs/architecture/12-camera-abstraction.md §1.1。
// 现阶段策略:IUvcDevice / XuPayload / UVC 协商 / 帧组装器等**平台无关复用件**当前物理位于
// native/berxel/portable/gomob_berxel_portable.h(gomob::berxel::host),已完全厂商无关。
// 为不退化 Berxel(硬约束),此处用 `using` 把它们**再导出**到 gomob::camera,
// 新 driver(eYs3D 等)只引用 gomob::camera::*;物理迁移(改 berxel 命名空间)留作后续 cleanup。
#pragma once

#include "gomob_berxel_portable.h"

namespace gomob::camera {

// ---- USB / XU 基础类型 ----
using UsbId = ::gomob::berxel::host::UsbId;
using XuPayload = ::gomob::berxel::host::XuPayload;

// ---- 平台无关 USB 设备抽象(libusb/Android fd 各自实现)----
using IUvcDevice = ::gomob::berxel::host::IUvcDevice;

// ---- UVC 协商 / 帧组装(标准 UVC,Berxel/eYs3D 共用)----
using UvcStreamConfig = ::gomob::berxel::host::UvcStreamConfig;
using UvcNegotiation = ::gomob::berxel::host::UvcNegotiation;
using BulkStats = ::gomob::berxel::host::BulkStats;
using UvcRawFrameAssembler = ::gomob::berxel::host::UvcRawFrameAssembler;
using UvcMjpegFrameAssembler = ::gomob::berxel::host::UvcMjpegFrameAssembler;
using RgbdFramePairer = ::gomob::berxel::host::RgbdFramePairer;

// ---- 自由函数再导出 ----
using ::gomob::berxel::host::negotiate_uvc_stream;
using ::gomob::berxel::host::parse_xu_payloads;
using ::gomob::berxel::host::replay_xu_payloads;

}  // namespace gomob::camera

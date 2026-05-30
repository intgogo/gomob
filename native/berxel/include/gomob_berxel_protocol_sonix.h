// Berxel iHawk P100R3 companion chip (Sonix XU 系) 私有 USB 协议复现层
//
// 替换 Berxel Android SDK 9.9.190 内嵌的 libuvc-0.0.7 + 自家 libusb stack
// （详见 docs/architecture/10-android-uvc-stack-rewrite.md M1.6 系列）。
//
// 协议设计来源：
// - .dev/m1.6.1-protocol-reverse/sonix-cmd-table.md  Linux SDK 反编译
// - .dev/m1.6.2-usb-trace/report.md                  Linux SDK USB trace ground truth
//
// 跟 USB trace 一一对应的字节序列：
//   asic_write(reg=R, val=V) →
//     bmRequestType=0x21 bRequest=0x01 wValue=0x0100 wIndex=0x0300 wLength=4
//     data=[R&0xff, (R>>8)&0xff, V, 0x00]
//   asic_read(reg=R) →
//     bmRequestType=0xa1 bRequest=0x81 wValue=0x0100 wIndex=0x0300 wLength=4
//   batch_cmd(selector=S, buf, len) →
//     bmRequestType=0x21 bRequest=0x01 wValue=(S<<8) wIndex=0x0300 wLength=len
//   xu_set_cur(selector=S, buf, len) → 同 batch_cmd
//   xu_get_cur(selector=S, buf, len) →
//     bmRequestType=0xa1 bRequest=0x81 wValue=(S<<8) wIndex=0x0300 wLength=len

#ifndef GOMOB_BERXEL_PROTOCOL_SONIX_H
#define GOMOB_BERXEL_PROTOCOL_SONIX_H

#include <cstdint>

// 前向声明：host test 用 mock，Android build link 真实 libusb
struct libusb_device_handle;

namespace gomob::berxel {

// USB / UVC 常量（公开，便于 test 断言）
constexpr uint8_t kSonixXuUnit = 0x03;           // Extension Unit ID 3 在 Interface 0
constexpr uint8_t kAsicXuSelector = 0x01;        // selector 0x01 = ASIC reg I/O
constexpr uint16_t kAsicXuLength = 4;            // 每次 4 byte: [reg_lo, reg_hi, value, status]

constexpr uint8_t kBmRequestTypeSetCur = 0x21;   // OUT | Class | Interface
constexpr uint8_t kBmRequestTypeGetCur = 0xa1;   // IN  | Class | Interface
constexpr uint8_t kBRequestSetCur = 0x01;        // UVC SET_CUR
constexpr uint8_t kBRequestGetCur = 0x81;        // UVC GET_CUR
constexpr uint32_t kDefaultTimeoutMs = 1000;

// libusb_control_transfer 抽象 — 让 host test 能 mock，Android build 直转 libusb
//
// 不直接 #include <libusb-1.0/libusb.h> 是因为：
// 1. host test 不 link libusb；
// 2. 头文件极简化，便于阅读 / 单元测试。
using ControlTransferFn = int (*)(
    libusb_device_handle* handle,
    uint8_t bmRequestType,
    uint8_t bRequest,
    uint16_t wValue,
    uint16_t wIndex,
    uint8_t* data,
    uint16_t wLength,
    uint32_t timeout
);

class BerxelProtocolSonix {
public:
    // ctor: 默认 transfer_fn = nullptr 时 Android build 应该接入真 libusb_control_transfer
    BerxelProtocolSonix(libusb_device_handle* handle, ControlTransferFn transfer_fn);

    // 5 个核心入口（M1.6.1 反编译结论 + M1.6.2 USB trace ground truth）

    // 标准 UVC XU SET_CUR — 任意 selector + payload length
    int xu_set_cur(uint8_t selector, const uint8_t* buf, uint16_t len,
                   uint32_t timeout_ms = kDefaultTimeoutMs);

    // 标准 UVC XU GET_CUR — 任意 selector + payload length
    int xu_get_cur(uint8_t selector, uint8_t* buf, uint16_t len,
                   uint32_t timeout_ms = kDefaultTimeoutMs);

    // ASIC 单寄存器写 (selector 0x01, wLength=4, [reg_lo, reg_hi, val, 0])
    int asic_write(uint16_t reg_addr, uint8_t value,
                   uint32_t timeout_ms = kDefaultTimeoutMs);

    // ASIC 单寄存器读 (selector 0x01, wLength=4, 返回 buf[2])
    // 返回 < 0 = error；>= 0 = 寄存器值
    int asic_read(uint16_t reg_addr, uint32_t timeout_ms = kDefaultTimeoutMs);

    // 批量 vendor command (selector 0x19 / 0x1e / ...)
    // 跟 xu_set_cur 同名但语义化区分（用于 stream_ctrl 块 / params upload）
    int batch_cmd(uint8_t selector, const uint8_t* buf, uint16_t len,
                  uint32_t timeout_ms = kDefaultTimeoutMs);

private:
    libusb_device_handle* m_handle;
    ControlTransferFn m_transfer;
};

} // namespace gomob::berxel

#endif // GOMOB_BERXEL_PROTOCOL_SONIX_H

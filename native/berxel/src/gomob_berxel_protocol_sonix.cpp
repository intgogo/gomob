// Berxel Sonix XU 协议复现 — 字节序列必须跟 .dev/m1.6.2-usb-trace/ ground truth 完全一致

#include "gomob_berxel_protocol_sonix.h"

namespace gomob::berxel {

BerxelProtocolSonix::BerxelProtocolSonix(libusb_device_handle* handle,
                                         ControlTransferFn transfer_fn)
    : m_handle(handle), m_transfer(transfer_fn) {}

int BerxelProtocolSonix::xu_set_cur(uint8_t selector, const uint8_t* buf,
                                    uint16_t len, uint32_t timeout_ms) {
    if (!m_transfer || !m_handle) return -1;
    // UVC SET_CUR: wValue 高字节 = selector，wIndex 高字节 = unit
    const uint16_t wValue = static_cast<uint16_t>(selector) << 8;
    const uint16_t wIndex = static_cast<uint16_t>(kSonixXuUnit) << 8;
    return m_transfer(m_handle, kBmRequestTypeSetCur, kBRequestSetCur,
                      wValue, wIndex, const_cast<uint8_t*>(buf), len, timeout_ms);
}

int BerxelProtocolSonix::xu_get_cur(uint8_t selector, uint8_t* buf,
                                    uint16_t len, uint32_t timeout_ms) {
    if (!m_transfer || !m_handle) return -1;
    const uint16_t wValue = static_cast<uint16_t>(selector) << 8;
    const uint16_t wIndex = static_cast<uint16_t>(kSonixXuUnit) << 8;
    return m_transfer(m_handle, kBmRequestTypeGetCur, kBRequestGetCur,
                      wValue, wIndex, buf, len, timeout_ms);
}

int BerxelProtocolSonix::asic_write(uint16_t reg_addr, uint8_t value,
                                    uint32_t timeout_ms) {
    // 字节序列：[reg_lo, reg_hi, value, 0x00]，wLength=4，selector=0x01
    uint8_t buf[kAsicXuLength];
    buf[0] = static_cast<uint8_t>(reg_addr & 0xff);
    buf[1] = static_cast<uint8_t>((reg_addr >> 8) & 0xff);
    buf[2] = value;
    buf[3] = 0x00;
    return xu_set_cur(kAsicXuSelector, buf, kAsicXuLength, timeout_ms);
}

int BerxelProtocolSonix::asic_read(uint16_t reg_addr, uint32_t timeout_ms) {
    // 先 SET_CUR 写 reg_addr，再 GET_CUR 读回 4 字节
    // 字节布局：写 [reg_lo, reg_hi, 0, 0]，读回 [reg_lo, reg_hi, value, status]
    uint8_t buf[kAsicXuLength];
    buf[0] = static_cast<uint8_t>(reg_addr & 0xff);
    buf[1] = static_cast<uint8_t>((reg_addr >> 8) & 0xff);
    buf[2] = 0x00;
    buf[3] = 0x00;
    int rc = xu_set_cur(kAsicXuSelector, buf, kAsicXuLength, timeout_ms);
    if (rc < 0) return rc;
    rc = xu_get_cur(kAsicXuSelector, buf, kAsicXuLength, timeout_ms);
    if (rc < 0) return rc;
    return buf[2]; // value in slot 2
}

int BerxelProtocolSonix::batch_cmd(uint8_t selector, const uint8_t* buf,
                                   uint16_t len, uint32_t timeout_ms) {
    // 跟 xu_set_cur 语义一致，命名区分用于 stream_ctrl / params upload 等大块 vendor cmd
    return xu_set_cur(selector, buf, len, timeout_ms);
}

} // namespace gomob::berxel

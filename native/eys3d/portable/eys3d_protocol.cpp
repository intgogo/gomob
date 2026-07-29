#include "eys3d_protocol.h"

namespace gomob::eys3d {

namespace {
// Etron XU:selector=3 → wValue=0x0300;unit id=4, iface=0 → wIndex=0x0400。
constexpr uint8_t kSelector = 3;
constexpr uint16_t kWValue = 0x0300;
constexpr uint16_t kWIndex = 0x0400;

XuPayload Make4(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3) {
  return XuPayload{kSelector, kWValue, kWIndex, {b0, b1, b2, b3}};
}
}  // namespace

XuPayload MakeFwWrite(uint8_t addr, uint8_t value) { return Make4(0x20, addr, value, 0x00); }
XuPayload MakeFwRead(uint8_t addr) { return Make4(0x82, addr, 0x00, 0x00); }
XuPayload MakeHwWrite8(uint8_t addr, uint8_t value) { return Make4(0x00, addr, value, 0x00); }
XuPayload MakeHwWrite16(uint16_t addr, uint8_t value) {
  return Make4(0x02, static_cast<uint8_t>(addr & 0xFF), static_cast<uint8_t>(addr >> 8), value);
}
XuPayload MakeCounterTick(uint8_t n) { return XuPayload{kCounterSelector, kCounterWValue, kWIndex, {n}}; }

XuPayload MakeSetVideoModeReg(uint8_t videomode_reg_value) {
  return MakeFwWrite(kRegVideoMode, videomode_reg_value);
}
XuPayload MakeSetInterleave(bool on) { return MakeFwWrite(kRegInterleave, on ? 0x01 : 0x00); }
XuPayload MakeSetStartTrigger() { return MakeFwWrite(kRegStartTrigger, 0x00); }
XuPayload MakeSetIrCurrent(uint8_t current) { return MakeFwWrite(kRegIrCurrent, current); }

ArmConfig DefaultRsd550Usb2() {
  ArmConfig c;
  c.videomode_reg = kVideoModeRegMode25;  // 36(0x24)= mode25 videoMode=depthDataType,离线锁定
  c.interleave = false;
  c.ir_current = 3;
  return c;
}

std::vector<XuPayload> BuildArmSequence(const ArmConfig& cfg) {
  std::vector<XuPayload> seq;
  seq.push_back(MakeSetVideoModeReg(cfg.videomode_reg));
  seq.push_back(MakeSetInterleave(cfg.interleave));
  if (cfg.ir_current > 0) seq.push_back(MakeSetIrCurrent(cfg.ir_current));
  return seq;
}

}  // namespace gomob::eys3d

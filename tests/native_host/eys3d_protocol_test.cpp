// eYs3D XU arming 协议 host 单测 — 验证 XU 写 wire 字节与反汇编模板一致。
#include "eys3d/portable/eys3d_protocol.h"

#include <cstdio>
#include <cstdlib>

using namespace gomob::eys3d;

namespace {
int g_fail = 0;
void CheckPayload(const char* tag, const XuPayload& p, uint16_t wv, uint16_t wi,
                  uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3) {
  bool ok = p.w_value == wv && p.w_index == wi && p.data.size() == 4 &&
            p.data[0] == b0 && p.data[1] == b1 && p.data[2] == b2 && p.data[3] == b3;
  std::printf("  %-34s wV=%04x wI=%04x data=[%02x %02x %02x %02x] -> %s\n", tag,
              p.w_value, p.w_index,
              p.data.size() > 0 ? p.data[0] : 0, p.data.size() > 1 ? p.data[1] : 0,
              p.data.size() > 2 ? p.data[2] : 0, p.data.size() > 3 ? p.data[3] : 0,
              ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
void Check(const char* tag, bool ok) {
  std::printf("  %-34s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
}  // namespace

int main() {
  // FW 写模板:wV=0x0300 wI=0x0400 data={0x20,addr,val,0x00}
  CheckPayload("FwWrite(0xF0,0x02)", MakeFwWrite(0xF0, 0x02), 0x0300, 0x0400, 0x20, 0xF0, 0x02, 0x00);
  // FW 读:opcode 0x82
  CheckPayload("FwRead(0xF0)", MakeFwRead(0xF0), 0x0300, 0x0400, 0x82, 0xF0, 0x00, 0x00);
  // SetVideoModeReg(36=0x24) = 写 reg 0xF0 = videoMode 字段 = depthDataType(mode25 离线锁定值)
  CheckPayload("SetVideoModeReg(36)", MakeSetVideoModeReg(36), 0x0300, 0x0400, 0x20, 0xF0, 0x24, 0x00);
  // interleave off = FW 0xED 0x00
  CheckPayload("SetInterleave(false)", MakeSetInterleave(false), 0x0300, 0x0400, 0x20, 0xED, 0x00, 0x00);
  CheckPayload("SetInterleave(true)", MakeSetInterleave(true), 0x0300, 0x0400, 0x20, 0xED, 0x01, 0x00);
  // 启动触发 = FW 0xF5 0x00
  CheckPayload("SetStartTrigger", MakeSetStartTrigger(), 0x0300, 0x0400, 0x20, 0xF5, 0x00, 0x00);
  // IR current = FW 0xE0
  CheckPayload("SetIrCurrent(3)", MakeSetIrCurrent(3), 0x0300, 0x0400, 0x20, 0xE0, 0x03, 0x00);
  // HW 写 8/16-bit
  CheckPayload("HwWrite8(0x12,0x34)", MakeHwWrite8(0x12, 0x34), 0x0300, 0x0400, 0x00, 0x12, 0x34, 0x00);
  CheckPayload("HwWrite16(0x1234,0x56)", MakeHwWrite16(0x1234, 0x56), 0x0300, 0x0400, 0x02, 0x34, 0x12, 0x56);
  // 计数器握手:selector 0x0a,wValue=0x0a00,1 字节序号
  {
    XuPayload t = MakeCounterTick(0x07);
    Check("CounterTick selector=0x0a", t.selector == 0x0a);
    Check("CounterTick wV=0x0a00 wI=0x0400 data=[07]",
          t.w_value == 0x0a00 && t.w_index == 0x0400 && t.data.size() == 1 && t.data[0] == 0x07);
  }
  // selector 字段
  Check("FwWrite selector=3", MakeFwWrite(0, 0).selector == 3);

  // USB2/ROSIE4 mode25 arming 序列 = [VideoModeReg(36), Interleave(off), IrCurrent(3)]
  {
    auto seq = BuildArmSequence(DefaultRsd550Usb2());
    Check("arm 序列 3 步", seq.size() == 3);
    if (seq.size() == 3) {
      CheckPayload("arm[0] VideoModeReg(36)", seq[0], 0x0300, 0x0400, 0x20, 0xF0, 0x24, 0x00);
      CheckPayload("arm[1] Interleave(off)", seq[1], 0x0300, 0x0400, 0x20, 0xED, 0x00, 0x00);
      CheckPayload("arm[2] IrCurrent(3)", seq[2], 0x0300, 0x0400, 0x20, 0xE0, 0x03, 0x00);
    }
    // ir_current=0 时跳过 IR
    ArmConfig c = DefaultRsd550Usb2();
    c.ir_current = 0;
    Check("ir_current=0 → 2 步", BuildArmSequence(c).size() == 2);
  }

  std::printf("eys3d_protocol_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}

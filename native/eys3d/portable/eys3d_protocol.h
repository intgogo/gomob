// eYs3D / Etron RS-D550 XU 开流激活协议(纯数据,无 libusb)。
//
// 复刻 APK/eSPDI 的设备 arming(见 docs/architecture/13-eys3d-driver.md §2/§2bis)。
// 所有 XU 控制传输模板(反汇编 libESPDI CVideoDevice::SetPropertyValue 确证):
//   SET_CUR: bmReqType=0x21 bReq=0x01 wValue=0x0300 wIndex=0x0400 wLength=4
//   GET_CUR: bmReqType=0xA1 bReq=0x81 同 wValue/wIndex/wLength(回读校验)
//   payload 4 字节 = {opcode, addr, value, 0x00};FW 写 opcode=0x20。
// XU unit id=4, iface=0 → wIndex=0x0400;wValue 高字节 selector=3。
//
// 用法:把 BuildArmSequence(...) 出的 payloads 经 camera/berxel 的 replay_xu_payloads()
//       在标准 UVC probe/commit 之前注入(claim IF0 之后)。
#pragma once
#include <cstdint>
#include <vector>

#include "camera/camera_device.h"  // gomob::camera::XuPayload(厂商无关再导出)

namespace gomob::eys3d {

using XuPayload = gomob::camera::XuPayload;

// Etron XU 寄存器地址(FW 空间,反汇编/逆向锁定)。
constexpr uint8_t kRegVideoMode = 0xF0;     // videoMode 寄存器:写 ApcCamera videoMode 字段(=depthDataType,见下)
constexpr uint8_t kRegInterleave = 0xED;    // interleave on/off
constexpr uint8_t kRegStartTrigger = 0xF5;  // post-commit 启动触发(proven 写 0)
constexpr uint8_t kRegIrCurrent = 0xE0;     // IR 投射器电流(facet A: FIRMWARE_ADDRESS_IR_CURRENT_VALUE=224)
// 注:IR mode(开关通道)/ IR max 的确切 FW 寄存器待 eSPDI 反汇编 APC_SetIRMode/SetIRMaxValue 锁定。
//     当前 arming 用 IR current 已足够点亮投射器;mode/max 走 SDK 路径或后续补。

// ★★ 2026-06-01 反编译订正:reg 0xF0 写的就是 ApcCamera 的 videoMode 字段 = depthDataType(二者同值)。
//   离线证据(零真机,jadx 反编译 VINCreator,功能互操作):
//     - CameraModeKt DEFAULT_ROSIE4_U2_MODE = CameraMode(id, isUsb3=false, mode=25, **videoMode=36**,
//       color 1280×256@5, depth 640×128@5)。ROSIE4 = 本设备 PID 0x0206;mode550=ROSIE2 是别的相机。
//     - CameraPresenter setVideoMode(getVideoMode()) → SetVideoMode 反汇编 @0x474b0 写 FW 0xF0 = 入参
//       → wire [0x20,0xF0,0x24,0x00]。36 = DEPTH_DATA_11_BITS(4)+SCALE_DOWN_OFFSET(32),自洽。
//   ⇒ mode25 写 0xF0 = 36(0x24)。旧 0x02 = 工程师手动强跑 DEPTH_DATA_14_BITS(1280×480 错配置)的
//     实测值,是深度退化为列恒定垃圾的真因。详见 finding_rsd550_open_sequence_decoded_2026-06-01。
constexpr uint8_t kVideoModeRegMode25 = 36;              // ★ ROSIE4/USB2 mode25 正确值(=depthDataType,离线锁定)
constexpr uint8_t kVideoModeRegWrongConfig14Bit = 0x02;  // 旧"proven"值,实为 14bit 错配置,仅留对比

// depthDataType(eSPDI_def.h)= 写 reg 0xF0 的 videoMode 字段。ROSIE4/USB2(mode25)=36;USB3=4。
constexpr uint8_t kDepthTypeRectify11 = 4;        // USB3, depth 640x480(RECTIFY_11_BITS)
constexpr uint8_t kDepthTypeScaleDown11 = 36;     // USB2, depth 640x128(SCALE_DOWN_11_BITS)= mode25

// XU 计数器握手:selector 0x0a → wValue=0x0a00 wIndex=0x0400,1 字节递增事务序号。
constexpr uint8_t kCounterSelector = 0x0a;
constexpr uint16_t kCounterWValue = 0x0a00;

// ---- XU 读写原语(§2.2 模板)----
// FW 写:payload = {0x20, addr, value, 0x00}
XuPayload MakeFwWrite(uint8_t addr, uint8_t value);
// FW 读:payload = {0x82, addr, 0x00, 0x00}(opcode 0x82=读;配 GET_CUR 回读结果)
XuPayload MakeFwRead(uint8_t addr);
// HW 写 8-bit 地址:{0x00, addr, value, 0x00}
XuPayload MakeHwWrite8(uint8_t addr, uint8_t value);
// HW 写 16-bit 地址(flag bit1):{0x02, addr_lo, addr_hi, value}
XuPayload MakeHwWrite16(uint16_t addr, uint8_t value);
// 计数器握手:selector 0x0a,1 字节序号 n(固件事务序号,漏了写不进)。
XuPayload MakeCounterTick(uint8_t n);

// ---- 语义动作(映射到 FW 写)----
// SetVideoMode:写 reg 0xF0 = videoMode 字段(= depthDataType)。mode25 = 36(0x24)。
XuPayload MakeSetVideoModeReg(uint8_t videomode_reg_value);
// interleave on/off:写 FW 0xED。
XuPayload MakeSetInterleave(bool on);
// 启动触发:post-commit 写 FW 0xF5 = 0。
XuPayload MakeSetStartTrigger();
// IR 投射器电流:写 FW 0xE0。0 关,典型 3(IR_DEF_VALUE),max 6。
XuPayload MakeSetIrCurrent(uint8_t current);

// ---- arming 配置 + 序列 ----
struct ArmConfig {
  uint8_t videomode_reg = kVideoModeRegMode25;  // 写 reg 0xF0 的值 = videoMode 字段 = depthDataType;mode25=36
  bool interleave = false;                      // ROSIE4 mode25 关
  uint8_t ir_current = 3;                       // 0=不点投射器(钢板等无纹理目标需点亮)
};

// 默认 USB2/ROSIE4(本设备 PID 0x0206 经带电 hub)配置 = mode25:videomode_reg=36(离线锁定)。
ArmConfig DefaultRsd550Usb2();

// 构造寄存器 arming 序列(在标准 probe/commit 之前回放,不含计数器握手——计数器是跨整个开流
// 的有状态序号,由会话 opener 自行交织 MakeCounterTick;此处只出语义寄存器写)。
// 顺序:SetVideoModeReg → SetInterleave →(可选 IR current)。
std::vector<XuPayload> BuildArmSequence(const ArmConfig& cfg);

}  // namespace gomob::eys3d

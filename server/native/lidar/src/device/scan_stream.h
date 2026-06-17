// Device scan-stream wire parser (re/SPEC.md + re/spec_protocol.md §3).
// Frame: 12-byte header (CA FE | flags | msg_type | rsv | crc16@+6 | length@+8 LE)
// + `length` payload bytes (optionally zstd if flags bit7). CRC-16/MODBUS over
// the 4 length bytes + payload. Units: raw angle / 3600 = degrees; raw mm * 0.001 = meters.
#pragma once

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace lidar::device {

enum class MsgType : std::uint8_t { PTS = 'P', LDR = 'L', IMG = 'V', ENC = 'E', Unknown = 0 };

struct Frame {
  MsgType type{MsgType::Unknown};
  bool     compressed{false};
  std::uint16_t crc{0};
  bool     crc_ok{false};
  std::vector<std::uint8_t> payload;  // decompressed payload bytes
};

// One reconstructed 2-D laser line (polar). Live PTZ synthesis uses capture_angle_deg.
struct LdrPoint { double dist_m{0}; std::int32_t attr{0}; double v_angle_deg{0}; std::int32_t pt_seq{0}; };
struct LdrFrame {
  double h_angle_deg{0};
  // 4002 包内 h_angle 不是转台扫掠角；设备状态角用于几何合成、采集验收和 PCD 属性。
  double capture_angle_deg{std::numeric_limits<double>::quiet_NaN()};
  std::int32_t scan_seq{0};
  std::uint16_t segm_seq{0};
  double v_step_deg{0}, v_start_deg{0};
  std::int32_t ptseq_base{0};
  std::vector<LdrPoint> points;
};

struct PtsPoint { double x{0}, y{0}, z{0}; std::uint32_t attr{0}; };
struct PtsFrame {
  double h_angle_deg{0};
  std::int32_t scan_seq{0};
  std::uint16_t segm_seq{0};
  std::vector<PtsPoint> points;
};

struct EncFrame {
  std::int32_t pos{0}, pos_total{0};
  double zero_deg{0}, mono_s{0}, real_s{0};
};

struct ImgFrame {
  double h_angle_deg{0};           // axis heading of this frame (payload+0x0C, deci-arcsec; live-verified)
  std::int16_t width{0}, height{0};
  std::vector<std::uint8_t> jpeg;  // raw JPEG bytes (offset located by SOI scan; see decodeIMG)
};

// CRC-16/MODBUS (init 0xFFFF, poly 0xA001 reflected, no final xor).
std::uint16_t crc16_modbus(const std::uint8_t* data, std::size_t n);

// Parse one frame from a contiguous wire buffer [buf, buf+len). Returns the number
// of wire bytes consumed (12 + length), or 0 if the buffer is too short for a full
// frame. Sets err and returns 0 on a bad magic. Fills out.payload (decompressed) and
// out.crc_ok. Use the returned count to advance a streaming TCP buffer.
std::size_t parseFrame(const std::uint8_t* buf, std::size_t len, Frame& out, std::string& err);

// Typed payload decoders (payload = Frame::payload, already decompressed).
bool decodeLDR(const std::vector<std::uint8_t>& payload, LdrFrame& out);
bool decodePTS(const std::vector<std::uint8_t>& payload, PtsFrame& out);
bool decodeENC(const std::vector<std::uint8_t>& payload, EncFrame& out);
bool decodeIMG(const std::vector<std::uint8_t>& payload, ImgFrame& out);

}  // namespace lidar::device

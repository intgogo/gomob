#include "device/scan_stream.h"

#include <cstring>
#include <zstd.h>

namespace lidar::device {
namespace {

// Little-endian readers (the wire is LE per spec_protocol §3.1).
std::int32_t  rd_i32(const std::uint8_t* p) { std::int32_t v;  std::memcpy(&v, p, 4); return v; }
std::uint32_t rd_u32(const std::uint8_t* p) { std::uint32_t v; std::memcpy(&v, p, 4); return v; }
std::uint16_t rd_u16(const std::uint8_t* p) { std::uint16_t v; std::memcpy(&v, p, 2); return v; }
std::int16_t  rd_i16(const std::uint8_t* p) { std::int16_t v;  std::memcpy(&v, p, 2); return v; }
std::int64_t  rd_i64(const std::uint8_t* p) { std::int64_t v;  std::memcpy(&v, p, 8); return v; }

constexpr double DEG = 1.0 / 3600.0;  // raw deci-arcsec -> degrees
constexpr double MM  = 0.001;          // raw mm -> meters
constexpr std::size_t HDR = 12;
constexpr std::size_t ZSTD_CAP = 1u << 20;  // 1 MiB decompress cap (matches lts VA 0x140010ccd)

}  // namespace

std::uint16_t crc16_modbus(const std::uint8_t* d, std::size_t n) {
  std::uint16_t c = 0xFFFF;
  for (std::size_t i = 0; i < n; ++i) {
    c ^= d[i];
    for (int k = 0; k < 8; ++k) c = (c & 1) ? static_cast<std::uint16_t>((c >> 1) ^ 0xA001) : static_cast<std::uint16_t>(c >> 1);
  }
  return c;
}

std::size_t parseFrame(const std::uint8_t* buf, std::size_t len, Frame& out, std::string& err) {
  if (len < HDR) return 0;  // need a full header first
  if (buf[0] != 0xCA || buf[1] != 0xFE) { err = "bad magic"; return 0; }
  const bool compressed = (buf[2] & 0x80) != 0;
  const auto msg = static_cast<MsgType>(buf[3]);
  const std::uint16_t crc = rd_u16(buf + 6);
  const std::uint32_t plen = rd_u32(buf + 8);
  if (len < HDR + plen) return 0;  // wait for the rest of the payload

  // CRC-16/MODBUS over the 4 length bytes (buf+8) + payload (contiguous on the wire).
  const std::uint16_t calc = crc16_modbus(buf + 8, 4 + plen);

  out.type = msg;
  out.compressed = compressed;
  out.crc = crc;
  out.crc_ok = (calc == crc);

  const std::uint8_t* pay = buf + HDR;
  if (compressed) {
    out.payload.assign(ZSTD_CAP, 0);
    const std::size_t got = ZSTD_decompress(out.payload.data(), out.payload.size(), pay, plen);
    if (ZSTD_isError(got)) { err = std::string("zstd: ") + ZSTD_getErrorName(got); out.payload.clear(); return HDR + plen; }
    out.payload.resize(got);
  } else {
    out.payload.assign(pay, pay + plen);
  }
  return HDR + plen;
}

bool decodeLDR(const std::vector<std::uint8_t>& p, LdrFrame& out) {
  if (p.size() < 0x30) return false;
  const std::uint8_t* d = p.data();
  out.h_angle_deg = rd_i32(d + 0x0C) * DEG;
  out.scan_seq    = rd_i32(d + 0x14);
  out.v_step_deg  = rd_i32(d + 0x20) * DEG;
  out.v_start_deg = rd_i32(d + 0x24) * DEG;
  out.ptseq_base  = rd_i32(d + 0x28);
  out.segm_seq    = rd_u16(d + 0x2C);
  const std::uint16_t n = rd_u16(d + 0x2E);
  if (p.size() < 0x30 + std::size_t(n) * 8) return false;
  out.points.resize(n);
  for (std::uint16_t i = 0; i < n; ++i) {
    const std::uint8_t* r = d + 0x30 + std::size_t(i) * 8;
    out.points[i].dist_m      = rd_i32(r + 0) * MM;
    out.points[i].attr        = rd_i32(r + 4);
    out.points[i].v_angle_deg = out.v_start_deg + i * out.v_step_deg;  // implicit/linear
    out.points[i].pt_seq      = out.ptseq_base + i;
  }
  return true;
}

bool decodePTS(const std::vector<std::uint8_t>& p, PtsFrame& out) {
  if (p.size() < 0x18) return false;
  const std::uint8_t* d = p.data();
  out.h_angle_deg = rd_i32(d + 0x08) * DEG;
  out.scan_seq    = rd_i32(d + 0x0C);
  out.segm_seq    = rd_u16(d + 0x10);
  const std::uint16_t n = rd_u16(d + 0x16);
  if (p.size() < 0x18 + std::size_t(n) * 16) return false;
  out.points.resize(n);
  for (std::uint16_t i = 0; i < n; ++i) {
    const std::uint8_t* r = d + 0x18 + std::size_t(i) * 16;
    out.points[i].x    = rd_i32(r + 0) * MM;
    out.points[i].y    = rd_i32(r + 4) * MM;
    out.points[i].z    = rd_i32(r + 8) * MM;
    out.points[i].attr = rd_u32(r + 12);
  }
  return true;
}

bool decodeENC(const std::vector<std::uint8_t>& p, EncFrame& out) {
  if (p.size() < 0x28) return false;
  const std::uint8_t* d = p.data();
  out.pos       = rd_i32(d + 0x08);
  out.zero_deg  = rd_i32(d + 0x0C) * MM;  // raw * 0.001 -> degrees (spec §3.5)
  out.pos_total = rd_i32(d + 0x10);
  out.mono_s    = rd_i64(d + 0x18) * 1e-6;
  out.real_s    = rd_i64(d + 0x20) * 1e-6;
  return true;
}

bool decodeIMG(const std::vector<std::uint8_t>& p, ImgFrame& out) {
  if (p.size() < 0x18) return false;
  const std::uint8_t* d = p.data();
  out.h_angle_deg = rd_i32(d + 0x0C) * DEG;   // heading (live-verified: matches PTS h at same instant)
  out.width  = rd_i16(d + 0x14);
  out.height = rd_i16(d + 0x16);
  // JPEG offset is UNCERTAIN (spec §3.4): locate SOI (FF D8) from +0x18 onward.
  std::size_t soi = 0x18;
  for (std::size_t i = 0x18; i + 1 < p.size(); ++i)
    if (d[i] == 0xFF && d[i + 1] == 0xD8) { soi = i; break; }
  out.jpeg.assign(p.begin() + soi, p.end());
  return true;
}

}  // namespace lidar::device

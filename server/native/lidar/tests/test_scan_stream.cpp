// Offline unit test for the device scan-stream parser (no hardware).
// Validates CRC-16/MODBUS against a standard vector, then round-trips synthetic
// LDR/PTS frames built per re/spec_protocol.md §3 through parseFrame + decoders.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "device/scan_stream.h"

using namespace lidar::device;

static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)

static bool near(double a, double b, double tol) { return std::fabs(a - b) <= tol; }

static void put_i32(std::vector<std::uint8_t>& v, std::size_t off, std::int32_t x) {
  if (v.size() < off + 4) v.resize(off + 4);
  std::memcpy(v.data() + off, &x, 4);
}
static void put_u16(std::vector<std::uint8_t>& v, std::size_t off, std::uint16_t x) {
  if (v.size() < off + 2) v.resize(off + 2);
  std::memcpy(v.data() + off, &x, 2);
}

// Wrap a payload into a full wire frame with a correct CRC.
static std::vector<std::uint8_t> makeFrame(char msg, const std::vector<std::uint8_t>& payload, std::uint8_t flags = 0) {
  std::vector<std::uint8_t> f(12 + payload.size(), 0);
  f[0] = 0xCA; f[1] = 0xFE; f[2] = flags; f[3] = static_cast<std::uint8_t>(msg);
  std::uint32_t len = static_cast<std::uint32_t>(payload.size());
  std::memcpy(f.data() + 8, &len, 4);
  std::memcpy(f.data() + 12, payload.data(), payload.size());
  std::uint16_t crc = crc16_modbus(f.data() + 8, 4 + payload.size());
  std::memcpy(f.data() + 6, &crc, 2);
  return f;
}

int main() {
  // --- CRC-16/MODBUS standard check vector "123456789" -> 0x4B37 ----------
  std::printf("[1] CRC-16/MODBUS\n");
  const std::uint8_t v[] = {'1','2','3','4','5','6','7','8','9'};
  CHECK(crc16_modbus(v, 9) == 0x4B37, "crc16_modbus(\"123456789\") == 0x4B37");

  // --- LDR frame round-trip (spec §3.3) ----------------------------------
  std::printf("[2] LDR frame\n");
  {
    std::vector<std::uint8_t> pay(0x30, 0);
    put_i32(pay, 0x0C, 45 * 3600);   // h_angle = 45.0 deg
    put_i32(pay, 0x14, 7);           // scan_seq
    put_i32(pay, 0x20, 1 * 3600);    // v_step = 1.0 deg
    put_i32(pay, 0x24, -10 * 3600);  // v_start = -10.0 deg
    put_i32(pay, 0x28, 1000);        // ptseq_base
    put_u16(pay, 0x2C, 3);           // segm_seq
    put_u16(pay, 0x2E, 2);           // count = 2
    // 2 records (8B each): dist mm, attr
    pay.resize(0x30 + 2 * 8);
    put_i32(pay, 0x30 + 0, 2500); put_i32(pay, 0x30 + 4, 11);   // dist 2.5m attr 11
    put_i32(pay, 0x38 + 0, 3000); put_i32(pay, 0x38 + 4, 22);   // dist 3.0m attr 22
    auto frame = makeFrame('L', pay);

    Frame fr; std::string err;
    std::size_t used = parseFrame(frame.data(), frame.size(), fr, err);
    CHECK(used == frame.size(), "parseFrame consumed whole frame");
    CHECK(fr.crc_ok, "LDR CRC ok");
    CHECK(fr.type == MsgType::LDR, "LDR msg_type");
    LdrFrame ldr;
    CHECK(decodeLDR(fr.payload, ldr), "decodeLDR ok");
    CHECK(near(ldr.h_angle_deg, 45.0, 1e-9), "h_angle == 45 deg");
    CHECK(ldr.scan_seq == 7 && ldr.segm_seq == 3, "scan_seq/segm_seq");
    CHECK(ldr.points.size() == 2, "2 points");
    CHECK(near(ldr.points[0].dist_m, 2.5, 1e-9) && ldr.points[0].attr == 11, "pt0 dist/attr");
    CHECK(near(ldr.points[1].v_angle_deg, -9.0, 1e-9), "pt1 v_angle = v_start + 1*v_step = -9");
    CHECK(ldr.points[1].pt_seq == 1001, "pt1 pt_seq = base+1");
  }

  // --- PTS frame round-trip (spec §3.2) ----------------------------------
  std::printf("[3] PTS frame\n");
  {
    std::vector<std::uint8_t> pay(0x18, 0);
    put_i32(pay, 0x08, 90 * 3600);   // h_angle = 90 deg
    put_i32(pay, 0x0C, 42);          // scan_seq
    put_u16(pay, 0x10, 5);           // segm_seq
    put_u16(pay, 0x16, 1);           // count = 1
    pay.resize(0x18 + 16);
    put_i32(pay, 0x18 + 0, 1234);    // x 1.234 m
    put_i32(pay, 0x18 + 4, -5678);   // y -5.678 m
    put_i32(pay, 0x18 + 8, 9000);    // z 9.0 m
    put_i32(pay, 0x18 + 12, 77);     // attr
    auto frame = makeFrame('P', pay);

    Frame fr; std::string err;
    std::size_t used = parseFrame(frame.data(), frame.size(), fr, err);
    CHECK(used == frame.size() && fr.crc_ok && fr.type == MsgType::PTS, "PTS parse + CRC + type");
    PtsFrame pts;
    CHECK(decodePTS(fr.payload, pts), "decodePTS ok");
    CHECK(near(pts.h_angle_deg, 90.0, 1e-9) && pts.scan_seq == 42, "PTS header");
    CHECK(pts.points.size() == 1 && near(pts.points[0].x, 1.234, 1e-9) &&
          near(pts.points[0].y, -5.678, 1e-9) && near(pts.points[0].z, 9.0, 1e-9), "PTS xyz (mm->m)");
  }

  // --- incomplete buffer returns 0, bad CRC flagged ----------------------
  std::printf("[4] partial / bad-CRC handling\n");
  {
    std::vector<std::uint8_t> pay(0x30, 0);
    put_u16(pay, 0x2E, 0);
    auto frame = makeFrame('L', pay);
    Frame fr; std::string err;
    CHECK(parseFrame(frame.data(), 8, fr, err) == 0, "header-only buffer -> 0 (need more)");
    CHECK(parseFrame(frame.data(), frame.size() - 1, fr, err) == 0, "truncated payload -> 0");
    frame[6] ^= 0xFF;  // corrupt CRC
    parseFrame(frame.data(), frame.size(), fr, err);
    CHECK(!fr.crc_ok, "corrupted CRC detected");
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}

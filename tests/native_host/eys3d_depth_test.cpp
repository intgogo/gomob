// eYs3D 深度度量转换 host 单测 — 验证 ZD 表 byteswap/查表/整帧/几何退化。
//
// 测试 1: FromFlashBlob 对大端 blob 做 byteswap
// 测试 2: ZMm 单点查表 + 越界返回 0
// 测试 3: DisparityToDepthMm 整帧(0视差→0, 越界→0, 线性补偿)
// 测试 4: GeometricZMm 几何退化 Z=fx·B/(disp/8)
// 测试 5(可选): 对真实设备 ZD 表(.dev/eys3d-sdk/tables/zd_dt4_b4096.bin)验交叉验证值
#include "eys3d/portable/eys3d_depth.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

using gomob::eys3d::ZdTable;
using gomob::eys3d::GeometricZMm;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-46s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
void CheckEq(const char* tag, long got, long want) {
  bool ok = got == want;
  std::printf("  %-46s got=%ld want=%ld -> %s\n", tag, got, want, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
void CheckNear(const char* tag, int got, int want, int tol) {
  bool ok = std::abs(got - want) <= tol;
  std::printf("  %-46s got=%d want=%d(±%d) -> %s\n", tag, got, want, tol, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
}  // namespace

int main() {
  // 测试 1: byteswap. 大端存 0x01F4(=500)即字节 [0x01,0x04?]... 用明确字节构造:
  // 厂家大端: mm=500=0x01F4 → 字节序列高位在前 = [0x01,0xF4]? 但 blob 读取按 data[2i]|data[2i+1]<<8
  // 即 blob 里存的是 little-endian 的"原始 u16",再 byteswap。模拟设备:存 0xF401(LE 读=0xF401),
  // byteswap → 0x01F4 = 500。
  {
    std::vector<uint8_t> blob = {0x01, 0xF4, 0x00, 0xFF};  // u16(LE)=0xF401, 0xFF00
    ZdTable t = ZdTable::FromFlashBlob(blob.data(), blob.size());
    Check("FromFlashBlob valid", t.valid());
    CheckEq("blob[0] byteswap 0xF401->0x01F4", t.ZMm(0), 0x01F4);  // =500
    CheckEq("blob[1] byteswap 0xFF00->0x00FF", t.ZMm(1), 0x00FF);  // =255
  }

  // 测试 2: 单点查表 + 越界
  {
    ZdTable t = ZdTable::FromMillimeters({0, 1000, 500, 250});
    CheckEq("ZMm(0)=0(无效视差)", t.ZMm(0), 0);
    CheckEq("ZMm(1)=1000", t.ZMm(1), 1000);
    CheckEq("ZMm(3)=250", t.ZMm(3), 250);
    CheckEq("ZMm(4)越界=0", t.ZMm(4), 0);
    CheckEq("ZMm(65535)越界=0", t.ZMm(65535), 0);
  }

  // 测试 3: 整帧转换
  {
    ZdTable t = ZdTable::FromMillimeters({0, 1000, 500, 250, 200});
    std::vector<uint16_t> disp = {0, 1, 3, 4, 99, 2};  // 0=无效, 4=表内, 99=越界
    std::vector<uint16_t> out(disp.size(), 0xAAAA);
    t.DisparityToDepthMm(disp.data(), disp.size(), out.data());
    CheckEq("frame[0] disp0->0", out[0], 0);
    CheckEq("frame[1] disp1->1000", out[1], 1000);
    CheckEq("frame[2] disp3->250", out[2], 250);
    CheckEq("frame[3] disp4->200", out[3], 200);
    CheckEq("frame[4] disp99越界->0", out[4], 0);
    CheckEq("frame[5] disp2->500", out[5], 500);
    // 线性补偿: new = disp*1 + 1, disp1 -> idx2 -> 500
    std::vector<uint16_t> out2(disp.size(), 0);
    t.DisparityToDepthMm(disp.data(), disp.size(), out2.data(), 1.0f, 1.0f);
    CheckEq("补偿 disp1*1+1=idx2->500", out2[1], 500);
  }

  // 测试 4: 几何退化 Z=fx·B/(disp/subpixel). fx=614.6,B=49.98,subpixel=8
  // disp=512 → 真视差64 → Z=614.6*49.98/64≈480mm
  {
    CheckNear("GeometricZMm(512,614.6,49.98)", GeometricZMm(512, 614.6f, 49.98f), 480, 3);
    CheckEq("GeometricZMm(0,...)=0", GeometricZMm(0, 614.6f, 49.98f), 0);
  }

  // 测试 5: 真实设备 ZD 表(若存在)
  {
    const char* path = "/root/lilw/gomob/.dev/eys3d-sdk/tables/zd_dt4_b4096.bin";
    FILE* fp = std::fopen(path, "rb");
    if (fp) {
      std::fseek(fp, 0, SEEK_END);
      long sz = std::ftell(fp);
      std::fseek(fp, 0, SEEK_SET);
      std::vector<uint8_t> blob(sz);
      size_t rd = std::fread(blob.data(), 1, sz, fp);
      std::fclose(fp);
      if (rd == (size_t)sz && sz == 4096) {
        ZdTable t = ZdTable::FromFlashBlob(blob.data(), blob.size());
        std::printf("  [真实ZD表 %ld 字节 = %zu 项]\n", sz, t.size());
        // 交叉验证值(finding 记录): 视差512→502mm, 1024→251mm, 2047→125mm
        CheckNear("真ZD表[512]≈502mm", t.ZMm(512), 502, 30);
        CheckNear("真ZD表[1024]≈251mm", t.ZMm(1024), 251, 20);
        CheckNear("真ZD表[2047]≈125mm", t.ZMm(2047), 125, 15);
        // 单调性: 视差越大 Z 越小(近)
        bool mono = t.ZMm(256) > t.ZMm(512) && t.ZMm(512) > t.ZMm(1024) && t.ZMm(1024) > t.ZMm(2047);
        Check("真ZD表单调递减(视差↑→Z↓)", mono);
      } else {
        std::printf("  [真实ZD表尺寸异常 sz=%ld, 跳过]\n", sz);
      }
    } else {
      std::printf("  [无真实ZD表(.dev/eys3d-sdk/tables/zd_dt4_b4096.bin), 跳过测试5]\n");
    }
  }

  std::printf("eys3d_depth_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}

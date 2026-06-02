// eYs3D IF2 深度路由单测 — 原始视差帧字节流 → metric depthMm,含状态行剥离 / 字节序 / 尺寸校验。
#include "eys3d/portable/eys3d_depth_router.h"

#include <cstdio>

using namespace gomob::eys3d;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-46s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}

// 小端 u16 写入字节缓冲。
void PutLe16(std::vector<uint8_t>& b, uint16_t v) {
  b.push_back(static_cast<uint8_t>(v & 0xFF));
  b.push_back(static_cast<uint8_t>(v >> 8));
}
}  // namespace

int main() {
  // 基础:2x2 无状态行,ZD 表查 mm。
  {
    DepthRouter r;
    r.Configure(DepthRouterConfig{/*width=*/2, /*height=*/2, /*status_rows=*/0, /*bswap=*/false});
    r.finalizer().SetZdTable(ZdTable::FromMillimeters({0, 1000, 500, 250}));
    Check("expected_frame_bytes=8", r.expected_frame_bytes() == 8);
    Check("active 2x2", r.active_width() == 2 && r.active_height() == 2);

    std::vector<uint8_t> raw;
    PutLe16(raw, 0);  // disp0 → 0
    PutLe16(raw, 1);  // disp1 → 1000
    PutLe16(raw, 3);  // disp3 → 250
    PutLe16(raw, 2);  // disp2 → 500
    std::vector<uint16_t> out;
    bool ok = r.Route(raw.data(), raw.size(), &out);
    Check("Route 成功 4 像素", ok && out.size() == 4);
    Check("查表 [0,1000,250,500]", out[0] == 0 && out[1] == 1000 && out[2] == 250 && out[3] == 500);
  }

  // 状态行剥离:height=3,status_rows=1 → active 2 行,顶行被跳过。
  {
    DepthRouter r;
    r.Configure(DepthRouterConfig{2, 3, /*status_rows=*/1, false});
    r.finalizer().SetZdTable(ZdTable::FromMillimeters({0, 111, 222, 333}));
    Check("active 高=2(剥1状态行)", r.active_height() == 2);
    Check("expected=2*3*2=12B", r.expected_frame_bytes() == 12);
    std::vector<uint8_t> raw;
    PutLe16(raw, 9); PutLe16(raw, 9);  // 状态行(应被跳过,值越界也无妨)
    PutLe16(raw, 1); PutLe16(raw, 2);  // active row0
    PutLe16(raw, 3); PutLe16(raw, 0);  // active row1
    std::vector<uint16_t> out;
    bool ok = r.Route(raw.data(), raw.size(), &out);
    Check("剥状态行后 [111,222,333,0]",
          ok && out.size() == 4 && out[0] == 111 && out[1] == 222 && out[2] == 333 && out[3] == 0);
  }

  // 字节序:disparity_byteswap=true,大端字节 → 视差查表。
  {
    DepthRouter r;
    r.Configure(DepthRouterConfig{2, 1, 0, /*bswap=*/true});
    r.finalizer().SetZdTable(ZdTable::FromMillimeters({0, 1000, 500}));
    std::vector<uint8_t> raw = {0x00, 0x01, 0x00, 0x02};  // 大端: 0x0100=256? 不——byteswap 后 = 0x0001=1,0x0002=2
    std::vector<uint16_t> out;
    bool ok = r.Route(raw.data(), raw.size(), &out);
    // raw 小端读 = 0x0100/0x0200,byteswap → 0x0001/0x0002 → 查表 1000/500
    Check("byteswap 后查表 [1000,500]", ok && out.size() == 2 && out[0] == 1000 && out[1] == 500);
  }

  // 几何退化路径(无 ZD 表)。
  {
    DepthRouter r;
    r.Configure(DepthRouterConfig{1, 1, 0, false});
    r.finalizer().SetGeometric(614.6f, 49.98f);  // fx,B
    std::vector<uint8_t> raw;
    PutLe16(raw, 512);  // 真视差64 → Z≈480mm
    std::vector<uint16_t> out;
    bool ok = r.Route(raw.data(), raw.size(), &out);
    Check("几何 disp512→≈480mm", ok && !out.empty() && out[0] >= 477 && out[0] <= 483);
  }

  // 失败路径:尺寸不足 / 终结器未就绪 / nullptr。
  {
    DepthRouter r;
    r.Configure(DepthRouterConfig{4, 4, 0, false});
    std::vector<uint16_t> out;
    std::vector<uint8_t> tiny(8, 0);
    r.finalizer().SetZdTable(ZdTable::FromMillimeters({0, 1}));
    Check("尺寸不足 → false", !r.Route(tiny.data(), tiny.size(), &out));
    DepthRouter r2;
    r2.Configure(DepthRouterConfig{2, 2, 0, false});
    std::vector<uint8_t> full(8, 0);
    Check("终结器未就绪 → false", !r2.Route(full.data(), full.size(), &out));
    Check("nullptr → false", !r.Route(nullptr, 100, &out));
  }

  std::printf("eys3d_depth_router_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}

// eYs3D 软件 stereo 深度单测 — 合成已知视差立体对,验 SAD 视差恢复 + 度量 + YUYV 拆分 + 病态判无效。
#include "eys3d/portable/eys3d_stereo_depth.h"

#include <cstdio>
#include <vector>

using namespace gomob::eys3d;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-50s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}

// 确定性非周期纹理(乘移哈希,局部对比足够)。
uint8_t Tex(int x, int y) {
  uint32_t h = (static_cast<uint32_t>(x) * 2654435761u) ^ (static_cast<uint32_t>(y) * 40503u + 7u);
  return static_cast<uint8_t>((h >> 13) & 0xFF);
}

// 造已知恒定视差 D 的已矫正 L/R:left[x]=scene[x], right[x]=scene[x+D] → left[x]=right[x-D] → 视差=D。
void MakePair(int w, int h, int D, std::vector<uint8_t>* left, std::vector<uint8_t>* right) {
  left->resize(static_cast<size_t>(w) * h);
  right->resize(static_cast<size_t>(w) * h);
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      (*left)[static_cast<size_t>(y) * w + x] = Tex(x, y);
      (*right)[static_cast<size_t>(y) * w + x] = Tex(x + D, y);
    }
}
}  // namespace

int main() {
  const int W = 128, H = 48, D = 20;
  std::vector<uint8_t> L, R;
  MakePair(W, H, D, &L, &R);

  BlockMatchConfig cfg;
  cfg.min_disparity = 0;
  cfg.max_disparity = 32;
  cfg.block_radius = 3;
  cfg.subpixel = false;  // 退化合成(精确平移)用整数精确恢复;亚像素另测
  cfg.lr_consistency_max = 1;

  // 视差恢复:valid 区(x>=max_disp+r)绝大多数应恰好 = D×8。
  {
    std::vector<uint16_t> disp;
    bool ok = ComputeDisparitySad(L.data(), R.data(), W, H, cfg, &disp);
    Check("ComputeDisparitySad 成功", ok && disp.size() == static_cast<size_t>(W) * H);
    int valid = 0, correct = 0;
    const int x0 = cfg.max_disparity + cfg.block_radius;
    for (int y = cfg.block_radius; y < H - cfg.block_radius; ++y)
      for (int x = x0; x < W - cfg.block_radius; ++x) {
        uint16_t v = disp[static_cast<size_t>(y) * W + x];
        if (v == 0) continue;
        ++valid;
        if (v == D * kStereoSubpixel) ++correct;
      }
    Check("valid 区有大量有效视差", valid > 1000);
    Check("恢复视差恰=D×8 占比 >95%", valid > 0 && correct * 100 / valid >= 95);
  }

  // 几何度量:Z=fx·B/(disp/8)=fx·B/D。fx=1229.205,B=49.98 → Z≈3072mm。
  {
    StereoDepthEngine eng;
    eng.SetConfig(cfg);
    Check("无度量 → not ready", !eng.ready());
    eng.finalizer().SetGeometric(1229.205f, 49.98f);
    Check("设几何 → ready", eng.ready());
    std::vector<uint16_t> mm;
    bool ok = eng.Compute(L.data(), R.data(), W, H, &mm);
    Check("Compute 出 depthMm", ok && mm.size() == static_cast<size_t>(W) * H);
    // 取一个 valid 区中心像素验证 Z≈3072(±3%)。
    const uint16_t z = mm[static_cast<size_t>(H / 2) * W + (W - 10)];
    Check("几何 Z≈3072mm(±100)", z >= 2972 && z <= 3172);
  }

  // ZD 表度量:disp×8=160 → 表查 mm。
  {
    StereoDepthEngine eng;
    eng.SetConfig(cfg);
    std::vector<uint16_t> table(256, 0);
    for (size_t i = 0; i < table.size(); ++i) table[i] = static_cast<uint16_t>(i * 10);  // mm[160]=1600
    eng.finalizer().SetZdTable(ZdTable::FromMillimeters(std::move(table)));
    std::vector<uint16_t> mm;
    eng.Compute(L.data(), R.data(), W, H, &mm);
    const uint16_t z = mm[static_cast<size_t>(H / 2) * W + (W - 10)];
    Check("ZD 表 disp160→1600mm", z == 1600);
  }

  // 亚像素:开启后 valid 区视差应落在 D×8 附近(±1px=±8)。
  {
    BlockMatchConfig c2 = cfg;
    c2.subpixel = true;
    std::vector<uint16_t> disp;
    ComputeDisparitySad(L.data(), R.data(), W, H, c2, &disp);
    int valid = 0, near = 0;
    for (int y = c2.block_radius; y < H - c2.block_radius; ++y)
      for (int x = c2.max_disparity + c2.block_radius; x < W - c2.block_radius; ++x) {
        uint16_t v = disp[static_cast<size_t>(y) * W + x];
        if (v == 0) continue;
        ++valid;
        if (v >= D * kStereoSubpixel - 8 && v <= D * kStereoSubpixel + 8) ++near;
      }
    Check("亚像素视差落 D×8±8 占比 >95%", valid > 0 && near * 100 / valid >= 95);
  }

  // 平坦图(无纹理)→ texture_min 判全无效。
  {
    std::vector<uint8_t> flat(static_cast<size_t>(W) * H, 100);
    std::vector<uint16_t> disp;
    ComputeDisparitySad(flat.data(), flat.data(), W, H, cfg, &disp);
    int nonzero = 0;
    for (uint16_t v : disp) if (v != 0) ++nonzero;
    Check("平坦图 → 全无效(0)", nonzero == 0);
  }

  // YUYV 并排拆分。
  {
    const int FW = 8, FH = 2;  // full 8 宽 → 左右各 4
    std::vector<uint8_t> yuyv(static_cast<size_t>(FW) * FH * 2, 0);
    for (int y = 0; y < FH; ++y)
      for (int x = 0; x < FW; ++x) yuyv[(static_cast<size_t>(y) * FW + x) * 2] = static_cast<uint8_t>(x + y * 10);
    std::vector<uint8_t> lg, rg;
    bool ok = SplitSideBySideYuyvToGray(yuyv.data(), FW, FH, &lg, &rg);
    // 左半 Y = x∈[0,4),右半 Y = x∈[4,8)。
    Check("YUYV 拆分尺寸 4×2", ok && lg.size() == 8 && rg.size() == 8);
    Check("左半 Y[0..3] = 0,1,2,3", lg[0] == 0 && lg[1] == 1 && lg[2] == 2 && lg[3] == 3);
    Check("右半 Y[0..3] = 4,5,6,7", rg[0] == 4 && rg[1] == 5 && rg[2] == 6 && rg[3] == 7);
    Check("奇数 full_width → false", !SplitSideBySideYuyvToGray(yuyv.data(), 7, FH, &lg, &rg));
  }

  // 未就绪 / 非法入参。
  {
    StereoDepthEngine eng;
    std::vector<uint16_t> mm;
    Check("未设度量 Compute → false", !eng.Compute(L.data(), R.data(), W, H, &mm));
    std::vector<uint16_t> disp;
    Check("max<=min → false", !ComputeDisparitySad(L.data(), R.data(), W, H,
                                                   BlockMatchConfig{0, 0, 3, 0.1f, 1, false, 4}, &disp));
  }

  std::printf("eys3d_stereo_depth_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}

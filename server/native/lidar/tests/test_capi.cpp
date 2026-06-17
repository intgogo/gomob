// C-ABI host 测试（M8'-C1）：lidar_scan_replay 用真机录制 PTS .bin 回放，验证流式逐帧回调、
// 两单元建云、ICP/union 融合、状态序列、协作取消。供 gomob 服务端 cgo 接入前的桩外验证。
// 用法: test_capi <binA> <binB>（CMake 传 out_live/car_10x_pts.bin）。
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "lib/lidar_scan.h"

static int g_fail = 0;
#define CHECK(c, m) do { if(!(c)){ std::printf("  FAIL: %s\n", m); ++g_fail; } else std::printf("  ok  : %s\n", m); } while(0)

struct Sink {
  long pts[3] = {0, 0, 0};   // unit 0/1/2 累计点数
  int  calls[3] = {0, 0, 0};
  std::vector<std::string> states;
  int  cancel_after = -1;    // >=0 时在第 N 次点回调后触发取消
  int  point_calls = 0;
};

static void onPoints(void* u, int unit, const float* xyz, int n, float h) {
  (void)h;
  Sink* s = static_cast<Sink*>(u);
  if (unit >= 0 && unit < 3) { s->pts[unit] += n; s->calls[unit]++; }
  // 抽样校验 mm 量级：真机点在 ±20m 内 ⇒ |坐标| < 25000 mm
  if (n > 0) { for (int k = 0; k < 3; ++k) { float v = xyz[k]; if (v < -25000 || v > 25000) { std::printf("  FAIL: 点超量程 %.1f\n", v); ++g_fail; break; } } }
  if (s->cancel_after >= 0 && ++s->point_calls == s->cancel_after) lidar_scan_cancel();
}
static void onStatus(void* u, const char* st, int a, int b) { (void)a; (void)b; static_cast<Sink*>(u)->states.push_back(st); }

int main(int argc, char** argv) {
  const char* binA = argc > 1 ? argv[1] : "out_live/car_101_pts.bin";
  const char* binB = argc > 2 ? argv[2] : "out_live/car_102_pts.bin";

  std::printf("[1] lidar_scan_replay(icp) — 流式回调 + 两单元建云 + 融合\n");
  Sink s;
  LidarScanResult r;
  int rc = lidar_scan_replay(binA, binB, "icp", nullptr, 1.0f, onPoints, onStatus, &s, &r);
  std::printf("  info: rc=%d align=%s a=%d b=%d fused=%d | stream a=%ld b=%ld fused=%ld\n",
              rc, r.align, r.pts_a, r.pts_b, r.fused, s.pts[0], s.pts[1], s.pts[2]);
  CHECK(rc == 0, "replay 返回 0");
  CHECK(s.pts[0] > 0 && s.pts[1] > 0, "两单元都有流式点(unit0/1)");
  CHECK(s.calls[0] > 10 && s.calls[1] > 10, "逐帧回调多次(非一次性)");
  CHECK(static_cast<long>(r.pts_a) == s.pts[0] && static_cast<long>(r.pts_b) == s.pts[1], "out 计数 == 流式累计");
  CHECK(r.fused == r.pts_a + r.pts_b, "融合 = union (fused==a+b)");
  CHECK(s.pts[2] == static_cast<long>(r.fused), "融合云流式点数 == fused");
  CHECK(std::strcmp(r.align, "icp") == 0 || std::strcmp(r.align, "none") == 0, "align=icp 或 none(未收敛)");
  CHECK(r.error[0] == 0, "无错误");

  std::printf("[2] 状态序列 scanning→fusing→done\n");
  bool seen_scan = false, seen_fuse = false, seen_done = false;
  for (auto& st : s.states) { if (st == "scanning") seen_scan = true; if (st == "fusing") seen_fuse = true; if (st == "done") seen_done = true; }
  CHECK(seen_scan && seen_fuse && seen_done, "状态含 scanning/fusing/done");

  std::printf("[3] keep_ratio 降采样\n");
  Sink s2; LidarScanResult r2;
  lidar_scan_replay(binA, binB, "none", nullptr, 0.5f, onPoints, onStatus, &s2, &r2);
  std::printf("  info: fused=%d (full=%d, 期望~半)\n", r2.fused, r.fused);
  CHECK(r2.fused > 0 && r2.fused < r.fused, "keep=0.5 融合点数严格少于全量");

  std::printf("[4] 协作取消\n");
  Sink s3; s3.cancel_after = 5; LidarScanResult r3;
  int rc3 = lidar_scan_replay(binA, binB, "none", nullptr, 1.0f, onPoints, onStatus, &s3, &r3);
  std::printf("  info: rc=%d unit0+1 流式点=%ld (全量 a+b=%d)\n", rc3, s3.pts[0] + s3.pts[1], r.pts_a + r.pts_b);
  CHECK(rc3 == 2, "取消返回 2");
  CHECK(s3.pts[0] + s3.pts[1] < static_cast<long>(r.pts_a) + r.pts_b, "取消后采集点数少于全量");

  std::printf("\n%s (%d 失败)\n", g_fail ? "FAILED" : "PASSED", g_fail);
  return g_fail ? 1 : 0;
}

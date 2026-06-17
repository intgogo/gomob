// framing_stream — 现场标定「实时取景」：被动连两单元 4003 图像流，逐帧 ArUco 检测后把
// 降采样预览 + 检测框经二进制协议推到 out（laserworker 读后转 NATS→ws），扫完聚合 umeyama 解 B→A。
//
// 设备控制（设扫描角/速度 + SCAN_START/STOP）由 Go 侧 devctl 负责；本命令**全程被动**：连流→就绪
// 发 READY→等设备进入 SCAN→边采边检测推流→扫完解算。与 cgo 采集路径同范式（passive，host 触发扫描）。
//
// out 二进制帧协议（大端 uint32 长度前缀）：
//   record = [4B N][1B type][N bytes payload]
//     type 's' 状态/事件 : payload = UTF-8 JSON（{"ev":"ready"} / {"ev":"error",...} / {"ev":"unit_done",...}）
//     type 'm' 取景帧    : payload = [4B metaLen][meta JSON UTF-8][preview JPEG 字节]
//                          meta = {"unit":0,"seq":3,"heading":12.3,"w":1280,"h":720,
//                                  "markers":[{"id":5,"px":[[x,y],[x,y],[x,y],[x,y]]}]}（px 已缩放到预览分辨率）
//     type 'r' 结果      : payload = UTF-8 JSON（{"ok":true,"n_common":6,"rms_m":0.004,"b_to_a":[16]}）
//   所有人读日志走 stderr，stdout 只有上述二进制协议。
#pragma once

#include <cstdio>
#include <string>

namespace lidar {

struct FramingStreamParams {
  std::string ipA, configA, calibA;
  std::string ipB, configB, calibB;
  std::string outJSON;          // 解算 ok 时写 site_extrinsic.json（{"b_to_a":[16]}）
  double      marker_len_m{0.15};
  int         min_common{2};  // 角点法单标记即可解，2 做冗余/校验（旧中心法需 ≥4，现放宽）
  int         preview_width{1280};  // 推前端的预览宽（检测仍在全分辨率做，px 再缩放回预览系）
  int         preview_quality{80};
  int         hard_timeout_ms{300000};  // 慢宽扫 + idle 收尾余量（5min）；实际靠 idle_timeout 自然收尾
};

// 运行取景流，向 out 写二进制帧协议。返回 0=正常运行结束（含「未达标」也算正常），非 0=运行级错误。
int runFramingStream(const FramingStreamParams& p, std::FILE* out);

}  // namespace lidar

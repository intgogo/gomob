#include "eys3d/host/eys3d_stream_loop.h"

#include <libusb-1.0/libusb.h>

#include <chrono>
#include <cstdio>
#include <cstring>

#include "eys3d/host/eys3d_usb_device.h"

namespace gomob::eys3d::host {

using gomob::camera::SessionConfig;
using gomob::camera::SessionState;
using gomob::camera::XuPayload;

namespace {

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// 26B PROBE/COMMIT 负载构造(proven 字节,见 eys3d_replay_stream)。
std::vector<uint8_t> Probe26(uint8_t format_index, uint8_t frame_index, uint32_t max_frame,
                             uint32_t max_payload, bool negotiated) {
  std::vector<uint8_t> p(26, 0);
  p[0] = 0x01; p[1] = 0x00;            // bmHint
  p[2] = format_index;                 // bFormatIndex
  p[3] = frame_index;                  // bFrameIndex
  p[4] = 0x80; p[5] = 0x84; p[6] = 0x1e; p[7] = 0x00;  // dwFrameInterval=2000000(proven)
  if (negotiated) {
    p[18] = static_cast<uint8_t>(max_frame & 0xFF);
    p[19] = static_cast<uint8_t>((max_frame >> 8) & 0xFF);
    p[20] = static_cast<uint8_t>((max_frame >> 16) & 0xFF);
    p[21] = static_cast<uint8_t>((max_frame >> 24) & 0xFF);
    p[22] = static_cast<uint8_t>(max_payload & 0xFF);
    p[23] = static_cast<uint8_t>((max_payload >> 8) & 0xFF);
  }
  return p;
}

// 单条流的实时取流 + 增量 FID 组装状态。回调只 memcpy 进 pending(同线程,无锁),
// 组装在 handle_events 之后由流线程做(满足"回调不做重活")。
struct StreamState {
  uint8_t ep = 0;
  int frame_bytes = 0;
  int urb_size = 0;
  bool is_depth = false;
  Eys3dSessionCore* core = nullptr;

  std::vector<uint8_t> pending;
  std::vector<int> pending_lens;
  int prev_fid = -1;
  std::vector<uint8_t> cur;

  std::vector<libusb_transfer*> urbs;
  std::vector<std::vector<uint8_t>> bufs;
  int inflight = 0;
  bool stop = false;
  int64_t errs = 0;
};

void LIBUSB_CALL StreamCb(libusb_transfer* t) {
  auto* s = static_cast<StreamState*>(t->user_data);
  if (t->status == LIBUSB_TRANSFER_COMPLETED && t->actual_length > 0) {
    const size_t n = static_cast<size_t>(t->actual_length);
    s->pending.insert(s->pending.end(), t->buffer, t->buffer + n);
    s->pending_lens.push_back(t->actual_length);
  } else if (t->status != LIBUSB_TRANSFER_COMPLETED && t->status != LIBUSB_TRANSFER_TIMED_OUT) {
    ++s->errs;
  }
  if (!s->stop) {
    if (libusb_submit_transfer(t) != 0) --s->inflight;
  } else {
    --s->inflight;
  }
}

// 消费 pending:按 UVC payload 头 FID(bit0) 翻转切帧、剥 bHeaderLength,满帧喂 core。
void DrainAndAssemble(StreamState& s) {
  size_t off = 0;
  for (int len : s.pending_lens) {
    const uint8_t* p = s.pending.data() + off;
    off += static_cast<size_t>(len);
    if (len < 2) continue;
    int hl = p[0];
    if (hl < 2 || hl > len) hl = (len >= 12) ? 12 : len;  // bHeaderLength 兜底
    const int fid = p[1] & 1;
    if (s.prev_fid >= 0 && fid != s.prev_fid) {  // FID 翻转 = 帧边界
      // frame_bytes>0(raw 定长 depth)要求精确尺寸;==0(MJPEG color 变长)只要非空即出。
      const bool ok = s.frame_bytes > 0 ? (static_cast<int>(s.cur.size()) == s.frame_bytes)
                                        : (!s.cur.empty());
      if (ok && s.core) {
        const int64_t ns = NowNs();
        if (s.is_depth) s.core->OnRawDepthFrame(s.cur.data(), s.cur.size(), ns);
        else s.core->OnColorFrame(s.cur.data(), s.cur.size(), ns);
      }
      s.cur.clear();
    }
    s.prev_fid = fid;
    if (len > hl) s.cur.insert(s.cur.end(), p + hl, p + len);  // 剥头累积
  }
  s.pending.clear();
  s.pending_lens.clear();
}

}  // namespace

Eys3dOpenPlan ProvenWrongModePlan() {
  Eys3dOpenPlan plan;
  plan.arm = DefaultRsd550Usb2();
  plan.arm.videomode_reg = kVideoModeRegWrongConfig14Bit;  // 0x02:14bit 错配置(proven 能流但深度垃圾)
  // color IF1: fmt=1 frame=4 maxFrame=0x12c000(1228800) maxPayload=0x800(2048)
  plan.color.endpoint = 0x81;
  plan.color.vs_interface = 1;
  plan.color.urb_size = 2048;
  plan.color.frame_bytes = 1228800;
  plan.color.probe_zero = Probe26(1, 4, 0, 0, false);
  plan.color.probe_neg = Probe26(1, 4, 0x12c000, 2048, true);
  // depth IF2: fmt=1 frame=1 maxFrame=0x258000 maxPayload=0xc00(3072),实测真帧=1228800B
  plan.depth.endpoint = 0x82;
  plan.depth.vs_interface = 2;
  plan.depth.urb_size = 3072;
  plan.depth.frame_bytes = 1228800;
  plan.depth.probe_zero = Probe26(1, 1, 0, 0, false);
  plan.depth.probe_neg = Probe26(1, 1, 0x258000, 3072, true);
  return plan;
}

Eys3dOpenPlan Mode25Usb2Plan(uint8_t color_frame_index, uint8_t depth_frame_index,
                             uint16_t depth_status_rows) {
  Eys3dOpenPlan plan;
  plan.arm = DefaultRsd550Usb2();  // videomode_reg=36(0x24)= mode25,interleave off,IR 3
  // color IF1: 1280×256 MJPEG@5。bFormatIndex=2(MJPEG),bFrameIndex 由描述符解析填。
  // MJPEG 变长 → frame_bytes=0(按 FID 翻转切帧,不校验固定尺寸)。maxFrame 给 MJPEG 上界估值。
  plan.color.endpoint = 0x81;
  plan.color.vs_interface = 1;
  plan.color.urb_size = 2048;
  plan.color.frame_bytes = 0;  // MJPEG 变长
  plan.color.probe_zero = Probe26(/*fmt=*/2, color_frame_index, 0, 0, false);
  plan.color.probe_neg = Probe26(2, color_frame_index, /*maxFrame≈*/1280u * 256u * 2u, 2048, true);
  // depth IF2: 640×128 YUY2 容器(11bit 视差)@5。bFormatIndex=1,bFrameIndex 由描述符解析填。
  // frame_bytes=640×(128+状态行)×2(状态行真机确认)。
  const uint32_t depth_h = 128u + depth_status_rows;
  const uint32_t depth_bytes = 640u * depth_h * 2u;
  plan.depth.endpoint = 0x82;
  plan.depth.vs_interface = 2;
  plan.depth.urb_size = 3072;
  plan.depth.frame_bytes = static_cast<int>(depth_bytes);
  plan.depth.probe_zero = Probe26(1, depth_frame_index, 0, 0, false);
  plan.depth.probe_neg = Probe26(1, depth_frame_index, depth_bytes, 3072, true);
  return plan;
}

void RunEys3dStreamLoop(libusb_context* ctx, libusb_device_handle* h, const Eys3dOpenPlan& plan,
                        const SessionConfig& cfg, Eys3dSessionCore& core) {
  if (!h) { core.MarkError("no device handle"); return; }

  // 会话开始先 reset 清残留(连续开关会卡 immediate-STALL)。reset 致重枚举时设备指针失效,
  // 此处简化:reset 失败/NOT_FOUND 仅告警,不在循环内重开避免拓扑竞态。
  int rr = libusb_reset_device(h);
  if (rr != 0 && rr != LIBUSB_ERROR_NOT_FOUND) {
    std::fprintf(stderr, "[eys3d] reset rc=%d %s\n", rr, libusb_error_name(rr));
  }

  Eys3dUsbDevice dev(h);
  if (!dev.claim(0)) { core.MarkError("claim IF0 failed"); return; }
  dev.claim(1);
  dev.claim(2);

  // ---- 回放开流序列(逐字复刻 proven;mode25 待锁定换 plan 寄存器值)----
  auto set_cur = [&](const XuPayload& x) {
    std::vector<uint8_t> d = x.data;
    return dev.uvc_set_cur(x.w_value, x.w_index, d.data(), static_cast<uint16_t>(d.size()), 2000);
  };
  auto ctr = [&](uint8_t n) { return set_cur(MakeCounterTick(n)); };
  // pre-XU:videoMode 读探询(proven 字面 82 f0 14)+ 写 videomode/interleave + 计数器握手。
  const XuPayload kFwReadVm{3, 0x0300, 0x0400, {0x82, 0xF0, 0x14, 0x00}};  // proven 字面(含 0x14)
  for (uint8_t c = 1; c <= 3; ++c) { set_cur(kFwReadVm); ctr(c); }
  ctr(4);
  for (uint8_t c = 5; c <= 7; ++c) { set_cur(kFwReadVm); ctr(c); }
  ctr(8); ctr(9); ctr(10);
  set_cur(MakeSetVideoModeReg(plan.arm.videomode_reg)); ctr(11);
  set_cur(MakeSetInterleave(plan.arm.interleave)); ctr(12);

  // VS PROBE→GET_CUR→COMMIT。PROBE 后必须 GET_CUR 回读完成握手。
  auto probe = [&](const Eys3dStreamPlan& sp, const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> d = payload;
    dev.uvc_set_cur(0x0100, static_cast<uint16_t>(sp.vs_interface), d.data(),
                    static_cast<uint16_t>(d.size()), 2000);
    std::vector<uint8_t> back(d.size());
    dev.uvc_get_cur(0x0100, static_cast<uint16_t>(sp.vs_interface), back.data(),
                    static_cast<uint16_t>(back.size()), 2000);
  };
  auto commit = [&](const Eys3dStreamPlan& sp, const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> d = payload;
    dev.uvc_set_cur(0x0200, static_cast<uint16_t>(sp.vs_interface), d.data(),
                    static_cast<uint16_t>(d.size()), 2000);
  };
  probe(plan.color, plan.color.probe_zero);
  probe(plan.color, plan.color.probe_zero);
  ctr(13); ctr(14); ctr(15); ctr(16); ctr(17);
  probe(plan.color, plan.color.probe_neg);
  probe(plan.color, plan.color.probe_neg);
  probe(plan.depth, plan.depth.probe_zero);
  probe(plan.depth, plan.depth.probe_zero);
  probe(plan.depth, plan.depth.probe_neg);
  probe(plan.depth, plan.depth.probe_neg);
  commit(plan.color, plan.color.probe_neg);
  commit(plan.depth, plan.depth.probe_neg);
  set_cur(MakeSetStartTrigger());
  ctr(18); ctr(19); ctr(20);
  if (plan.arm.ir_current > 0) set_cur(MakeSetIrCurrent(plan.arm.ir_current));

  // ---- 双端点并发异步多 URB ----
  StreamState sc, sd;
  sc.ep = plan.color.endpoint; sc.frame_bytes = plan.color.frame_bytes;
  sc.urb_size = plan.color.urb_size; sc.is_depth = false; sc.core = &core;
  sd.ep = plan.depth.endpoint; sd.frame_bytes = plan.depth.frame_bytes;
  sd.urb_size = plan.depth.urb_size; sd.is_depth = true; sd.core = &core;
  StreamState* streams[2] = {&sc, &sd};
  const bool want[2] = {cfg.want_color, cfg.want_depth};

  constexpr int kNumUrb = 32;
  for (int si = 0; si < 2; ++si) {
    if (!want[si]) continue;
    StreamState& s = *streams[si];
    s.urbs.resize(kNumUrb, nullptr);
    s.bufs.assign(kNumUrb, std::vector<uint8_t>(s.urb_size));
    for (int i = 0; i < kNumUrb; ++i) {
      s.urbs[i] = libusb_alloc_transfer(0);
      libusb_fill_bulk_transfer(s.urbs[i], h, s.ep, s.bufs[i].data(), s.urb_size, StreamCb, &s, 0);
      if (libusb_submit_transfer(s.urbs[i]) == 0) ++s.inflight;
    }
  }
  core.MarkStreaming();

  // 事件循环:handle_events → 组装喂 core,直到 stop 请求或致命错误。
  while (!core.stop_requested()) {
    timeval tv{0, 50000};
    libusb_handle_events_timeout(ctx, &tv);  // handle 所属 context(host 默认=nullptr;Android 具名)
    for (int si = 0; si < 2; ++si) {
      if (!want[si]) continue;
      DrainAndAssemble(*streams[si]);
    }
    if (sc.errs > 0 && sd.errs > 0) { core.MarkError("bulk errors on both streams"); break; }
  }

  // 收尾:取消 URB 并排空。
  for (int si = 0; si < 2; ++si) {
    if (!want[si]) continue;
    StreamState& s = *streams[si];
    s.stop = true;
    for (auto* t : s.urbs) if (t) libusb_cancel_transfer(t);
  }
  for (int spin = 0; spin < 80; ++spin) {
    bool busy = false;
    for (int si = 0; si < 2; ++si) if (want[si] && streams[si]->inflight > 0) busy = true;
    if (!busy) break;
    timeval tv{0, 20000};
    libusb_handle_events_timeout(ctx, &tv);
  }
  for (int si = 0; si < 2; ++si) {
    if (!want[si]) continue;
    for (auto* t : streams[si]->urbs) if (t) libusb_free_transfer(t);
  }
  if (core.state() != SessionState::kError) core.MarkStopped();
}

}  // namespace gomob::eys3d::host

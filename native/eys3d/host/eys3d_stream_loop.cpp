#include "eys3d/host/eys3d_stream_loop.h"

#include <libusb-1.0/libusb.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>

#include "eys3d/host/eys3d_usb_device.h"
#include "eys3d/host/eys3d_proven_replay.h"  // 逐字复刻 proven SDK arming（自动生成）

#if defined(__ANDROID__)
#include <android/log.h>
#define EYS_LOG(...) __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", __VA_ARGS__)
#else
#define EYS_LOG(...) std::fprintf(stderr, __VA_ARGS__)
#endif

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
  // ── 诊断计数（M6.8 真机 0 帧定位）──
  int64_t completed = 0;  // 成功完成且有数据的 transfer 数
  int64_t bytes = 0;      // 累计收到字节
  int64_t frames = 0;     // 组装出的完整帧数
  int64_t last_status = 0;  // 最近一次非完成 transfer 的 status（负=libusb 错误码）
  // ── UVC payload 头诊断（定位"吐 ~2 帧即停"）──
  int64_t hdr_err = 0;    // bmHeaderInfo bit6=ERR 的 payload 数
  int64_t hdr_eof = 0;    // bit1=EOF 的 payload 数
  uint8_t last_hdr1 = 0;  // 最近 payload 的 bmHeaderInfo
};

void LIBUSB_CALL StreamCb(libusb_transfer* t) {
  auto* s = static_cast<StreamState*>(t->user_data);
  if (t->status == LIBUSB_TRANSFER_COMPLETED && t->actual_length > 0) {
    const size_t n = static_cast<size_t>(t->actual_length);
    s->pending.insert(s->pending.end(), t->buffer, t->buffer + n);
    s->pending_lens.push_back(t->actual_length);
    ++s->completed;
    s->bytes += static_cast<int64_t>(n);
  } else if (t->status != LIBUSB_TRANSFER_COMPLETED && t->status != LIBUSB_TRANSFER_TIMED_OUT) {
    ++s->errs;
    s->last_status = static_cast<int64_t>(t->status);
  }
  if (!s->stop) {
    if (Eys3dUsb().submit_transfer(t) != 0) --s->inflight;
  } else {
    --s->inflight;
  }
}

// 中断 EP 0x83 持续轮询状态：数据丢弃，只为保持 HC 对该 EP 的周期轮询（设备流维持心跳）。
struct IntrState {
  std::vector<libusb_transfer*> urbs;
  std::vector<std::vector<uint8_t>> bufs;
  int inflight = 0;
  bool stop = false;
  int64_t completed = 0;
};

void LIBUSB_CALL IntrCb(libusb_transfer* t) {
  auto* s = static_cast<IntrState*>(t->user_data);
  ++s->completed;  // 完成或超时都计一次轮询周期并重提（主动周期 IN，复刻 SDK ~3ms 节拍）
  if (!s->stop) {
    if (Eys3dUsb().submit_transfer(t) != 0) --s->inflight;
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
    s.last_hdr1 = p[1];
    if (p[1] & 0x40) ++s.hdr_err;  // ERR
    if (p[1] & 0x02) ++s.hdr_eof;  // EOF
    const int fid = p[1] & 1;
    if (s.prev_fid >= 0 && fid != s.prev_fid) {  // FID 翻转 = 帧边界
      // frame_bytes>0(raw 定长 depth)要求精确尺寸;==0(MJPEG color 变长)只要非空即出。
      const bool ok = s.frame_bytes > 0 ? (static_cast<int>(s.cur.size()) == s.frame_bytes)
                                        : (!s.cur.empty());
      if (ok && s.core) {
        const int64_t ns = NowNs();
        if (s.is_depth) s.core->OnRawDepthFrame(s.cur.data(), s.cur.size(), ns);
        else s.core->OnColorFrame(s.cur.data(), s.cur.size(), ns);
        ++s.frames;
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
  // ★ Android fd 路径（wrap_sys_device）严禁 reset：reset 触发端口重枚举 → 设备换地址，
  //   但我们 wrap 的是 Java openDevice 拿到的旧 fd，重枚举后 fd 立刻失效 → 后续控制/bulk 全打到
  //   死 handle（0 帧 + EventHub epoll hang-up + num_connects 攀升），且每次 open 都重枚举一次。
  //   host(libusb 自枚举) 才需要 reset 清残留 STALL；Android fd 每次会话都是全新 openDevice，不需要。
#if !defined(__ANDROID__)
  int rr = Eys3dUsb().reset_device(h);
  if (rr != 0 && rr != LIBUSB_ERROR_NOT_FOUND) {
    std::fprintf(stderr, "[eys3d] reset rc=%d %s\n", rr, Eys3dUsb().error_name(rr));
  }
#endif

  Eys3dUsbDevice dev(h);
#if defined(__ANDROID__)
  // Android：IF0/1/2 已由 Java 侧 UsbDeviceConnection.claimInterface(force=true) 认领并 detach uvcvideo。
  //   libusb 在同一 fd 再 claim 会 BUSY（实测返 0），但 usbfs 提交 URB 只要该 fd 持有接口认领即可，
  //   故此处不再 libusb_claim_interface，直接进开流序列（控制传输走 ep0 也不需要 claim）。
  EYS_LOG("claim: skipped on Android (Java UsbDeviceConnection owns IF0/1/2)");
#else
  const bool c0 = dev.claim(0);
  const bool c1 = dev.claim(1);
  const bool c2 = dev.claim(2);
  EYS_LOG("claim IF0=%d IF1=%d IF2=%d", c0, c1, c2);
  if (!c0) { core.MarkError("claim IF0 failed"); return; }
#endif

  // ---- 逐字复刻 proven eSPDI SDK 开流 arming（kProvenArming：153 条控制传输，源自 usbmon 抓包，14bit 配置）----
  //   含全 XU 能力枚举 + counter 单向递增 + entity-4 flash ZD 读握手 + IF1/IF2 PROBE 协商，末项 = COMMIT IF1。
  //   自研旧手拼序列漏了能力枚举/PROBE GET_MIN-MAX/entity-3 读位置错位 → entity-4 flash SET_CUR STALL → bulk 0 帧。
  //   读(payload 空)也照发：设备 flash 状态机对"该读被发过"敏感。控制响应本身慢(~2-17ms)，无需人造延时。
  int64_t wr_ok = 0, wr_fail = 0, rd_ok = 0, rd_fail = 0;
  std::vector<uint8_t> rbuf;
  int idx = 0;
  if (plan.external_arming) {
    EYS_LOG("arming: skipped in native (done via Java controlTransfer to COMMIT IF1)");
  } else
  for (const auto& x : kProvenArming) {
    uint8_t* data = nullptr;
    uint16_t len = x.wlen;
    if (!x.payload.empty()) {
      rbuf = x.payload; data = rbuf.data(); len = static_cast<uint16_t>(rbuf.size());
    } else if (x.wlen) {
      rbuf.assign(x.wlen, 0); data = rbuf.data();
    }
    // ★ counter GET-back / entity-4 flash 写在 Android usbfs 下偶发协议 STALL(PIPE)，RHEL9 host 不会。
    //   协议 STALL 在下个 SETUP 自动清(实测 STALL 后的写恢复正常) → 同笔短延后重试可推过去。
    int rc = -1;
    for (int attempt = 0; attempt < 4; ++attempt) {
      rc = dev.control_transfer(x.bmreq, x.breq, x.wval, x.widx, data, len, 2000);
      if (rc >= 0 || rc != LIBUSB_ERROR_PIPE) break;
      std::this_thread::sleep_for(std::chrono::milliseconds(3));
    }
    const bool is_write = (x.bmreq & 0x80) == 0;
    if (rc < 0) {
      if (is_write) ++wr_fail; else ++rd_fail;
      EYS_LOG("replay FAIL #%d: bmreq=%02x breq=%02x wval=%04x widx=%04x wlen=%u rc=%d(%s)",
              idx, x.bmreq, x.breq, x.wval, x.widx, x.wlen, rc, Eys3dUsb().error_name(rc));
    } else {
      if (is_write) ++wr_ok; else ++rd_ok;
    }
    ++idx;
  }
  EYS_LOG("arming replay: writes ok=%lld fail=%lld | reads ok=%lld fail=%lld (末项=COMMIT IF1)",
          (long long)wr_ok, (long long)wr_fail, (long long)rd_ok, (long long)rd_fail);

  // ---- 双端点流状态 + 按 proven 顺序交织 bulk 提交与 COMMIT IF2 / 启动触发 / CLEAR_HALT ----
  StreamState sc, sd;
  sc.ep = plan.color.endpoint; sc.frame_bytes = plan.color.frame_bytes;
  sc.urb_size = plan.color.urb_size; sc.is_depth = false; sc.core = &core;
  sd.ep = plan.depth.endpoint; sd.frame_bytes = plan.depth.frame_bytes;
  sd.urb_size = plan.depth.urb_size; sd.is_depth = true; sd.core = &core;
  const bool want[2] = {cfg.want_color, cfg.want_depth};

  constexpr int kNumUrb = 32;
  auto submit_stream = [&](StreamState& s) {
    s.urbs.resize(kNumUrb, nullptr);
    s.bufs.assign(kNumUrb, std::vector<uint8_t>(s.urb_size));
    for (int i = 0; i < kNumUrb; ++i) {
      s.urbs[i] = Eys3dUsb().alloc_transfer(0);
      libusb_fill_bulk_transfer(s.urbs[i], h, s.ep, s.bufs[i].data(), s.urb_size, StreamCb, &s, 0);
      if (Eys3dUsb().submit_transfer(s.urbs[i]) == 0) ++s.inflight;
    }
  };
  // COMMIT IF1 已是 arming 末项 → 先挂 color bulk（必须在 COMMIT IF2 之前，all-control-first 实测 0 帧）。
  if (want[0]) submit_stream(sc);
  int t_cif2 = 0, t_trig = 0, t_ch1 = 0, t_ch2 = 0;
  if (want[1]) {
    uint8_t commit_if2[26] = {0x01, 0x00, 0x01, 0x01, 0x80, 0x84, 0x1e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                              0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x25, 0x00, 0x00, 0x0c, 0x00, 0x00};
    t_cif2 = dev.control_transfer(0x21, 0x01, 0x0200, 0x0002, commit_if2, 26, 2000);
    submit_stream(sd);
  }
  { uint8_t p[4] = {0x20, 0xF5, 0x00, 0x00}; t_trig = dev.control_transfer(0x21, 0x01, 0x0300, 0x0400, p, 4, 2000); }
  { uint8_t p[1] = {0x14}; dev.control_transfer(0x21, 0x01, 0x0a00, 0x0400, p, 1, 2000); }
  if (want[0]) t_ch1 = dev.control_transfer(0x02, 0x01, 0x0000, 0x0081, nullptr, 0, 2000);
  { uint8_t p[1] = {0x15}; dev.control_transfer(0x21, 0x01, 0x0a00, 0x0400, p, 1, 2000); }
  { uint8_t p[1] = {0x16}; dev.control_transfer(0x21, 0x01, 0x0a00, 0x0400, p, 1, 2000); }
  if (want[1]) t_ch2 = dev.control_transfer(0x02, 0x01, 0x0000, 0x0082, nullptr, 0, 2000);
  EYS_LOG("native tail: COMMIT_IF2=%d trigger=%d clrHalt81=%d clrHalt82=%d (<0=失败)", t_cif2, t_trig, t_ch1, t_ch2);

  // ★ 中断 EP 0x83 持续轮询 = 流维持心跳。proven：SDK 全程 ~3ms 轮询 0x83 贯穿整个 streaming，
  //   稳态无任何控制写。缺它设备只吐 ~1s 缓冲(实测 ~2.3MB)即断流。数据全丢弃，只为让 HC 周期轮询该 EP。
  IntrState intr;
  constexpr int kNumIntr = 2;
  intr.urbs.resize(kNumIntr, nullptr);
  intr.bufs.assign(kNumIntr, std::vector<uint8_t>(1024));
  for (int i = 0; i < kNumIntr; ++i) {
    intr.urbs[i] = Eys3dUsb().alloc_transfer(0);
    // timeout=3ms：设备 NAK 时 URB 超时→IntrCb 重提，形成 ~3ms 主动周期 IN（复刻 SDK 心跳）。
    libusb_fill_interrupt_transfer(intr.urbs[i], h, 0x83, intr.bufs[i].data(), 1024, IntrCb, &intr, 3);
    if (Eys3dUsb().submit_transfer(intr.urbs[i]) == 0) ++intr.inflight;
  }

  StreamState* streams[2] = {&sc, &sd};
  EYS_LOG("streaming color(ep=0x%02x want=%d inflight=%d) depth(ep=0x%02x want=%d inflight=%d) intr(ep=0x83 inflight=%d)",
          sc.ep, want[0], sc.inflight, sd.ep, want[1], sd.inflight, intr.inflight);
  core.MarkStreaming();

  // 事件循环:handle_events → 组装喂 core,直到 stop 请求或致命错误。
  int tick = 0;
  while (!core.stop_requested()) {
    timeval tv{0, 50000};
    Eys3dUsb().handle_events_timeout(ctx, &tv);  // handle 所属 context(host 默认=nullptr;Android 具名)
    for (int si = 0; si < 2; ++si) {
      if (!want[si]) continue;
      DrainAndAssemble(*streams[si]);
    }
    if (++tick % 20 == 0) {  // ~1s 一次：定位卡在 transfer / 组装 / 心跳
      EYS_LOG("tick color[done=%lld B=%lld f=%lld err=%lld eof=%lld h1=%02x] depth[done=%lld B=%lld f=%lld err=%lld eof=%lld h1=%02x] intr[done=%lld]",
              (long long)sc.completed, (long long)sc.bytes, (long long)sc.frames, (long long)sc.hdr_err,
              (long long)sc.hdr_eof, sc.last_hdr1, (long long)sd.completed, (long long)sd.bytes,
              (long long)sd.frames, (long long)sd.hdr_err, (long long)sd.hdr_eof, sd.last_hdr1, (long long)intr.completed);
    }
    if (sc.errs > 0 && sd.errs > 0) { core.MarkError("bulk errors on both streams"); break; }
  }
  EYS_LOG("loop exit color[done=%lld B=%lld f=%lld e=%lld] depth[done=%lld B=%lld f=%lld e=%lld]",
          (long long)sc.completed, (long long)sc.bytes, (long long)sc.frames, (long long)sc.errs,
          (long long)sd.completed, (long long)sd.bytes, (long long)sd.frames, (long long)sd.errs);

  // 收尾:取消 URB（含中断心跳）并排空。
  intr.stop = true;
  for (auto* t : intr.urbs) if (t) Eys3dUsb().cancel_transfer(t);
  for (int si = 0; si < 2; ++si) {
    if (!want[si]) continue;
    StreamState& s = *streams[si];
    s.stop = true;
    for (auto* t : s.urbs) if (t) Eys3dUsb().cancel_transfer(t);
  }
  for (int spin = 0; spin < 80; ++spin) {
    bool busy = intr.inflight > 0;
    for (int si = 0; si < 2; ++si) if (want[si] && streams[si]->inflight > 0) busy = true;
    if (!busy) break;
    timeval tv{0, 20000};
    Eys3dUsb().handle_events_timeout(ctx, &tv);
  }
  for (auto* t : intr.urbs) if (t) Eys3dUsb().free_transfer(t);
  for (int si = 0; si < 2; ++si) {
    if (!want[si]) continue;
    for (auto* t : streams[si]->urbs) if (t) Eys3dUsb().free_transfer(t);
  }
  if (core.state() != SessionState::kError) core.MarkStopped();
}

}  // namespace gomob::eys3d::host

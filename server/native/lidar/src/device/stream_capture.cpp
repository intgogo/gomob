#include "device/stream_capture.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <algorithm>
#include <mutex>
#include <thread>
#include <vector>

#include "device/scan_stream.h"

namespace lidar::device {

namespace {

// Open+connect a blocking TCP socket with a 2s timeout. Returns fd>=0 or -1 (err set).
int connectTo(const std::string& ip, int port, std::string& err) {
  int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) { err = "socket() failed"; return -1; }
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(port));
  if (::inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) != 1) { err = "bad ip"; ::close(fd); return -1; }
  timeval tv{2, 0};
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    err = std::string("connect failed: ") + std::strerror(errno);
    ::close(fd);
    return -1;
  }
  return fd;
}

struct ScanStatusSample {
  std::string state;
  bool angle_seen{false};
  double latest_angle{0.0};
};

bool jsonNumberAfterKey(const std::string& body, const char* key, double& out) {
  const auto k = body.find(key);
  if (k == std::string::npos) return false;
  const auto c = body.find(':', k);
  if (c == std::string::npos) return false;
  const char* p = body.c_str() + c + 1;
  char* end = nullptr;
  const double v = std::strtod(p, &end);
  if (end == p || !std::isfinite(v)) return false;
  out = v;
  return true;
}

// 极简 HTTP/1.0 GET /api/device_status：返回 scan.state 和 encoder.latest_angle。
// 失败时 state 为空（调用方按 unknown 处理）。
ScanStatusSample httpGetScanStatus(const std::string& ip, int status_port) {
  ScanStatusSample out;
  std::string err;
  int fd = connectTo(ip, status_port, err);
  if (fd < 0) return out;
  const std::string req = "GET /api/device_status HTTP/1.0\r\nHost: x\r\n\r\n";
  ::send(fd, req.data(), req.size(), 0);
  std::string resp;
  char rb[2048];
  for (int i = 0; i < 8; ++i) {
    ssize_t n = ::recv(fd, rb, sizeof(rb), 0);
    if (n <= 0) break;
    resp.append(rb, static_cast<std::size_t>(n));
    if (resp.size() > 16384) break;
  }
  ::close(fd);
  const auto k = resp.find("\"state\"");
  if (k == std::string::npos) return out;
  const auto c = resp.find('"', resp.find(':', k) + 1);
  if (c == std::string::npos) return out;
  const auto e = resp.find('"', c + 1);
  if (e == std::string::npos) return out;
  out.state = resp.substr(c + 1, e - c - 1);
  out.angle_seen = jsonNumberAfterKey(resp, "\"latest_angle\"", out.latest_angle);
  return out;
}

std::string httpGetScanState(const std::string& ip, int status_port) {
  return httpGetScanStatus(ip, status_port).state;
}

double normalizedAngleDelta(double current, double previous) {
  double delta = current - previous;
  while (delta > 180.0) delta -= 360.0;
  while (delta < -180.0) delta += 360.0;
  return delta;
}

void addHeading(SweepCaptureResult& s, double h_angle_deg) {
  if (!std::isfinite(h_angle_deg)) return;
  if (!s.h_angle_seen) {
    s.h_angle_seen = true;
    s.h_first_raw_deg = h_angle_deg;
    s.h_last_raw_deg = h_angle_deg;
    s.h_unwrapped_deg = h_angle_deg;
    s.h_min_deg = s.h_unwrapped_deg;
    s.h_max_deg = s.h_unwrapped_deg;
    s.h_span_deg = 0.0;
    return;
  }
  s.h_unwrapped_deg += normalizedAngleDelta(h_angle_deg, s.h_last_raw_deg);
  s.h_last_raw_deg = h_angle_deg;
  if (s.h_unwrapped_deg < s.h_min_deg) s.h_min_deg = s.h_unwrapped_deg;
  if (s.h_unwrapped_deg > s.h_max_deg) s.h_max_deg = s.h_unwrapped_deg;
  s.h_span_deg = s.h_max_deg - s.h_min_deg;
}

}  // namespace

SweepCaptureResult captureSweep(const std::string& ip, int data_port, int status_port,
                                int poll_ms, int idle_timeout_ms, int hard_timeout_ms,
                                double min_expected_sweep_deg, const std::string& raw_dump_path,
                                const PtsFrameCallback& on_pts,
                                const std::atomic<bool>* cancel,
                                const CaptureReadyCallback& on_ready) {
  using clock = std::chrono::steady_clock;
  SweepCaptureResult s;
  int fd = connectTo(ip, data_port, s.error);
  if (fd < 0) {
    if (on_ready) on_ready(false);
    return s;
  }
  s.connected = true;
  if (on_ready) on_ready(true);

  std::ofstream dump;
  if (!raw_dump_path.empty()) dump.open(raw_dump_path, std::ios::binary);

  timeval rcv{0, 200000};                  // 200ms read timeout
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv, sizeof(rcv));
  std::uint8_t rb[65536];

  const bool poll_status = status_port > 0;
  const auto t_start = clock::now();
  auto t_next_poll = t_start;
  auto t_last_data = t_start;
  auto t_ready_after_sweep = t_start;
  bool ready_after_sweep = false;
  (void)min_expected_sweep_deg;  // PTS live 不再用角度跨度决定终止；仅保留 h_span 日志诊断。
  std::mutex parse_mu;
  std::condition_variable parse_cv;
  std::deque<std::vector<std::uint8_t>> parse_chunks;
  bool parse_done = false;
  auto reset_scan_angle = [&]() {
    s.h_angle_seen = false;
    s.h_first_raw_deg = 0.0;
    s.h_last_raw_deg = 0.0;
    s.h_unwrapped_deg = 0.0;
    s.h_min_deg = 0.0;
    s.h_max_deg = 0.0;
    s.h_span_deg = 0.0;
  };

  auto enqueue_parse = [&](const std::uint8_t* data, std::size_t n) {
    if (n == 0) return;
    std::vector<std::uint8_t> chunk(data, data + n);
    {
      std::lock_guard<std::mutex> lk(parse_mu);
      parse_chunks.push_back(std::move(chunk));
    }
    parse_cv.notify_one();
  };

  std::thread parser([&]() {
    std::vector<std::uint8_t> buf;
    std::size_t parsed_off = 0;
    auto drain_buf = [&]() {
      while (parsed_off + 12 <= buf.size()) {
        Frame fr; std::string err;
        std::size_t used = parseFrame(buf.data() + parsed_off, buf.size() - parsed_off, fr, err);
        if (used == 0) {
          if (!err.empty()) { ++parsed_off; continue; }
          break;
        }
        ++s.frames; (fr.crc_ok ? s.crc_ok : s.crc_bad)++;
        if (on_pts && fr.type == MsgType::PTS && fr.crc_ok) {
          PtsFrame p;
          if (decodePTS(fr.payload, p)) {
            if (!poll_status) {
              addHeading(s, p.h_angle_deg);
            }
            on_pts(p);
          }
        }
        parsed_off += used;
      }
      if (parsed_off > 0) {
        buf.erase(buf.begin(), buf.begin() + static_cast<std::ptrdiff_t>(parsed_off));
        parsed_off = 0;
      }
    };

    while (true) {
      std::vector<std::uint8_t> chunk;
      {
        std::unique_lock<std::mutex> lk(parse_mu);
        parse_cv.wait(lk, [&]() { return parse_done || !parse_chunks.empty(); });
        if (!parse_chunks.empty()) {
          chunk = std::move(parse_chunks.front());
          parse_chunks.pop_front();
        } else if (parse_done) {
          break;
        }
      }
      if (!chunk.empty()) {
        buf.insert(buf.end(), chunk.begin(), chunk.end());
        drain_buf();
      }
    }
    drain_buf();
  });

  auto finish_parser = [&]() {
    {
      std::lock_guard<std::mutex> lk(parse_mu);
      parse_done = true;
    }
    parse_cv.notify_one();
    if (parser.joinable()) parser.join();
  };

  while (true) {
    const auto now = clock::now();
    if (cancel && cancel->load()) { s.final_state = "CANCELLED"; break; }
    if (now - t_start > std::chrono::milliseconds(hard_timeout_ms)) { s.final_state = "TIMEOUT"; break; }

    // periodic status poll for sweep gating
    if (poll_status && now >= t_next_poll) {
      const ScanStatusSample st = httpGetScanStatus(ip, status_port);
      if (!st.state.empty()) {
        s.final_state = st.state;
        if (st.state == "SCAN" || st.state == "BUSY") {
          if (!s.sweep_seen) {
            t_last_data = clock::now();
            reset_scan_angle();
          }
          s.sweep_seen = true;
          ready_after_sweep = false;
          if (st.angle_seen) {
            addHeading(s, st.latest_angle);
          }
        } else if (s.sweep_seen && (st.state == "READY" || st.state == "IDLE")) {
          // 状态可能先于 4010 数据流回 READY；继续接收，直到数据真正空闲。
          if (!ready_after_sweep) {
            ready_after_sweep = true;
            t_ready_after_sweep = clock::now();
          }
        }
      }
      t_next_poll = now + std::chrono::milliseconds(poll_ms);
    }

    ssize_t n = ::recv(fd, rb, sizeof(rb), 0);
    if (n > 0) {
      // PTS 必须先高速收完整原始流，不能在 socket 读循环里同步做 cgo/Go/NATS 回调；
      // 回调链稍慢就会反压 4010，导致顶视只剩一小段。状态门控只决定何时开始接收原始流。
      if (!poll_status || s.sweep_seen) {
        s.bytes += static_cast<std::size_t>(n);
        if (dump) dump.write(reinterpret_cast<const char*>(rb), n);
        s.raw.insert(s.raw.end(), rb, rb + n);
        enqueue_parse(rb, static_cast<std::size_t>(n));
      }
      if (!poll_status && !s.sweep_seen) {
        s.sweep_seen = true;
        s.final_state = "DATA";
      }
      t_last_data = clock::now();
    // PTS socket 可能在扫掠中段短暂停顿；启用状态轮询时，只有控制板已回 READY/IDLE，
    // 才把 idle 当作最终收尾。
    } else if (s.sweep_seen && (!poll_status || ready_after_sweep) &&
               (clock::now() - t_last_data) > std::chrono::milliseconds(idle_timeout_ms)) {
      break;  // sweep seen and the stream has gone quiet
    } else if (ready_after_sweep &&
               (clock::now() - t_ready_after_sweep) > std::chrono::milliseconds(8000)) {
      break;  // READY 后仍持续出流时给足收尾窗口，再防止无限等待。
    }
  }
  ::close(fd);
  finish_parser();
  return s;
}

SweepCaptureResult captureLdrSweep(const std::string& ip, int data_port, int status_port,
                                   int poll_ms, int idle_timeout_ms, int hard_timeout_ms,
                                   double min_expected_sweep_deg, const std::string& raw_dump_path,
                                   const LdrFrameCallback& on_ldr,
                                   const std::atomic<bool>* cancel,
                                   const CaptureReadyCallback& on_ready) {
  using clock = std::chrono::steady_clock;
  SweepCaptureResult s;
  int fd = connectTo(ip, data_port, s.error);
  if (fd < 0) {
    if (on_ready) on_ready(false);
    return s;
  }
  s.connected = true;
  if (on_ready) on_ready(true);

  std::ofstream dump;
  if (!raw_dump_path.empty()) dump.open(raw_dump_path, std::ios::binary);

  timeval rcv{0, 200000};
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv, sizeof(rcv));
  std::uint8_t rb[65536];

  const bool poll_status = status_port > 0;
  const auto t_start = clock::now();
  auto t_next_poll = t_start;
  auto t_last_data = t_start;
  auto t_last_angle_progress = t_start;
  auto t_ready_after_sweep = t_start;
  bool ready_after_sweep = false;
  std::size_t parsed_off = 0;
  const double min_required_sweep = min_expected_sweep_deg > 0.0
      ? std::max(10.0, min_expected_sweep_deg * 0.80)
      : 0.0;
  double last_progress_span = 0.0;
  bool latest_status_angle_seen = false;
  double latest_status_angle = 0.0;
  bool accept_scan_data = !poll_status;
  auto note_progress = [&]() {
    if (s.h_span_deg > last_progress_span + 0.25) {
      last_progress_span = s.h_span_deg;
      t_last_angle_progress = clock::now();
    }
  };
  auto sweep_enough = [&]() {
    return min_required_sweep <= 0.0 || (s.h_angle_seen && s.h_span_deg >= min_required_sweep);
  };

  auto drain = [&]() {
    while (parsed_off + 12 <= s.raw.size()) {
      Frame fr; std::string err;
      std::size_t used = parseFrame(s.raw.data() + parsed_off, s.raw.size() - parsed_off, fr, err);
      if (used == 0) { if (!err.empty()) { ++parsed_off; continue; } break; }
      ++s.frames; (fr.crc_ok ? s.crc_ok : s.crc_bad)++;
      if (fr.type == MsgType::LDR && fr.crc_ok) {
        LdrFrame l;
        if (decodeLDR(fr.payload, l)) {
          if (latest_status_angle_seen) {
            l.capture_angle_deg = latest_status_angle;
          } else {
            l.capture_angle_deg = l.h_angle_deg;
            addHeading(s, l.h_angle_deg);
            note_progress();
          }
          if (accept_scan_data && on_ldr) on_ldr(l);
        }
      }
      parsed_off += used;
    }
  };

  while (true) {
    const auto now = clock::now();
    if (cancel && cancel->load()) { s.final_state = "CANCELLED"; break; }
    if (now - t_start > std::chrono::milliseconds(hard_timeout_ms)) { s.final_state = "TIMEOUT"; break; }

    if (poll_status && now >= t_next_poll) {
      const ScanStatusSample st = httpGetScanStatus(ip, status_port);
      if (!st.state.empty()) {
        s.final_state = st.state;
        if (st.state == "SCAN" || st.state == "BUSY") {
          if (!s.sweep_seen) t_last_data = clock::now();
          s.sweep_seen = true;
          accept_scan_data = st.state == "SCAN";
          ready_after_sweep = false;
          if (st.state == "SCAN" && st.angle_seen) {
            latest_status_angle_seen = true;
            latest_status_angle = st.latest_angle;
            addHeading(s, latest_status_angle);
            note_progress();
          }
        } else if (s.sweep_seen && (st.state == "READY" || st.state == "IDLE")) {
          accept_scan_data = false;
          if (!ready_after_sweep) {
            ready_after_sweep = true;
            t_ready_after_sweep = clock::now();
          }
        }
      }
      t_next_poll = now + std::chrono::milliseconds(poll_ms);
    }

    ssize_t n = ::recv(fd, rb, sizeof(rb), 0);
    if (n > 0) {
      s.bytes += static_cast<std::size_t>(n);
      if (dump) dump.write(reinterpret_cast<const char*>(rb), n);
      s.raw.insert(s.raw.end(), rb, rb + n);
      if (!poll_status && !s.sweep_seen) {
        s.sweep_seen = true;
        s.final_state = "DATA";
      }
      t_last_data = clock::now();
      drain();
      if (ready_after_sweep && sweep_enough() &&
          (clock::now() - t_ready_after_sweep) > std::chrono::milliseconds(1200)) break;
      if (ready_after_sweep && !sweep_enough() &&
          (clock::now() - t_last_angle_progress) > std::chrono::milliseconds(12000)) {
        s.final_state = "ANGLE_INCOMPLETE";
        break;
      }
    } else if (s.sweep_seen && (!poll_status || ready_after_sweep) &&
               (clock::now() - t_last_data) > std::chrono::milliseconds(idle_timeout_ms)) {
      if (!sweep_enough()) s.final_state = "ANGLE_INCOMPLETE";
      break;
    } else if (ready_after_sweep && sweep_enough() &&
               (clock::now() - t_ready_after_sweep) > std::chrono::milliseconds(1200)) {
      break;
    } else if (ready_after_sweep && !sweep_enough() &&
               (clock::now() - t_last_angle_progress) > std::chrono::milliseconds(12000)) {
      s.final_state = "ANGLE_INCOMPLETE";
      break;
    }
  }
  ::close(fd);
  drain();
  return s;
}

SweepCaptureResult captureImageSweep(const std::string& ip, int data_port, int status_port,
                                     int poll_ms, int idle_timeout_ms, int hard_timeout_ms,
                                     const std::string& raw_dump_path, const ImgFrameCallback& on_img,
                                     const std::atomic<bool>* cancel,
                                     const CaptureReadyCallback& on_ready) {
  using clock = std::chrono::steady_clock;
  SweepCaptureResult s;
  int fd = connectTo(ip, data_port, s.error);
  if (fd < 0) {
    if (on_ready) on_ready(false);
    return s;
  }
  s.connected = true;
  if (on_ready) on_ready(true);

  std::ofstream dump;
  if (!raw_dump_path.empty()) dump.open(raw_dump_path, std::ios::binary);

  timeval rcv{0, 200000};
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv, sizeof(rcv));
  std::uint8_t rb[65536];

  const bool poll_status = status_port > 0;
  const auto t_start = clock::now();
  auto t_next_poll = t_start;
  auto t_last_data = t_start;
  auto t_ready_after_sweep = t_start;
  bool ready_after_sweep = false;
  std::size_t parsed_off = 0;

  auto drain = [&]() {
    while (parsed_off + 12 <= s.raw.size()) {
      Frame fr; std::string err;
      std::size_t used = parseFrame(s.raw.data() + parsed_off, s.raw.size() - parsed_off, fr, err);
      if (used == 0) { if (!err.empty()) { ++parsed_off; continue; } break; }
      ++s.frames; (fr.crc_ok ? s.crc_ok : s.crc_bad)++;
      // 102 的 IMG 大帧现场出现过 CRC 不匹配但 JPEG payload 完整的情况；纹理链路以 JPEG 解码
      // 成功为准，crc_ok/crc_bad 仍照常统计进日志供追查链路质量。
      if (on_img && fr.type == MsgType::IMG) {
        ImgFrame im;
        if (decodeIMG(fr.payload, im)) on_img(im);
      }
      parsed_off += used;
    }
  };

  while (true) {
    const auto now = clock::now();
    if (cancel && cancel->load()) { s.final_state = "CANCELLED"; break; }
    if (now - t_start > std::chrono::milliseconds(hard_timeout_ms)) { s.final_state = "TIMEOUT"; break; }

    if (poll_status && now >= t_next_poll) {
      const std::string st = httpGetScanState(ip, status_port);
      if (!st.empty()) {
        s.final_state = st;
        if (st == "SCAN" || st == "BUSY") {
          if (!s.sweep_seen) t_last_data = clock::now();
          s.sweep_seen = true;
          ready_after_sweep = false;
        } else if (s.sweep_seen && (st == "READY" || st == "IDLE")) {
          // IMG 帧率低，READY 不能作为立即截断条件；等待数据流自然空闲。
          if (!ready_after_sweep) {
            ready_after_sweep = true;
            t_ready_after_sweep = clock::now();
          }
        }
      }
      t_next_poll = now + std::chrono::milliseconds(poll_ms);
    }

    ssize_t n = ::recv(fd, rb, sizeof(rb), 0);
    if (n > 0) {
      s.bytes += static_cast<std::size_t>(n);
      if (dump) dump.write(reinterpret_cast<const char*>(rb), n);
      s.raw.insert(s.raw.end(), rb, rb + n);
      if (!poll_status && !s.sweep_seen) {
        s.sweep_seen = true;
        s.final_state = "DATA";
      }
      t_last_data = clock::now();
      drain();
      if (ready_after_sweep &&
          (clock::now() - t_ready_after_sweep) > std::chrono::milliseconds(8000)) break;
    } else if (s.sweep_seen && (clock::now() - t_last_data) > std::chrono::milliseconds(idle_timeout_ms)) {
      break;
    } else if (ready_after_sweep &&
               (clock::now() - t_ready_after_sweep) > std::chrono::milliseconds(8000)) {
      break;
    }
  }
  ::close(fd);
  drain();
  return s;
}

StreamCaptureSummary captureStream(const std::string& ip, int port, int seconds,
                                   const std::string& raw_dump_path) {
  StreamCaptureSummary s;

  int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) { s.error = "socket() failed"; return s; }

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(port));
  if (::inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) != 1) { s.error = "bad ip"; ::close(fd); return s; }

  // connect timeout via a brief blocking connect (ports already known open).
  timeval tv{2, 0};
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
    s.error = std::string("connect failed: ") + std::strerror(errno);
    ::close(fd);
    return s;
  }
  s.connected = true;

  std::ofstream dump;
  if (!raw_dump_path.empty()) dump.open(raw_dump_path, std::ios::binary);

  std::vector<std::uint8_t> buf;
  buf.reserve(1 << 20);
  std::uint8_t rb[65536];

  timeval rcv{0, 200000};                      // 200ms read timeout
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv, sizeof(rcv));

  const auto t_end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
  while (std::chrono::steady_clock::now() < t_end) {
    ssize_t n = ::recv(fd, rb, sizeof(rb), 0);
    if (n > 0) {
      s.bytes += static_cast<std::size_t>(n);
      if (dump) dump.write(reinterpret_cast<const char*>(rb), n);
      buf.insert(buf.end(), rb, rb + n);

      // parse as many complete frames as the buffer holds
      std::size_t off = 0;
      while (off + 12 <= buf.size()) {
        Frame fr;
        std::string err;
        std::size_t used = parseFrame(buf.data() + off, buf.size() - off, fr, err);
        if (used == 0) {
          if (!err.empty()) { off += 1; continue; }  // resync on bad magic
          break;                                       // need more bytes
        }
        ++s.frames;
        (fr.crc_ok ? s.crc_ok : s.crc_bad)++;
        char t = static_cast<char>(fr.type);
        s.type_counts[t]++;
        if (s.sample_kind.empty()) {
          if (fr.type == MsgType::LDR) {
            LdrFrame ldr;
            if (decodeLDR(fr.payload, ldr) && !ldr.points.empty()) {
              s.sample_kind = "LDR";
              s.sample_first_dist_or_xyz = ldr.points.front().dist_m;
              char tmp[256];
              std::snprintf(tmp, sizeof(tmp), "LDR h=%.3f scan=%d pts=%zu dist0=%.4f v0=%.3f",
                            ldr.h_angle_deg, ldr.scan_seq, ldr.points.size(),
                            ldr.points.front().dist_m, ldr.points.front().v_angle_deg);
              s.sample = tmp;
            }
          } else if (fr.type == MsgType::PTS) {
            PtsFrame pts;
            if (decodePTS(fr.payload, pts) && !pts.points.empty()) {
              s.sample_kind = "PTS";
              const auto& p0 = pts.points.front();
              s.sample_first_dist_or_xyz = std::sqrt(p0.x * p0.x + p0.y * p0.y + p0.z * p0.z);
              char tmp[256];
              std::snprintf(tmp, sizeof(tmp), "PTS h=%.3f scan=%d pts=%zu xyz0=(%.4f,%.4f,%.4f)",
                            pts.h_angle_deg, pts.scan_seq, pts.points.size(), p0.x, p0.y, p0.z);
              s.sample = tmp;
            }
          }
        }
        off += used;
      }
      if (off > 0) buf.erase(buf.begin(), buf.begin() + off);
    }
    // n<=0 => recv timed out (no data this 200ms slice); keep looping until t_end.
  }

  ::close(fd);
  return s;
}

}  // namespace lidar::device

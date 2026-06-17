// Raw TCP capture of a device data-stream port (4001/4002/4003/4010). Connects, reads
// for `seconds`, parses CA FE frames (device/scan_stream), and summarizes which msg types
// arrive + sample decoded values — resolves R3 (port->type) and U1 (mm-vs-m) on live data.
// Socket headers are confined to the .cpp (kept away from Eigen TUs).
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "device/scan_stream.h"

namespace lidar::device {

struct StreamCaptureSummary {
  std::size_t bytes{0}, frames{0}, crc_ok{0}, crc_bad{0};
  std::map<char, int> type_counts;   // msg_type char -> frame count
  bool connected{false};
  std::string error;
  std::string sample;                // human-readable decode of the first L/P frame seen
  double sample_first_dist_or_xyz{0};// first dist (LDR) or |xyz| (PTS) — magnitude hints mm vs m
  std::string sample_kind;           // "LDR" | "PTS" | ""
};

// Capture for `seconds` from ip:port. If raw_dump_path is non-empty, the raw bytes are written
// there (replayable offline). Returns the summary (connected=false + error on failure).
StreamCaptureSummary captureStream(const std::string& ip, int port, int seconds,
                                   const std::string& raw_dump_path = "");

// State-GATED capture for the live vehicle-scan path (passive: the USER triggers SCAN_START).
// Connects to `data_port` and polls `/api/device_status` on `status_port`; buffers stream bytes,
// flags `sweep_seen` once scan.state==SCAN, and stops when state returns to READY/IDLE after the
// sweep (or after `idle_timeout_ms` with no data post-sweep, or `hard_timeout_ms` overall).
struct SweepCaptureResult {
  std::vector<std::uint8_t> raw;     // captured stream bytes spanning the sweep (replayable)
  std::size_t bytes{0}, frames{0}, crc_ok{0}, crc_bad{0};
  bool connected{false}, sweep_seen{false};
  bool h_angle_seen{false};
  double h_first_raw_deg{0.0};
  double h_last_raw_deg{0.0}, h_unwrapped_deg{0.0};
  double h_min_deg{0.0}, h_max_deg{0.0}, h_span_deg{0.0};
  std::string final_state, error;
};
// on_pts（可选）：每解出一帧 PTS 即回调，用于流式（采集中实时推点/增量建云）。
// cancel（可选）：置 true 时协作中断采集（用于 lidar_scan_cancel）。
using PtsFrameCallback = std::function<void(const PtsFrame&)>;
using LdrFrameCallback = std::function<void(const LdrFrame&)>;
using CaptureReadyCallback = std::function<void(bool connected)>;
SweepCaptureResult captureSweep(const std::string& ip, int data_port = 4010, int status_port = 4000,
                                int poll_ms = 400, int idle_timeout_ms = 8000,
                                int hard_timeout_ms = 180000, double min_expected_sweep_deg = 0.0,
                                const std::string& raw_dump_path = "",
                                const PtsFrameCallback& on_pts = nullptr,
                                const std::atomic<bool>* cancel = nullptr,
                                const CaptureReadyCallback& on_ready = nullptr);
SweepCaptureResult captureLdrSweep(const std::string& ip, int data_port = 4002, int status_port = 4000,
                                   int poll_ms = 400, int idle_timeout_ms = 8000,
                                   int hard_timeout_ms = 180000, double min_expected_sweep_deg = 0.0,
                                   const std::string& raw_dump_path = "",
                                   const LdrFrameCallback& on_ldr = nullptr,
                                   const std::atomic<bool>* cancel = nullptr,
                                   const CaptureReadyCallback& on_ready = nullptr);

using ImgFrameCallback = std::function<void(const ImgFrame&)>;
SweepCaptureResult captureImageSweep(const std::string& ip, int data_port = 4003, int status_port = 4000,
                                     int poll_ms = 400, int idle_timeout_ms = 8000,
                                     int hard_timeout_ms = 180000, const std::string& raw_dump_path = "",
                                     const ImgFrameCallback& on_img = nullptr,
                                     const std::atomic<bool>* cancel = nullptr,
                                     const CaptureReadyCallback& on_ready = nullptr);

}  // namespace lidar::device

#include "gomob_berxel_portable.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <iomanip>
#include <limits>
#include <queue>
#include <regex>
#include <sstream>
#include <thread>
#include <utility>

namespace gomob::berxel::host {

namespace detail {

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void log_line(LogFn log, const std::string& msg) {
    if (log) log(msg);
}

uint32_t read_le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t read_le16(const std::vector<uint8_t>& data, size_t offset) {
    if (offset + 1 >= data.size()) return 0;
    return static_cast<uint16_t>(data[offset]) |
           static_cast<uint16_t>(static_cast<uint16_t>(data[offset + 1]) << 8);
}

void write_le16(std::vector<uint8_t>& data, size_t offset, uint16_t v) {
    if (offset + 1 >= data.size()) return;
    data[offset] = static_cast<uint8_t>(v & 0xff);
    data[offset + 1] = static_cast<uint8_t>((v >> 8) & 0xff);
}

void write_le32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xff);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xff);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xff);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xff);
}

bool is_master_time_sync_payload(const std::vector<uint8_t>& data) {
    static constexpr uint8_t kPrefix[] = {
        0x42, 0x58, 0x0a, 0x00, 0x05, 0x00, 0x00, 0x00, 0x06, 0x00,
    };
    return data.size() >= 18 &&
           std::equal(kPrefix, kPrefix + sizeof(kPrefix), data.begin());
}

bool is_p100r3_color_open_stream_payload(const XuPayload& payload) {
    return payload.w_value == 0x0100 &&
           payload.w_index == kP100R3MasterXu5WIndex &&
           payload.data.size() >= 20 &&
           payload.data[0] == 0x42 &&
           payload.data[1] == 0x58 &&
           read_le16(payload.data, 2) == 12 &&
           read_le16(payload.data, 4) == 0x0006 &&
           read_le16(payload.data, 6) == 0x0000 &&
           read_le16(payload.data, 8) == 1;
}

bool is_p100r3_depth_open_stream_payload(const XuPayload& payload) {
    return payload.selector == 25 &&
           payload.w_index == kP100R3CompanionXu3WIndex &&
           payload.data.size() >= 3 &&
           payload.data[0] == 0x01 &&
           payload.data[1] == 0x02 &&
           (payload.data[2] == 0x03 || payload.data[2] == 0x08 || payload.data[2] == 0x0c);
}

std::string hex16(uint16_t v) {
    std::ostringstream ss;
    ss << "0x" << std::hex << std::setw(4) << std::setfill('0') << v;
    return ss.str();
}

int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

std::vector<uint8_t> hex_to_bytes(const std::string& hex) {
    std::vector<uint8_t> out;
    if ((hex.size() % 2) != 0) return out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        const int hi = hex_digit(hex[i]);
        const int lo = hex_digit(hex[i + 1]);
        if (hi < 0 || lo < 0) return {};
        out.push_back(static_cast<uint8_t>((hi << 4) | lo));
    }
    return out;
}

bool extract_int(const std::string& text, const std::string& key, int* out) {
    const std::regex re("\"" + key + "\"\\s*:\\s*([0-9]+)");
    std::smatch m;
    if (!std::regex_search(text, m, re)) return false;
    *out = std::atoi(m[1].str().c_str());
    return true;
}

bool extract_hex(const std::string& text, const std::string& key, std::string* out) {
    const std::regex re("\"" + key + "\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    std::smatch m;
    if (!std::regex_search(text, m, re)) return false;
    *out = m[1].str();
    return true;
}
size_t find_marker(const std::vector<uint8_t>& data, uint8_t a, uint8_t b, size_t start) {
    for (size_t i = start; i + 1 < data.size(); ++i) {
        if (data[i] == a && data[i + 1] == b) return i;
    }
    return std::string::npos;
}

size_t rfind_marker(const std::vector<uint8_t>& data, uint8_t a, uint8_t b) {
    if (data.size() < 2) return std::string::npos;
    for (size_t i = data.size() - 2;; --i) {
        if (data[i] == a && data[i + 1] == b) return i;
        if (i == 0) break;
    }
    return std::string::npos;
}
bool find_jpeg_bounds(const std::vector<uint8_t>& frame, size_t* begin, size_t* end) {
    if (frame.empty()) return false;
    const size_t soi = find_marker(frame, 0xff, 0xd8, 0);
    const size_t eoi = rfind_marker(frame, 0xff, 0xd9);
    if (soi == std::string::npos || eoi == std::string::npos || eoi <= soi) return false;
    if (begin) *begin = soi;
    if (end) *end = eoi + 2;
    return true;
}

std::string hex_bytes(const uint8_t* data, int length) {
    std::ostringstream ss;
    ss << std::hex << std::setfill('0');
    for (int i = 0; i < length; ++i) {
        if (i > 0) ss << ' ';
        ss << std::setw(2) << static_cast<int>(data[i]);
    }
    return ss.str();
}
UvcPayloadView parse_uvc_payload(const uint8_t* data, int actual) {
    UvcPayloadView view;
    if (!data || actual <= 0) return view;
    view.length = actual;
    if (actual < 2) return view;

    const int header_len = data[0];
    const uint8_t flags = data[1];
    const bool looks_like_uvc = header_len >= 2 &&
                                header_len <= actual &&
                                header_len <= 64 &&
                                ((flags & 0x80) != 0);
    if (!looks_like_uvc) return view;

    view.offset = header_len;
    view.length = actual - header_len;
    view.valid = true;
    view.eof = (flags & 0x02) != 0;
    view.error = (flags & 0x40) != 0;
    view.fid = flags & 0x01;
    int cursor = 2;
    if ((flags & 0x04) != 0 && cursor + 4 <= header_len) {
        view.has_pts = true;
        view.pts = read_le32(data + cursor);
        cursor += 4;
    }
    if ((flags & 0x08) != 0 && cursor + 6 <= header_len) {
        view.has_scr = true;
        view.scr_stc = read_le32(data + cursor);
        view.scr_sof = static_cast<uint16_t>(data[cursor + 4]) |
                       static_cast<uint16_t>(static_cast<uint16_t>(data[cursor + 5]) << 8);
    }
    return view;
}

void begin_frame_info(UvcFrameInfo* info,
                      uint8_t endpoint,
                      const P100R3VideoMode& mode,
                      uint64_t frame_number,
                      int64_t host_ns,
                      const UvcPayloadView& payload) {
    if (!info) return;
    *info = UvcFrameInfo{};
    info->endpoint = endpoint;
    info->mode = mode;
    info->frame_number = frame_number;
    info->host_start_ns = host_ns;
    info->host_end_ns = host_ns;
    if (payload.valid) {
        info->has_uvc_header = true;
        info->fid = payload.fid;
        if (payload.has_pts) {
            info->has_uvc_pts = true;
            info->uvc_pts = payload.pts;
        }
        if (payload.has_scr) {
            info->has_uvc_scr = true;
            info->uvc_scr_stc = payload.scr_stc;
            info->uvc_scr_sof = payload.scr_sof;
        }
    }
}

void update_frame_info(UvcFrameInfo* info,
                       int actual,
                       int payload_length,
                       int64_t host_ns,
                       const UvcPayloadView& payload) {
    if (!info) return;
    info->host_end_ns = host_ns;
    info->transport_bytes += static_cast<uint32_t>(std::max(0, actual));
    info->payload_bytes += static_cast<uint32_t>(std::max(0, payload_length));
    if (payload.valid && !info->has_uvc_header) {
        info->has_uvc_header = true;
        info->fid = payload.fid;
    }
    if (payload.has_pts && !info->has_uvc_pts) {
        info->has_uvc_pts = true;
        info->uvc_pts = payload.pts;
    }
    if (payload.has_scr && !info->has_uvc_scr) {
        info->has_uvc_scr = true;
        info->uvc_scr_stc = payload.scr_stc;
        info->uvc_scr_sof = payload.scr_sof;
    }
}

void note_bulk_error(BulkStats& stats, int rc) {
    if (rc == kUvcErrorTimeout || rc == 0) {
        stats.timeouts++;
        return;
    }
    stats.errors++;
    if (stats.first_error == 0) stats.first_error = rc;
}

bool is_master_keepalive_payload(const XuPayload& payload) {
    static constexpr uint8_t kPrefix[] = {
        0x42, 0x58, 0x0a, 0x00, 0x0d, 0x00, 0x00, 0x00,
    };
    return payload.data.size() >= sizeof(kPrefix) &&
           std::equal(kPrefix, kPrefix + sizeof(kPrefix), payload.data.begin());
}

bool find_keepalive_seed(const std::vector<XuPayload>& payloads, XuPayload* out) {
    for (auto it = payloads.rbegin(); it != payloads.rend(); ++it) {
        if (!is_master_keepalive_payload(*it)) continue;
        if (out) *out = *it;
        return true;
    }
    return false;
}

int depth_frame_size(const P100R3VideoMode& mode) {
    return static_cast<int>(mode.width) * static_cast<int>(mode.height) * 2;
}

int color_yuy2_frame_size(const P100R3VideoMode& mode) {
    return static_cast<int>(mode.width) * static_cast<int>(mode.height) * 2;
}

}  // namespace detail

using namespace detail;  // portable 内部沿用 detail helper 不必逐处限定

const char* usb_error_name(int rc) {
    switch (rc) {
        case 0: return "LIBUSB_SUCCESS";
        case -1: return "LIBUSB_ERROR_IO";
        case -2: return "LIBUSB_ERROR_INVALID_PARAM";
        case -3: return "LIBUSB_ERROR_ACCESS";
        case -4: return "LIBUSB_ERROR_NO_DEVICE";
        case -5: return "LIBUSB_ERROR_NOT_FOUND";
        case -6: return "LIBUSB_ERROR_BUSY";
        case -7: return "LIBUSB_ERROR_TIMEOUT";
        case -8: return "LIBUSB_ERROR_OVERFLOW";
        case -9: return "LIBUSB_ERROR_PIPE";
        case -10: return "LIBUSB_ERROR_INTERRUPTED";
        case -11: return "LIBUSB_ERROR_NO_MEM";
        case -12: return "LIBUSB_ERROR_NOT_SUPPORTED";
        case -99: return "LIBUSB_ERROR_OTHER";
        default: return "LIBUSB_ERROR_UNKNOWN";
    }
}

std::string usb_id_string(UsbId id) {
    std::ostringstream ss;
    ss << std::hex << std::setw(4) << std::setfill('0') << id.vid
       << ":" << std::setw(4) << std::setfill('0') << id.pid;
    return ss.str();
}

int refresh_master_time_sync_payloads(std::vector<XuPayload>* payloads) {
    if (!payloads) return 0;
    const auto now = std::chrono::system_clock::now();
    const auto since_epoch = now.time_since_epoch();
    const auto sec = std::chrono::duration_cast<std::chrono::seconds>(since_epoch);
    const auto usec =
        std::chrono::duration_cast<std::chrono::microseconds>(since_epoch - sec);

    int patched = 0;
    for (auto& payload : *payloads) {
        if (!is_master_time_sync_payload(payload.data)) continue;
        write_le32(&payload.data[10], static_cast<uint32_t>(sec.count()));
        write_le32(&payload.data[14], static_cast<uint32_t>(usec.count()));
        patched++;
    }
    return patched;
}

int64_t uvc_frame_midpoint_ns(const UvcFrameInfo& info) {
    return info.host_start_ns + (info.host_end_ns - info.host_start_ns) / 2;
}

UvcRawFrameAssembler::UvcRawFrameAssembler(UvcRawFrameAssemblerConfig config)
    : config_(std::move(config)) {
    if (config_.frame_size > 0) {
        frame_.reserve(std::min<size_t>(config_.frame_size * 2, 8 * 1024 * 1024));
    }
}

void UvcRawFrameAssembler::reset() {
    stats_ = UvcRawFrameAssemblerStats{};
    frame_.clear();
    current_ = UvcFrameInfo{};
    have_current_ = false;
}

bool UvcRawFrameAssembler::push_packet(const uint8_t* data,
                                       int actual,
                                       int64_t packet_ns,
                                       std::vector<UvcRawFrame>* out_frames) {
    if (out_frames) out_frames->clear();
    if (!data || actual <= 0 || config_.frame_size == 0) return false;

    const UvcPayloadView payload = parse_uvc_payload(data, actual);
    if (payload.error) return true;
    if (payload.valid) {
        stats_.uvc_headers++;
        if (config_.drop_partial_on_uvc_header && !frame_.empty() &&
            frame_.size() < config_.frame_size) {
            frame_.clear();
            have_current_ = false;
            stats_.frame_drops++;
            stats_.partial_frame_drops++;
        }
    }

    const int payload_offset = payload.valid ? payload.offset : 0;
    const int payload_length = payload.valid ? payload.length : actual;
    if (payload_length <= 0) {
        stats_.buffered_bytes = frame_.size();
        return true;
    }

    if (!have_current_) {
        begin_frame_info(&current_,
                         config_.endpoint,
                         config_.mode,
                         static_cast<uint64_t>(stats_.frames + 1),
                         packet_ns,
                         payload);
        have_current_ = true;
    }
    update_frame_info(&current_, actual, payload_length, packet_ns, payload);
    frame_.insert(frame_.end(),
                  data + payload_offset,
                  data + payload_offset + payload_length);

    const size_t max_buffer = config_.max_buffer_bytes > 0
        ? config_.max_buffer_bytes
        : config_.frame_size * 3;
    if (frame_.size() > max_buffer) {
        const auto keep_begin = frame_.end() - static_cast<std::ptrdiff_t>(config_.frame_size);
        frame_.erase(frame_.begin(), keep_begin);
        stats_.frame_drops++;
        stats_.oversized_frame_drops++;
        // 丢掉超长残留后，current_ 仍带被截断半帧的 host_start_ns/fid/pts；重置成保留尾段的新帧信息，
        // 否则下面 while 出帧会用 stale 元数据，污染 RGBD 配对的 host_delta。
        begin_frame_info(&current_,
                         config_.endpoint,
                         config_.mode,
                         static_cast<uint64_t>(stats_.frames + 1),
                         packet_ns,
                         UvcPayloadView{});
        current_.payload_bytes = static_cast<uint32_t>(
            std::min<size_t>(frame_.size(), std::numeric_limits<uint32_t>::max()));
        have_current_ = true;
    }

    while (frame_.size() >= config_.frame_size) {
        UvcRawFrame frame;
        frame.info = current_;
        frame.info.frame_number = static_cast<uint64_t>(stats_.frames + 1);
        frame.info.payload_bytes = static_cast<uint32_t>(
            std::min<size_t>(config_.frame_size, std::numeric_limits<uint32_t>::max()));
        frame.info.completed_by_size = true;
        frame.payload.assign(frame_.begin(),
                             frame_.begin() + static_cast<std::ptrdiff_t>(config_.frame_size));
        if (out_frames) out_frames->push_back(std::move(frame));
        stats_.frames++;
        stats_.completed_by_size++;
        frame_.erase(frame_.begin(),
                     frame_.begin() + static_cast<std::ptrdiff_t>(config_.frame_size));
        have_current_ = false;
        if (!frame_.empty()) {
            begin_frame_info(&current_,
                             config_.endpoint,
                             config_.mode,
                             static_cast<uint64_t>(stats_.frames + 1),
                             packet_ns,
                             UvcPayloadView{});
            current_.payload_bytes = static_cast<uint32_t>(
                std::min<size_t>(frame_.size(), std::numeric_limits<uint32_t>::max()));
            have_current_ = true;
        }
    }

    stats_.buffered_bytes = frame_.size();
    return true;
}

UvcRawFrameAssemblerStats UvcRawFrameAssembler::stats() const {
    UvcRawFrameAssemblerStats out = stats_;
    out.buffered_bytes = frame_.size();
    return out;
}

UvcMjpegFrameAssembler::UvcMjpegFrameAssembler(UvcMjpegFrameAssemblerConfig config)
    : config_(std::move(config)) {
    frame_.reserve(1024 * 1024);
}

void UvcMjpegFrameAssembler::reset() {
    stats_ = UvcMjpegFrameAssemblerStats{};
    frame_.clear();
    current_ = UvcFrameInfo{};
    have_current_ = false;
    have_fid_ = false;
    waiting_for_next_fid_ = false;
    current_fid_ = 0;
}

bool UvcMjpegFrameAssembler::push_packet(const uint8_t* data,
                                         int actual,
                                         int64_t packet_ns,
                                         std::vector<UvcMjpegFrame>* out_frames) {
    if (out_frames) out_frames->clear();
    if (!data || actual <= 0) return false;

    const UvcPayloadView payload = parse_uvc_payload(data, actual);
    if (payload.error) return true;
    if (payload.valid) {
        stats_.uvc_headers++;
        if (!have_fid_) {
            have_fid_ = true;
            current_fid_ = payload.fid;
        } else if (waiting_for_next_fid_ && payload.fid == current_fid_) {
            if (payload.eof) {
                waiting_for_next_fid_ = false;
            }
            stats_.buffered_bytes = frame_.size();
            return true;
        } else if (payload.fid != current_fid_) {
            stats_.fid_toggles++;
            waiting_for_next_fid_ = false;
            if (!frame_.empty()) {
                size_t jpeg_begin = 0;
                size_t jpeg_end = 0;
                if (find_jpeg_bounds(frame_, &jpeg_begin, &jpeg_end)) {
                    UvcMjpegFrame frame;
                    frame.info = current_;
                    frame.info.frame_number = static_cast<uint64_t>(stats_.frames + 1);
                    frame.info.payload_bytes = static_cast<uint32_t>(jpeg_end - jpeg_begin);
                    frame.info.completed_by_fid = true;
                    frame.jpeg.assign(frame_.begin() + static_cast<std::ptrdiff_t>(jpeg_begin),
                                      frame_.begin() + static_cast<std::ptrdiff_t>(jpeg_end));
                    if (out_frames) out_frames->push_back(std::move(frame));
                    stats_.frames++;
                    stats_.completed_by_fid++;
                } else {
                    stats_.frame_drops++;
                }
            }
            frame_.clear();
            have_current_ = false;
            current_fid_ = payload.fid;
        }
    }

    const int payload_offset = payload.valid ? payload.offset : 0;
    const int payload_length = payload.valid ? payload.length : actual;
    if (payload_length <= 0) {
        stats_.buffered_bytes = frame_.size();
        return true;
    }

    if (!have_current_) {
        begin_frame_info(&current_,
                         config_.endpoint,
                         config_.mode,
                         static_cast<uint64_t>(stats_.frames + 1),
                         packet_ns,
                         payload);
        have_current_ = true;
    }
    update_frame_info(&current_, actual, payload_length, packet_ns, payload);
    frame_.insert(frame_.end(),
                  data + payload_offset,
                  data + payload_offset + payload_length);

    if (frame_.size() > config_.max_frame_bytes) {
        frame_.clear();
        have_current_ = false;
        stats_.frame_drops++;
        stats_.oversized_frame_drops++;
        stats_.buffered_bytes = 0;
        return true;
    }

    size_t jpeg_begin = 0;
    size_t jpeg_end = 0;
    const bool has_jpeg = find_jpeg_bounds(frame_, &jpeg_begin, &jpeg_end);
    const bool complete_by_eof = payload.eof;
    const bool complete_by_eoi = has_jpeg && !payload.eof;
    if (complete_by_eof || complete_by_eoi) {
        if (has_jpeg) {
            UvcMjpegFrame frame;
            frame.info = current_;
            frame.info.frame_number = static_cast<uint64_t>(stats_.frames + 1);
            frame.info.payload_bytes = static_cast<uint32_t>(jpeg_end - jpeg_begin);
            frame.info.completed_by_eof = complete_by_eof;
            frame.info.completed_by_jpeg_eoi = complete_by_eoi;
            frame.jpeg.assign(frame_.begin() + static_cast<std::ptrdiff_t>(jpeg_begin),
                              frame_.begin() + static_cast<std::ptrdiff_t>(jpeg_end));
            if (out_frames) out_frames->push_back(std::move(frame));
            stats_.frames++;
            if (complete_by_eof) stats_.completed_by_eof++;
            if (complete_by_eoi) stats_.completed_by_jpeg_eoi++;
        } else {
            stats_.frame_drops++;
        }
        frame_.clear();
        have_current_ = false;
        waiting_for_next_fid_ = complete_by_eoi && payload.valid;
    }

    stats_.buffered_bytes = frame_.size();
    return true;
}

UvcMjpegFrameAssemblerStats UvcMjpegFrameAssembler::stats() const {
    UvcMjpegFrameAssemblerStats out = stats_;
    out.buffered_bytes = frame_.size();
    return out;
}

RgbdFramePairer::RgbdFramePairer(RgbdFramePairerConfig config)
    : config_(config) {
}

void RgbdFramePairer::reset() {
    color_queue_.clear();
    depth_queue_.clear();
    pair_count_ = 0;
    dropped_color_frames_ = 0;
    dropped_depth_frames_ = 0;
    last_host_delta_ns_ = 0;
    sum_abs_host_delta_ns_ = 0;
    max_abs_host_delta_ns_ = 0;
    last_color_frame_number_ = 0;
    last_depth_frame_number_ = 0;
}

bool RgbdFramePairer::push_depth(const UvcFrameInfo& depth, RgbdFramePairInfo* out) {
    depth_queue_.push_back(depth);
    while (depth_queue_.size() > config_.max_depth_queue) {
        depth_queue_.pop_front();
        dropped_depth_frames_++;
    }
    return try_pair(out);
}

bool RgbdFramePairer::push_color(const UvcFrameInfo& color, RgbdFramePairInfo* out) {
    color_queue_.push_back(color);
    while (color_queue_.size() > config_.max_color_queue) {
        color_queue_.pop_front();
        dropped_color_frames_++;
    }
    return try_pair(out);
}

bool RgbdFramePairer::try_pair(RgbdFramePairInfo* out) {
    if (color_queue_.empty() || depth_queue_.empty()) {
        return false;
    }

    const UvcFrameInfo& color = color_queue_.front();
    const int64_t color_mid = uvc_frame_midpoint_ns(color);
    if (uvc_frame_midpoint_ns(depth_queue_.back()) < color_mid) {
        return false;
    }

    size_t best_index = 0;
    int64_t best_abs_delta = std::numeric_limits<int64_t>::max();
    int64_t best_delta = 0;
    for (size_t i = 0; i < depth_queue_.size(); ++i) {
        const int64_t delta = uvc_frame_midpoint_ns(depth_queue_[i]) - color_mid;
        const int64_t abs_delta = delta < 0 ? -delta : delta;
        if (abs_delta < best_abs_delta) {
            best_index = i;
            best_abs_delta = abs_delta;
            best_delta = delta;
        }
    }

    if (best_abs_delta > config_.max_delta_ns) {
        while (!depth_queue_.empty() &&
               uvc_frame_midpoint_ns(depth_queue_.front()) < color_mid - config_.max_delta_ns) {
            depth_queue_.pop_front();
            dropped_depth_frames_++;
        }
        color_queue_.pop_front();
        dropped_color_frames_++;
        return false;
    }

    if (out) {
        out->pair_number = static_cast<uint64_t>(pair_count_ + 1);
        out->color = color;
        out->depth = depth_queue_[best_index];
        out->host_delta_ns = best_delta;
        out->within_tolerance = true;
    }
    last_host_delta_ns_ = best_delta;
    sum_abs_host_delta_ns_ += best_abs_delta;
    max_abs_host_delta_ns_ = std::max(max_abs_host_delta_ns_, best_abs_delta);
    last_color_frame_number_ = color.frame_number;
    last_depth_frame_number_ = depth_queue_[best_index].frame_number;
    color_queue_.pop_front();
    dropped_depth_frames_ += static_cast<int64_t>(best_index);
    depth_queue_.erase(depth_queue_.begin(),
                       depth_queue_.begin() + static_cast<std::ptrdiff_t>(best_index + 1));
    pair_count_++;
    return true;
}

int64_t RgbdFramePairer::pair_count() const {
    return pair_count_;
}

int64_t RgbdFramePairer::dropped_color_frames() const {
    return dropped_color_frames_;
}

int64_t RgbdFramePairer::dropped_depth_frames() const {
    return dropped_depth_frames_;
}

size_t RgbdFramePairer::queued_color_frames() const {
    return color_queue_.size();
}

size_t RgbdFramePairer::queued_depth_frames() const {
    return depth_queue_.size();
}

RgbdPairingStats RgbdFramePairer::stats() const {
    RgbdPairingStats out;
    out.pairs = pair_count_;
    out.dropped_color_frames = dropped_color_frames_;
    out.dropped_depth_frames = dropped_depth_frames_;
    out.queued_color_frames = color_queue_.size();
    out.queued_depth_frames = depth_queue_.size();
    out.last_host_delta_ns = last_host_delta_ns_;
    out.mean_abs_host_delta_ns = pair_count_ > 0 ? sum_abs_host_delta_ns_ / pair_count_ : 0;
    out.max_abs_host_delta_ns = max_abs_host_delta_ns_;
    out.last_color_frame_number = last_color_frame_number_;
    out.last_depth_frame_number = last_depth_frame_number_;
    return out;
}

const char* p100r3_session_state_name(P100R3SessionState state) {
    switch (state) {
        case P100R3SessionState::kIdle: return "idle";
        case P100R3SessionState::kOpening: return "opening";
        case P100R3SessionState::kStreaming: return "streaming";
        case P100R3SessionState::kStopping: return "stopping";
        case P100R3SessionState::kStopped: return "stopped";
        case P100R3SessionState::kFailed: return "failed";
    }
    return "unknown";
}

const char* p100r3_session_stop_reason_name(P100R3SessionStopReason reason) {
    switch (reason) {
        case P100R3SessionStopReason::kNone: return "none";
        case P100R3SessionStopReason::kUserStop: return "user_stop";
        case P100R3SessionStopReason::kDurationReached: return "duration_reached";
        case P100R3SessionStopReason::kCallbackStop: return "callback_stop";
        case P100R3SessionStopReason::kBulkError: return "bulk_error";
        case P100R3SessionStopReason::kSetupFailed: return "setup_failed";
    }
    return "unknown";
}

std::string hex_bytes_compact(const std::vector<uint8_t>& data, size_t max_bytes) {
    static constexpr char kHex[] = "0123456789abcdef";
    const size_t n = max_bytes == 0 ? data.size() : std::min(max_bytes, data.size());
    std::string out;
    out.reserve(n * 2);
    for (size_t i = 0; i < n; ++i) {
        out.push_back(kHex[(data[i] >> 4) & 0x0f]);
        out.push_back(kHex[data[i] & 0x0f]);
    }
    return out;
}

uint8_t p100r3_depth_mode_code(const P100R3VideoMode& mode) {
    if (mode.width >= 1280) return 0x03;
    if (mode.width >= 640) return 0x08;
    return 0x0c;
}

uint8_t p100r3_depth_fraction_bits(P100R3DepthPixelFormat format) {
    switch (format) {
        case P100R3DepthPixelFormat::k12I4D:
            return 4;
        case P100R3DepthPixelFormat::k13I3D:
            return 3;
        case P100R3DepthPixelFormat::k14I2D:
            return 2;
    }
    return 4;
}

uint16_t p100r3_depth_active_height(const P100R3VideoMode& transport_mode) {
    if (transport_mode.height == 801 || transport_mode.height == 401 ||
        transport_mode.height == 201) {
        return static_cast<uint16_t>(transport_mode.height - 1);
    }
    return transport_mode.height;
}

P100R3VideoMode p100r3_depth_active_mode(const P100R3VideoMode& transport_mode) {
    P100R3VideoMode mode = transport_mode;
    mode.height = p100r3_depth_active_height(transport_mode);
    return mode;
}

float p100r3_depth_raw_to_mm(uint16_t raw, P100R3DepthPixelFormat format) {
    if (raw == 0) return 0.0f;
    const uint8_t frac_bits = p100r3_depth_fraction_bits(format);
    return static_cast<float>(raw) / static_cast<float>(1u << frac_bits);
}

bool process_p100r3_depth_frame(const uint8_t* transport_frame,
                                size_t transport_size,
                                const P100R3VideoMode& transport_mode,
                                const P100R3DepthProcessingConfig& config,
                                std::vector<uint16_t>* processed_active_raw16,
                                std::vector<uint8_t>* confidence,
                                P100R3DepthProcessingStats* stats) {
    if (!transport_frame || !processed_active_raw16 || transport_mode.width == 0 ||
        transport_mode.height == 0) {
        return false;
    }
    const uint16_t active_width = transport_mode.width;
    const uint16_t active_height = p100r3_depth_active_height(transport_mode);
    const size_t transport_pixels =
        static_cast<size_t>(transport_mode.width) * static_cast<size_t>(transport_mode.height);
    const size_t active_pixels =
        static_cast<size_t>(active_width) * static_cast<size_t>(active_height);
    if (transport_size < transport_pixels * sizeof(uint16_t)) return false;

    P100R3DepthProcessingStats local_stats;
    local_stats.active_width = active_width;
    local_stats.active_height = active_height;
    local_stats.active_pixels = static_cast<uint32_t>(std::min<size_t>(
        active_pixels,
        static_cast<size_t>(std::numeric_limits<uint32_t>::max())));
    local_stats.max_fill_distance_px = std::max(0, config.max_fill_distance_px);

    const uint8_t frac_bits = p100r3_depth_fraction_bits(config.format);
    const float scale = static_cast<float>(1u << frac_bits);
    const auto clamp_raw = [](float raw) -> uint16_t {
        const float clamped = std::max(0.0f, std::min(65535.0f, raw));
        return static_cast<uint16_t>(std::lround(clamped));
    };
    const uint16_t min_raw = clamp_raw(config.min_depth_mm * scale);
    const uint16_t max_raw = clamp_raw(config.max_depth_mm * scale);

    processed_active_raw16->assign(active_pixels, 0);
    if (confidence) confidence->assign(active_pixels, 0);
    std::vector<uint8_t> raw_valid(active_pixels, 0);

    const auto read_raw = [&](size_t transport_index) -> uint16_t {
        const size_t byte_index = transport_index * 2;
        return static_cast<uint16_t>(transport_frame[byte_index]) |
               static_cast<uint16_t>(static_cast<uint16_t>(transport_frame[byte_index + 1]) << 8);
    };

    for (uint16_t y = 0; y < active_height; ++y) {
        for (uint16_t x = 0; x < active_width; ++x) {
            const size_t active_index = static_cast<size_t>(y) * active_width + x;
            const size_t transport_index = static_cast<size_t>(y) * transport_mode.width + x;
            const uint16_t raw = read_raw(transport_index);
            if (raw >= min_raw && raw <= max_raw) {
                (*processed_active_raw16)[active_index] = raw;
                raw_valid[active_index] = 1;
                if (confidence) (*confidence)[active_index] = config.raw_confidence;
                local_stats.raw_valid_pixels++;
            } else if (raw != 0) {
                local_stats.rejected_out_of_range_pixels++;
            }
        }
    }

    const int width = active_width;
    const int height = active_height;
    const int support_radius = std::max(0, config.seed_support_radius_px);
    const int min_seed_support = std::max(1, config.min_seed_support);
    const int max_fill_distance = std::max(0, config.max_fill_distance_px);
    const int consistency_radius = std::max(0, config.fill_consistency_radius_px);
    const uint16_t max_fill_delta_raw = clamp_raw(
        std::max(0.0f, config.max_fill_depth_delta_mm) * scale);
    std::vector<int16_t> distance(active_pixels, -1);
    std::vector<uint8_t> edge_blocked(active_pixels, 0);
    std::queue<int> queue;

    auto has_raw_value = [&](int x, int y) -> bool {
        return raw_valid[static_cast<size_t>(y) * width + x] != 0;
    };

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int index = y * width + x;
            if (raw_valid[static_cast<size_t>(index)] == 0) continue;
            int support = 0;
            for (int yy = std::max(0, y - support_radius);
                 yy <= std::min(height - 1, y + support_radius);
                 ++yy) {
                for (int xx = std::max(0, x - support_radius);
                     xx <= std::min(width - 1, x + support_radius);
                     ++xx) {
                    if (has_raw_value(xx, yy)) support++;
                }
            }
            if (support >= min_seed_support) {
                distance[static_cast<size_t>(index)] = 0;
                queue.push(index);
                local_stats.seed_pixels++;
            }
        }
    }

    const int fill_conf_delta =
        std::max(0, static_cast<int>(config.fill_confidence_near) -
                        static_cast<int>(config.fill_confidence_min));
    const uint32_t target_valid_pixels = config.target_valid_ratio > 0.0f
        ? static_cast<uint32_t>(std::min<float>(
              static_cast<float>(std::numeric_limits<uint32_t>::max()),
              std::ceil(static_cast<float>(active_pixels) *
                        std::min(1.0f, config.target_valid_ratio))))
        : 0;
    uint32_t current_valid_pixels = local_stats.raw_valid_pixels;
    auto fill_confidence = [&](int fill_distance) -> uint8_t {
        if (max_fill_distance <= 0) return config.fill_confidence_min;
        const int decayed = static_cast<int>(config.fill_confidence_near) -
                            (fill_conf_delta * fill_distance) / max_fill_distance;
        return static_cast<uint8_t>(std::max(static_cast<int>(config.fill_confidence_min),
                                             std::min(255, decayed)));
    };
    auto depth_distance_raw = [](uint16_t a, uint16_t b) -> uint16_t {
        return static_cast<uint16_t>(std::abs(static_cast<int>(a) - static_cast<int>(b)));
    };
    auto choose_edge_aware_fill = [&](int next, uint16_t proposed, uint16_t* out) -> bool {
        if (!config.edge_aware_fill || consistency_radius <= 0 || max_fill_delta_raw == 0) {
            *out = proposed;
            return true;
        }
        const int x = next % width;
        const int y = next / width;
        int observed = 0;
        int consistent = 0;
        uint32_t sum = 0;
        for (int yy = std::max(0, y - consistency_radius);
             yy <= std::min(height - 1, y + consistency_radius);
             ++yy) {
            for (int xx = std::max(0, x - consistency_radius);
                 xx <= std::min(width - 1, x + consistency_radius);
                 ++xx) {
                const int neighbor = yy * width + xx;
                if (neighbor == next) continue;
                const uint16_t value = (*processed_active_raw16)[static_cast<size_t>(neighbor)];
                if (value == 0) continue;
                observed++;
                if (depth_distance_raw(value, proposed) > max_fill_delta_raw) {
                    return false;
                }
                consistent++;
                sum += value;
            }
        }
        if (observed == 0 || consistent == 0) {
            *out = proposed;
        } else {
            *out = static_cast<uint16_t>(std::lround(
                static_cast<double>(sum) / static_cast<double>(consistent)));
        }
        return true;
    };

    if (max_fill_distance > 0) {
        const uint16_t kNoDistance = std::numeric_limits<uint16_t>::max();
        std::vector<uint16_t> left_value(active_pixels, 0);
        std::vector<uint16_t> right_value(active_pixels, 0);
        std::vector<uint16_t> up_value(active_pixels, 0);
        std::vector<uint16_t> down_value(active_pixels, 0);
        std::vector<uint16_t> left_distance(active_pixels, kNoDistance);
        std::vector<uint16_t> right_distance(active_pixels, kNoDistance);
        std::vector<uint16_t> up_distance(active_pixels, kNoDistance);
        std::vector<uint16_t> down_distance(active_pixels, kNoDistance);

        for (int y = 0; y < height; ++y) {
            uint16_t last = 0;
            int dist = max_fill_distance + 1;
            for (int x = 0; x < width; ++x) {
                const int index = y * width + x;
                if (raw_valid[static_cast<size_t>(index)] != 0) {
                    last = (*processed_active_raw16)[static_cast<size_t>(index)];
                    dist = 0;
                } else if (last != 0) {
                    dist++;
                    if (dist <= max_fill_distance) {
                        left_value[static_cast<size_t>(index)] = last;
                        left_distance[static_cast<size_t>(index)] = static_cast<uint16_t>(dist);
                    }
                }
            }

            last = 0;
            dist = max_fill_distance + 1;
            for (int x = width - 1; x >= 0; --x) {
                const int index = y * width + x;
                if (raw_valid[static_cast<size_t>(index)] != 0) {
                    last = (*processed_active_raw16)[static_cast<size_t>(index)];
                    dist = 0;
                } else if (last != 0) {
                    dist++;
                    if (dist <= max_fill_distance) {
                        right_value[static_cast<size_t>(index)] = last;
                        right_distance[static_cast<size_t>(index)] = static_cast<uint16_t>(dist);
                    }
                }
            }
        }

        for (int x = 0; x < width; ++x) {
            uint16_t last = 0;
            int dist = max_fill_distance + 1;
            for (int y = 0; y < height; ++y) {
                const int index = y * width + x;
                if (raw_valid[static_cast<size_t>(index)] != 0) {
                    last = (*processed_active_raw16)[static_cast<size_t>(index)];
                    dist = 0;
                } else if (last != 0) {
                    dist++;
                    if (dist <= max_fill_distance) {
                        up_value[static_cast<size_t>(index)] = last;
                        up_distance[static_cast<size_t>(index)] = static_cast<uint16_t>(dist);
                    }
                }
            }

            last = 0;
            dist = max_fill_distance + 1;
            for (int y = height - 1; y >= 0; --y) {
                const int index = y * width + x;
                if (raw_valid[static_cast<size_t>(index)] != 0) {
                    last = (*processed_active_raw16)[static_cast<size_t>(index)];
                    dist = 0;
                } else if (last != 0) {
                    dist++;
                    if (dist <= max_fill_distance) {
                        down_value[static_cast<size_t>(index)] = last;
                        down_distance[static_cast<size_t>(index)] = static_cast<uint16_t>(dist);
                    }
                }
            }
        }

        auto add_observation = [&](uint16_t value,
                                   uint16_t dist,
                                   int* count,
                                   uint16_t* min_value,
                                   uint16_t* max_value,
                                   int* min_distance,
                                   double* weighted_sum,
                                   double* weight_sum) {
            if (value == 0 || dist == kNoDistance || dist == 0) return;
            const double weight = 1.0 / static_cast<double>(dist);
            *weighted_sum += static_cast<double>(value) * weight;
            *weight_sum += weight;
            *min_value = *count == 0 ? value : std::min(*min_value, value);
            *max_value = *count == 0 ? value : std::max(*max_value, value);
            *min_distance = *count == 0 ? static_cast<int>(dist)
                                        : std::min(*min_distance, static_cast<int>(dist));
            (*count)++;
        };

        for (int index = 0; index < static_cast<int>(active_pixels); ++index) {
            if (target_valid_pixels > 0 && current_valid_pixels >= target_valid_pixels) break;
            const size_t i = static_cast<size_t>(index);
            if ((*processed_active_raw16)[i] != 0) continue;

            int count = 0;
            uint16_t min_value = 0;
            uint16_t max_value = 0;
            int min_distance = 0;
            double weighted_sum = 0.0;
            double weight_sum = 0.0;
            add_observation(left_value[i],
                            left_distance[i],
                            &count,
                            &min_value,
                            &max_value,
                            &min_distance,
                            &weighted_sum,
                            &weight_sum);
            add_observation(right_value[i],
                            right_distance[i],
                            &count,
                            &min_value,
                            &max_value,
                            &min_distance,
                            &weighted_sum,
                            &weight_sum);
            add_observation(up_value[i],
                            up_distance[i],
                            &count,
                            &min_value,
                            &max_value,
                            &min_distance,
                            &weighted_sum,
                            &weight_sum);
            add_observation(down_value[i],
                            down_distance[i],
                            &count,
                            &min_value,
                            &max_value,
                            &min_distance,
                            &weighted_sum,
                            &weight_sum);
            if (count < 2 || weight_sum <= 0.0) continue;
            if (max_fill_delta_raw > 0 && depth_distance_raw(min_value, max_value) > max_fill_delta_raw) {
                edge_blocked[i] = 1;
                local_stats.edge_blocked_pixels++;
                continue;
            }

            const uint16_t fill_value = clamp_raw(static_cast<float>(weighted_sum / weight_sum));
            (*processed_active_raw16)[i] = fill_value;
            distance[i] = static_cast<int16_t>(std::min(min_distance, max_fill_distance));
            if (confidence) (*confidence)[i] = fill_confidence(min_distance);
            local_stats.filled_pixels++;
            current_valid_pixels++;
            queue.push(index);
        }
    }

    while (!queue.empty()) {
        if (target_valid_pixels > 0 && current_valid_pixels >= target_valid_pixels) break;
        const int index = queue.front();
        queue.pop();
        const int current_distance = distance[static_cast<size_t>(index)];
        if (current_distance < 0 || current_distance >= max_fill_distance) continue;
        const int x = index % width;
        const int y = index / width;
        const int next_distance = current_distance + 1;
        const int neighbors[4] = {
            x > 0 ? index - 1 : -1,
            x + 1 < width ? index + 1 : -1,
            y > 0 ? index - width : -1,
            y + 1 < height ? index + width : -1,
        };
        for (const int next : neighbors) {
            if (target_valid_pixels > 0 && current_valid_pixels >= target_valid_pixels) break;
            if (next < 0) continue;
            const size_t next_index = static_cast<size_t>(next);
            if ((*processed_active_raw16)[next_index] != 0) continue;
            uint16_t fill_value = 0;
            if (!choose_edge_aware_fill(
                    next,
                    (*processed_active_raw16)[static_cast<size_t>(index)],
                    &fill_value)) {
                if (edge_blocked[next_index] == 0) {
                    edge_blocked[next_index] = 1;
                    local_stats.edge_blocked_pixels++;
                }
                continue;
            }
            (*processed_active_raw16)[next_index] = fill_value;
            distance[next_index] = static_cast<int16_t>(next_distance);
            if (confidence) (*confidence)[next_index] = fill_confidence(next_distance);
            local_stats.filled_pixels++;
            current_valid_pixels++;
            queue.push(next);
        }
    }

    for (uint16_t raw : *processed_active_raw16) {
        if (raw != 0) local_stats.processed_valid_pixels++;
    }
    if (stats) *stats = local_stats;
    return true;
}

bool process_p100r3_light_ir_frame(const uint8_t* transport_frame,
                                   size_t transport_size,
                                   const P100R3VideoMode& transport_mode,
                                   std::vector<uint16_t>* active_ir10) {
    if (!transport_frame || !active_ir10 || transport_mode.width == 0 ||
        transport_mode.height == 0) {
        return false;
    }
    const uint16_t active_width = transport_mode.width;
    const uint16_t active_height = p100r3_depth_active_height(transport_mode);
    const size_t active_pixels = static_cast<size_t>(active_width) * active_height;
    if (transport_size < active_pixels * 2) return false;

    active_ir10->assign(active_pixels, 0);
    for (size_t i = 0; i < active_pixels; ++i) {
        const uint16_t raw = static_cast<uint16_t>(transport_frame[i * 2]) |
                             static_cast<uint16_t>(
                                 static_cast<uint16_t>(transport_frame[i * 2 + 1]) << 8);
        (*active_ir10)[i] = static_cast<uint16_t>(raw >> 6);
    }
    return true;
}

std::vector<uint8_t> p100r3_ir_speckle_confidence(const std::vector<uint16_t>& active_ir_raw16,
                                                  uint16_t width,
                                                  uint16_t height,
                                                  const P100R3IrConfidenceConfig& config) {
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (pixels == 0 || active_ir_raw16.size() != pixels) return {};
    const int W = width, H = height;

    // 高字节 = IR 灰度散斑强度（低字节 phase code 不用）。
    std::vector<int64_t> hi(pixels);
    for (size_t i = 0; i < pixels; ++i) hi[i] = static_cast<int64_t>(active_ir_raw16[i] >> 8);

    // 积分图（+1 padding）求 box 内 sum / sumsq → 局部 std（散斑可见度）。O(1)/像素。
    const int IW = W + 1;
    std::vector<int64_t> isum(static_cast<size_t>(IW) * (H + 1), 0);
    std::vector<int64_t> isq(static_cast<size_t>(IW) * (H + 1), 0);
    for (int y = 0; y < H; ++y) {
        int64_t rs = 0, rq = 0;
        for (int x = 0; x < W; ++x) {
            const int64_t v = hi[static_cast<size_t>(y) * W + x];
            rs += v; rq += v * v;
            const size_t up = static_cast<size_t>(y) * IW + (x + 1);
            isum[static_cast<size_t>(y + 1) * IW + (x + 1)] = isum[up] + rs;
            isq[static_cast<size_t>(y + 1) * IW + (x + 1)] = isq[up] + rq;
        }
    }
    auto box = [&](const std::vector<int64_t>& I, int x0, int y0, int x1, int y1) -> int64_t {
        return I[static_cast<size_t>(y1) * IW + x1] - I[static_cast<size_t>(y0) * IW + x1] -
               I[static_cast<size_t>(y1) * IW + x0] + I[static_cast<size_t>(y0) * IW + x0];
    };

    const int r = std::max(1, config.window / 2);
    std::vector<float> lstd(pixels, 0.0f);
    for (int y = 0; y < H; ++y) {
        const int y0 = std::max(0, y - r), y1 = std::min(H, y + r + 1);
        for (int x = 0; x < W; ++x) {
            const int x0 = std::max(0, x - r), x1 = std::min(W, x + r + 1);
            const int64_t n = static_cast<int64_t>(x1 - x0) * (y1 - y0);
            if (n <= 0) continue;
            const double mean = static_cast<double>(box(isum, x0, y0, x1, y1)) / n;
            const double msq = static_cast<double>(box(isq, x0, y0, x1, y1)) / n;
            const double var = msq - mean * mean;
            lstd[static_cast<size_t>(y) * W + x] = static_cast<float>(std::sqrt(var > 0 ? var : 0.0));
        }
    }

    // 帧内鲁棒尺度 = IR>0 像素 local-std 的中值（曝光自适应，避绝对阈值泛化陷阱）。
    std::vector<float> nz;
    nz.reserve(pixels);
    for (size_t i = 0; i < pixels; ++i) if (hi[i] > 0) nz.push_back(lstd[i]);
    float scale = 1.0f;
    if (!nz.empty()) {
        std::nth_element(nz.begin(), nz.begin() + nz.size() / 2, nz.end());
        scale = std::max(1e-6f, nz[nz.size() / 2]);
    }

    const float lo = config.contrast_lo_rel;
    const float hi_rel = std::max(lo + 1e-3f, config.contrast_hi_rel);
    const int mn = config.min_conf;
    std::vector<uint8_t> conf(pixels, 0);
    for (size_t i = 0; i < pixels; ++i) {
        if (hi[i] <= 0) continue;  // 无 IR 回波 = 无结构光信号 → 置信 0
        const float norm = lstd[i] / scale;
        float t = (norm - lo) / (hi_rel - lo);
        t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
        conf[i] = static_cast<uint8_t>(mn + std::lround(t * (255 - mn)));
    }
    return conf;
}

bool p100r3_flying_spatial_evidence(const std::vector<uint16_t>& fused_raw16,
                                    uint16_t width,
                                    uint16_t height,
                                    size_t idx,
                                    const P100R3FlyingPixelConfig& config,
                                    float angle_scale,
                                    P100R3FlyingSpatialEvidence* out) {
    if (!out) return false;
    *out = P100R3FlyingSpatialEvidence{};
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (fused_raw16.size() != pixels || idx >= pixels || width == 0 || height == 0) return false;
    const uint16_t center_raw = fused_raw16[idx];
    if (center_raw == 0) return false;

    const float scale = static_cast<float>(1u << p100r3_depth_fraction_bits(config.format));
    const float z = static_cast<float>(center_raw) / scale;  // 中心深度 mm
    const int w = static_cast<int>(width);
    const int h = static_cast<int>(height);
    const int x = static_cast<int>(idx % width);
    const int y = static_cast<int>(idx / width);
    constexpr float kDeg2Rad = 0.017453292519943295f;
    const float tan_g = std::tan(std::max(1.0f, std::min(89.5f, config.grazing_angle_max_deg)) * kDeg2Rad);
    const float as = std::max(1.0f, angle_scale);
    const float fx = std::max(1.0f, config.fx_px);
    const float fy = std::max(1.0f, config.fy_px);
    // 真实斜面阶跃上界 step_max(Z)=tan(grazing)·Z/f·Δpx(=1)；横向用 fx、纵向用 fy。
    const float step_x = tan_g * z / fx * as;
    const float step_y = tan_g * z / fy * as;

    // 双侧角度超界夹心（被更近的崖 near 和更远的崖 far 同时夹住 = 悬浮）。
    // 沿 4 方向外探到半径 R：薄飞点带(1-3px)中间像素的直邻也是飞点，须探到更远才见真 fg/bg。
    // 阶跃上界随距离 Δpx 线性放大（真实斜面在 d px 处阶跃 ≤ step·d），故远处邻居用 step*d 比较。
    bool near_over = false, far_over = false;
    const int radius = std::max(1, config.sandwich_radius_px);
    const int dir[4][3] = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 1}, {0, 1, 1}};  // dx,dy,vertical
    for (const auto& dd : dir) {
        const float step1 = dd[2] ? step_y : step_x;
        if (step1 <= 0.0f) continue;
        for (int r = 1; r <= radius; ++r) {
            const int nx = x + dd[0] * r, ny = y + dd[1] * r;
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) break;
            const uint16_t q = fused_raw16[static_cast<size_t>(ny) * width + nx];
            if (q == 0) continue;  // 无效跳过，继续外探
            const float dz = static_cast<float>(q) / scale - z;  // >0 更远(背景), <0 更近(前景)
            if (std::abs(dz) > step1 * static_cast<float>(r)) {
                if (dz < 0.0f) near_over = true; else far_over = true;
                break;  // 该方向已找到超界崖，停止外探
            }
        }
    }
    out->sandwich = near_over && far_over;

    // 8 邻域共面支撑：斜面/曲面恒有近共面邻居 → 否决删除。
    const float band = std::max(0.0f, config.support_band_mm) + std::max(0.0f, config.support_band_pct) * z;
    int support = 0;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            if (dx == 0 && dy == 0) continue;
            const int nx = x + dx, ny = y + dy;
            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
            const uint16_t q = fused_raw16[static_cast<size_t>(ny) * width + nx];
            if (q == 0) continue;
            if (std::abs(static_cast<float>(q) / scale - z) < band) support++;
        }
    }
    out->support = support;
    out->no_support = support < std::max(0, config.min_coplanar_support);
    return true;
}

P100R3TemporalFilter::P100R3TemporalFilter(P100R3TemporalFilterConfig config)
    : config_(config) {
    // count_/cursor_ 为 uint8_t，窗口深度上限 255。
    window_ = std::max(1, std::min(255, config_.window));
}

void P100R3TemporalFilter::reset() {
    std::fill(count_.begin(), count_.end(), 0);
    std::fill(cursor_.begin(), cursor_.end(), 0);
    std::fill(window_span_raw_.begin(), window_span_raw_.end(), 0);
    std::fill(stable_run_.begin(), stable_run_.end(), 0);
    std::fill(frames_seen_.begin(), frames_seen_.end(), 0);
    noise_est_raw_ = 0.0f;  // 重新自适应（首帧用绝对底）
    prior_conf_.clear();    // 新 burst/pose：旧 IR 先验作废
    // samples_ 不必清零：count_=0 时不会被读到。
}

bool P100R3TemporalFilter::push(const std::vector<uint16_t>& active_raw16,
                                uint16_t width,
                                uint16_t height,
                                std::vector<uint16_t>* fused_raw16,
                                std::vector<uint8_t>* confidence,
                                P100R3TemporalFilterStats* stats,
                                std::vector<uint8_t>* flying_mask) {
    if (!fused_raw16 || width == 0 || height == 0) return false;
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (active_raw16.size() != pixels) return false;

    if (width != width_ || height != height_ ||
        samples_.size() != pixels * static_cast<size_t>(window_)) {
        width_ = width;
        height_ = height;
        samples_.assign(pixels * static_cast<size_t>(window_), 0);
        count_.assign(pixels, 0);
        cursor_.assign(pixels, 0);
        window_span_raw_.assign(pixels, 0);
        stable_run_.assign(pixels, 0);
        frames_seen_.assign(pixels, 0);
    }

    fused_raw16->assign(pixels, 0);
    if (confidence) confidence->assign(pixels, 0);

    const float scale = static_cast<float>(1u << p100r3_depth_fraction_bits(config_.format));
    const float abs_thresh_raw = std::max(0.0f, config_.motion_reset_mm) * scale;
    const float percent = std::max(0.0f, config_.motion_reset_percent);
    const int min_full = std::max(1, config_.min_samples_full_conf);
    // 自适应噪声门限：用上一帧估的噪声底（raw）× k。首帧 noise_est=0 → 只用绝对底。
    const float noise_thresh_raw = std::max(0.0f, config_.motion_reset_noise_k) * noise_est_raw_;
    diff_scratch_.clear();

    P100R3TemporalFilterStats local{};
    uint64_t fill_sum = 0;

    // 读窗口里前 cnt 个样本之和（环形缓冲整段写满前按 cnt 截断）。
    const auto window_sum = [&](size_t base, int cnt) -> uint32_t {
        uint32_t sum = 0;
        for (int s = 0; s < cnt; ++s) sum += samples_[base + static_cast<size_t>(s)];
        return sum;
    };

    for (size_t i = 0; i < pixels; ++i) {
        const uint16_t cur = active_raw16[i];
        const size_t base = i * static_cast<size_t>(window_);
        int cnt = count_[i];

        if (cur == 0) {
            // 当前无效：保留已累积估计（短暂掉点不清窗），输出旧估计。
            if (cnt > 0) {
                const uint16_t est = static_cast<uint16_t>(
                    window_sum(base, cnt) / static_cast<uint32_t>(cnt));
                (*fused_raw16)[i] = est;
                local.fused_pixels++;
                fill_sum += static_cast<uint64_t>(cnt);
                if (confidence) {
                    (*confidence)[i] = cnt >= min_full
                        ? config_.full_confidence
                        : static_cast<uint8_t>(config_.single_sample_confidence +
                              (config_.full_confidence - config_.single_sample_confidence) *
                                  (cnt - 1) / std::max(1, min_full - 1));
                }
            }
            continue;
        }

        // 运动判定：与当前窗口估计偏离超阈值 → 清窗重启（运动/场景变不拖影）。
        bool reset_this_frame = false;
        if (cnt > 0) {
            const uint16_t est = static_cast<uint16_t>(
                window_sum(base, cnt) / static_cast<uint32_t>(cnt));
            const int diff = std::abs(static_cast<int>(cur) - static_cast<int>(est));
            diff_scratch_.push_back(diff);  // 收集 |cur-est| 供本帧末估噪声底（median 抗飞点尾）
            const float thresh = std::max({abs_thresh_raw, noise_thresh_raw,
                                           percent * static_cast<float>(est)});
            if (static_cast<float>(diff) > thresh) {
                cnt = 0;
                cursor_[i] = 0;
                local.motion_resets++;
                reset_this_frame = true;
            }
        }

        // 入窗（环形写）：未写满时顺序追加，写满后覆盖最旧。
        const uint8_t cur_slot = cnt < window_ ? static_cast<uint8_t>(cnt) : cursor_[i];
        samples_[base + cur_slot] = cur;
        if (cnt < window_) {
            cnt++;
        } else {
            cursor_[i] = static_cast<uint8_t>((cursor_[i] + 1) % window_);
        }
        count_[i] = static_cast<uint8_t>(cnt);

        // 飞点时域门所需统计：稳定连跑数（reset→0，否则++）、总观测数、窗口 span。
        frames_seen_[i] = static_cast<uint8_t>(std::min(255, static_cast<int>(frames_seen_[i]) + 1));
        stable_run_[i] = reset_this_frame
            ? 0
            : static_cast<uint8_t>(std::min(255, static_cast<int>(stable_run_[i]) + 1));
        uint16_t wmin = std::numeric_limits<uint16_t>::max(), wmax = 0;
        for (int s = 0; s < cnt; ++s) {
            const uint16_t v = samples_[base + static_cast<size_t>(s)];
            if (v < wmin) wmin = v;
            if (v > wmax) wmax = v;
        }
        window_span_raw_[i] = cnt > 0 ? static_cast<uint16_t>(wmax - wmin) : 0;

        const uint16_t est = static_cast<uint16_t>(
            window_sum(base, cnt) / static_cast<uint32_t>(cnt));
        (*fused_raw16)[i] = est;
        local.fused_pixels++;
        fill_sum += static_cast<uint64_t>(cnt);
        if (cnt == 1) local.single_sample_pixels++;
        if (confidence) {
            (*confidence)[i] = cnt >= min_full
                ? config_.full_confidence
                : static_cast<uint8_t>(config_.single_sample_confidence +
                      (config_.full_confidence - config_.single_sample_confidence) *
                          (cnt - 1) / std::max(1, min_full - 1));
        }
    }

    local.mean_window_fill = local.fused_pixels > 0
        ? static_cast<float>(fill_sum) / static_cast<float>(local.fused_pixels)
        : 0.0f;

    // 更新自适应噪声底（供下一帧门限）：median(|cur-est|) 抗飞点/运动尾，EMA 平滑。
    if (!diff_scratch_.empty()) {
        const size_t mid = diff_scratch_.size() / 2;
        std::nth_element(diff_scratch_.begin(), diff_scratch_.begin() + mid, diff_scratch_.end());
        const float med = static_cast<float>(diff_scratch_[mid]);
        noise_est_raw_ = noise_est_raw_ <= 0.0f ? med : 0.6f * noise_est_raw_ + 0.4f * med;
    }
    local.noise_floor_mm = noise_est_raw_ / scale;

    // ── 真置信：用窗口 span 派生稳定性，降低抖动像素的置信（数据保稠密，只改 confidence）──
    // 设备 confidence 饱和无效，靠 fill 度给的 conf 会让"攒满窗口但一直跳"的噪声像素拿满分。
    // span ≤ stable_band→不降；≥ unstable_band→降到 min_valid；线性过渡。est 用本像素深度做带宽缩放。
    if (confidence && config_.confidence_from_stability) {
        for (size_t i = 0; i < pixels; ++i) {
            if ((*fused_raw16)[i] == 0 || (*confidence)[i] == 0) continue;
            if (count_[i] < 2) continue;  // 样本不足，span 无意义，保留 fill 置信
            const float est_mm = static_cast<float>((*fused_raw16)[i]) / scale;
            const float lo = config_.conf_stable_span_mm + config_.conf_span_percent * est_mm;
            const float hi = std::max(lo + 1.0f,
                config_.conf_unstable_span_mm + config_.conf_span_percent * est_mm);
            const float span_mm = static_cast<float>(window_span_raw_[i]) / scale;
            float stab;
            if (span_mm <= lo) stab = 1.0f;
            else if (span_mm >= hi) stab = 0.0f;
            else stab = (hi - span_mm) / (hi - lo);
            const int c = static_cast<int>(std::lround(static_cast<float>((*confidence)[i]) * stab));
            (*confidence)[i] = static_cast<uint8_t>(std::max<int>(config_.conf_min_valid, c));
        }
    }

    // ── 融合 IR 散斑单帧先验置信（set_prior_confidence 喂入）：conf = min(时域, IR) ──
    // 时域置信需积累窗口（首帧/暖机/运动场景给不出），IR 散斑对比度给【单帧零延迟】可信度，互补。
    // min 语义：任一信号判不可信即降权；尤其救暖机像素（count<2 时上面跳过了时域降权，IR 仍能压）。
    if (confidence && config_.fuse_prior_confidence &&
        prior_conf_.size() == pixels) {
        for (size_t i = 0; i < pixels; ++i) {
            if ((*fused_raw16)[i] == 0 || (*confidence)[i] == 0) continue;
            (*confidence)[i] = std::min((*confidence)[i], prior_conf_[i]);
        }
    }

    // ── 空间降噪（fuse 后、飞点前）：median3 去脉冲 + bilateral5 保边，原地改 fused_raw16 值 ──
    // 飞点剔除随后在已降噪的 fused 上算几何证据（脉冲已被 median 削平，sandwich 判据信噪比更高）。
    if (config_.spatial_denoise_enable) {
        apply_spatial_denoise(fused_raw16);
    }

    // ── 飞点剔除（fuse 之后做）：三证合一 = 时域不稳 ∧ 双侧夹心 ∧ 无共面支撑 ──
    // 在 fused depth 上算（已 ~3.7× 降噪，梯度算在 ~10mm 噪声底而非单帧 38mm，避免假断崖）。
    // 时域信号取自窗口：stable_run（连续未 reset 帧数）+ window_span（窗内抖动）+ frames_seen（区分暖机）。
    // 命中：flying_mask=1 且 confidence 该位清 0；fused_raw16 原值不动（保"raw 是测量真值"）。
    if (flying_mask && config_.flying_enable) {
        flying_mask->assign(pixels, 0);
        const float tstd_floor_raw = std::max(0.0f, config_.flying_tstd_floor_mm) * scale;
        const float tstd_pct = std::max(0.0f, config_.flying_tstd_percent);
        const int min_stable = std::max(1, config_.flying_min_stable_samples);
        for (size_t i = 0; i < pixels; ++i) {
            const uint16_t est = (*fused_raw16)[i];
            if (est == 0) continue;
            const bool warmup = frames_seen_[i] < min_stable;  // 总观测不足 → 信号不可信
            const float span_thresh = std::max(tstd_floor_raw, tstd_pct * static_cast<float>(est));
            const bool span_unstable = static_cast<float>(window_span_raw_[i]) > span_thresh;
            const bool run_unstable = stable_run_[i] < min_stable;  // 未连续稳定够久（含慢性 reset）
            const bool temporal_unstable = span_unstable || run_unstable;

            P100R3FlyingSpatialEvidence ev;
            const float angle_scale = warmup ? config_.flying_single_frame_angle_scale : 1.0f;
            if (!p100r3_flying_spatial_evidence(*fused_raw16, width, height, i, config_.flying,
                                                angle_scale, &ev)) {
                continue;
            }
            if (ev.sandwich) local.flying_spatial_hits++;
            if (temporal_unstable) local.flying_temporal_gated++;

            if (warmup) {
                // 暖机：信号不足，绝不硬删；仅在强空间证据下降权交后续帧补判（诚实降级，不留假 fallback）。
                if (ev.sandwich && ev.no_support && confidence) {
                    (*confidence)[i] = std::min((*confidence)[i], config_.flying_weak_confidence);
                }
                continue;
            }
            // 三证合一才删：时域不稳 ∧ 双侧夹心 ∧ 无共面支撑。
            if (temporal_unstable && ev.sandwich) {
                if (!ev.no_support) {
                    local.flying_blocked_by_support++;  // 有共面支撑 = 真表面，救回
                    continue;
                }
                (*flying_mask)[i] = 1;
                if (confidence) (*confidence)[i] = 0;
                local.flying_pixels++;
            }
        }
    }

    if (stats) *stats = local;
    return true;
}

// median3（仅有效邻居中值，0 保持 0，不新填）→ bilateral5（σ_s/σ_r，range 核用 LUT 去 exp）。
// 真帧实测 noise_p50 27→9mm、edge_keep 0.89、density 不掉。算力 ~0.68 Gop/s，端侧 45fps 余量足。
void P100R3TemporalFilter::apply_spatial_denoise(std::vector<uint16_t>* fused) {
    const int W = width_, H = height_;
    const size_t pixels = static_cast<size_t>(W) * static_cast<size_t>(H);
    if (!fused || fused->size() != pixels || W < 3 || H < 3) return;
    const float scale = static_cast<float>(1u << p100r3_depth_fraction_bits(config_.format));

    // stage1: median3 → denoise_scratch_（去单像素脉冲尖峰，避免双边 range 核被尖峰带偏）。
    denoise_scratch_.assign(pixels, 0);
    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const size_t k = static_cast<size_t>(y) * W + x;
            const uint16_t c = (*fused)[k];
            if (c == 0) continue;  // 无效保持 0，不新填
            uint16_t buf[9];
            int n = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                const int yy = y + dy;
                if (yy < 0 || yy >= H) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    const int xx = x + dx;
                    if (xx < 0 || xx >= W) continue;
                    const uint16_t v = (*fused)[static_cast<size_t>(yy) * W + xx];
                    if (v) buf[n++] = v;
                }
            }
            if (n == 0) { denoise_scratch_[k] = c; continue; }
            std::sort(buf, buf + n);
            denoise_scratch_[k] = buf[n / 2];
        }
    }

    // stage2: bilateral5（输入 denoise_scratch_，输出回 fused）。空间核常量、range 核 LUT。
    const float sr_raw = std::max(1.0f, config_.spatial_sigma_r_mm * scale);
    const float ss = std::max(0.5f, config_.spatial_sigma_s);
    float sk[5][5];
    for (int dy = -2; dy <= 2; ++dy)
        for (int dx = -2; dx <= 2; ++dx)
            sk[dy + 2][dx + 2] = std::exp(-static_cast<float>(dx * dx + dy * dy) / (2.0f * ss * ss));
    const int lut_n = static_cast<int>(3.0f * sr_raw) + 1;  // |dv| 截到 3σr，外权重 ~0
    std::vector<float> wr(static_cast<size_t>(lut_n));
    const float inv = 1.0f / (2.0f * sr_raw * sr_raw);
    for (int d = 0; d < lut_n; ++d)
        wr[d] = std::exp(-static_cast<float>(d) * static_cast<float>(d) * inv);

    for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
            const size_t k = static_cast<size_t>(y) * W + x;
            const uint16_t c = denoise_scratch_[k];
            if (c == 0) { (*fused)[k] = 0; continue; }
            float wsum = 0.0f, vsum = 0.0f;
            for (int dy = -2; dy <= 2; ++dy) {
                const int yy = y + dy;
                if (yy < 0 || yy >= H) continue;
                for (int dx = -2; dx <= 2; ++dx) {
                    const int xx = x + dx;
                    if (xx < 0 || xx >= W) continue;
                    const uint16_t v = denoise_scratch_[static_cast<size_t>(yy) * W + xx];
                    if (!v) continue;
                    const int ad = std::abs(static_cast<int>(v) - static_cast<int>(c));
                    if (ad >= lut_n) continue;
                    const float w = sk[dy + 2][dx + 2] * wr[static_cast<size_t>(ad)];
                    wsum += w;
                    vsum += w * static_cast<float>(v);
                }
            }
            (*fused)[k] = wsum > 0.0f ? static_cast<uint16_t>(vsum / wsum + 0.5f) : c;
        }
    }
}

XuPayload make_p100r3_master_open_stream_payload(uint16_t stream_type,
                                                 const P100R3VideoMode& mode) {
    XuPayload payload;
    payload.selector = 1;
    payload.w_value = 0x0100;
    payload.w_index = kP100R3MasterXu5WIndex;
    payload.data.assign(64, 0);
    payload.data[0] = 0x42;
    payload.data[1] = 0x58;
    write_le16(payload.data, 2, 12);
    write_le16(payload.data, 4, 0x0006);
    write_le16(payload.data, 6, 0x0000);
    write_le16(payload.data, 8, stream_type);
    write_le16(payload.data, 10, mode.width);
    write_le16(payload.data, 12, mode.height);
    write_le16(payload.data, 14, mode.fps);
    write_le16(payload.data, 16, 0);
    write_le16(payload.data, 18, 0);
    return payload;
}

XuPayload make_p100r3_master_color_open_stream_payload(const P100R3VideoMode& mode) {
    return make_p100r3_master_open_stream_payload(1, mode);
}

XuPayload make_p100r3_master_force_internal_pwm_trigger_payload(bool enabled, uint8_t fps) {
    XuPayload payload;
    payload.selector = 1;
    payload.w_value = 0x0100;
    payload.w_index = kP100R3MasterXu5WIndex;
    payload.data.assign(64, 0);
    payload.data[0] = 0x42;
    payload.data[1] = 0x58;
    write_le16(payload.data, 2, 4);
    write_le16(payload.data, 4, 0x0005);
    write_le16(payload.data, 6, 0x0000);
    write_le16(payload.data, 8, 0x0030);
    payload.data[10] = enabled ? 1 : 0;
    payload.data[11] = enabled ? fps : 0;
    return payload;
}

XuPayload make_p100r3_companion_stream_mode_payload(uint8_t mode_code) {
    XuPayload payload;
    payload.selector = 25;
    payload.w_value = 0x1900;
    payload.w_index = kP100R3CompanionXu3WIndex;
    payload.data.assign(512, 0);
    payload.data[0] = 0x01;
    payload.data[1] = 0x02;
    payload.data[2] = mode_code;
    return payload;
}

XuPayload make_p100r3_companion_depth_open_stream_payload(const P100R3VideoMode& mode) {
    return make_p100r3_companion_stream_mode_payload(p100r3_depth_mode_code(mode));
}

XuPayload make_p100r3_companion_light_ir_open_stream_payload(const P100R3VideoMode&) {
    return make_p100r3_companion_stream_mode_payload(0x02);
}

XuPayload make_p100r3_companion_hv3_command_payload(const std::vector<uint8_t>& prefix) {
    XuPayload payload;
    payload.selector = 25;
    payload.w_value = 0x1900;
    payload.w_index = kP100R3CompanionXu3WIndex;
    payload.data.assign(512, 0);
    const size_t n = std::min(prefix.size(), payload.data.size());
    std::copy_n(prefix.begin(), n, payload.data.begin());
    return payload;
}

XuPayload make_p100r3_depth_auto_exposure_payload(bool enabled) {
    return make_p100r3_companion_hv3_command_payload({
        0x01,
        0x02,
        static_cast<uint8_t>(enabled ? 0xcb : 0xc8),
    });
}

XuPayload make_p100r3_depth_confidence_payload(uint8_t confidence) {
    const uint8_t value = static_cast<uint8_t>(std::clamp<int>(confidence, 1, 5));
    return make_p100r3_companion_hv3_command_payload({
        0x0c,
        0x02,
        0x01,
        value,
    });
}

XuPayload make_p100r3_depth_gain_payload(uint8_t gain) {
    const uint8_t value = static_cast<uint8_t>(std::clamp<int>(gain, 1, 4));
    return make_p100r3_companion_hv3_command_payload({
        0x06,
        0x11,
        0xc0,
        0x01,
        0x35,
        0x09,
        static_cast<uint8_t>(value << 4),
    });
}

XuPayload make_p100r3_depth_temporal_denoise_payload(bool enabled) {
    return make_p100r3_companion_hv3_command_payload({
        0x0c,
        0x06,
        0x01,
        static_cast<uint8_t>(enabled ? 1 : 0),
    });
}

XuPayload make_p100r3_depth_spatial_denoise_payload(bool enabled) {
    return make_p100r3_companion_hv3_command_payload({
        0x0c,
        0x08,
        0x01,
        static_cast<uint8_t>(enabled ? 1 : 0),
    });
}

XuPayload make_p100r3_master_close_stream_payload(uint8_t stream_type) {
    XuPayload payload;
    payload.selector = 1;
    payload.w_value = 0x0100;
    payload.w_index = kP100R3MasterXu5WIndex;
    payload.data.assign(64, 0);
    payload.data[0] = 0x42;
    payload.data[1] = 0x58;
    write_le16(payload.data, 2, 2);
    write_le16(payload.data, 4, 0x0007);
    write_le16(payload.data, 6, 0x0000);
    write_le16(payload.data, 8, stream_type);
    return payload;
}

int patch_p100r3_master_color_open_stream_payloads(std::vector<XuPayload>* payloads,
                                                   const P100R3VideoMode& mode,
                                                   std::string* payload_hex) {
    if (!payloads) return 0;
    int patched = 0;
    for (auto& payload : *payloads) {
        if (!is_p100r3_color_open_stream_payload(payload)) continue;
        const uint8_t selector = payload.selector;
        const uint16_t w_value = payload.w_value;
        const uint16_t w_index = payload.w_index;
        payload = make_p100r3_master_color_open_stream_payload(mode);
        payload.selector = selector;
        payload.w_value = w_value;
        payload.w_index = w_index;
        patched++;
        if (payload_hex) *payload_hex = hex_bytes_compact(payload.data);
    }
    if (patched == 0) {
        payloads->push_back(make_p100r3_master_color_open_stream_payload(mode));
        patched = 1;
        if (payload_hex) *payload_hex = hex_bytes_compact(payloads->back().data);
    }
    return patched;
}

int patch_p100r3_companion_depth_open_stream_payloads(std::vector<XuPayload>* payloads,
                                                      const P100R3VideoMode& mode,
                                                      std::string* payload_prefix_hex) {
    if (!payloads) return 0;
    int patched = 0;
    for (auto& payload : *payloads) {
        if (!is_p100r3_depth_open_stream_payload(payload)) continue;
        const uint8_t selector = payload.selector;
        const uint16_t w_value = payload.w_value;
        const uint16_t w_index = payload.w_index;
        payload = make_p100r3_companion_depth_open_stream_payload(mode);
        payload.selector = selector;
        payload.w_value = w_value;
        payload.w_index = w_index;
        patched++;
        if (payload_prefix_hex) *payload_prefix_hex = hex_bytes_compact(payload.data, 16);
    }
    if (patched == 0) {
        payloads->push_back(make_p100r3_companion_depth_open_stream_payload(mode));
        patched = 1;
        if (payload_prefix_hex) *payload_prefix_hex = hex_bytes_compact(payloads->back().data, 16);
    }
    return patched;
}

int patch_p100r3_companion_light_ir_open_stream_payloads(std::vector<XuPayload>* payloads,
                                                         const P100R3VideoMode& mode,
                                                         std::string* payload_prefix_hex) {
    if (!payloads) return 0;
    int patched = 0;
    for (auto& payload : *payloads) {
        if (!is_p100r3_depth_open_stream_payload(payload)) continue;
        const uint8_t selector = payload.selector;
        const uint16_t w_value = payload.w_value;
        const uint16_t w_index = payload.w_index;
        payload = make_p100r3_companion_light_ir_open_stream_payload(mode);
        payload.selector = selector;
        payload.w_value = w_value;
        payload.w_index = w_index;
        patched++;
        if (payload_prefix_hex) *payload_prefix_hex = hex_bytes_compact(payload.data, 16);
    }
    if (patched == 0) {
        payloads->push_back(make_p100r3_companion_light_ir_open_stream_payload(mode));
        patched = 1;
        if (payload_prefix_hex) *payload_prefix_hex = hex_bytes_compact(payloads->back().data, 16);
    }
    return patched;
}

bool replay_xu_payloads(IUvcDevice& device,
                        const std::vector<XuPayload>& payloads,
                        bool read_back,
                        const std::string& label,
                        LogFn log) {
    for (size_t i = 0; i < payloads.size(); ++i) {
        std::vector<uint8_t> data = payloads[i].data;
        const int set_rc = device.uvc_set_cur(payloads[i].w_value,
                                              payloads[i].w_index,
                                              data.data(),
                                              static_cast<uint16_t>(data.size()));
        if (set_rc < 0) {
            log_line(log, label + " init#" + std::to_string(i) +
                          " SET_CUR wValue=" + hex16(payloads[i].w_value) +
                          " wIndex=" + hex16(payloads[i].w_index) +
                          " rc=" + std::to_string(set_rc) + " " + usb_error_name(set_rc));
            return false;
        }
        if (read_back) {
            std::vector<uint8_t> back(data.size());
            const int get_rc = device.uvc_get_cur(payloads[i].w_value,
                                                  payloads[i].w_index,
                                                  back.data(),
                                                  static_cast<uint16_t>(back.size()));
            if (get_rc < 0) {
                log_line(log, label + " init#" + std::to_string(i) +
                              " GET_CUR ignored rc=" + std::to_string(get_rc) +
                              " " + usb_error_name(get_rc));
            }
        }
    }
    log_line(log, label + " replay done: " + std::to_string(payloads.size()) + " payloads");
    return true;
}

bool apply_p100r3_depth_controls(IUvcDevice& device,
                                 const P100R3DepthControls& controls,
                                 LogFn log) {
    if (!controls.enabled) return true;

    std::vector<XuPayload> payloads;
    std::vector<std::string> names;
    if (controls.set_auto_exposure) {
        payloads.push_back(make_p100r3_depth_auto_exposure_payload(controls.auto_exposure));
        names.push_back(std::string("AE=") + (controls.auto_exposure ? "1" : "0"));
    }
    if (controls.set_confidence) {
        const int confidence = std::clamp<int>(controls.confidence, 1, 5);
        payloads.push_back(make_p100r3_depth_confidence_payload(static_cast<uint8_t>(confidence)));
        names.push_back("confidence=" + std::to_string(confidence));
    }
    if (controls.set_depth_gain) {
        const int gain = std::clamp<int>(controls.depth_gain, 1, 4);
        payloads.push_back(make_p100r3_depth_gain_payload(static_cast<uint8_t>(gain)));
        names.push_back("gain=" + std::to_string(gain));
    }
    if (controls.set_temporal_denoise) {
        payloads.push_back(make_p100r3_depth_temporal_denoise_payload(controls.temporal_denoise));
        names.push_back(std::string("temporal_denoise=") +
                        (controls.temporal_denoise ? "1" : "0"));
    }
    if (controls.set_spatial_denoise) {
        payloads.push_back(make_p100r3_depth_spatial_denoise_payload(controls.spatial_denoise));
        names.push_back(std::string("spatial_denoise=") +
                        (controls.spatial_denoise ? "1" : "0"));
    }
    if (payloads.empty()) {
        log_line(log, "companion depth controls enabled but no command selected");
        return true;
    }

    std::ostringstream ss;
    for (size_t i = 0; i < names.size(); ++i) {
        if (i > 0) ss << ", ";
        ss << names[i];
    }
    log_line(log, "companion depth controls: " + ss.str());
    return replay_xu_payloads(device, payloads, true, "companion-depth-controls", log);
}

bool negotiate_uvc_stream(IUvcDevice& device,
                          const UvcStreamConfig& config,
                          UvcNegotiation* out,
                          LogFn log) {
    std::array<uint8_t, 26> probe = {};
    if (config.use_get_def) {
        const int rc = device.uvc_get_def(0x0100,
                                          static_cast<uint16_t>(config.vs_interface),
                                          probe.data(),
                                          static_cast<uint16_t>(probe.size()));
        if (rc < 0) {
            log_line(log, config.name + " GET_DEF ignored rc=" + std::to_string(rc) +
                          " " + usb_error_name(rc));
            probe.fill(0);
        }
    }

    probe[0] = 0x01;
    probe[1] = 0x00;
    probe[2] = config.format_index;
    probe[3] = config.frame_index;
    write_le32(&probe[4], config.frame_interval_100ns);
    if (config.max_video_frame_size != 0) {
        write_le32(&probe[18], config.max_video_frame_size);
    }
    if (config.max_payload_transfer_size != 0) {
        write_le32(&probe[22], config.max_payload_transfer_size);
    }

    int rc = device.uvc_set_cur(0x0100,
                                static_cast<uint16_t>(config.vs_interface),
                                probe.data(),
                                static_cast<uint16_t>(probe.size()));
    if (rc < 0) {
        log_line(log, config.name + " SET_PROBE rc=" + std::to_string(rc) +
                      " " + usb_error_name(rc));
        return false;
    }

    std::array<uint8_t, 26> negotiated = {};
    rc = device.uvc_get_cur(0x0100,
                            static_cast<uint16_t>(config.vs_interface),
                            negotiated.data(),
                            static_cast<uint16_t>(negotiated.size()));
    if (rc < 0) {
        log_line(log, config.name + " GET_PROBE rc=" + std::to_string(rc) +
                      " " + usb_error_name(rc));
        return false;
    }

    rc = device.uvc_set_cur(0x0200,
                            static_cast<uint16_t>(config.vs_interface),
                            negotiated.data(),
                            static_cast<uint16_t>(negotiated.size()));
    if (rc < 0) {
        log_line(log, config.name + " COMMIT rc=" + std::to_string(rc) +
                      " " + usb_error_name(rc));
        return false;
    }

    UvcNegotiation n;
    n.probe = negotiated;
    n.format_index = negotiated[2];
    n.frame_index = negotiated[3];
    n.frame_interval_100ns = read_le32(&negotiated[4]);
    n.max_video_frame_size = read_le32(&negotiated[18]);
    n.max_payload_transfer_size = read_le32(&negotiated[22]);
    if (out) *out = n;

    log_line(log, config.name + " UVC committed fmt=" + std::to_string(n.format_index) +
                  " frame=" + std::to_string(n.frame_index) +
                  " interval100ns=" + std::to_string(n.frame_interval_100ns) +
                  " frameSize=" + std::to_string(n.max_video_frame_size) +
                  " payload=" + std::to_string(n.max_payload_transfer_size));
    return true;
}

void master_keepalive_loop(IUvcDevice& master,
                           XuPayload seed,
                           int interval_ms,
                           std::atomic<bool>& running,
                           BulkStats& stats,
                           LogFn log) {
    if (seed.data.size() < 14) {
        log_line(log, "master keepalive seed too short");
        return;
    }
    uint32_t counter = read_le32(&seed.data[10]);
    uint64_t iter = 0;
    while (running.load()) {
        counter += 0x36;
        write_le32(&seed.data[10], counter);
        int rc = master.uvc_set_cur(seed.w_value,
                                    seed.w_index,
                                    seed.data.data(),
                                    static_cast<uint16_t>(seed.data.size()),
                                    500);
        if (rc < 0) {
            stats.errors++;
            if (stats.first_error == 0) stats.first_error = rc;
            // 诊断：前 5 次错误逐条打 rc + name，之后每 200 次一条，定位 keepalive 停摆原因
            if (stats.errors <= 5 || stats.errors % 200 == 0) {
                log_line(log, std::string("set_cur err rc=") + std::to_string(rc) +
                              " (" + usb_error_name(rc) + ") errs=" + std::to_string(stats.errors) +
                              " ok=" + std::to_string(stats.chunks));
            }
        } else {
            stats.chunks++;
            std::vector<uint8_t> back(seed.data.size());
            int grc = master.uvc_get_cur(seed.w_value,
                                         seed.w_index,
                                         back.data(),
                                         static_cast<uint16_t>(back.size()),
                                         500);
            if (grc < 0 && stats.chunks <= 5) {
                log_line(log, std::string("get_cur err rc=") + std::to_string(grc) +
                              " (" + usb_error_name(grc) + ")");
            }
        }
        ++iter;
        std::this_thread::sleep_for(std::chrono::milliseconds(interval_ms));
    }
    log_line(log, std::string("keepalive loop exit iters=") + std::to_string(iter) +
                  " ok=" + std::to_string(stats.chunks) + " errs=" + std::to_string(stats.errors));
}

std::vector<XuPayload> parse_xu_payloads(const std::string& text,
                                         uint16_t default_w_value,
                                         uint16_t default_w_index,
                                         int limit) {
    const std::regex object_re(R"(\{[^{}]*"data_hex"[^{}]*\})");
    std::vector<XuPayload> out;
    for (auto it = std::sregex_iterator(text.begin(), text.end(), object_re);
         it != std::sregex_iterator(); ++it) {
        if (limit > 0 && static_cast<int>(out.size()) >= limit) break;
        const std::string object = it->str();

        std::string hex;
        if (!detail::extract_hex(object, "data_hex", &hex)) continue;
        XuPayload payload;
        payload.data = detail::hex_to_bytes(hex);
        if (payload.data.empty() && !hex.empty()) continue;

        int selector = -1;
        if (detail::extract_int(object, "selector", &selector)) {
            payload.selector = static_cast<uint8_t>(selector & 0xff);
        }

        int w_value = -1;
        if (detail::extract_int(object, "wValue", &w_value)) {
            payload.w_value = static_cast<uint16_t>(w_value & 0xffff);
        } else if (selector >= 0) {
            payload.w_value = static_cast<uint16_t>((selector & 0xff) << 8);
        } else {
            payload.w_value = default_w_value;
        }

        int w_index = -1;
        if (detail::extract_int(object, "wIndex", &w_index)) {
            payload.w_index = static_cast<uint16_t>(w_index & 0xffff);
        } else {
            payload.w_index = default_w_index;
        }
        out.push_back(std::move(payload));
    }
    return out;
}

}  // namespace gomob::berxel::host

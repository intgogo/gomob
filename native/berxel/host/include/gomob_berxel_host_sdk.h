#ifndef GOMOB_BERXEL_HOST_SDK_H
#define GOMOB_BERXEL_HOST_SDK_H

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include "gomob_berxel_portable.h"

struct libusb_context;
struct libusb_device_handle;

namespace gomob::berxel::host {

struct UsbDeviceInfo {
    UsbId id;
    uint8_t bus = 0;
    uint8_t address = 0;
    uint16_t bcd_usb = 0;
    uint16_t bcd_device = 0;
    std::string manufacturer;
    std::string product;
    std::string serial;
};

struct P100R3DualSessionConfig {
    bool enable_color = true;
    bool enable_depth = true;
    bool depth_as_light_ir = false;
    bool color_first = false;
    bool fresh_time_sync = true;
    bool send_master_stop = true;
    int duration_ms = 0;
    int keepalive_interval_ms = 50;
    int master_limit = 0;
    int read_len = 16384;
    int color_format = 1;
    int color_raw_frame_size = 0;
    int depth_frame_size = 0;
    P100R3VideoMode color_mode = P100R3VideoMode{2, 1280, 800, 30, 333333};
    P100R3VideoMode depth_mode = P100R3VideoMode{2, 640, 401, 45, 222222};
    P100R3DepthControls depth_controls;
    RgbdFramePairerConfig pairer_config;
    std::string master_payloads;
    std::string keepalive_payloads;
    std::string companion_init;
    std::string color_bulk_sample_path;
};

struct P100R3DualSessionCallbacks {
    LogFn log;
    UvcFrameCallback color_frame;
    UvcFrameCallback depth_frame;
    std::function<void(const RgbdFramePairInfo& pair)> rgbd_pair;
};

struct P100R3DualSessionStats {
    BulkStats keepalive;
    BulkStats color;
    BulkStats depth;
    int64_t rgbd_pairs = 0;
    int64_t dropped_color_pairs = 0;
    int64_t dropped_depth_pairs = 0;
    size_t queued_color_pairs = 0;
    size_t queued_depth_pairs = 0;
    RgbdPairingStats rgbd_pairing;
    P100R3SessionState state = P100R3SessionState::kIdle;
    P100R3SessionStopReason stop_reason = P100R3SessionStopReason::kNone;
    std::string error_message;
};

class P100R3DualSession {
public:
    explicit P100R3DualSession(P100R3DualSessionConfig config = {});
    P100R3DualSession(const P100R3DualSession&) = delete;
    P100R3DualSession& operator=(const P100R3DualSession&) = delete;
    ~P100R3DualSession();

    bool start(P100R3DualSessionCallbacks callbacks = {});
    void stop();
    void join();
    P100R3SessionState state() const;
    P100R3DualSessionStats stats() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

class UsbDevice : public IUvcDevice {
public:
    UsbDevice(const UsbDevice&) = delete;
    UsbDevice& operator=(const UsbDevice&) = delete;
    UsbDevice(UsbDevice&&) = delete;
    UsbDevice& operator=(UsbDevice&&) = delete;
    ~UsbDevice();

    bool valid() const;
    bool reset(LogFn log = {});
    bool claim_interface(int iface, LogFn log = {});
    void release_interface(int iface);
    void release_all();

    int control_transfer(uint8_t bm_request_type,
                         uint8_t b_request,
                         uint16_t w_value,
                         uint16_t w_index,
                         uint8_t* data,
                         uint16_t length,
                         uint32_t timeout_ms) override;

    int uvc_set_cur(uint16_t w_value,
                    uint16_t w_index,
                    uint8_t* data,
                    uint16_t length,
                    uint32_t timeout_ms = 2000) override;

    int uvc_get_cur(uint16_t w_value,
                    uint16_t w_index,
                    uint8_t* data,
                    uint16_t length,
                    uint32_t timeout_ms = 2000) override;

    int uvc_get_def(uint16_t w_value,
                    uint16_t w_index,
                    uint8_t* data,
                    uint16_t length,
                    uint32_t timeout_ms = 2000) override;

    int bulk_in(uint8_t endpoint,
                uint8_t* data,
                int length,
                int* actual_length,
                uint32_t timeout_ms) override;

private:
    friend class UsbContext;

    explicit UsbDevice(libusb_device_handle* handle);

    libusb_device_handle* handle_ = nullptr;
    std::vector<int> claimed_interfaces_;
};

class UsbContext {
public:
    UsbContext();
    UsbContext(const UsbContext&) = delete;
    UsbContext& operator=(const UsbContext&) = delete;
    UsbContext(UsbContext&&) = delete;
    UsbContext& operator=(UsbContext&&) = delete;
    ~UsbContext();

    bool ok() const;
    int status() const;
    std::vector<UsbDeviceInfo> list_devices() const;
    std::unique_ptr<UsbDevice> open(UsbId id) const;

private:
    libusb_context* context_ = nullptr;
    int status_ = 0;
};

std::vector<XuPayload> load_xu_payloads(const std::string& path,
                                        uint16_t default_w_value,
                                        uint16_t default_w_index,
                                        int limit = -1);

BulkStats pull_raw_frames_until(UsbDevice& device,
                                uint8_t endpoint,
                                std::atomic<bool>& running,
                                int duration_ms,
                                int read_len,
                                int frame_size,
                                const P100R3VideoMode& mode,
                                UvcFrameCallback callback,
                                LogFn log = {});

BulkStats pull_raw_frames(UsbDevice& device,
                          uint8_t endpoint,
                          int duration_ms,
                          int read_len,
                          int frame_size,
                          const P100R3VideoMode& mode,
                          UvcFrameCallback callback,
                          LogFn log = {});

BulkStats pull_raw_bulk(UsbDevice& device,
                        uint8_t endpoint,
                        int duration_ms,
                        int read_len,
                        int frame_size,
                          const std::string& first_frame_path,
                          LogFn log = {});

BulkStats pull_mjpeg_frames_until(UsbDevice& device,
                                  uint8_t endpoint,
                                  std::atomic<bool>& running,
                                  int duration_ms,
                                  int read_len,
                                  const P100R3VideoMode& mode,
                                  const std::string& bulk_sample_path,
                                  UvcFrameCallback callback,
                                  LogFn log = {});

BulkStats pull_mjpeg_frames(UsbDevice& device,
                            uint8_t endpoint,
                            int duration_ms,
                            int read_len,
                            const P100R3VideoMode& mode,
                            const std::string& bulk_sample_path,
                            UvcFrameCallback callback,
                            LogFn log = {});

BulkStats pull_mjpeg_bulk(UsbDevice& device,
                          uint8_t endpoint,
                          int duration_ms,
                          int read_len,
                          const std::string& first_jpeg_path,
                          const std::string& bulk_sample_path,
                          LogFn log = {});

}  // namespace gomob::berxel::host

#endif  // GOMOB_BERXEL_HOST_SDK_H

// STEP 8b — device HTTP REST client (QHttpComm equivalent). Endpoints per spec_protocol §2.
// GET = read (device_info, device_status); POST = action (control_scan, update_control,
// update_calib_parameters, config_network). Bodies are application/json.
// (De)serialization is split from transport so it is unit-testable without a network;
// DeviceClient wraps cpp-httplib for the actual round-trips.
#pragma once

#include <array>
#include <string>
#include "config/calibration_json.h"

namespace lidar::device {

enum class ScanCmd { SCAN_START, SCAN_STOP, SCAN_WATCH, ALIGN_ZERO, CLEAR_ERROR, SOFT_REBOOT };
const char* toString(ScanCmd c);

// Schema confirmed against live LTS-T1 firmware v1.4 (re/live/SAMPLE_device_info.json).
struct DeviceInfo {
  std::string model, sn, network_type, hwver, swver, network;  // device.*
  std::string lidar_model, lidar_ip;                            // lidar.model/ip
  int    lidar_port{0};
  std::string camera_model, camera_device;                      // camera.model/device
  int    camera_width{0}, camera_height{0};
  double camera_fps{0};
  int    encoder_resolution{0}, encoder_multi{0};               // encoder.resolution/multi
  CalibParams calib;                                            // device_info.parameters{lidar,camera,body2world}
  bool   has_calib{false};
};

// device_status (live schema): state under scan.state; angles under encoder; error under control.
struct DeviceStatus {
  std::string state;          // scan.state: READY | SCAN | BUSY | WATCH | ALIGN | ERROR
  std::string scan_msg;       // scan.msg
  bool        encoder_online{false}, lidar_online{false}, camera_online{false}, control_online{false};
  double      latest_angle{0}, zero_degs{0}, angle_degs{0}, tempre{0};
  double      uptime{0};
  long        error_code{0};  // control.error_code (bitmask ZERO HEAT TEMP LIMIT GAP ENC MOTOR)
  bool        online() const { return encoder_online && lidar_online && control_online; }
};

struct ControlParams {
  double scan_speed{5.0}, zero_speed{10.0};
  double scan_start_angle{-45.0}, scan_stop_angle{45.0}, watching_angle{0.0};
  double lidar_filter_ghost{0.0};
  std::array<double, 2> lidar_filter_zone{0.0, 0.0};
  int    camera_fps{10};
};

// --- pure (de)serialization (no network) ---
std::string  buildControlScanBody(ScanCmd cmd);                         // {"cmd":"…"}
std::string  buildUpdateControlBody(const ControlParams& p, bool wrap_in_control = true);
std::string  buildUpdateCalibBody(const CalibParams& p);                // {"parameters":{lidar,camera,body2world}}
DeviceInfo   parseDeviceInfo(const std::string& json);
DeviceStatus parseDeviceStatus(const std::string& json);

// --- transport ---
class DeviceClient {
 public:
  explicit DeviceClient(std::string ip, int port = 4000, int timeout_ms = 3000);

  bool getDeviceInfo(DeviceInfo& out, std::string& err);
  bool getDeviceStatus(DeviceStatus& out, std::string& err);
  // Pull the device's stored calibration (device_info.parameters). Returns false if absent.
  bool getCalibration(CalibParams& out, std::string& err);
  bool controlScan(ScanCmd cmd, std::string& err);                      // POST /api/control_scan
  bool updateControl(const ControlParams& p, std::string& err);         // POST /api/update_control
  bool updateCalibParameters(const CalibParams& p, std::string& err);   // POST /api/update_calib_parameters
  // DESTRUCTIVE — changes device IP; explicit + guarded (spec_protocol §6 "do NOT run casually").
  bool configNetwork(const std::string& new_ip, std::string& err, bool i_understand_this_changes_ip = false);

  const std::string& ip() const { return ip_; }
  int port() const { return port_; }

 private:
  std::string ip_;
  int port_;
  int timeout_ms_;
};

}  // namespace lidar::device

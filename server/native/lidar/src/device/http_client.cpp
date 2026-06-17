#include "device/http_client.h"

#include <nlohmann/json.hpp>
#define CPPHTTPLIB_THREAD_POOL_COUNT 2
#include <httplib.h>

namespace lidar::device {
namespace {
using json = nlohmann::json;

json quatArr(const Eigen::Quaterniond& q) { return json::array({q.w(), q.x(), q.y(), q.z()}); }
json vecArr(const Eigen::Vector3d& v)     { return json::array({v.x(), v.y(), v.z()}); }

// Read a possibly-string-or-number into double; tolerate either wire type (spec_protocol §2.2 UNCERTAIN).
double asNum(const json& n, double def = 0.0) {
  if (n.is_number()) return n.get<double>();
  if (n.is_string()) { try { return std::stod(n.get<std::string>()); } catch (...) {} }
  return def;
}
long asInt(const json& n, long def = 0) {
  if (n.is_number()) return n.get<long>();
  if (n.is_boolean()) return n.get<bool>() ? 1 : 0;
  if (n.is_string()) { try { return std::stol(n.get<std::string>()); } catch (...) {} }
  return def;
}
}  // namespace

const char* toString(ScanCmd c) {
  switch (c) {
    case ScanCmd::SCAN_START:  return "SCAN_START";
    case ScanCmd::SCAN_STOP:   return "SCAN_STOP";
    case ScanCmd::SCAN_WATCH:  return "SCAN_WATCH";
    case ScanCmd::ALIGN_ZERO:  return "ALIGN_ZERO";
    case ScanCmd::CLEAR_ERROR: return "CLEAR_ERROR";
    case ScanCmd::SOFT_REBOOT: return "SOFT_REBOOT";
  }
  return "SCAN_STOP";
}

std::string buildControlScanBody(ScanCmd cmd) {
  return json{{"cmd", toString(cmd)}}.dump();
}

std::string buildUpdateControlBody(const ControlParams& p, bool wrap_in_control) {
  json c = {
      {"scan_speed", p.scan_speed}, {"zero_speed", p.zero_speed},
      {"scan_start_angle", p.scan_start_angle}, {"scan_stop_angle", p.scan_stop_angle},
      {"watching_angle", p.watching_angle}, {"lidar_filter_ghost", p.lidar_filter_ghost},
      {"lidar_filter_zone", json::array({p.lidar_filter_zone[0], p.lidar_filter_zone[1]})},
      {"camera_fps", p.camera_fps}};
  // spec_protocol §2.4: likely wrapped in {"control":{…}} (UNCERTAIN until live). Toggleable.
  return wrap_in_control ? json{{"control", c}}.dump() : c.dump();
}

std::string buildUpdateCalibBody(const CalibParams& p) {
  json root;
  root["parameters"]["lidar"] = {
      {"lidar_rot_quat", quatArr(p.lidar_rot_quat)},
      {"lidar_corr_quat", quatArr(p.lidar_corr_quat)},
      {"lidar_corr_offset", vecArr(p.lidar_corr_offset)}};
  root["parameters"]["camera"] = {
      {"camera_rot_quat", quatArr(p.camera_rot_quat)},
      {"camera_corr_quat", quatArr(p.camera_corr_quat)},
      {"camera_corr_offset", vecArr(p.camera_corr_offset)},
      {"camera_intrinsic", json::array({p.camera_intrinsic[0], p.camera_intrinsic[1], p.camera_intrinsic[2], p.camera_intrinsic[3]})},
      {"camera_distortion", json::array({p.camera_distortion[0], p.camera_distortion[1], p.camera_distortion[2], p.camera_distortion[3], p.camera_distortion[4]})}};
  root["parameters"]["body2world"] = {
      {"b2w_quat", quatArr(p.b2w_quat)}, {"b2w_offset", vecArr(p.b2w_offset)}, {"b2w_scale", p.b2w_scale}};
  return root.dump();
}

namespace {
Eigen::Quaterniond quatFromJson(const json& n, const Eigen::Quaterniond& def) {
  if (!n.is_array() || n.size() != 4) return def;
  return Eigen::Quaterniond(n[0].get<double>(), n[1].get<double>(), n[2].get<double>(), n[3].get<double>());
}
Eigen::Vector3d vec3FromJson(const json& n, const Eigen::Vector3d& def) {
  if (!n.is_array() || n.size() != 3) return def;
  return {n[0].get<double>(), n[1].get<double>(), n[2].get<double>()};
}
// Parse a device_info/calibration "parameters" node into CalibParams. Returns false if absent.
bool parseParameters(const json& j, CalibParams& p) {
  if (!j.contains("parameters") || !j["parameters"].is_object()) return false;
  const json& P = j["parameters"];
  if (const json& L = P.value("lidar", json::object()); L.is_object()) {
    p.lidar_rot_quat = quatFromJson(L.value("lidar_rot_quat", json()), p.lidar_rot_quat);
    p.lidar_corr_quat = quatFromJson(L.value("lidar_corr_quat", json()), p.lidar_corr_quat);
    p.lidar_corr_offset = vec3FromJson(L.value("lidar_corr_offset", json()), p.lidar_corr_offset);
  }
  if (const json& C = P.value("camera", json::object()); C.is_object()) {
    p.camera_rot_quat = quatFromJson(C.value("camera_rot_quat", json()), p.camera_rot_quat);
    p.camera_corr_quat = quatFromJson(C.value("camera_corr_quat", json()), p.camera_corr_quat);
    p.camera_corr_offset = vec3FromJson(C.value("camera_corr_offset", json()), p.camera_corr_offset);
    if (const json& a = C.value("camera_intrinsic", json()); a.is_array() && a.size() == 4)
      for (int i = 0; i < 4; ++i) p.camera_intrinsic[i] = a[i].get<double>();
    if (const json& a = C.value("camera_distortion", json()); a.is_array() && a.size() == 5)
      for (int i = 0; i < 5; ++i) p.camera_distortion[i] = a[i].get<double>();
  }
  if (const json& B = P.value("body2world", json::object()); B.is_object()) {
    p.b2w_quat = quatFromJson(B.value("b2w_quat", json()), p.b2w_quat);
    p.b2w_offset = vec3FromJson(B.value("b2w_offset", json()), p.b2w_offset);
    p.b2w_scale = B.value("b2w_scale", p.b2w_scale);
  }
  return true;
}
}  // namespace

DeviceInfo parseDeviceInfo(const std::string& body) {
  DeviceInfo d;
  json j = json::parse(body, nullptr, false);
  if (j.is_discarded()) return d;
  const json& dev = j.value("device", json::object());
  d.model = dev.value("model", std::string());
  d.sn = dev.value("sn", std::string());
  d.network_type = dev.value("network_type", std::string());
  d.network = dev.value("network", std::string());
  d.hwver = dev.value("hwver", std::string());
  d.swver = dev.value("swver", std::string());
  const json& li = j.value("lidar", json::object());
  d.lidar_model = li.value("model", std::string());
  d.lidar_ip = li.value("ip", std::string());
  d.lidar_port = li.value("port", 0);
  const json& cam = j.value("camera", json::object());
  d.camera_model = cam.value("model", std::string());
  d.camera_device = cam.value("device", std::string());
  d.camera_width = cam.value("width", 0);
  d.camera_height = cam.value("height", 0);
  d.camera_fps = asNum(cam.value("capture_fps", json()), 0);
  const json& enc = j.value("encoder", json::object());
  d.encoder_resolution = enc.value("resolution", 0);
  d.encoder_multi = enc.value("multi", 0);
  d.has_calib = parseParameters(j, d.calib);
  return d;
}

DeviceStatus parseDeviceStatus(const std::string& body) {
  DeviceStatus s;
  json j = json::parse(body, nullptr, false);
  if (j.is_discarded()) return s;
  s.uptime = asNum(j.value("uptime", json()), 0);
  const json& sc = j.value("scan", json::object());
  s.state = sc.value("state", std::string());
  s.scan_msg = sc.value("msg", std::string());
  const json& en = j.value("encoder", json::object());
  s.encoder_online = en.value("online", false);
  s.latest_angle = asNum(en.value("latest_angle", json()), 0);
  s.zero_degs = asNum(en.value("zero_degs", json()), 0);
  s.lidar_online = j.value("lidar", json::object()).value("online", false);
  s.camera_online = j.value("camera", json::object()).value("online", false);
  const json& ct = j.value("control", json::object());
  s.control_online = ct.value("online", false);
  s.angle_degs = asNum(ct.value("angle_degs", json()), 0);
  s.error_code = asInt(ct.value("error_code", json()), 0);
  s.tempre = asNum(ct.value("tempre", json()), 0);
  return s;
}

DeviceClient::DeviceClient(std::string ip, int port, int timeout_ms)
    : ip_(std::move(ip)), port_(port), timeout_ms_(timeout_ms) {}

namespace {
httplib::Client makeClient(const std::string& ip, int port, int timeout_ms) {
  httplib::Client cli(ip, port);
  cli.set_connection_timeout(0, timeout_ms * 1000);
  cli.set_read_timeout(timeout_ms / 1000, (timeout_ms % 1000) * 1000);
  cli.set_default_headers({{"Custom-Auth-Client", "lidar-ptz-linux"}});
  return cli;
}
bool ok2xx(const httplib::Result& r) { return r && r->status >= 200 && r->status < 300; }
}  // namespace

bool DeviceClient::getDeviceInfo(DeviceInfo& out, std::string& err) {
  auto cli = makeClient(ip_, port_, timeout_ms_);
  auto r = cli.Get("/api/device_info");
  if (!ok2xx(r)) { err = "GET /api/device_info failed"; return false; }
  out = parseDeviceInfo(r->body);
  return true;
}

bool DeviceClient::getCalibration(CalibParams& out, std::string& err) {
  auto cli = makeClient(ip_, port_, timeout_ms_);
  auto r = cli.Get("/api/device_info");
  if (!ok2xx(r)) { err = "GET /api/device_info failed"; return false; }
  DeviceInfo di = parseDeviceInfo(r->body);
  if (!di.has_calib) { err = "device_info has no 'parameters' (calibration) node"; return false; }
  out = di.calib;
  return true;
}

bool DeviceClient::getDeviceStatus(DeviceStatus& out, std::string& err) {
  auto cli = makeClient(ip_, port_, timeout_ms_);
  auto r = cli.Get("/api/device_status");
  if (!ok2xx(r)) { err = "GET /api/device_status failed"; return false; }
  out = parseDeviceStatus(r->body);
  return true;
}

bool DeviceClient::controlScan(ScanCmd cmd, std::string& err) {
  auto cli = makeClient(ip_, port_, timeout_ms_);
  auto r = cli.Post("/api/control_scan", buildControlScanBody(cmd), "application/json");
  if (!ok2xx(r)) { err = "POST /api/control_scan failed"; return false; }
  return true;
}

bool DeviceClient::updateControl(const ControlParams& p, std::string& err) {
  auto cli = makeClient(ip_, port_, timeout_ms_);
  auto r = cli.Post("/api/update_control", buildUpdateControlBody(p), "application/json");
  if (!ok2xx(r)) { err = "POST /api/update_control failed"; return false; }
  return true;
}

bool DeviceClient::updateCalibParameters(const CalibParams& p, std::string& err) {
  auto cli = makeClient(ip_, port_, timeout_ms_);
  auto r = cli.Post("/api/update_calib_parameters", buildUpdateCalibBody(p), "application/json");
  if (!ok2xx(r)) { err = "POST /api/update_calib_parameters failed"; return false; }
  return true;
}

bool DeviceClient::configNetwork(const std::string& new_ip, std::string& err, bool ack) {
  if (!ack) { err = "configNetwork refused: destructive (changes device IP). Pass ack=true."; return false; }
  auto cli = makeClient(ip_, port_, timeout_ms_);
  nlohmann::json body = {{"network", {{"ip", new_ip}}}};
  auto r = cli.Post("/api/config_network", body.dump(), "application/json");
  if (!ok2xx(r)) { err = "POST /api/config_network failed"; return false; }
  return true;
}

}  // namespace lidar::device

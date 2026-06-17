// STEP 8b offline test: device HTTP client (de)serialization + full round-trip against an
// in-process cpp-httplib mock device. No real hardware. (Live request/response shapes for
// update_* remain UNCERTAIN per spec_protocol §2.4/2.5 until a real capture.)
#include <cmath>
#include <cstdio>
#include <string>
#include <thread>

// Project headers (pull in Eigen) MUST precede <httplib.h>: httplib leaks a system
// socket macro that otherwise breaks Eigen's template headers (matches http_client.cpp order).
#include "config/calibration_json.h"
#include "device/http_client.h"

#include <nlohmann/json.hpp>
#include <httplib.h>

using namespace lidar;
using namespace lidar::device;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)

int main() {
  std::printf("[1] request body builders\n");
  {
    auto j = nlohmann::json::parse(buildControlScanBody(ScanCmd::SCAN_START));
    CHECK(j["cmd"] == "SCAN_START", "control_scan -> {\"cmd\":\"SCAN_START\"}");

    CalibParams p;
    p.camera_intrinsic = {1873.01, 1870.2, 1926.46, 1134.81};
    p.camera_distortion = {-0.0188, 0.0239, -0.0006, -0.0007, -0.0208};
    p.b2w_scale = 1.0;
    auto cj = nlohmann::json::parse(buildUpdateCalibBody(p));
    CHECK(cj.contains("parameters") && cj["parameters"].contains("lidar") &&
          cj["parameters"]["camera"]["camera_intrinsic"].size() == 4 &&
          cj["parameters"]["camera"]["camera_distortion"].size() == 5 &&
          cj["parameters"]["body2world"]["b2w_quat"].size() == 4,
          "update_calib body matches parameters{lidar,camera,body2world} schema");

    ControlParams ctl;
    auto uj = nlohmann::json::parse(buildUpdateControlBody(ctl, true));
    CHECK(uj.contains("control") && uj["control"]["lidar_filter_zone"].size() == 2, "update_control wrapped in {control:{}}");
  }

  std::printf("[2] response parsers (LIVE LTS-T1 v1.4 schema)\n");
  {
    // real device_info shape: device/encoder/lidar/camera/control + parameters
    auto info = parseDeviceInfo(R"({"device":{"model":"LTS-T1","sn":"262105-c0bac6c7","hwver":"v1.8","swver":"v1.4","network_type":"ethernet","network":"192.168.9.101/24"},"encoder":{"resolution":2500,"multi":4},"lidar":{"model":"Pico100","ip":"192.168.0.1","port":2111},"camera":{"model":"IMX415","device":"/dev/video0","width":3840,"height":2160,"capture_fps":0.33},"parameters":{"lidar":{"lidar_rot_quat":[0.5,0.5,0.5,0.5]},"camera":{"camera_intrinsic":[2090.76,2086.82,1885.66,1128.65],"camera_distortion":[0.06,-0.08,-0.001,-0.0006,0.019]},"body2world":{"b2w_quat":[1,0,0,0],"b2w_offset":[0,0,0],"b2w_scale":1}}})");
    CHECK(info.model == "LTS-T1" && info.lidar_model == "Pico100" && info.lidar_port == 2111 &&
          info.camera_width == 3840 && info.encoder_resolution == 2500, "parseDeviceInfo (live schema)");
    CHECK(info.has_calib && std::fabs(info.calib.camera_intrinsic[0] - 2090.76) < 1e-2 &&
          std::fabs(info.calib.lidar_rot_quat.norm() - 1.0) < 1e-9, "device_info.parameters -> CalibParams");
    // real device_status: state under scan.state, angles under encoder, error under control
    auto st = parseDeviceStatus(R"({"uptime":96180.2,"encoder":{"online":true,"latest_angle":1.5,"zero_degs":0.1},"lidar":{"online":true},"camera":{"online":false,"msg":"camera data timeout"},"control":{"online":true,"angle_degs":0,"error_code":32,"tempre":45.6},"scan":{"state":"READY","msg":"camera offline"}})");
    CHECK(st.state == "READY" && st.online() && !st.camera_online && st.latest_angle == 1.5 &&
          st.error_code == 32 && std::fabs(st.tempre - 45.6) < 1e-3, "parseDeviceStatus (live schema)");
  }

  std::printf("[3] full round-trip vs in-process mock device\n");
  {
    httplib::Server svr;
    svr.Get("/api/device_info", [](const httplib::Request&, httplib::Response& res) {
      res.set_content(R"({"device":{"model":"LTS-T1","sn":"262106"},"lidar":{"model":"Pico100","ip":"192.168.0.1","port":2111},"camera":{"width":3840,"height":2160},"parameters":{"lidar":{"lidar_rot_quat":[0.5,0.5,0.5,0.5]},"camera":{"camera_intrinsic":[2090.76,2086.82,1885.66,1128.65],"camera_distortion":[0.06,-0.08,-0.001,-0.0006,0.019]},"body2world":{"b2w_quat":[1,0,0,0],"b2w_offset":[0,0,0],"b2w_scale":1}}})", "application/json");
    });
    svr.Get("/api/device_status", [](const httplib::Request&, httplib::Response& res) {
      res.set_content(R"({"uptime":100,"encoder":{"online":true},"lidar":{"online":true},"camera":{"online":true},"control":{"online":true,"error_code":32},"scan":{"state":"READY"}})", "application/json");
    });
    std::string got_cmd, got_calib;
    svr.Post("/api/control_scan", [&](const httplib::Request& req, httplib::Response& res) {
      got_cmd = req.body; res.set_content(R"({"code":0})", "application/json");
    });
    svr.Post("/api/update_calib_parameters", [&](const httplib::Request& req, httplib::Response& res) {
      got_calib = req.body; res.set_content(R"({"code":0})", "application/json");
    });

    int port = svr.bind_to_any_port("127.0.0.1");
    std::thread th([&] { svr.listen_after_bind(); });
    svr.wait_until_ready();

    DeviceClient cli("127.0.0.1", port, 2000);
    std::string err;
    DeviceInfo info; DeviceStatus st;
    CHECK(cli.getDeviceInfo(info, err) && info.model == "LTS-T1" && info.lidar_model == "Pico100" && info.has_calib, "GET device_info round-trip");
    CHECK(cli.getDeviceStatus(st, err) && st.state == "READY" && st.online(), "GET device_status round-trip");
    CalibParams live_calib;
    CHECK(cli.getCalibration(live_calib, err) && std::fabs(live_calib.camera_intrinsic[0] - 2090.76) < 1e-2, "getCalibration pulls device parameters");
    CHECK(cli.controlScan(ScanCmd::SCAN_START, err) && nlohmann::json::parse(got_cmd)["cmd"] == "SCAN_START", "POST control_scan round-trip");
    CalibParams p; p.b2w_scale = 1.0;
    CHECK(cli.updateCalibParameters(p, err) && nlohmann::json::parse(got_calib).contains("parameters"), "POST update_calib round-trip");
    // destructive guard
    CHECK(!cli.configNetwork("192.168.9.50", err, false), "configNetwork refused without ack");

    svr.stop();
    th.join();
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}

#include "calib/framing_stream.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

#include "device/http_client.h"  // DeviceClient：独立线程轮询设备状态判扫掠结束（不在 recv 循环里）

#include <opencv2/aruco.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/core.hpp>
#include <opencv2/core/utils/logger.hpp>  // setLogLevel：禁 OpenCV 日志写 stdout（否则污染二进制协议）
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <nlohmann/json.hpp>

#include "calib/site_marker_calib.h"     // MarkerCenterObs / aggregateMarkerCorners / solveSiteExtrinsic
#include "cloud/registration.h"          // saveSiteExtrinsic
#include "config/calibration_json.h"     // loadCalibrationJson
#include "config/config_yaml.h"          // loadConfig
#include "device/scan_stream.h"          // ImgFrame
#include "device/stream_capture.h"       // captureImageSweep
#include "texture/colorizer.h"           // CameraModel

namespace lidar {
namespace {
constexpr double kDegToRad = 3.14159265358979323846 / 180.0;

CameraModel buildCameraModel(const std::string& config_path, const std::string& calib_path) {
  CameraModel cam = CameraModel::fromConfig(loadConfig(config_path));
  cam.applyCalibration(loadCalibrationJson(calib_path));
  return cam;
}

// 一帧的检测结果：用于解算的相机系观测 + 用于前端叠加的角点像素（全分辨率）。
struct FrameMarker {
  int                            id{-1};
  std::array<cv::Point2f, 4>     corners{};      // 角点像素（预览系，供前端叠加）
  Eigen::Vector3d                center_cam{0, 0, 0};
  std::array<Eigen::Vector3d, 4> corners_cam{};  // 角点相机系（米），solvePnP 位姿×物点，供 6DoF 解算
};

// 全分辨率检测一帧：detectMarkers + solvePnP(已知边长)。约定同 site_marker_calib detectUnitCenters。
// dict 由调用方建好传入（不再用 static 共享）；本函数的 OpenCV 调用由调用方 procMu 串行化（两单元线程
// 并发跑 OpenCV 会 race 崩溃，实测：fast 调度崩、gdb 串行不崩）。
std::vector<FrameMarker> detectFrame(const cv::Mat& img, const cv::Ptr<cv::aruco::Dictionary>& dict,
                                     const CameraModel& cam, double marker_len_m) {
  std::vector<FrameMarker> out;
  const double fx = cam.intrinsic[0], fy = cam.intrinsic[1], cx = cam.intrinsic[2], cy = cam.intrinsic[3];
  cv::Mat K = (cv::Mat_<double>(3, 3) << fx, 0, cx, 0, fy, cy, 0, 0, 1);
  cv::Mat D = (cv::Mat_<double>(1, 5) << cam.distortion[0], cam.distortion[1], cam.distortion[2],
               cam.distortion[3], cam.distortion[4]);
  const float h = static_cast<float>(marker_len_m / 2.0);
  const std::vector<cv::Point3f> objPts = {{-h, h, 0}, {h, h, 0}, {h, -h, 0}, {-h, -h, 0}};

  std::vector<int>                      ids;
  std::vector<std::vector<cv::Point2f>> corners;
  cv::aruco::detectMarkers(img, dict, corners, ids);
  for (size_t i = 0; i < ids.size(); ++i) {
    cv::Vec3d rvec, tvec;
    if (!cv::solvePnP(objPts, corners[i], K, D, rvec, tvec, false, cv::SOLVEPNP_IPPE_SQUARE)) continue;
    FrameMarker m;
    m.id = ids[i];
    for (int c = 0; c < 4 && c < static_cast<int>(corners[i].size()); ++c) m.corners[c] = corners[i][c];
    m.center_cam = Eigen::Vector3d(tvec[0], tvec[1], tvec[2]);
    // 4 角点（相机系）= 位姿 × 标记物点，带朝向，供完整 6DoF 解算。
    cv::Mat R;
    cv::Rodrigues(rvec, R);
    for (int k = 0; k < 4; ++k) {
      cv::Mat pm = (cv::Mat_<double>(3, 1) << objPts[k].x, objPts[k].y, objPts[k].z);
      cv::Mat pc = R * pm + cv::Mat(tvec);
      m.corners_cam[k] = Eigen::Vector3d(pc.at<double>(0), pc.at<double>(1), pc.at<double>(2));
    }
    out.push_back(m);
  }
  return out;
}

// ---- 二进制帧协议写出（大端长度前缀；out 单写者由 mutex 串行化）----
void putU32(std::FILE* f, std::uint32_t v) {
  std::uint8_t b[4] = {static_cast<std::uint8_t>(v >> 24), static_cast<std::uint8_t>(v >> 16),
                       static_cast<std::uint8_t>(v >> 8), static_cast<std::uint8_t>(v)};
  std::fwrite(b, 1, 4, f);
}

void emitText(std::FILE* f, std::mutex& mu, char type, const std::string& s) {
  std::lock_guard<std::mutex> lk(mu);
  putU32(f, static_cast<std::uint32_t>(s.size()));
  std::fputc(type, f);
  std::fwrite(s.data(), 1, s.size(), f);
  std::fflush(f);
}

void emitFrame(std::FILE* f, std::mutex& mu, const std::string& meta, const std::vector<std::uint8_t>& jpeg) {
  std::lock_guard<std::mutex> lk(mu);
  const std::uint32_t metaLen = static_cast<std::uint32_t>(meta.size());
  const std::uint32_t N = 4 + metaLen + static_cast<std::uint32_t>(jpeg.size());
  putU32(f, N);
  std::fputc('m', f);
  putU32(f, metaLen);
  std::fwrite(meta.data(), 1, meta.size(), f);
  std::fwrite(jpeg.data(), 1, jpeg.size(), f);
  std::fflush(f);
}

// 单元采集上下文：各自持有 obs。处理在单一 worker 线程串行执行，故 obs/seq/n_det 无需锁。
struct UnitCtx {
  int          unit;
  CameraModel  cam;
  cv::Ptr<cv::aruco::Dictionary> dict;  // 共享只读；单 worker 串行用，无 race
  double       marker_len_m;
  int          preview_width;
  int          preview_quality;
  std::FILE*   out;
  std::mutex*  outMu;
  std::vector<MarkerCenterObs> obs;  // worker 单线程写，无需锁
  int          seq{0};
  int          n_det{0};
};

// 队列项：采集线程只搬运原始 JPEG（O(1)，不阻塞 recv），处理在 worker 线程做。
struct FrameItem {
  int                        unit{0};
  std::vector<std::uint8_t>  jpeg;
  double                     heading_deg{0};
};

// processFrame 在单一 worker 线程串行执行：解码→检测→预览编码→推流→累积观测。
// 关键：本函数的耗时（imdecode + 全分辨率 aruco）**不在采集 recv 循环里**，故设备不会因 client 读慢丢帧。
void processFrame(UnitCtx& c, const std::vector<std::uint8_t>& jpeg, double heading_deg) {
  if (jpeg.empty()) return;
  cv::Mat enc(1, static_cast<int>(jpeg.size()), CV_8UC1, const_cast<std::uint8_t*>(jpeg.data()));
  cv::Mat raw = cv::imdecode(enc, cv::IMREAD_COLOR);
  if (raw.empty()) return;

  // 等比缩到预览分辨率（检测**也在此分辨率**做：全分辨率 aruco ~1.8s/帧 worker 跟不上 0.33fps×2 流；
  // 缩到 1280 宽 ~9x 提速→处理满所有帧。1~3m 的 150mm 标记在 1280 下仍 34~103px，detectMarkers 可靠）。
  double scale = (raw.cols > 0) ? static_cast<double>(c.preview_width) / raw.cols : 1.0;
  if (scale > 1.0) scale = 1.0;  // 不放大
  cv::Mat preview;
  if (scale < 1.0)
    cv::resize(raw, preview, cv::Size(), scale, scale, cv::INTER_AREA);
  else
    preview = raw;

  // 缩放内参用于在预览分辨率上 solvePnP（fx,fy,cx,cy 随分辨率线性缩放；畸变系数无量纲不缩放）。
  // tvec 以米为单位、与分辨率无关，故 cameraToWorld/聚合不受影响；角点直接落在预览像素系供前端叠加。
  CameraModel camS = c.cam;
  for (int i = 0; i < 4; ++i) camS.intrinsic[i] *= scale;
  auto markers = detectFrame(preview, c.dict, camS, c.marker_len_m);
  for (const auto& m : markers) {
    MarkerCenterObs o;
    o.id = m.id;
    o.center_cam = m.center_cam;
    o.corners_cam = m.corners_cam;
    o.heading_rad = heading_deg * kDegToRad;
    c.obs.push_back(o);
    ++c.n_det;
  }

  std::vector<std::uint8_t> jpg;
  std::vector<int> enc_params = {cv::IMWRITE_JPEG_QUALITY, c.preview_quality};
  if (!cv::imencode(".jpg", preview, jpg, enc_params)) return;

  nlohmann::json meta;
  meta["unit"] = c.unit;
  meta["seq"] = c.seq++;
  meta["heading"] = heading_deg;
  meta["w"] = preview.cols;
  meta["h"] = preview.rows;
  nlohmann::json mk = nlohmann::json::array();
  for (const auto& m : markers) {
    nlohmann::json px = nlohmann::json::array();
    for (int i = 0; i < 4; ++i)
      px.push_back({std::lround(m.corners[i].x), std::lround(m.corners[i].y)});  // 角点已在预览系
    mk.push_back({{"id", m.id}, {"px", px}});
  }
  meta["markers"] = mk;
  emitFrame(c.out, *c.outMu, meta.dump(), jpg);
}
}  // namespace

int runFramingStream(const FramingStreamParams& p, std::FILE* out) {
  // ★ 关键：OpenCV 的 INFO 日志（TBB/parallel 后端注册等）默认写 stdout，会把 "[ INFO:...]" 文本
  // 注入到本命令的二进制帧协议里污染流（实测 Go 端把 "[ INF" 读成 1.5GB 长度 → unexpected EOF）。
  // 全程静默 OpenCV 日志；libjpeg 的 "Corrupt JPEG" 警告走 stderr 不影响协议。
  cv::utils::logging::setLogLevel(cv::utils::logging::LOG_LEVEL_SILENT);
  std::mutex outMu;
  CameraModel camA, camB;
  try {
    camA = buildCameraModel(p.configA, p.calibA);
    camB = buildCameraModel(p.configB, p.calibB);
  } catch (const std::exception& e) {
    emitText(out, outMu, 's', std::string("{\"ev\":\"error\",\"msg\":\"加载相机标定失败: ") + e.what() + "\"}");
    return 2;
  }

  cv::Ptr<cv::aruco::Dictionary> dict = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_APRILTAG_36h11);
  UnitCtx ctxA{0, camA, dict, p.marker_len_m, p.preview_width, p.preview_quality, out, &outMu, {}, 0, 0};
  UnitCtx ctxB{1, camB, dict, p.marker_len_m, p.preview_width, p.preview_quality, out, &outMu, {}, 0, 0};

  // ★ 生产者-消费者解耦（修真机实测 ~95% 丢帧）：4003 真出满 0.33fps(~60帧/180s)，但旧版把全分辨率
  // aruco 检测放在采集 recv 回调里同步跑，client 读太慢→设备丢帧→只剩个位数。现采集线程只入队原始 JPEG
  // (O(1) 不阻塞 recv)，单 worker 线程串行做解码/检测/推流；队列吸收处理滞后，采集满速→不再丢帧。
  std::queue<FrameItem> q;
  std::mutex            qmu;
  std::condition_variable qcv;
  std::atomic<bool>     captureDone{false};
  auto enqueue = [&](int unit, const device::ImgFrame& im) {
    if (im.jpeg.empty()) return;
    {
      std::lock_guard<std::mutex> lk(qmu);
      q.push(FrameItem{unit, im.jpeg, im.h_angle_deg});
    }
    qcv.notify_one();
  };
  std::thread worker([&] {
    for (;;) {
      FrameItem item;
      {
        std::unique_lock<std::mutex> lk(qmu);
        qcv.wait(lk, [&] { return !q.empty() || captureDone.load(); });
        if (q.empty()) break;  // 谓词保证：队空被唤醒必是 captureDone → 收工
        item = std::move(q.front());
        q.pop();
      }
      processFrame(item.unit == 0 ? ctxA : ctxB, item.jpeg, item.heading_deg);
    }
  });

  std::atomic<int>  connected{0};
  std::atomic<bool> anyFail{false};

  auto onReady = [&](int unit, bool ok) {
    if (!ok) {
      anyFail = true;
      nlohmann::json j = {{"ev", "error"}, {"unit", unit}, {"msg", "图像流连接失败"}};
      emitText(out, outMu, 's', j.dump());
      return;
    }
    if (connected.fetch_add(1) + 1 == 2) emitText(out, outMu, 's', "{\"ev\":\"ready\"}");
  };

  // ★ status_port=0：不在 recv 循环里轮询设备 :4000（阻塞式 HTTP 会饿死 4003 读→socket 缓冲满→设备丢帧，
  // 实测旧版 status_port=4000 只拿到 ~2 帧 vs 纹理路径 status_port=0 拿满 ~60）。扫掠结束由**独立状态线程**
  // 轮询 device_status 判定后 cancel（不阻塞 recv）；idle_timeout(20s)/hard_timeout 兜底。
  std::atomic<bool> cancelFlag{false};
  std::thread statusMon([&] {
    using namespace std::chrono;
    device::DeviceClient cliA(p.ipA, 4000), cliB(p.ipB, 4000);
    bool aSeen = false, bSeen = false;
    auto poll = [](device::DeviceClient& cli, bool& seen) -> bool {  // 返回 true=该单元扫掠已结束
      device::DeviceStatus st;
      std::string err;
      if (!cli.getDeviceStatus(st, err)) return false;
      if (st.state == "SCAN" || st.state == "BUSY") { seen = true; return false; }
      return seen && (st.state == "READY" || st.state == "IDLE");
    };
    while (!cancelFlag.load()) {
      bool aDone = poll(cliA, aSeen);
      bool bDone = poll(cliB, bSeen);
      if (aDone && bDone) {
        std::this_thread::sleep_for(seconds(5));  // 宽限：收尾在途帧（相机出帧滞后转台）
        cancelFlag = true;
        break;
      }
      std::this_thread::sleep_for(seconds(1));
    }
  });

  device::SweepCaptureResult ra, rb;
  std::thread tA([&] {
    ra = device::captureImageSweep(
        p.ipA, 4003, 0, 400, 20000, p.hard_timeout_ms, "",
        [&](const device::ImgFrame& im) { enqueue(0, im); }, &cancelFlag,
        [&](bool ok) { onReady(0, ok); });
  });
  std::thread tB([&] {
    rb = device::captureImageSweep(
        p.ipB, 4003, 0, 400, 20000, p.hard_timeout_ms, "",
        [&](const device::ImgFrame& im) { enqueue(1, im); }, &cancelFlag,
        [&](bool ok) { onReady(1, ok); });
  });
  tA.join();
  tB.join();
  cancelFlag = true;  // 确保状态线程退出（采集已结束）
  statusMon.join();
  // 采集结束→通知 worker 把剩余队列处理完再收工（保证不丢已采帧）。
  captureDone = true;
  qcv.notify_all();
  worker.join();

  {
    nlohmann::json j = {{"ev", "unit_done"}, {"a_frames", ctxA.seq}, {"a_det", ctxA.n_det},
                        {"b_frames", ctxB.seq}, {"b_det", ctxB.n_det},
                        {"a_sweep", ra.sweep_seen}, {"b_sweep", rb.sweep_seen}};
    emitText(out, outMu, 's', j.dump());
  }

  SiteMarkerConfig cfg;
  cfg.marker_len_m = p.marker_len_m;
  cfg.min_common = p.min_common;
  auto mwA = aggregateMarkerCorners(ctxA.obs, camA);
  auto mwB = aggregateMarkerCorners(ctxB.obs, camB);
  SiteMarkerResult res = solveSiteExtrinsic(mwA, mwB, cfg);
  if (res.ok) saveSiteExtrinsic(p.outJSON, res.b_to_a);

  nlohmann::json rj;
  rj["ok"] = res.ok;
  rj["n_common"] = res.n_common;
  rj["rms_m"] = res.rms_m;
  rj["msg"] = res.msg;
  rj["a_markers"] = mwA.size();
  rj["b_markers"] = mwB.size();
  nlohmann::json b2a = nlohmann::json::array();
  for (int r = 0; r < 4; ++r)
    for (int cc = 0; cc < 4; ++cc) b2a.push_back(res.b_to_a(r, cc));
  rj["b_to_a"] = b2a;
  emitText(out, outMu, 'r', rj.dump());
  return 0;
}

}  // namespace lidar

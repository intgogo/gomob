#include "lib/lidar_scan.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <condition_variable>
#include <exception>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "cloud/cloud_build.h"
#include "cloud/fusion.h"
#include "cloud/registration.h"
#include "cloud/types.h"
#include "config/calibration_json.h"
#include "config/config_yaml.h"
#include "device/scan_stream.h"
#include "device/stream_capture.h"

#ifdef LIDAR_SCAN_HAVE_TEXTURE
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "texture/colorizer.h"
#endif

namespace {

std::atomic<bool> g_cancel{false};

// 实时 RGB 预览回调（端侧小窗）：全局指针，setter 见文件末 extern "C" 区。
// "一次一个扫描会话"约束下不存在并发会话，扫前注册、扫后清空，无需加锁。
LidarImageCB g_preview_cb = nullptr;
void*        g_preview_user = nullptr;
constexpr int kPreviewWidth = 360;  // 小窗预览宽，省带宽（设备原图远大于此）

// 设备原始相机 JPEG → 解码 → 降采样到 kPreviewWidth 宽 → 重编码 JPEG，供端侧小窗预览。
// 无纹理构建（无 OpenCV）时返回空，预览自动跳过。
std::vector<unsigned char> previewJpeg(const std::vector<std::uint8_t>& src) {
#ifdef LIDAR_SCAN_HAVE_TEXTURE
    if (src.empty()) return {};
    cv::Mat enc(1, static_cast<int>(src.size()), CV_8UC1, const_cast<std::uint8_t*>(src.data()));
    cv::Mat img = cv::imdecode(enc, cv::IMREAD_COLOR);
    if (img.empty()) return {};
    if (img.cols > kPreviewWidth) {
        int th = static_cast<int>(std::lround(static_cast<double>(img.rows) * kPreviewWidth / img.cols));
        cv::resize(img, img, cv::Size(kPreviewWidth, std::max(1, th)), 0, 0, cv::INTER_AREA);
    }
    std::vector<unsigned char> out;
    std::vector<int> params{cv::IMWRITE_JPEG_QUALITY, 70};
    if (!cv::imencode(".jpg", img, out, params)) return {};
    return out;
#else
    (void)src;
    return {};
#endif
}

// 设备偶发吐出坐标爆表的无效返回（实测到 ~1e34 m / 1e37 mm）。Pico100 物理量程远 <50m，
// 任何超此或非有限(NaN/Inf)的点都是垃圾，必须在源头剔除：否则污染融合云、实时预览（端侧 autoFit
// 用全点算质心被拉飞）、以及测量。阈值 50m 远大于真实场景(~6m)，不会误杀有效点。
constexpr double kMaxRangeM = 50.0;
inline bool ptsPointValid(double x, double y, double z, double min_range_m = 0.0, double max_range_m = kMaxRangeM) {
    if (x == 0.0 && y == 0.0 && z == 0.0) return false;          // 设备无效点哨兵
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) return false;
    if (std::fabs(x) > kMaxRangeM || std::fabs(y) > kMaxRangeM || std::fabs(z) > kMaxRangeM) return false;
    const double r = std::sqrt(x * x + y * y + z * z);
    return r >= min_range_m && r <= max_range_m;
}

double envDoubleOrDefault(const char* key, double fallback) {
    const char* v = std::getenv(key);
    if (!v || !*v) return fallback;
    char* end = nullptr;
    const double parsed = std::strtod(v, &end);
    return (end != v && std::isfinite(parsed)) ? parsed : fallback;
}

// 处理一帧 PTS：把有效点累入 unit 云（米），并以 mm 回调流式推点。
void handlePts(const lidar::device::PtsFrame& p, int unit, lidar::CloudXYZ& cloud,
               LidarPointCB cb, void* user, double min_range_m = 0.0, double max_range_m = kMaxRangeM) {
    std::vector<float> mm;
    mm.reserve(p.points.size() * 3);
    for (const auto& q : p.points) {
        if (!ptsPointValid(q.x, q.y, q.z, min_range_m, max_range_m)) continue;  // 跳过无效/超距点
        cloud.points.emplace_back(static_cast<float>(q.x), static_cast<float>(q.y), static_cast<float>(q.z));
        mm.push_back(static_cast<float>(q.x * 1000.0));
        mm.push_back(static_cast<float>(q.y * 1000.0));
        mm.push_back(static_cast<float>(q.z * 1000.0));
    }
    cloud.width = static_cast<std::uint32_t>(cloud.points.size());
    cloud.height = 1;
    if (cb && !mm.empty())
        cb(user, unit, mm.data(), static_cast<int>(mm.size() / 3), static_cast<float>(p.h_angle_deg));
}

void setErr(LidarScanResult* out, const char* msg) {
    if (!out) return;
    std::snprintf(out->error, sizeof out->error, "%s", msg);
}

// 离线回放一个 .bin：走帧解析 → PTS → handlePts。返回 false=读失败。
bool replayBin(const char* path, int unit, lidar::CloudXYZ& cloud, LidarPointCB cb, void* user) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    std::vector<std::uint8_t> buf((std::istreambuf_iterator<char>(in)), {});
    std::size_t off = 0;
    while (off + 12 <= buf.size()) {
        if (g_cancel.load()) break;
        lidar::device::Frame fr;
        std::string err;
        std::size_t used = lidar::device::parseFrame(buf.data() + off, buf.size() - off, fr, err);
        if (used == 0) { if (!err.empty()) { ++off; continue; } break; }
        if (fr.type == lidar::device::MsgType::PTS && fr.crc_ok) {
            lidar::device::PtsFrame p;
            if (lidar::device::decodePTS(fr.payload, p)) handlePts(p, unit, cloud, cb, user);
        }
        off += used;
    }
    return true;
}

void logSweepResult(const char* tag, const lidar::device::SweepCaptureResult& r) {
    std::fprintf(stderr,
                 "[laser] %s connected=%d sweep_seen=%d frames=%zu crc_ok=%zu crc_bad=%zu bytes=%zu h_span=%.1f h_min=%.1f h_max=%.1f h_first=%.1f h_last=%.1f h_delta=%.1f final=%s error=%s\n",
                 tag, r.connected ? 1 : 0, r.sweep_seen ? 1 : 0, r.frames, r.crc_ok, r.crc_bad, r.bytes,
                 r.h_span_deg, r.h_min_deg, r.h_max_deg, r.h_first_raw_deg, r.h_last_raw_deg,
                 r.h_unwrapped_deg - r.h_first_raw_deg,
                 r.final_state.c_str(), r.error.c_str());
}

// 把一单元图像流存盘为 img_<NN>_h<度>.jpg（site-calib 自标定采图用；按航向命名供 CLI 解析）。
// dir 为空则不存。与纹理无关，始终编译。
void dumpImgFrames(const std::vector<lidar::device::ImgFrame>& imgs, const char* dir) {
    if (!dir || !*dir) return;
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    int n = 0;
    for (const auto& im : imgs) {
        if (im.jpeg.empty()) continue;
        char name[1024];
        std::snprintf(name, sizeof name, "%s/img_%03d_h%.2f.jpg", dir, n, im.h_angle_deg);
        std::ofstream f(name, std::ios::binary);
        if (f) { f.write(reinterpret_cast<const char*>(im.jpeg.data()), static_cast<std::streamsize>(im.jpeg.size())); ++n; }
    }
    std::fprintf(stderr, "[site-calib] dump %d 图 -> %s\n", n, dir);
}

#ifdef LIDAR_SCAN_HAVE_TEXTURE
std::string envOrDefault(const char* key, const char* fallback) {
    const char* v = std::getenv(key);
    return (v && *v) ? std::string(v) : std::string(fallback);
}

bool textureDisabledByEnv() {
    const char* v = std::getenv("GOMOB_LASER_TEXTURE");
    if (!v) return false;
    std::string s(v);
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s == "0" || s == "false" || s == "off" || s == "no";
}

std::vector<lidar::CameraFrame> decodeCameraFrames(const std::vector<lidar::device::ImgFrame>& images) {
    std::vector<lidar::CameraFrame> frames;
    frames.reserve(images.size());
    constexpr double kDegToRad = 3.14159265358979323846 / 180.0;
    for (const auto& im : images) {
        if (im.jpeg.empty()) continue;
        cv::Mat encoded(1, static_cast<int>(im.jpeg.size()), CV_8UC1, const_cast<std::uint8_t*>(im.jpeg.data()));
        cv::Mat bgr = cv::imdecode(encoded, cv::IMREAD_COLOR);
        if (!bgr.empty()) frames.push_back({bgr, im.h_angle_deg * kDegToRad});
    }
    return frames;
}

// 通用单元上色：cloud 在自身设备/世界系，images 为该单元相机 IMG 流，cfg/calib 为该单元相机标定。
// 颜色与刚体变换无关 → A(unit=0,世界系) / B(unit=1,B 系) 各自用各自相机上色后，融合时按点序拼装即一致。
void emitColorizedUnit(int unit, const lidar::CloudXYZ& cloud,
                       const std::vector<lidar::device::ImgFrame>& images,
                       LidarColorPointCB cb, void* user) {
    if (!cb) return;
    if (cloud.empty() || images.empty() || textureDisabledByEnv()) {
        std::fprintf(stderr, "[laser texture] unit=%d skip cloud=%zu images=%zu disabled=%d\n",
                     unit, cloud.size(), images.size(), textureDisabledByEnv() ? 1 : 0);
        return;
    }
    // 按单元取相机标定：unit0=A(.101)，unit1=B(.102)。缺省指向仓内标定资产(相对 laserworker
    // 工作目录=gomob 根，relaunch 脚本 cd 到此)；生产经 env 显式覆盖。
    const std::string cfgPath = (unit == 0)
        ? envOrDefault("GOMOB_LASER_TEXTURE_CONFIG_A", "server/native/lidar/calib/config_101_live.yaml")
        : envOrDefault("GOMOB_LASER_TEXTURE_CONFIG",   "server/native/lidar/calib/config_102_live.yaml");
    const std::string calibPath = (unit == 0)
        ? envOrDefault("GOMOB_LASER_TEXTURE_CALIB_A", "server/native/lidar/calib/calib_101.json")
        : envOrDefault("GOMOB_LASER_TEXTURE_CALIB",   "server/native/lidar/calib/calib_102.json");
    if (!std::filesystem::exists(cfgPath)) {
        std::fprintf(stderr, "[laser texture] unit=%d config missing: %s\n", unit, cfgPath.c_str());
        return;
    }

    try {
        auto frames = decodeCameraFrames(images);
        std::fprintf(stderr, "[laser texture] unit=%d images=%zu decoded=%zu cloud=%zu cfg=%s calib=%s\n",
                     unit, images.size(), frames.size(), cloud.size(), cfgPath.c_str(), calibPath.c_str());
        if (frames.empty()) return;
        lidar::CameraModel cam = lidar::CameraModel::fromConfig(lidar::loadConfig(cfgPath));
        if (std::filesystem::exists(calibPath)) {
            cam.applyCalibration(lidar::loadCalibrationJson(calibPath));
        }
        cam.image_width = frames.front().image_bgr.cols;
        cam.image_height = frames.front().image_bgr.rows;
        std::size_t mapped = 0;
        auto colored = lidar::colorize(cloud, frames, cam, &mapped);
        std::fprintf(stderr, "[laser texture] unit=%d mapped=%zu colored=%zu\n",
                     unit, mapped, colored ? colored->size() : 0);
        if (!colored || colored->empty()) return;
        std::vector<float> mm;
        std::vector<std::uint32_t> rgb;
        mm.reserve(colored->size() * 3);
        rgb.reserve(colored->size());
        for (const auto& q : colored->points) {
            mm.push_back(q.x * 1000.0f);
            mm.push_back(q.y * 1000.0f);
            mm.push_back(q.z * 1000.0f);
            rgb.push_back((static_cast<std::uint32_t>(q.r) << 16) |
                          (static_cast<std::uint32_t>(q.g) << 8) |
                          static_cast<std::uint32_t>(q.b));
        }
        cb(user, unit, mm.data(), rgb.data(), static_cast<int>(colored->size()), static_cast<int>(mapped));
    } catch (const std::exception& e) {
        std::fprintf(stderr, "[laser texture] unit=%d exception: %s\n", unit, e.what());
        return;
    } catch (...) {
        std::fprintf(stderr, "[laser texture] unit=%d unknown exception\n", unit);
        return;
    }
}
#else
void emitColorizedUnit(int, const lidar::CloudXYZ&, const std::vector<lidar::device::ImgFrame>&,
                       LidarColorPointCB, void*) {}
#endif

int finishRawOnly(lidar::CloudXYZ::Ptr A, lidar::CloudXYZ::Ptr B,
                  LidarStatusCB on_status, void* user, LidarScanResult* out) {
    if (out) {
        out->pts_a = static_cast<int>(A->size());
        out->pts_b = static_cast<int>(B->size());
        out->fused = 0;
        out->after_crop = 0;
        for (int i = 0; i < 16; ++i) out->b_to_a[i] = 0.0f;
        out->b_to_a[0] = 1.0f;
        out->b_to_a[5] = 1.0f;
        out->b_to_a[10] = 1.0f;
        out->b_to_a[15] = 1.0f;
        std::snprintf(out->align, sizeof out->align, "%s", "raw");
        out->error[0] = 0;
    }
    if (on_status) on_status(user, "done", static_cast<int>(A->size()), static_cast<int>(B->size()));
    return 0;
}

// 对齐 B→A（site/icp/none）→ union → 随机降采样 → 流式推融合云(mm) → 填 out。
int runFusion(lidar::CloudXYZ::Ptr A, lidar::CloudXYZ::Ptr B, const std::string& align,
              const char* site_json, float keep_ratio,
              LidarPointCB on_points, LidarStatusCB on_status, void* user, LidarScanResult* out) {
    if (align == "raw") return finishRawOnly(A, B, on_status, user, out);
    if (on_status) on_status(user, "fusing", static_cast<int>(A->size()), static_cast<int>(B->size()));

    Eigen::Matrix4d T = Eigen::Matrix4d::Identity();
    std::string used = "none";
    lidar::CloudXYZ::Ptr bAligned;
    if (align == "site" && site_json && lidar::loadSiteExtrinsic(site_json, T)) {
        // site 是服务端权威外参，native 只做确定性变换。最终精修由 Go 点到面算法完成；
        // 双单元常看见物体对立面，点到点 ICP 会产生表面厚度量级偏置，不能留在生产链。
        bAligned = lidar::applyTransform(*B, T);
        used = "site";
    } else if (align == "icp") {
        const auto r = lidar::registerTwoUnits(*B, *A);
        T = r.transform;
        bAligned = r.converged ? lidar::applyTransform(*B, T) : B;
        used = r.converged ? "icp" : "none";
    } else {
        bAligned = B;
    }

    auto fused = lidar::fuse({A, lidar::CloudXYZ::ConstPtr(bAligned)});
    if (keep_ratio < 1.0f) fused = lidar::randomKeep(*fused, keep_ratio);

    if (on_points && !fused->empty()) {
        std::vector<float> mm;
        mm.reserve(fused->size() * 3);
        for (const auto& q : fused->points) {
            mm.push_back(q.x * 1000.0f); mm.push_back(q.y * 1000.0f); mm.push_back(q.z * 1000.0f);
        }
        on_points(user, 2, mm.data(), static_cast<int>(fused->size()), 0.0f);
    }

    if (out) {
        out->pts_a = static_cast<int>(A->size());
        out->pts_b = static_cast<int>(B->size());
        out->fused = static_cast<int>(fused->size());
        out->after_crop = static_cast<int>(fused->size());
        for (int r = 0; r < 4; ++r)
            for (int c = 0; c < 4; ++c)
                out->b_to_a[r * 4 + c] =
                    static_cast<float>(T(r, c) * ((c == 3 && r < 3) ? 1000.0 : 1.0));  // 平移 m→mm
        std::snprintf(out->align, sizeof out->align, "%s", used.c_str());
        out->error[0] = 0;
    }
    if (on_status) on_status(user, "done", static_cast<int>(A->size()), static_cast<int>(B->size()));
    return 0;
}

}  // namespace

extern "C" {

int lidar_scan_replay_ex(const char* binA, const char* binB, const char* align, const char* site_json,
                         float keep_ratio, LidarPointCB on_points, LidarColorPointCB,
                         LidarStatusCB on_status, void* user, LidarScanResult* out) {
    g_cancel = false;
    if (out) std::memset(out, 0, sizeof *out);
    if (on_status) on_status(user, "scanning", 0, 0);
    auto A = std::make_shared<lidar::CloudXYZ>();
    auto B = std::make_shared<lidar::CloudXYZ>();
    if (!replayBin(binA, 0, *A, on_points, user)) { setErr(out, "read binA failed"); return 1; }
    if (!replayBin(binB, 1, *B, on_points, user)) { setErr(out, "read binB failed"); return 1; }
    if (g_cancel.load()) { setErr(out, "cancelled"); if (on_status) on_status(user, "cancelled", 0, 0); return 2; }
    return runFusion(A, B, align ? align : "icp", site_json, keep_ratio, on_points, on_status, user, out);
}

int lidar_scan_replay(const char* binA, const char* binB, const char* align, const char* site_json,
                      float keep_ratio, LidarPointCB on_points, LidarStatusCB on_status,
                      void* user, LidarScanResult* out) {
    return lidar_scan_replay_ex(binA, binB, align, site_json, keep_ratio, on_points, nullptr, on_status, user, out);
}

int lidar_scan_live_configured_ex(const char* ipA, const char* ipB, const char* align, const char* site_json,
                                  float keep_ratio, float expected_sweep_a_deg, float expected_sweep_b_deg,
                                  LidarPointCB on_points, LidarColorPointCB on_color_points,
                                  LidarStatusCB on_status, void* user, LidarScanResult* out) {
    g_cancel = false;
    if (out) std::memset(out, 0, sizeof *out);
    if (on_status) on_status(user, "connecting", 0, 0);
    // 需要开图像流的条件：要纹理上色(on_color_points) 或 要实时预览(g_preview_cb)。
    const bool wantImg = (on_color_points != nullptr) || (g_preview_cb != nullptr);
    auto A = std::make_shared<lidar::CloudXYZ>();
    auto B = std::make_shared<lidar::CloudXYZ>();
    lidar::device::SweepCaptureResult ra, rb;
    lidar::device::SweepCaptureResult riA, riB;
    std::mutex img_mu_a, img_mu_b;
    std::vector<lidar::device::ImgFrame> imagesA, imagesB;
    std::mutex ready_mu;
    std::condition_variable ready_cv;
    bool readyA = false, readyB = false, readyIA = !wantImg, readyIB = !wantImg;
    bool connA = false, connB = false, connIA = !wantImg, connIB = !wantImg;
    // 默认全量保留设备 4010 PTS 点云；现场要临时去背景时再用 env 收紧范围。
    const double ptsMinRangeM = std::max(0.0, envDoubleOrDefault("GOMOB_LASER_PTS_MIN_RANGE_M", 0.0));
    const double ptsMaxRangeM = std::max(ptsMinRangeM + 0.01, envDoubleOrDefault("GOMOB_LASER_PTS_MAX_RANGE_M", kMaxRangeM));
    std::fprintf(stderr, "[laser] pts range %.2f..%.2fm\n", ptsMinRangeM, ptsMaxRangeM);
    auto mark_ready = [&](char unit, bool connected) {
        {
            std::lock_guard<std::mutex> lk(ready_mu);
            if (unit == 'A') {
                readyA = true;
                connA = connected;
            } else if (unit == 'B') {
                readyB = true;
                connB = connected;
            } else if (unit == 'i') {  // A 站图像流
                readyIA = true;
                connIA = connected;
            } else {                   // B 站图像流
                readyIB = true;
                connIB = connected;
            }
        }
        ready_cv.notify_one();
    };
    // 每单元一线程；各自只写自己的云（无共享写）；on_points 可能被两线程并发调用，cb 须线程安全。
    std::thread ta([&] {
        ra = lidar::device::captureSweep(ipA, 4010, 0, 100, 8000, 360000, expected_sweep_a_deg, "",
              [&](const lidar::device::PtsFrame& p) { handlePts(p, 0, *A, on_points, user, ptsMinRangeM, ptsMaxRangeM); }, &g_cancel,
              [&](bool ok) { mark_ready('A', ok); });
    });
    std::thread tb([&] {
        rb = lidar::device::captureSweep(ipB, 4010, 0, 100, 8000, 360000, expected_sweep_b_deg, "",
              [&](const lidar::device::PtsFrame& p) { handlePts(p, 1, *B, on_points, user, ptsMinRangeM, ptsMaxRangeM); }, &g_cancel,
              [&](bool ok) { mark_ready('B', ok); });
    });
    std::thread tiA, tiB;
    if (wantImg) {
        tiA = std::thread([&] {
            riA = lidar::device::captureImageSweep(ipA, 4003, 0, 400, 8000, 360000, "",
                  [&](const lidar::device::ImgFrame& im) {
                      if (on_color_points) { std::lock_guard<std::mutex> lk(img_mu_a); imagesA.push_back(im); }
                      if (g_preview_cb) {
                          auto jp = previewJpeg(im.jpeg);
                          if (!jp.empty()) g_preview_cb(g_preview_user, 0, jp.data(), static_cast<int>(jp.size()), static_cast<float>(im.h_angle_deg));
                      }
                  }, &g_cancel, [&](bool ok) { mark_ready('i', ok); });
        });
        tiB = std::thread([&] {
            riB = lidar::device::captureImageSweep(ipB, 4003, 0, 400, 8000, 360000, "",
                  [&](const lidar::device::ImgFrame& im) {
                      if (on_color_points) { std::lock_guard<std::mutex> lk(img_mu_b); imagesB.push_back(im); }
                      if (g_preview_cb) {
                          auto jp = previewJpeg(im.jpeg);
                          if (!jp.empty()) g_preview_cb(g_preview_user, 1, jp.data(), static_cast<int>(jp.size()), static_cast<float>(im.h_angle_deg));
                      }
                  }, &g_cancel, [&](bool ok) { mark_ready('j', ok); });
        });
    }
    {
        std::unique_lock<std::mutex> lk(ready_mu);
        ready_cv.wait_for(lk, std::chrono::seconds(8), [&] { return readyA && readyB && readyIA && readyIB; });
    }
    if (!connA || !connB) {
        g_cancel = true;
        if (on_status) on_status(user, "error", 0, 0);
        ta.join(); tb.join();
        if (tiA.joinable()) tiA.join();
        if (tiB.joinable()) tiB.join();
        logSweepResult("unitA pts", ra);
        logSweepResult("unitB pts", rb);
        if (on_color_points) { logSweepResult("unitA img", riA); logSweepResult("unitB img", riB); }
        setErr(out, "connect failed");
        return 1;
    }
    if (on_color_points && !connIA) {
        std::fprintf(stderr, "[laser texture] unitA image stream connect failed before scan; continue without A RGB\n");
    }
    if (on_color_points && !connIB) {
        std::fprintf(stderr, "[laser texture] unitB image stream connect failed before scan; continue without B RGB\n");
    }
    if (on_status) on_status(user, "armed", 0, 0);
    if (on_status) on_status(user, "scanning", 0, 0);
    ta.join(); tb.join();
    if (tiA.joinable()) tiA.join();
    if (tiB.joinable()) tiB.join();

    logSweepResult("unitA pts", ra);
    logSweepResult("unitB pts", rb);
    if (on_color_points) { logSweepResult("unitA img", riA); logSweepResult("unitB img", riB); }

    if (g_cancel.load()) { setErr(out, "cancelled"); if (on_status) on_status(user, "cancelled", (int)A->size(), (int)B->size()); return 2; }
    if (!ra.connected || !rb.connected) { setErr(out, "connect failed"); if (on_status) on_status(user, "error", 0, 0); return 1; }
    if (!ra.sweep_seen || !rb.sweep_seen) { setErr(out, "sweep not observed"); if (on_status) on_status(user, "error", (int)A->size(), (int)B->size()); return 1; }
    if (ra.final_state == "ANGLE_INCOMPLETE" || rb.final_state == "ANGLE_INCOMPLETE") {
        char msg[128];
        std::snprintf(msg, sizeof msg, "sweep angle incomplete: A %.1f/%.1f deg, B %.1f/%.1f deg",
                      ra.h_span_deg, expected_sweep_a_deg, rb.h_span_deg, expected_sweep_b_deg);
        setErr(out, msg);
        if (on_status) on_status(user, "error", (int)A->size(), (int)B->size());
        return 1;
    }
    if (on_color_points) {
        // site-calib：若设了落盘目录，把两单元图像流存盘（按航向命名）供 ArUco 自标定 CLI 解析。
        const char* dumpA = std::getenv("GOMOB_LASER_MARKER_DUMP_A");
        const char* dumpB = std::getenv("GOMOB_LASER_MARKER_DUMP_B");
        if (riA.connected && riA.sweep_seen) {
            std::vector<lidar::device::ImgFrame> imgs;
            { std::lock_guard<std::mutex> lk(img_mu_a); imgs.swap(imagesA); }
            dumpImgFrames(imgs, dumpA);
            emitColorizedUnit(0, *A, imgs, on_color_points, user);  // 纹理关时内部跳过
        }
        if (riB.connected && riB.sweep_seen) {
            std::vector<lidar::device::ImgFrame> imgs;
            { std::lock_guard<std::mutex> lk(img_mu_b); imgs.swap(imagesB); }
            dumpImgFrames(imgs, dumpB);
            emitColorizedUnit(1, *B, imgs, on_color_points, user);
        }
    }
    return runFusion(A, B, align ? align : "icp", site_json, keep_ratio, on_points, on_status, user, out);
}

int lidar_scan_live_ex(const char* ipA, const char* ipB, const char* align, const char* site_json,
                       float keep_ratio, LidarPointCB on_points, LidarColorPointCB on_color_points,
                       LidarStatusCB on_status, void* user, LidarScanResult* out) {
    return lidar_scan_live_configured_ex(ipA, ipB, align, site_json, keep_ratio, 0.0f, 0.0f,
                                         on_points, on_color_points, on_status, user, out);
}

int lidar_scan_live(const char* ipA, const char* ipB, const char* align, const char* site_json,
                    float keep_ratio, LidarPointCB on_points, LidarStatusCB on_status,
                    void* user, LidarScanResult* out) {
    return lidar_scan_live_ex(ipA, ipB, align, site_json, keep_ratio, on_points, nullptr, on_status, user, out);
}

void lidar_scan_cancel(void) { g_cancel = true; }

void lidar_scan_set_preview_cb(LidarImageCB cb, void* user) {
    g_preview_cb = cb;
    g_preview_user = user;
}

}  // extern "C"

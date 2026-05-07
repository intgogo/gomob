// scan_quality runner — host 合成扫描序列 → 跑 native scanSession → 落 mesh + ground truth
//
// 命令行参数：
//   --out <dir>     输出目录（mesh.obj / cloud.ply / ground_truth.ply / stats.json 都落这里）
//   --voxel <mm>    TSDF voxel 边长（默认 4.0）
//   --extent <mm>   TSDF 网格边长（默认 200.0）
//   --frames <N>    模拟扫描帧数（默认 12）
//   --shape <name>  ground truth 形状：sphere（默认）
//   --radius <mm>   球半径（默认 60）

#include "reconstruction/icp.h"
#include "reconstruction/marching_cubes.h"
#include "reconstruction/mesh_export.h"
#include "reconstruction/tsdf.h"

#include <Eigen/Dense>
#include <Eigen/Geometry>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <random>
#include <string>
#include <vector>

namespace gomob::reconstruction {
struct ScanSession;
ScanSession* SessionCreate(float voxel_size_mm, float grid_extent_mm, float grid_center_z_mm);
int SessionIngest(ScanSession*,
                  const uint16_t* depth_mm, int width, int height,
                  double fx, double fy, double cx, double cy,
                  const float* pose7);
bool SessionFinalize(ScanSession*, const char* out_dir, int* out_stats3);
void SessionClose(ScanSession*);
}

namespace {

struct Args {
    std::string out_dir = ".dev/scan-quality/case";
    float voxel_mm = 4.0f;
    float extent_mm = 200.0f;
    int frames = 12;
    std::string shape = "sphere";
    float radius_mm = 60.0f;
};

Args ParseArgs(int argc, char** argv) {
    Args a;
    for (int i = 1; i < argc; ++i) {
        std::string s = argv[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= argc) { std::fprintf(stderr, "missing value after %s\n", s.c_str()); std::exit(2); }
            return argv[++i];
        };
        if (s == "--out") a.out_dir = next();
        else if (s == "--voxel") a.voxel_mm = std::stof(next());
        else if (s == "--extent") a.extent_mm = std::stof(next());
        else if (s == "--frames") a.frames = std::stoi(next());
        else if (s == "--shape") a.shape = next();
        else if (s == "--radius") a.radius_mm = std::stof(next());
        else { std::fprintf(stderr, "unknown arg: %s\n", s.c_str()); std::exit(2); }
    }
    return a;
}

std::vector<uint16_t> SynthSphereDepth(
        int W, int H, double fx, double fy, double cx, double cy,
        const float* pose7, float R) {
    Eigen::Vector3f t(pose7[0], pose7[1], pose7[2]);
    Eigen::Quaternionf q(pose7[6], pose7[3], pose7[4], pose7[5]);
    q.normalize();
    Eigen::Matrix3f R_cw = q.toRotationMatrix();
    std::vector<uint16_t> depth(W * H, 0);
    for (int v = 0; v < H; ++v)
    for (int u = 0; u < W; ++u) {
        float dx = static_cast<float>((u - cx) / fx);
        float dy = static_cast<float>((v - cy) / fy);
        Eigen::Vector3f dir_cam(dx, dy, 1.0f);
        dir_cam /= dir_cam.norm();
        Eigen::Vector3f dir_w = R_cw * dir_cam;
        float b = 2.f * t.dot(dir_w);
        float c = t.squaredNorm() - R * R;
        float disc = b * b - 4.f * c;
        if (disc < 0) continue;
        float t1 = (-b - std::sqrt(disc)) * 0.5f;
        if (t1 <= 0) continue;
        Eigen::Vector3f Pw = t + t1 * dir_w;
        Eigen::Vector3f Pc = R_cw.transpose() * (Pw - t);
        if (Pc.z() <= 0) continue;
        depth[v * W + u] = static_cast<uint16_t>(std::min(65500.f, Pc.z()));
    }
    return depth;
}

// 写球面均匀采样的 ground truth 点云
void WriteGroundTruthSphere(const std::string& path, float R, int n_points) {
    std::ofstream f(path, std::ios::binary | std::ios::out | std::ios::trunc);
    if (!f.is_open()) return;
    // PLY ASCII（简单）
    f << "ply\nformat ascii 1.0\nelement vertex " << n_points << "\n"
      << "property float x\nproperty float y\nproperty float z\nend_header\n";
    std::mt19937 rng(123);
    std::uniform_real_distribution<float> u(-1.f, 1.f);
    int written = 0;
    while (written < n_points) {
        float x = u(rng), y = u(rng), z = u(rng);
        float n = std::sqrt(x*x + y*y + z*z);
        if (n > 1.f || n < 1e-3f) continue;
        x = x / n * R; y = y / n * R; z = z / n * R;
        f << x << " " << y << " " << z << "\n";
        written++;
    }
}

void WriteJson(const std::string& path,
               const Args& a, int v_count, int f_count, int kf_count,
               long long duration_ms) {
    std::ofstream f(path);
    f << "{\n"
      << "  \"shape\": \"" << a.shape << "\",\n"
      << "  \"radius_mm\": " << a.radius_mm << ",\n"
      << "  \"voxel_mm\": " << a.voxel_mm << ",\n"
      << "  \"extent_mm\": " << a.extent_mm << ",\n"
      << "  \"frames\": " << a.frames << ",\n"
      << "  \"vertices\": " << v_count << ",\n"
      << "  \"triangles\": " << f_count << ",\n"
      << "  \"keyframes\": " << kf_count << ",\n"
      << "  \"duration_ms\": " << duration_ms << "\n"
      << "}\n";
}

} // anonymous

int main(int argc, char** argv) {
    Args a = ParseArgs(argc, argv);
    using namespace gomob::reconstruction;

    std::filesystem::create_directories(a.out_dir);

    auto t_start = std::chrono::steady_clock::now();

    // harness 物体放在原点 → grid_center_z=0
    ScanSession* s = SessionCreate(a.voxel_mm, a.extent_mm, /*grid_center_z_mm=*/0.0f);
    const int W = 240, H = 240;
    const double fx = 200.0, fy = 200.0, cx = 120.0, cy = 120.0;
    const float cam_dist = a.radius_mm * 4.2f; // 视野 + 距离合理

    for (int i = 0; i < a.frames; ++i) {
        float angle = i * (2.f * float(M_PI) / a.frames);
        Eigen::Vector3f cam_pos(std::sin(angle) * cam_dist, 0, std::cos(angle) * cam_dist);
        Eigen::Vector3f z_axis = (-cam_pos).normalized();
        Eigen::Vector3f y_axis(0, 1, 0);
        Eigen::Vector3f x_axis = y_axis.cross(z_axis).normalized();
        y_axis = z_axis.cross(x_axis);
        Eigen::Matrix3f R_cw;
        R_cw.col(0) = x_axis;
        R_cw.col(1) = y_axis;
        R_cw.col(2) = z_axis;
        Eigen::Quaternionf q(R_cw); q.normalize();
        float pose[7] = {cam_pos.x(), cam_pos.y(), cam_pos.z(),
                         q.x(), q.y(), q.z(), q.w()};
        auto depth = SynthSphereDepth(W, H, fx, fy, cx, cy, pose, a.radius_mm);
        SessionIngest(s, depth.data(), W, H, fx, fy, cx, cy, pose);
    }

    int stats[3] = {0, 0, 0};
    bool ok = SessionFinalize(s, a.out_dir.c_str(), stats);
    SessionClose(s);

    auto t_end = std::chrono::steady_clock::now();
    long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count();

    if (!ok) {
        std::fprintf(stderr, "finalize failed\n");
        return 1;
    }

    WriteGroundTruthSphere(a.out_dir + "/ground_truth.ply", a.radius_mm, /*n=*/8000);
    WriteJson(a.out_dir + "/stats.json", a, stats[0], stats[1], stats[2], ms);

    std::printf("scan_quality runner done: out=%s vertices=%d triangles=%d kf=%d duration=%lldms\n",
                a.out_dir.c_str(), stats[0], stats[1], stats[2], ms);
    return 0;
}

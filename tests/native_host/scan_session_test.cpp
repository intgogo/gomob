// scanSession host 端到端测试 — 模拟"用户绕物体转一圈"扫描
//
// 合成场景：
//   - 物体：原点球 R=60mm
//   - 相机：距原点 250mm，绕 Y 轴转 12 个视角（每 30°），始终看向原点
//   - 每个视角：ray-sphere 交点 → depth 图（480×480, fx=fy=400, cx=cy=240）
//   - 喂 SessionIngest（pose7 给精确相机姿态）→ SessionFinalize
//
// 验收：
//   - mesh 顶点数 > 0
//   - 顶点距球心平均距离 ≈ R=60mm（容差 5mm，因为 voxel 量化 + 多视角积分误差）
//   - PLY/OBJ 文件落到磁盘 + 文件非空

#include "reconstruction/icp.h"
#include "reconstruction/marching_cubes.h"
#include "reconstruction/mesh_export.h"
#include "reconstruction/tsdf.h"

#include <Eigen/Dense>
#include <Eigen/Geometry>

#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <vector>

namespace gomob::reconstruction {
struct ScanSession;
ScanSession* SessionCreate(float voxel_size_mm, float grid_extent_mm, float grid_center_z_mm);
int SessionIngest(ScanSession*,
                  const uint16_t* depth_mm, int width, int height,
                  double fx, double fy, double cx, double cy,
                  const float* pose7, const uint8_t* conf = nullptr);
bool SessionFinalize(ScanSession*, const char* out_dir, int* out_stats3);
void SessionClose(ScanSession*);
}

namespace {

bool CheckRange(const char* tag, float got, float lo, float hi) {
    bool ok = got >= lo && got <= hi;
    std::printf("  %-40s got=%.4f range=[%.4f,%.4f] -> %s\n",
                tag, got, lo, hi, ok ? "OK" : "FAIL");
    return ok;
}

// 合成一帧 ray-sphere 的 depth 图
// pose7 = [tx,ty,tz, qx,qy,qz,qw]，含义"相机在世界系的位姿"
// camera ray (camera frame): direction = ((u-cx)/fx, (v-cy)/fy, 1) 归一化，origin = (0,0,0) 相机系
// 转世界系：origin = t_cw, dir = R_cw * dir_cam
std::vector<uint16_t> SynthesizeSphereDepth(
        int width, int height, double fx, double fy, double cx, double cy,
        const float* pose7, float sphere_R) {
    Eigen::Vector3f t_cw(pose7[0], pose7[1], pose7[2]);
    Eigen::Quaternionf q(pose7[6], pose7[3], pose7[4], pose7[5]);
    q.normalize();
    Eigen::Matrix3f R_cw = q.toRotationMatrix();

    std::vector<uint16_t> depth(width * height, 0);

    for (int v = 0; v < height; ++v) {
        for (int u = 0; u < width; ++u) {
            float dx = static_cast<float>((u - cx) / fx);
            float dy = static_cast<float>((v - cy) / fy);
            Eigen::Vector3f dir_cam(dx, dy, 1.0f);
            float n = dir_cam.norm();
            dir_cam /= n;
            Eigen::Vector3f dir_w = R_cw * dir_cam;

            // ray-sphere intersection: |t_cw + t * dir_w|^2 = R^2
            // t^2 + 2 (t_cw · dir_w) t + (|t_cw|^2 - R^2) = 0
            float b = 2.f * t_cw.dot(dir_w);
            float c = t_cw.squaredNorm() - sphere_R * sphere_R;
            float disc = b * b - 4.f * c;
            if (disc < 0) continue;
            float sd = std::sqrt(disc);
            float t1 = (-b - sd) * 0.5f;
            if (t1 <= 0) continue; // 球在背后
            // 世界交点
            Eigen::Vector3f Pw = t_cw + t1 * dir_w;
            // 转回相机系，z = depth_mm
            Eigen::Vector3f Pc = R_cw.transpose() * (Pw - t_cw);
            float z = Pc.z();
            if (z <= 0) continue;
            depth[v * width + u] = static_cast<uint16_t>(std::min(65500.f, z));
        }
    }
    return depth;
}

int TestEndToEndSphere() {
    std::printf("[scan_session_e2e_sphere]\n");
    using namespace gomob::reconstruction;

    // 输出目录
    std::filesystem::path out = ".dev/native-host/scan_e2e";
    std::filesystem::create_directories(out);

    // SessionCreate：物体半径 60mm → grid_extent 200mm 充足，voxel 4mm 求精度速度平衡
    // 测试中物体在原点 → grid_center_z=0
    ScanSession* s = SessionCreate(/*voxel_size_mm=*/4.0f, /*grid_extent_mm=*/200.0f, /*grid_center_z_mm=*/0.0f);

    const int W = 240, H = 240;          // 减小帧分辨率加速
    const double fx = 200.0, fy = 200.0; // 视场半角 ~31°
    const double cx = 120.0, cy = 120.0;
    const float sphere_R = 60.0f;
    const float cam_dist = 250.0f;
    const int n_views = 12;

    int total_keyframes = 0;
    for (int i = 0; i < n_views; ++i) {
        float angle = i * (2.f * float(M_PI) / n_views);
        // 相机绕 Y 轴
        Eigen::Vector3f cam_pos(std::sin(angle) * cam_dist, 0, std::cos(angle) * cam_dist);
        // 相机看向原点：z 轴 = (origin - cam_pos)/||·||；y 轴 = world up = (0, 1, 0)；x 轴 = z × y 反向
        Eigen::Vector3f z_axis = (-cam_pos).normalized();
        Eigen::Vector3f y_axis(0, 1, 0);
        Eigen::Vector3f x_axis = y_axis.cross(z_axis).normalized();
        y_axis = z_axis.cross(x_axis);
        Eigen::Matrix3f R_cw;
        R_cw.col(0) = x_axis;
        R_cw.col(1) = y_axis;
        R_cw.col(2) = z_axis;
        Eigen::Quaternionf q(R_cw);
        q.normalize();
        float pose[7] = {cam_pos.x(), cam_pos.y(), cam_pos.z(),
                         q.x(), q.y(), q.z(), q.w()};

        auto depth = SynthesizeSphereDepth(W, H, fx, fy, cx, cy, pose, sphere_R);

        int kfs = SessionIngest(s, depth.data(), W, H, fx, fy, cx, cy, pose);
        total_keyframes = kfs;
    }
    std::printf("  ingested %d frames, keyframes=%d\n", n_views, total_keyframes);

    int stats[3] = {0, 0, 0};
    bool ok_finalize = SessionFinalize(s, out.string().c_str(), stats);
    SessionClose(s);

    std::printf("  finalize ok=%d vertex=%d triangles=%d keyframes=%d\n",
                ok_finalize, stats[0], stats[1], stats[2]);

    if (!ok_finalize || stats[1] == 0) {
        std::printf("  finalize failed or zero triangles -> FAIL\n");
        return 1;
    }

    // 读 OBJ 拿顶点验证球面拟合
    std::ifstream fin((out / "mesh.obj").string());
    if (!fin.is_open()) { std::printf("  cannot open mesh.obj -> FAIL\n"); return 1; }
    float min_d = 1e9f, max_d = 0.f, sum = 0.f;
    int n_v = 0;
    std::string line;
    while (std::getline(fin, line)) {
        if (line.size() < 2 || line[0] != 'v' || line[1] != ' ') continue;
        float x, y, z;
        if (std::sscanf(line.c_str(), "v %f %f %f", &x, &y, &z) == 3) {
            float d = std::sqrt(x*x + y*y + z*z);
            sum += d;
            if (d < min_d) min_d = d;
            if (d > max_d) max_d = d;
            n_v++;
        }
    }
    float mean_d = sum / std::max(n_v, 1);
    std::printf("  obj vertex parsed=%d min=%.2f mean=%.2f max=%.2f\n",
                n_v, min_d, mean_d, max_d);

    bool ok = true;
    ok &= CheckRange("obj vertex count",        static_cast<float>(n_v), 100.0f, 200000.0f);
    ok &= CheckRange("mean dist to origin",     mean_d, sphere_R - 5.0f, sphere_R + 5.0f);

    // 文件大小检查
    auto ply_size = std::filesystem::file_size(out / "cloud.ply");
    auto obj_size = std::filesystem::file_size(out / "mesh.obj");
    std::printf("  cloud.ply=%lluB mesh.obj=%lluB\n",
                (unsigned long long)ply_size, (unsigned long long)obj_size);
    ok &= CheckRange("cloud.ply size kb",       ply_size / 1024.f, 1.f, 50000.f);
    ok &= CheckRange("mesh.obj size kb",        obj_size / 1024.f, 1.f, 50000.f);

    return ok ? 0 : 1;
}

} // anonymous

int main() {
    int fails = TestEndToEndSphere();
    std::printf("\n[scan_session_test summary] failures=%d\n", fails);
    return fails;
}

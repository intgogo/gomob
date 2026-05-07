// reconstruction/scan_session.cpp — 三维外廓扫描会话主流程
//
// 业务定位（详见 docs/architecture/04-reconstruction-pipeline.md §2）：
//
//   SessionCreate    → 分配 TSDF voxel + 关键帧 graph + ICP 初值
//   SessionIngest    → 一帧深度进来：
//                      1) depth → 点云（depth_projection）
//                      2) ICP 把当前点云对齐到 reference_cloud，初值 = user 给的 pose7 或 last_pose
//                      3) TSDF 用对齐后的位姿积分这一帧
//                      4) 每 N 帧（默认 5）更新 reference_cloud + 入 keyframe_poses
//   SessionFinalize  → Marching Cubes 提 mesh + 写 PLY/OBJ；返回 [vertex, face, keyframe]
//   SessionClose     → 释放
//
// 第一性：不接受 stub。ICP / TSDF / MC 三件套的真实实施在 icp.cpp / tsdf.cpp / marching_cubes.cpp，
// 本文件只串管线。

#include "icp.h"
#include "marching_cubes.h"
#include "mesh_export.h"
#include "tsdf.h"

#include <Eigen/Dense>
#include <Eigen/Geometry>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace gomob {
namespace depth {
extern std::vector<float> ProjectToPointCloud(
    const int16_t* depth, int width, int height,
    double fx, double fy, double cx, double cy);
} // namespace depth

namespace reconstruction {

struct ScanSession {
    TsdfConfig tsdf_cfg;
    TsdfVolume tsdf;
    // 关键帧参考点云（**世界系**）— ICP 的 dst 必须与 src+init pose 同系（世界系）
    std::vector<float> reference_cloud_world;
    std::array<float, 7> last_pose;                // 上一帧位姿（ICP 初值）
    std::vector<std::array<float, 7>> keyframe_poses;
    int keyframe_interval = 5;
    int keyframe_count = 0;
    int total_frame_count = 0;

    explicit ScanSession(const TsdfConfig& c) : tsdf_cfg(c), tsdf(c) {
        last_pose = {0, 0, 0, 0, 0, 0, 1};
    }
};

// 用 pose7 把相机系点云变到世界系：P_w = R * P_c + t
static std::vector<float> TransformCloud(const std::vector<float>& cam_cloud,
                                         const std::array<float, 7>& pose) {
    Eigen::Vector3f t(pose[0], pose[1], pose[2]);
    Eigen::Quaternionf q(pose[6], pose[3], pose[4], pose[5]);
    q.normalize();
    Eigen::Matrix3f R = q.toRotationMatrix();
    std::vector<float> out(cam_cloud.size());
    for (std::size_t i = 0; i < cam_cloud.size() / 3; ++i) {
        const float* p = cam_cloud.data() + i * 3;
        // 跳过 (0,0,0) 占位（depthToPointCloud 的无效深度像素）
        if (p[0] == 0.f && p[1] == 0.f && p[2] == 0.f) {
            out[i*3] = 0; out[i*3+1] = 0; out[i*3+2] = 0;
            continue;
        }
        Eigen::Vector3f pw = R * Eigen::Vector3f(p[0], p[1], p[2]) + t;
        out[i*3] = pw.x();
        out[i*3+1] = pw.y();
        out[i*3+2] = pw.z();
    }
    return out;
}

ScanSession* SessionCreate(float voxel_size_mm, float grid_extent_mm) {
    TsdfConfig cfg;
    cfg.voxel_size_mm = voxel_size_mm > 0.5f ? voxel_size_mm : 2.0f;
    cfg.grid_extent_mm = grid_extent_mm > 50.f ? grid_extent_mm : 400.0f;
    cfg.truncation_dist_mm = std::max(4.0f * cfg.voxel_size_mm, 8.0f);
    cfg.weight_clamp = 100.0f;
    float half = cfg.grid_extent_mm * 0.5f;
    cfg.grid_origin_mm = {-half, -half, -half};
    return new ScanSession(cfg);
}

int SessionIngest(ScanSession* s,
                  const uint16_t* depth_mm, int width, int height,
                  double fx, double fy, double cx, double cy,
                  const float* pose7) {
    if (!s) return -1;
    s->total_frame_count++;

    // 1) depth → 点云（相机系，mm）
    // 依赖 depth/depth_projection.cpp 的 int16_t 接口；reinterpret 是合法的（同 size）
    auto cloud = gomob::depth::ProjectToPointCloud(
        reinterpret_cast<const int16_t*>(depth_mm), width, height, fx, fy, cx, cy);

    // 2) 第一帧：把 user 的 pose7 当世界系起点（一般是 identity，但允许外部 IMU 给定）
    if (s->reference_cloud_world.empty()) {
        std::array<float, 7> p0;
        for (int i = 0; i < 7; ++i) p0[i] = pose7[i];
        s->reference_cloud_world = TransformCloud(cloud, p0);
        s->tsdf.Integrate(depth_mm, width, height, fx, fy, cx, cy, p0.data());
        s->last_pose = p0;
        s->keyframe_poses.push_back(p0);
        s->keyframe_count = 1;
        return s->keyframe_count;
    }

    // 3) ICP：当前 cloud（相机系）→ reference_cloud_world（世界系），初值优先 user pose7
    std::array<float, 7> init = s->last_pose;
    bool user_pose_meaningful = (pose7[0] != 0.f || pose7[1] != 0.f || pose7[2] != 0.f ||
                                  pose7[3] != 0.f || pose7[4] != 0.f || pose7[5] != 0.f ||
                                  pose7[6] != 1.f);
    if (user_pose_meaningful) {
        for (int i = 0; i < 7; ++i) init[i] = pose7[i];
    }
    auto icp_result = IcpRegister(
        cloud.data(), cloud.size() / 3,
        s->reference_cloud_world.data(), s->reference_cloud_world.size() / 3,
        init.data());

    if (icp_result.status == IcpResultStatus::DegenerateInput) {
        return s->keyframe_count;
    }

    // 4) TSDF 积分（用 ICP 输出位姿）
    s->tsdf.Integrate(depth_mm, width, height, fx, fy, cx, cy, icp_result.pose7.data());
    s->last_pose = icp_result.pose7;

    // 5) 关键帧判定：每 N 帧更新参考点云（世界系） + 入轨迹
    if (s->total_frame_count % s->keyframe_interval == 0) {
        s->reference_cloud_world = TransformCloud(cloud, s->last_pose);
        s->keyframe_poses.push_back(s->last_pose);
        s->keyframe_count++;
    }
    return s->keyframe_count;
}

bool SessionFinalize(ScanSession* s, const char* out_dir, int* out_stats3) {
    if (!s) return false;
    // 1) Marching Cubes 提 mesh
    Mesh mesh = ExtractMesh(s->tsdf);

    // 2) 写出
    std::string dir = out_dir ? std::string(out_dir) : std::string(".");
    if (!dir.empty() && dir.back() != '/') dir.push_back('/');
    long long ply_bytes = WritePly(dir + "cloud.ply", mesh.vertices,
                                   mesh.normals.empty() ? nullptr : &mesh.normals,
                                   /*rgb=*/nullptr,
                                   mesh.indices.empty() ? nullptr : &mesh.indices);
    long long obj_bytes = WriteObj(dir + "mesh.obj", mesh);

    if (out_stats3) {
        out_stats3[0] = static_cast<int>(mesh.vertex_count());
        out_stats3[1] = static_cast<int>(mesh.triangle_count());
        out_stats3[2] = s->keyframe_count;
    }
    // 即便 0 face 也算 finalize 成功（用户的扫描可能就是空场景）
    return ply_bytes >= 0 && obj_bytes >= 0;
}

void SessionClose(ScanSession* s) { delete s; }

} // namespace reconstruction
} // namespace gomob

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

/**
 * @param grid_center_z_mm grid 沿世界 z 轴的中心偏移：相机系朝 +z，手持物体一般在 +z 方向
 *        25–80cm 处；grid 中心放在 (0, 0, grid_center_z_mm) 让物体落进 grid 内。
 *        用户手持扫描默认 400mm（40cm 中心，覆盖 [center-extent/2, center+extent/2]）。
 *        若上层用 IMU/外部 SLAM 给精确世界系 pose，可传 0.0 让 grid 中心在原点。
 */
ScanSession* SessionCreate(float voxel_size_mm, float grid_extent_mm, float grid_center_z_mm) {
    TsdfConfig cfg;
    cfg.voxel_size_mm = voxel_size_mm > 0.5f ? voxel_size_mm : 4.0f;
    cfg.grid_extent_mm = grid_extent_mm > 50.f ? grid_extent_mm : 600.0f;
    cfg.truncation_dist_mm = std::max(4.0f * cfg.voxel_size_mm, 8.0f);
    cfg.weight_clamp = 100.0f;
    float half = cfg.grid_extent_mm * 0.5f;
    cfg.grid_origin_mm = {-half, -half, grid_center_z_mm - half};
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

/**
 * 扫描中实时预览：从 TSDF 体素里挑近表面（|sdf| 小 + weight ≥ 1）的体素中心，扁平输出 ≤ max_vertices
 * 个 (x,y,z) — UI 端转 2D 投影画给用户看，作扫描进度可视化。
 *
 * 不跑 Marching Tetrahedra（那个跑一次几百毫秒到秒级，影响 ingest 实时性）；直接遍历 voxel grid 子采样。
 */
std::vector<float> SessionPeekVertices(ScanSession* s, int max_vertices) {
    std::vector<float> out;
    if (!s || max_vertices <= 0) return out;
    const auto& tsdf = s->tsdf;
    int N = tsdf.dim();
    long long total = static_cast<long long>(N) * N * N;
    // 子采样 stride：让最坏情况下扫到的体素 ≈ max_vertices × 8（先收，后裁）
    int stride = std::max(1, static_cast<int>(total / (static_cast<long long>(max_vertices) * 8)));
    out.reserve(static_cast<size_t>(max_vertices) * 3);
    long long counter = 0;
    for (int k = 0; k < N; ++k) {
        for (int j = 0; j < N; ++j) {
            for (int i = 0; i < N; ++i) {
                if (counter++ % stride != 0) continue;
                float sdf, w;
                tsdf.Get(i, j, k, sdf, w);
                if (w < 1.f) continue;
                if (std::abs(sdf) > 0.6f) continue;  // 近零等值面（归一化到 ±1）— 放宽阈值便于早期帧能 peek 出点
                auto c = tsdf.VoxelCenter(i, j, k);
                out.push_back(c[0]);
                out.push_back(c[1]);
                out.push_back(c[2]);
                if (static_cast<int>(out.size() / 3) >= max_vertices) return out;
            }
        }
    }
    return out;
}

} // namespace reconstruction
} // namespace gomob

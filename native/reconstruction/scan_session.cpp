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

// Android logcat 直通；host 单测时这俩头不存在 → 退化成 printf
#if defined(__ANDROID__)
#  include <android/log.h>
#  define GOMOB_LOG_TAG "gomob_native"
#  define GOMOB_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  GOMOB_LOG_TAG, __VA_ARGS__)
#  define GOMOB_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  GOMOB_LOG_TAG, __VA_ARGS__)
#else
#  define GOMOB_LOGI(...) std::fprintf(stderr, "[I] " __VA_ARGS__); std::fprintf(stderr, "\n")
#  define GOMOB_LOGW(...) std::fprintf(stderr, "[W] " __VA_ARGS__); std::fprintf(stderr, "\n")
#endif

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
        int updated = s->tsdf.Integrate(depth_mm, width, height, fx, fy, cx, cy, p0.data());
        s->last_pose = p0;
        s->keyframe_poses.push_back(p0);
        s->keyframe_count = 1;
        // 第一帧诊断：把内参 / pose / cloud / 积分体素数 / depth 直方图全打出来
        int valid_cloud = 0;
        for (std::size_t i = 0; i < cloud.size() / 3; ++i) {
            const float* p = cloud.data() + i * 3;
            if (p[0] != 0.f || p[1] != 0.f || p[2] != 0.f) ++valid_cloud;
        }
        // depth 直方图 — 让 grid 覆盖范围 vs 实际物体距离的失配可立即被看到
        uint32_t d_count = 0, d_min = 65535, d_max = 0;
        uint64_t d_sum = 0;
        // 5 个直方桶 [0,200) [200,500) [500,1000) [1000,2000) [2000,...)
        int d_buckets[5] = {0, 0, 0, 0, 0};
        for (int idx = 0; idx < width * height; ++idx) {
            uint16_t d = depth_mm[idx];
            if (d == 0) continue;
            ++d_count;
            if (d < d_min) d_min = d;
            if (d > d_max) d_max = d;
            d_sum += d;
            if      (d < 200)  ++d_buckets[0];
            else if (d < 500)  ++d_buckets[1];
            else if (d < 1000) ++d_buckets[2];
            else if (d < 2000) ++d_buckets[3];
            else               ++d_buckets[4];
        }
        uint32_t d_avg = d_count > 0 ? static_cast<uint32_t>(d_sum / d_count) : 0;
        GOMOB_LOGI("Ingest[1st] %dx%d fx=%.2f fy=%.2f cx=%.2f cy=%.2f pose=(%.1f,%.1f,%.1f)+q "
                   "cloud=%zu valid=%d voxelsUpdated=%d gridDim=%d origin=(%.0f,%.0f,%.0f) "
                   "extent=%.0fmm",
                   width, height, fx, fy, cx, cy, pose7[0], pose7[1], pose7[2],
                   cloud.size() / 3, valid_cloud, updated, s->tsdf.dim(),
                   s->tsdf.config().grid_origin_mm[0], s->tsdf.config().grid_origin_mm[1],
                   s->tsdf.config().grid_origin_mm[2], s->tsdf.config().grid_extent_mm);
        GOMOB_LOGI("Depth hist mm: valid=%u min=%u max=%u avg=%u "
                   "[<200=%d, 200-500=%d, 500-1000=%d, 1000-2000=%d, >2000=%d]",
                   d_count, d_min, d_max, d_avg,
                   d_buckets[0], d_buckets[1], d_buckets[2], d_buckets[3], d_buckets[4]);
        // grid 与 avg depth 失配告警 — 物体不在 grid 覆盖区间内必出 nearSurf=0
        float gz_lo = s->tsdf.config().grid_origin_mm[2];
        float gz_hi = gz_lo + s->tsdf.config().grid_extent_mm;
        if (d_count > 0 && (d_avg < gz_lo || d_avg > gz_hi)) {
            GOMOB_LOGW("Ingest[1st] WARN avg depth %umm 不在 grid z[%.0f, %.0f]mm 内 — peek nearSurf 一定是 0；"
                       "需扩 gridExtent 或调 gridCenterZ 让 grid 覆盖物体距离",
                       d_avg, gz_lo, gz_hi);
        }
        if (valid_cloud == 0) {
            GOMOB_LOGW("Ingest[1st] WARN cloud 全 0 — 检查 depth 是否全 0 (sdk 流没上来) 或 fx/fy/cx/cy 非法");
        }
        if (updated == 0) {
            GOMOB_LOGW("Ingest[1st] WARN voxelsUpdated=0 — 物体可能不在 grid 内 (检查 gridCenterZMm 与实际工作距离匹配)");
        }
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
    int updated = s->tsdf.Integrate(depth_mm, width, height, fx, fy, cx, cy, icp_result.pose7.data());
    s->last_pose = icp_result.pose7;
    // 每 10 帧 log 一次，避免刷屏；第 1 帧已 log 过初次诊断
    if (s->total_frame_count % 10 == 0) {
        GOMOB_LOGI("Ingest[#%d] icp.iter=%d pairs=%d err=%.2fmm pose=(%.1f,%.1f,%.1f) voxelsUpdated=%d",
                   s->total_frame_count, icp_result.iterations, icp_result.pair_count_final,
                   icp_result.mean_error_mm, s->last_pose[0], s->last_pose[1], s->last_pose[2],
                   updated);
    }

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
 *
 * 诊断：每次输出统计到 logcat（gomob_native）— 让上层知道 TSDF 积分到底是不是在工作。
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
    long long sampled = 0;
    long long w_pos = 0;          // weight ≥ 1 的体素数（被任何一帧观测到）
    long long near_surface = 0;   // weight ≥ 1 且 |sdf|<0.6 的体素数（近零等值面）
    for (int k = 0; k < N; ++k) {
        for (int j = 0; j < N; ++j) {
            for (int i = 0; i < N; ++i) {
                if (counter++ % stride != 0) continue;
                ++sampled;
                float sdf, w;
                tsdf.Get(i, j, k, sdf, w);
                if (w < 1.f) continue;
                ++w_pos;
                if (std::abs(sdf) > 0.6f) continue;
                ++near_surface;
                auto c = tsdf.VoxelCenter(i, j, k);
                out.push_back(c[0]);
                out.push_back(c[1]);
                out.push_back(c[2]);
                if (static_cast<int>(out.size() / 3) >= max_vertices) {
                    GOMOB_LOGI("Peek (capped) frames=%d N=%d stride=%d sampled=%lld w>=1=%lld nearSurf=%lld out=%d",
                               s->total_frame_count, N, stride, sampled, w_pos, near_surface,
                               static_cast<int>(out.size() / 3));
                    return out;
                }
            }
        }
    }
    GOMOB_LOGI("Peek frames=%d N=%d stride=%d sampled=%lld w>=1=%lld nearSurf=%lld out=%d",
               s->total_frame_count, N, stride, sampled, w_pos, near_surface,
               static_cast<int>(out.size() / 3));
    return out;
}

} // namespace reconstruction
} // namespace gomob

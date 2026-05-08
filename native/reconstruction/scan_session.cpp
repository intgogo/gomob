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
#include <cmath>
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
extern bool IsDepthValid(int16_t d);
} // namespace depth

namespace reconstruction {

struct ForegroundDepthStats {
    int raw_valid = 0;
    int band_valid = 0;
    int kept = 0;
    int component_area = 0;
    int component_center_hits = 0;
    int seed_depth_mm = 0;
    int lower_mm = 0;
    int upper_mm = 0;
};

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
    // finalize 完成后保留 mesh —— UI 端 Completed 状态拉这份数据用 lit material 渲染实体面
    Mesh last_mesh;

    explicit ScanSession(const TsdfConfig& c) : tsdf_cfg(c), tsdf(c) {
        last_pose = {0, 0, 0, 0, 0, 0, 1};
    }
};

// ICP 输入点云上限。256K 全量进 ICP 会让 SpatialHash 构建 + NN 搜索跑到秒级
// （256K dst × 256K src × 27 邻 cell × ~60 cell_avg ≈ 4 亿次距离比较 / iter，30 iter = 12 秒）。
// 降到 8K 后：8K × 27×2 cell_avg = 43 万 / iter，30 iter ≈ 1300 万次，几十 ms 一帧。
// ICP 收敛精度对点数不敏感，8K 均匀子采样仍能给亚像素配准 — 实测足够。
static constexpr std::size_t kIcpMaxPoints = 8000;

static int PercentileFromHistogram(const std::vector<int>& hist, int total, float percentile) {
    if (total <= 0) return 0;
    int target = static_cast<int>(std::ceil(static_cast<float>(total) * percentile));
    target = std::max(1, target);
    int acc = 0;
    for (std::size_t d = 0; d < hist.size(); ++d) {
        acc += hist[d];
        if (acc >= target) return static_cast<int>(d);
    }
    return static_cast<int>(hist.size() - 1);
}

static int EstimateForegroundSeedDepth(const uint16_t* depth_mm, int width, int height) {
    // 直方图覆盖到 P100R3 spec 上限 8000mm；之前写 2500 会把中远距扫描的所有像素截到顶 bin，
    // 25% 分位数永远落在 2500 → 种子始终被钳到错误位置 → BuildForegroundDepth 选错连通块。
    constexpr int kMaxDepthMm = 8000;
    std::vector<int> hist(kMaxDepthMm + 1, 0);
    int count = 0;
    int x0 = width / 5;
    int x1 = width - x0;
    int y0 = height / 5;
    int y1 = height - y0;
    for (int v = y0; v < y1; ++v) {
        for (int u = x0; u < x1; ++u) {
            uint16_t d = depth_mm[v * width + u];
            if (!gomob::depth::IsDepthValid(static_cast<int16_t>(d))) continue;
            hist[std::min<int>(d, kMaxDepthMm)]++;
            count++;
        }
    }
    if (count < 500) {
        std::fill(hist.begin(), hist.end(), 0);
        count = 0;
        for (int i = 0; i < width * height; ++i) {
            uint16_t d = depth_mm[i];
            if (!gomob::depth::IsDepthValid(static_cast<int16_t>(d))) continue;
            hist[std::min<int>(d, kMaxDepthMm)]++;
            count++;
        }
    }
    return PercentileFromHistogram(hist, count, 0.25f);
}

/**
 * 从整张 depth 中自动抠出“要扫描的前景连续体”。
 *
 * 现阶段上层还没有 ARCore/IMU 位姿，也没有手动 ROI；如果直接把整张深度图送进 ICP，
 * 大面积桌面/墙面会比真实物体拥有更多配对点，ICP 会锁背景，TSDF 也会重建整块场景。
 *
 * 第一性策略：外廓扫描要求用户把目标放在画面中心，且目标通常是当前视角下最近的一组
 * 连续深度。因此用中心 ROI 的近四分位深度估计目标距离，再在这个深度带里选取和中心区
 * 相交的最大连通块。输出 depth 尺寸不变，非前景像素置 0，保证 ICP 与 TSDF 使用同一真理源。
 */
static std::vector<uint16_t> BuildForegroundDepth(const uint16_t* depth_mm, int width, int height,
                                                  ForegroundDepthStats* out_stats) {
    std::vector<uint16_t> filtered(static_cast<std::size_t>(width) * height, 0);
    if (!depth_mm || width <= 0 || height <= 0) return filtered;

    ForegroundDepthStats stats{};
    int seed = EstimateForegroundSeedDepth(depth_mm, width, height);
    if (seed <= 0) {
        if (out_stats) *out_stats = stats;
        return filtered;
    }

    // band_after = 种子深度后向延伸厚度（mm），用于覆盖目标主体在视线方向的厚度。
    //   - 系数 0.45×seed: 距离越远物体在视线方向显得越"长"（透视），按比例放宽
    //   - clamp 下限 280mm: 桌面级薄物（杯子、零件）至少留 28cm 容量
    //   - clamp 上限 2000mm: 单件大物（汽车 / 沙发 / 机器人）厚度天花板；不能再放宽，
    //     否则扫桌面物时背景墙也会被纳入"前景连通块"
    //   注：当前是 P100R3 默认参数；后续若引入"扫描预设"（桌面/中件/大件/大场景）
    //     由上层 SessionIngest 下发更精细的 ROI/深度带，这里就退成兜底默认值。
    // lower / upper 边界对齐 IsDepthValid 的 [200, 8000]，避免本函数把 spec 内的有效深度滤掉。
    int band_after = static_cast<int>(std::round(std::clamp(seed * 0.45f, 280.0f, 2000.0f)));
    int lower = std::max(200, seed - 80);
    int upper = std::min(8000, seed + band_after);
    stats.seed_depth_mm = seed;
    stats.lower_mm = lower;
    stats.upper_mm = upper;

    int total = width * height;
    std::vector<uint8_t> mask(static_cast<std::size_t>(total), 0);
    for (int i = 0; i < total; ++i) {
        uint16_t d = depth_mm[i];
        if (!gomob::depth::IsDepthValid(static_cast<int16_t>(d))) continue;
        stats.raw_valid++;
        if (d < lower || d > upper) continue;
        mask[i] = 1;
        stats.band_valid++;
    }

    int cx0 = width * 3 / 10;
    int cx1 = width * 7 / 10;
    int cy0 = height * 3 / 10;
    int cy1 = height * 7 / 10;

    std::vector<uint8_t> seen(static_cast<std::size_t>(total), 0);
    std::vector<int> queue;
    std::vector<int> best_pixels;
    int best_score = -1;
    int best_area = 0;
    int best_center_hits = 0;

    auto is_center = [&](int idx) {
        int y = idx / width;
        int x = idx - y * width;
        return x >= cx0 && x < cx1 && y >= cy0 && y < cy1;
    };

    for (int start = 0; start < total; ++start) {
        if (!mask[start] || seen[start]) continue;
        queue.clear();
        queue.push_back(start);
        seen[start] = 1;
        int head = 0;
        int center_hits = 0;
        while (head < static_cast<int>(queue.size())) {
            int idx = queue[head++];
            if (is_center(idx)) center_hits++;
            int y = idx / width;
            int x = idx - y * width;
            auto push = [&](int nx, int ny) {
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) return;
                int ni = ny * width + nx;
                if (!mask[ni] || seen[ni]) return;
                seen[ni] = 1;
                queue.push_back(ni);
            };
            push(x - 1, y);
            push(x + 1, y);
            push(x, y - 1);
            push(x, y + 1);
        }

        int area = static_cast<int>(queue.size());
        // 中心命中的连通块优先；没有中心命中时退到最大块，避免目标略偏时全空。
        int score = center_hits > 0 ? center_hits * 32 + area : area;
        if (score > best_score) {
            best_score = score;
            best_area = area;
            best_center_hits = center_hits;
            best_pixels.assign(queue.begin(), queue.end());
        }
    }

    stats.component_area = best_area;
    stats.component_center_hits = best_center_hits;
    if (best_area >= 500) {
        for (int idx : best_pixels) {
            filtered[idx] = depth_mm[idx];
        }
        stats.kept = static_cast<int>(best_pixels.size());
    } else {
        // 极端纹理/遮挡导致连通块碎裂时，保留深度带结果，宁可少滤一点也不让扫描完全无输入。
        for (int i = 0; i < total; ++i) {
            if (mask[i]) {
                filtered[i] = depth_mm[i];
                stats.kept++;
            }
        }
    }

    if (out_stats) *out_stats = stats;
    return filtered;
}

// 把扁平 [x,y,z, ...] 点云均匀降采样到 ≤ max_points 个有效点。
//
// 旧实现 bug：stride 在 *总像素数* (256K) 上算，cloud 中 ~44% 是 (0,0,0) 占位
// （ProjectToPointCloud 对无效深度像素吐 (0,0,0)）；i += stride 步进时一半步会落
// 在 (0,0,0) 上被 continue 跳过 → 实际 out 只有 max_points × 56% ≈ 4500 点。
// 稀疏点云 + 桌面大平面让 ICP slide 到 ±200mm 局部极值（log 实测每帧 pose 乱跳）。
//
// 修法：第一性 — 先把所有有效点 *压实* 到一个 vector，再在有效点上做线性子采样，
// 保证 out.size() 真的接近 max_points × 3。ICP 输入稠密后，pairs 数稳定 8K，
// 收敛到 identity（静态扫描）。
static std::vector<float> DownsampleCloud(const std::vector<float>& cloud,
                                          std::size_t max_points) {
    std::vector<float> out;
    std::size_t total = cloud.size() / 3;
    if (total == 0 || max_points == 0) return out;

    // step 1: 压实有效点
    std::vector<float> valid;
    valid.reserve(total * 3 / 2);  // 经验保守估
    for (std::size_t i = 0; i < total; ++i) {
        const float* p = cloud.data() + i * 3;
        if (p[0] == 0.f && p[1] == 0.f && p[2] == 0.f) continue;
        valid.push_back(p[0]);
        valid.push_back(p[1]);
        valid.push_back(p[2]);
    }
    std::size_t valid_total = valid.size() / 3;
    if (valid_total == 0) return out;

    // step 2: 在有效点上线性取样到严格 ≤ max_points。
    // 不能用 valid_total / max_points 的整数 stride：当 valid_total ∈ (max, 2*max)
    // 时 stride=1，会把全部点送进 ICP，导致 pair 数和运行时间抖动。
    std::size_t take = std::min(valid_total, max_points);
    out.reserve(take * 3);
    for (std::size_t oi = 0; oi < take; ++oi) {
        std::size_t i = oi * valid_total / take;
        const float* p = valid.data() + i * 3;
        out.push_back(p[0]);
        out.push_back(p[1]);
        out.push_back(p[2]);
    }
    return out;
}

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

static float TranslationDeltaMm(const std::array<float, 7>& a,
                                const std::array<float, 7>& b) {
    float dx = a[0] - b[0];
    float dy = a[1] - b[1];
    float dz = a[2] - b[2];
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

static float RotationDeltaDeg(const std::array<float, 7>& a,
                              const std::array<float, 7>& b) {
    Eigen::Quaternionf qa(a[6], a[3], a[4], a[5]);
    Eigen::Quaternionf qb(b[6], b[3], b[4], b[5]);
    qa.normalize();
    qb.normalize();
    float dot = std::abs(qa.dot(qb));
    dot = std::clamp(dot, 0.0f, 1.0f);
    float rad = 2.0f * std::acos(dot);
    return rad * 57.2957795f;
}

static bool PoseFinite(const std::array<float, 7>& p) {
    for (float v : p) {
        if (!std::isfinite(v)) return false;
    }
    return true;
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

    // 1) raw depth → 前景 depth → 点云（相机系，mm）
    //
    // 当前三维外廓扫描没有 ARCore/IMU 位姿输入，上层 pose7 仍是 identity；若整张 depth
    // 直接进 ICP，背景/桌面的大面积平面会主导配准，目标物体只占少数点。这里先把每帧收敛到
    // 画面中心最近连通前景，ICP 与 TSDF 都吃同一份 filtered_depth。
    ForegroundDepthStats fg_stats{};
    auto filtered_depth = BuildForegroundDepth(depth_mm, width, height, &fg_stats);

    // 依赖 depth/depth_projection.cpp 的 int16_t 接口；reinterpret 是合法的（同 size）
    auto cloud = gomob::depth::ProjectToPointCloud(
        reinterpret_cast<const int16_t*>(filtered_depth.data()), width, height, fx, fy, cx, cy);

    // 2) 第一帧：把 user 的 pose7 当世界系起点（一般是 identity，但允许外部 IMU 给定）
    if (s->reference_cloud_world.empty()) {
        std::array<float, 7> p0;
        for (int i = 0; i < 7; ++i) p0[i] = pose7[i];
        // reference_cloud_world 只服务 ICP，存降采样版避免后续每帧 ICP 重建 256K 点 hash 跑几秒
        s->reference_cloud_world = DownsampleCloud(TransformCloud(cloud, p0), kIcpMaxPoints);
        int updated = s->tsdf.Integrate(filtered_depth.data(), width, height, fx, fy, cx, cy, p0.data());
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
        GOMOB_LOGI("Foreground depth: seed=%dmm band=[%d,%d] rawValid=%d bandValid=%d "
                   "component=%d centerHits=%d kept=%d",
                   fg_stats.seed_depth_mm, fg_stats.lower_mm, fg_stats.upper_mm,
                   fg_stats.raw_valid, fg_stats.band_valid, fg_stats.component_area,
                   fg_stats.component_center_hits, fg_stats.kept);
        // grid 与前景深度失配告警 — 物体不在 grid 覆盖区间内会导致 nearSurf 很少或为 0。
        float gz_lo = s->tsdf.config().grid_origin_mm[2];
        float gz_hi = gz_lo + s->tsdf.config().grid_extent_mm;
        int fg_depth_for_grid = fg_stats.kept > 0 ? fg_stats.seed_depth_mm : static_cast<int>(d_avg);
        if (fg_depth_for_grid > 0 && (fg_depth_for_grid < gz_lo || fg_depth_for_grid > gz_hi)) {
            GOMOB_LOGW("Ingest[1st] WARN foreground depth %dmm 不在 grid z[%.0f, %.0f]mm 内 — peek nearSurf 会很少；"
                       "需扩 gridExtent 或调 gridCenterZ 让 grid 覆盖物体距离",
                       fg_depth_for_grid, gz_lo, gz_hi);
        }
        if (fg_stats.raw_valid > 0 && fg_stats.raw_valid < 5000) {
            GOMOB_LOGW("Ingest[1st] WARN 前景有效深度只有 %d px — 目标可能低于 iHawk 250mm 工作距离下限或占画面太小",
                       fg_stats.raw_valid);
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
    // ICP src 也降采样：256K → 8K，匹配 dst 的子采样密度，避免每帧几秒
    auto icp_src = DownsampleCloud(cloud, kIcpMaxPoints);
    auto icp_result = IcpRegister(
        icp_src.data(), icp_src.size() / 3,
        s->reference_cloud_world.data(), s->reference_cloud_world.size() / 3,
        init.data());

    if (icp_result.status == IcpResultStatus::DegenerateInput) {
        return s->keyframe_count;
    }

    // ICP 置信门控：错误位姿一旦进 TSDF，后续近表面会被积分到错误世界位置，
    // UI 表现就是点云越扫越乱。没有 IMU/SLAM 初值时，单帧 ICP 必须满足“小步连续”：
    //   - 平均配准误差不应超过 TSDF 截断带（4mm voxel → trunc 16mm，门限取 20mm）
    //   - 相邻已接受位姿的平移/旋转跳变不能过大
    // 超限帧直接丢弃，不更新 last_pose / reference_cloud / TSDF。
    float max_icp_error_mm = std::max(12.0f, s->tsdf.config().truncation_dist_mm * 1.25f);
    float delta_t_mm = TranslationDeltaMm(icp_result.pose7, s->last_pose);
    float delta_r_deg = RotationDeltaDeg(icp_result.pose7, s->last_pose);
    constexpr float kMaxAcceptedPoseJumpMm = 90.0f;
    constexpr float kMaxAcceptedPoseJumpDeg = 25.0f;
    if (!PoseFinite(icp_result.pose7) ||
        !std::isfinite(icp_result.mean_error_mm) ||
        icp_result.mean_error_mm > max_icp_error_mm ||
        delta_t_mm > kMaxAcceptedPoseJumpMm ||
        delta_r_deg > kMaxAcceptedPoseJumpDeg) {
        GOMOB_LOGW("Ingest[#%d] reject ICP: status=%d iter=%d pairs=%d err=%.2fmm "
                   "dT=%.1fmm dR=%.1fdeg pose=(%.1f,%.1f,%.1f) last=(%.1f,%.1f,%.1f)",
                   s->total_frame_count, static_cast<int>(icp_result.status),
                   icp_result.iterations, icp_result.pair_count_final,
                   icp_result.mean_error_mm, delta_t_mm, delta_r_deg,
                   icp_result.pose7[0], icp_result.pose7[1], icp_result.pose7[2],
                   s->last_pose[0], s->last_pose[1], s->last_pose[2]);
        return s->keyframe_count;
    }

    // 4) TSDF 积分（用 ICP 输出位姿）
    int updated = s->tsdf.Integrate(filtered_depth.data(), width, height, fx, fy, cx, cy, icp_result.pose7.data());
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
        s->reference_cloud_world =
            DownsampleCloud(TransformCloud(cloud, s->last_pose), kIcpMaxPoints);
        s->keyframe_poses.push_back(s->last_pose);
        s->keyframe_count++;
    }
    return s->keyframe_count;
}

bool SessionFinalize(ScanSession* s, const char* out_dir, int* out_stats3) {
    if (!s) return false;
    // 1) Marching Cubes 提 mesh —— 移到 session 字段，UI 端 Completed 状态可拉数据渲染实体面
    s->last_mesh = ExtractMesh(s->tsdf);
    const Mesh& mesh = s->last_mesh;

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

// ─── finalize 后 UI 端拉 mesh 数据用于 lit material 渲染 ──────────────────────
//
// 设计：finalize 时把 ExtractMesh 结果存到 ScanSession::last_mesh；UI 在 Completed 状态
// 调以下三个 API 一次性拉走（vertex / normal / index）。返回值都是 vector 拷贝 — JNI 端
// 用 SetFloatArrayRegion 再拷贝一次到 jfloatArray，无生命周期风险。
// finalize 未调 / mesh 为空 → 都返回空 vector（Kotlin 端见 length=0 跳过渲染）。
const std::vector<float>& SessionMeshVertices(ScanSession* s) {
    static const std::vector<float> kEmpty;
    return s ? s->last_mesh.vertices : kEmpty;
}
const std::vector<float>& SessionMeshNormals(ScanSession* s) {
    static const std::vector<float> kEmpty;
    return s ? s->last_mesh.normals : kEmpty;
}
const std::vector<uint32_t>& SessionMeshIndices(ScanSession* s) {
    static const std::vector<uint32_t> kEmpty;
    return s ? s->last_mesh.indices : kEmpty;
}

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

    // 3D 等距子采样：axis_stride 控制物理间距。
    //
    // 第一性：物体表面采样应基于 **物理空间间距**，不是 grid 维数。voxel=4mm 时表面带 ±16mm，
    // 8mm 间距能稳定采到 2 层 voxel；voxel=6mm 时表面带 ±24mm，8mm 间距同样能稳采。所以采样
    // stride 应该让物理间距稳定在 ~8mm，而不是让 sampled = max_vertices×64（这条公式让 voxel
    // 粗时 stride 反而更大 → 物理间距 12mm → 物体表面被跳过 → "扫描出不来点云" 假象）。
    //
    // 历史 bug:
    //   * 早期用 1D `counter % stride` 被退化成"只取 i=0 切片"（详见 git log d10bedc / b2b2f0a）
    //   * 改 3D 网格步长 + max_vertices×64 公式时是按 voxel=4mm + N=200 测出来的；
    //     M3.7 P100R3 升 voxel=6mm + N=250 后这个公式给出 stride=2 → 物理间距 12mm，
    //     远大于"物体表面 voxel 几乎只占 1 层 6mm voxel" 的实际厚度 → nearSurf 从 ~600 降到 80。
    //
    // 当前修法：直接按 voxel_size 反推 stride，恒定 ~8mm 物理间距；只在内存压力（sampled 太多）
    // 时退化到更粗的 stride 兜底。
    constexpr float kPreviewStrideMm = 8.0f;
    int stride_by_physics = std::max(1,
        static_cast<int>(std::round(kPreviewStrideMm / s->tsdf_cfg.voxel_size_mm)));
    // 兜底：sampled 不能超过 max_vertices × 64（避免极小 voxel 时遍历爆炸）
    double target_per_axis = std::cbrt(static_cast<double>(max_vertices) * 64.0);
    int stride_by_count = std::max(1, static_cast<int>(static_cast<double>(N) / target_per_axis));
    int axis_stride = std::max(stride_by_physics, stride_by_count);

    // 近表面阈值（语义修正）：tsdf.cpp Integrate 把 sdf 归一化成 sdf_norm = sdf / trunc，
    // 存到 sdf_[] 的值范围严格 [-1, +1]。所以 |sdf| 是 **归一化** 值，不是 voxel 单位。
    //   - 阈值 0.6 ⇒ 物理近表面带厚度 = ±0.6 × trunc(=4 voxel=16mm) = ±9.6mm （真正的近零等值面）
    //   - 阈值 1.5 ⇒ |sdf| > 1.5 永不成立 = 不过滤，整个 frustum 内被观测体素都吐出（包括
    //     自由空间 sdf≈+1）→ UI 看到的是"相机视锥盒子"形状，与物体真实形状完全脱钩，
    //     这是之前误判"扫描第一帧固化"的真实根因。
    //   - 实测 nearSurf 数量少不是阈值问题（0.6 给 ±9.6mm 已是宽 5 倍体素的厚带），
    //     是 axis_stride 太粗或物体表面 voxel 本身少（薄物体 / 远距离 / FOV 边缘）。
    constexpr float kNearSurfaceNormThresh = 0.6f;

    out.reserve(static_cast<size_t>(max_vertices) * 3);
    long long sampled = 0;
    long long w_pos = 0;          // weight ≥ 1 的体素数（被任何一帧观测到）
    long long near_surface = 0;   // weight ≥ 1 且 |sdf|<阈值 的体素数（近零等值面）
    for (int k = 0; k < N; k += axis_stride) {
        for (int j = 0; j < N; j += axis_stride) {
            for (int i = 0; i < N; i += axis_stride) {
                ++sampled;
                float sdf, w;
                tsdf.Get(i, j, k, sdf, w);
                if (w < 1.f) continue;
                ++w_pos;
                if (std::abs(sdf) > kNearSurfaceNormThresh) continue;
                ++near_surface;
                auto c = tsdf.VoxelCenter(i, j, k);
                out.push_back(c[0]);
                out.push_back(c[1]);
                out.push_back(c[2]);
                if (static_cast<int>(out.size() / 3) >= max_vertices) {
                    GOMOB_LOGI("Peek (capped) frames=%d N=%d axisStride=%d sampled=%lld w>=1=%lld nearSurf=%lld out=%d",
                               s->total_frame_count, N, axis_stride, sampled, w_pos, near_surface,
                               static_cast<int>(out.size() / 3));
                    return out;
                }
            }
        }
    }
    GOMOB_LOGI("Peek frames=%d N=%d axisStride=%d sampled=%lld w>=1=%lld nearSurf=%lld out=%d",
               s->total_frame_count, N, axis_stride, sampled, w_pos, near_surface,
               static_cast<int>(out.size() / 3));
    return out;
}

} // namespace reconstruction
} // namespace gomob

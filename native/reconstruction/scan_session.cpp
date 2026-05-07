// reconstruction/ — 三维外廓扫描重建管线（占位）
//
// 当前阶段：仅声明 Session 类型 + 4 个 lifecycle 函数桩，让 jni_bridge.cpp 能链接通过。
// 真实实施在 M3.* 阶段（详见 docs/architecture/04-reconstruction-pipeline.md）：
//   - ICP 增量配准（先用 point-to-point，后续可加 point-to-plane）
//   - TSDF 体素积分（自写 CUDA-free 内核，arm64 NEON 加速）
//   - Marching Cubes 出 mesh
//   - 关键帧纹理烘焙

#include <cstdint>
#include <cstring>
#include <vector>

namespace gomob::reconstruction {

struct ScanSession {
    float voxel_size_mm;
    float grid_extent_mm;
    int keyframe_count;
    int total_frame_count;
    // TODO M3.x: TSDF voxel grid / KFGraph / pose track
};

ScanSession* SessionCreate(float voxel_size_mm, float grid_extent_mm) {
    auto* s = new ScanSession{};
    s->voxel_size_mm = voxel_size_mm;
    s->grid_extent_mm = grid_extent_mm;
    s->keyframe_count = 0;
    s->total_frame_count = 0;
    return s;
}

int SessionIngest(
        ScanSession* s,
        const uint16_t* depth_mm, int width, int height,
        double fx, double fy, double cx, double cy,
        const float* pose7) {
    if (!s) return -1;
    (void)depth_mm; (void)width; (void)height;
    (void)fx; (void)fy; (void)cx; (void)cy;
    (void)pose7;
    s->total_frame_count++;
    // TODO M3.x: depth → 点云 → ICP 配准 → TSDF 积分 → 关键帧判定
    return s->keyframe_count;
}

bool SessionFinalize(ScanSession* s, const char* /*out_dir*/, int* out_stats3) {
    if (!s) return false;
    if (out_stats3) {
        out_stats3[0] = 0;  // vertex count
        out_stats3[1] = 0;  // face count
        out_stats3[2] = s->keyframe_count;
    }
    // TODO M3.x: Marching Cubes → 写 .ply / .gltf / .obj
    return true;
}

void SessionClose(ScanSession* s) { delete s; }

// ICP 单步配准（占位）
std::vector<float> IcpRegister(
        const float* /*src*/, size_t /*src_count*/,
        const float* /*dst*/, size_t /*dst_count*/,
        const float* initial_pose7) {
    // 当前桩：原样返回 initial_pose（即"配准失败时退回初值"语义）
    return std::vector<float>(initial_pose7, initial_pose7 + 7);
}

} // namespace gomob::reconstruction

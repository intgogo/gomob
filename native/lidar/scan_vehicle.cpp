#include "lidar/scan_vehicle.h"

#include "lidar/fusion.h"
#include "lidar/registration.h"

namespace gomob::lidar {

ScanVehicleResult reconstructVehicle(const Cloud& unitA, const Cloud& unitB,
                                     const ScanVehicleParams& p) {
    ScanVehicleResult r;
    r.pts_a = unitA.size();
    r.pts_b = unitB.size();

    // 1. 把 unit-B 对齐进 unit-A 帧
    Cloud bAligned;
    switch (p.align) {
        case AlignMethod::Site:
            r.b_to_a = p.site_extrinsic;
            bAligned = transformCloud(unitB, p.site_extrinsic);
            r.align_used = AlignMethod::Site;
            break;
        case AlignMethod::Icp: {
            const auto reg = registerTwoUnits(unitB, unitA, p.icp_reject_mm);
            r.b_to_a = reg.transform;
            r.fitness = reg.fitness;
            bAligned = reg.converged ? transformCloud(unitB, reg.transform) : unitB;
            r.align_used = reg.converged ? AlignMethod::Icp : AlignMethod::None;
            break;
        }
        case AlignMethod::None:
        default:
            bAligned = unitB;
            r.align_used = AlignMethod::None;
            break;
    }

    // 2. 融合 = 纯点集 UNION
    Cloud fused = fuseUnion({&unitA, &bAligned});
    r.fused = fused.size();

    // 3. 随机保留比例降采样
    Cloud cloud = (p.keep_ratio < 1.0f) ? randomKeep(fused, p.keep_ratio) : std::move(fused);
    r.after_downsample = cloud.size();

    // 4. 可选 AABB 裁剪（zMin 切地面，无 RANSAC）
    if (p.crop) cloud = cropBox(cloud, p.crop_min, p.crop_max, true);
    r.after_crop = cloud.size();

    r.bbox = boundingBox(cloud);
    r.cloud = std::move(cloud);
    return r;
}

}  // namespace gomob::lidar

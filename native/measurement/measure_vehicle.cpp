#include "measurement/measure_vehicle.h"

#include "lidar/fusion.h"          // cropBox（复用 M8.1 几何，避免重写 AABB 裁剪）
#include "measurement/dimensions.h"
#include "measurement/preprocess.h"

namespace gomob::measure {

VehicleDimensions measureVehicle(const Cloud& raw, const MeasureParams& p) {
    VehicleDimensions d;
    d.raw_pts = raw.size();
    if (raw.empty()) return d;

    // ② 预处理：ROI 体裁剪 → 最大连通簇（剥脱离噪声）→ 半径离群剔除。
    Cloud roi = p.use_roi ? gomob::lidar::cropBox(raw, p.roi_min, p.roi_max, /*inside=*/true) : raw;
    d.roi_pts = roi.size();
    if (roi.empty()) return d;

    Cloud body = largestEuclideanCluster(roi, p.cluster_leaf);
    if (p.use_ror) body = radiusOutlierRemoval(body, p.ror_radius, p.ror_min_neighbors);
    d.body_pts = body.size();
    d.body_ratio = d.roi_pts ? static_cast<float>(d.body_pts) / static_cast<float>(d.roi_pts) : 0.f;
    if (body.empty()) return d;

    // ⑥ 尺寸提取：俯视最小面积矩形 → 车长/车宽；Z 跨度 → 车高。
    d.body_aabb = gomob::lidar::boundingBox(body);
    const ObbXY obb = minAreaRectXY(body, p.obb_step_deg);
    d.length = obb.length;
    d.width = obb.width;
    d.height = d.body_aabb.size().z();
    d.obb_angle_deg = obb.angle_deg;
    d.valid = true;
    return d;
}

}  // namespace gomob::measure

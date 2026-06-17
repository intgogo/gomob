#include "cloud/fusion.h"

#include <cmath>
#include <pcl/common/transforms.h>
#include <pcl/filters/crop_box.h>
#include <pcl/filters/random_sample.h>

namespace lidar {

CloudXYZ::Ptr transformCloud(const CloudXYZ& in, const Pose& pose) {
  auto out = std::make_shared<CloudXYZ>();
  pcl::transformPointCloud(in, *out, pose.affine().matrix().cast<float>());
  return out;
}

CloudXYZ::Ptr fuse(const std::vector<CloudXYZ::ConstPtr>& clouds) {
  auto out = std::make_shared<CloudXYZ>();
  for (const auto& c : clouds)
    if (c) *out += *c;  // plain point-set union, no ICP (spec_fusion_export §1.2)
  out->width  = static_cast<std::uint32_t>(out->points.size());
  out->height = 1;
  out->is_dense = false;
  return out;
}

CloudXYZ::Ptr randomKeep(const CloudXYZ& in, double ratio, unsigned seed) {
  auto out = std::make_shared<CloudXYZ>();
  if (ratio >= 1.0) { *out = in; return out; }
  if (ratio <= 0.0) { out->height = 1; return out; }
  const auto keep = static_cast<std::size_t>(std::llround(in.size() * ratio));
  pcl::RandomSample<PointXYZ> rs;
  rs.setInputCloud(in.makeShared());
  rs.setSample(static_cast<unsigned>(keep));
  rs.setSeed(seed);
  rs.filter(*out);
  return out;
}

CloudXYZ::Ptr cropBox(const CloudXYZ& in, const Eigen::Vector3d& mn, const Eigen::Vector3d& mx,
                      bool keep_inside) {
  auto out = std::make_shared<CloudXYZ>();
  pcl::CropBox<PointXYZ> cb;
  cb.setInputCloud(in.makeShared());
  cb.setMin(Eigen::Vector4f(static_cast<float>(mn.x()), static_cast<float>(mn.y()), static_cast<float>(mn.z()), 1.0f));
  cb.setMax(Eigen::Vector4f(static_cast<float>(mx.x()), static_cast<float>(mx.y()), static_cast<float>(mx.z()), 1.0f));
  cb.setNegative(!keep_inside);
  cb.filter(*out);
  return out;
}

}  // namespace lidar

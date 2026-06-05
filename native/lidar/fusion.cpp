#include "lidar/fusion.h"

#include <cstddef>

namespace gomob::lidar {

Cloud transformCloud(const Cloud& in, const Eigen::Matrix4f& T) {
    const Eigen::Matrix3f R = T.block<3, 3>(0, 0);
    const Eigen::Vector3f t = T.block<3, 1>(0, 3);
    Cloud out;
    out.reserve(in.size());
    for (const auto& p : in) out.emplace_back(R * p + t);
    return out;
}

Cloud fuseUnion(const std::vector<const Cloud*>& clouds) {
    std::size_t n = 0;
    for (const auto* c : clouds) if (c) n += c->size();
    Cloud out;
    out.reserve(n);
    for (const auto* c : clouds)
        if (c) out.insert(out.end(), c->begin(), c->end());
    return out;
}

Cloud randomKeep(const Cloud& in, float ratio, std::uint32_t seed) {
    if (ratio >= 1.0f || in.empty()) return in;
    if (ratio <= 0.0f) return {};
    const std::size_t keep = static_cast<std::size_t>(in.size() * ratio + 0.5f);
    // Fisher-Yates 部分洗牌（自带 LCG，不依赖 <random> 的实现差异，跨端可复现）。
    std::vector<std::size_t> idx(in.size());
    for (std::size_t i = 0; i < idx.size(); ++i) idx[i] = i;
    std::uint32_t s = seed ? seed : 1u;
    auto next = [&s]() { s = s * 1664525u + 1013904223u; return s; };
    for (std::size_t i = 0; i < keep; ++i) {
        const std::size_t j = i + next() % (idx.size() - i);
        std::swap(idx[i], idx[j]);
    }
    Cloud out;
    out.reserve(keep);
    for (std::size_t i = 0; i < keep; ++i) out.push_back(in[idx[i]]);
    return out;
}

Cloud cropBox(const Cloud& in, const Eigen::Vector3f& mn, const Eigen::Vector3f& mx, bool inside) {
    Cloud out;
    out.reserve(in.size());
    for (const auto& p : in) {
        const bool in_box = (p.x() >= mn.x() && p.x() <= mx.x() &&
                             p.y() >= mn.y() && p.y() <= mx.y() &&
                             p.z() >= mn.z() && p.z() <= mx.z());
        if (in_box == inside) out.push_back(p);
    }
    return out;
}

}  // namespace gomob::lidar

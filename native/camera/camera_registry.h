// 厂商无关相机注册表(native/camera)。纯 portable,无 libusb / 无 Android。
//
// 用途:"同时支持两种相机"的自动识别脊柱。各 driver(Eys3dDriver / Berxel adapter)注册进来,
// 上层枚举到 USB 设备后按 vid:pid 找认领的 driver → driver.open_fd/open_host 出会话;
// driver.capabilities().model 给 UI 显型号。避免在 JNI / feature 散落 if(vid==..) 硬分支。
//
// 设计见 docs/architecture/12-camera-abstraction.md §注册分发。
#pragma once
#include <memory>
#include <vector>

#include "camera_session.h"  // ICameraDriver / UsbId

namespace gomob::camera {

class CameraRegistry {
 public:
  // 注册一个 driver(其 match_usb_ids() 决定认领哪些设备)。后注册的排在后面,先注册的优先认领。
  void Register(std::shared_ptr<ICameraDriver> driver) {
    if (driver) drivers_.push_back(std::move(driver));
  }

  // 按 USB id 找第一个认领该设备的 driver;无 → nullptr。
  ICameraDriver* MatchByUsbId(UsbId id) const {
    for (const auto& d : drivers_) {
      for (const UsbId& known : d->match_usb_ids()) {
        if (known.vid == id.vid && known.pid == id.pid) return d.get();
      }
    }
    return nullptr;
  }

  bool Knows(UsbId id) const { return MatchByUsbId(id) != nullptr; }

  // 所有注册 driver 认领的 USB id 并集(供 USB 过滤 / 权限请求白名单)。
  std::vector<UsbId> all_known_ids() const {
    std::vector<UsbId> out;
    for (const auto& d : drivers_) {
      for (const UsbId& id : d->match_usb_ids()) out.push_back(id);
    }
    return out;
  }

  size_t size() const { return drivers_.size(); }
  const std::vector<std::shared_ptr<ICameraDriver>>& drivers() const { return drivers_; }

 private:
  std::vector<std::shared_ptr<ICameraDriver>> drivers_;
};

}  // namespace gomob::camera

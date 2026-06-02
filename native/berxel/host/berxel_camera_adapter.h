// Berxel iHawk P100R3 的 ICameraSession 适配工厂（M6.8b ④）。
//
// 让 Berxel 双流(master 0x0603:0x001f + companion 0x3558:0x1012)经厂商无关 CameraRegistry/
// cameraOpenByFds 统一分发，与 eYs3D 同契约。实现在 native/jni/berxel_dual_session_jni.cpp
// （需访问该 TU 内的 DualSession + 双流取流 core；adapter/driver 定义在那里，此处只暴露工厂）。
//
// adapter 的 snapshot/open 即双流取流 core 的唯一封装（历史 berxelDual* 回退 JNI 已删，core 是唯一真理源）。
#pragma once

#include <memory>

#include "camera/camera_session.h"

namespace gomob::berxel::host {

// Berxel(0x0603:0x001f)的 ICameraDriver。在 camera_session_jni 注册进进程级 CameraRegistry。
// open_fd 收 [masterFd, companionFd] + options_json（masterXu/companionInit/14-int config 打包）。
std::shared_ptr<gomob::camera::ICameraDriver> MakeBerxelDriver();

}  // namespace gomob::berxel::host

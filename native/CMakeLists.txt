cmake_minimum_required(VERSION 3.22.1)

project(gomob_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 全局编译选项 — 严格警告，禁止未声明类型
add_compile_options(-Wall -Wextra -Wpedantic)

# Debug 也强开 -O2：Eigen / TSDF / ICP 全是 header-only template，-O0 下性能跌 50-100×。
# 实测 LOG-AN10 + Berxel 640×400 深度帧 + 200³ TSDF：-O0 首帧 ingest 22.5s，-O2 < 200ms。
# 调试用 -g 保留符号；行号在 -O2 下虽不精确但栈展开仍可用，性能调试只能这样。
# CMAKE_BUILD_TYPE=Debug 时 AGP 默认追加 "-O0 -g"，此处把 *_DEBUG 也设成 -O2 把它覆盖。
if(CMAKE_BUILD_TYPE STREQUAL "Debug" OR CMAKE_BUILD_TYPE STREQUAL "")
    set(CMAKE_C_FLAGS_DEBUG   "-O2 -g")
    set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")
    add_compile_options(-O2)
endif()

# Release 包不暴露非 JNI 符号；AGP release 默认传 RelWithDebInfo。
if(CMAKE_BUILD_TYPE STREQUAL "Release" OR
   CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo" OR
   CMAKE_BUILD_TYPE STREQUAL "MinSizeRel")
    set(CMAKE_C_FLAGS_RELWITHDEBINFO   "-O3 -DNDEBUG -g0")
    set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG -g0")
    add_compile_options(
        -O3
        -DNDEBUG
        -g0
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
    )
    add_link_options(
        -Wl,--gc-sections
        -Wl,--strip-all
    )
endif()

# 包含目录
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/depth
    ${CMAKE_CURRENT_SOURCE_DIR}/fusion
    ${CMAKE_CURRENT_SOURCE_DIR}/reconstruction
    ${CMAKE_CURRENT_SOURCE_DIR}/vin
    ${CMAKE_CURRENT_SOURCE_DIR}/calibration
)

# Eigen 3.4 header-only — third_party/eigen-3.4.0/ 投放（详见 README.gomob.md）
# 用于 ICP SVD / 位姿四元数 / TSDF 后处理；header-only 不需 add_subdirectory
set(GOMOB_EIGEN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/eigen-3.4.0)
if(NOT EXISTS ${GOMOB_EIGEN_DIR}/Eigen/Dense)
    message(FATAL_ERROR "Eigen 3.4 not found at ${GOMOB_EIGEN_DIR}; 检查 third_party/ 投放")
endif()
include_directories(SYSTEM ${GOMOB_EIGEN_DIR})

# Berxel Android SDK 不暴露 C++ 头给业务，业务侧从 Kotlin 直接调
# BerxelSDK.jar 的 Java API（见 core:native-bridge/build.gradle.kts），
# 我们的 gomob_native.so 不需要在 C++ 层链接 Berxel SDK 任何 .so。
# 所以这里没有 -DGOMOB_HAS_BERXEL，没有 IMPORTED libBerxel —— 故意留空。
#
# 但 M1.6.5 起，我们自己写了 Sonix XU 协议复现层（native/berxel/，独立于 Berxel SDK），
# 接 third_party/libusb-android prebuilt，跑在 Berxel SDK 边上以替代其内嵌的 libuvc/libusb stack。

# libusb-1.0 prebuilt（third_party/libusb-android/，arm64-v8a + armeabi-v7a，主线 v1.0.27）
# SYSTEM include 避免 -Wpedantic 触发第三方头警告
set(GOMOB_LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libusb-android)
if(NOT EXISTS ${GOMOB_LIBUSB_DIR}/include/libusb-1.0/libusb.h)
    message(FATAL_ERROR "libusb-android not found at ${GOMOB_LIBUSB_DIR}; 见 third_party/libusb-android/README.md")
endif()
include_directories(SYSTEM ${GOMOB_LIBUSB_DIR}/include)
add_library(usb-1.0 SHARED IMPORTED)
set_target_properties(usb-1.0 PROPERTIES IMPORTED_LOCATION
    ${GOMOB_LIBUSB_DIR}/lib/${ANDROID_ABI}/libusb-1.0.so)

# berxel/ 子模块（Sonix XU 协议复现，M1.6.5+）
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/berxel/include)
file(GLOB BERXEL_SOURCES CONFIGURE_DEPENDS "berxel/src/*.cpp")

# berxel/portable 子模块（P100R3 可移植层，libusb-free，2026-05-29）
# Linux host 与 Android 共用同一份已验证纯逻辑：UVC 帧组装 / RGBD 配对 / depth&light-ir 解码 /
# XU payload 生成 / 协议编排（IUvcDevice 接口）。不依赖 libusb，Android 端各自实现 IUvcDevice。
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/berxel/portable)
file(GLOB BERXEL_PORTABLE_SOURCES CONFIGURE_DEPENDS "berxel/portable/*.cpp")

# camera/ 厂商无关相机抽象 + eys3d/ 自研驱动（双相机统一契约，M6.x）
# camera(抽象/注册表/host UsbContext header)+ eys3d/portable(纯逻辑) + eys3d/android(fd 会话)
# + eys3d/host/eys3d_stream_loop.cpp(传输无关取流，host 与 Android 共用)。
# ★ eys3d/host 下 replay_stream/probe/v4l2/xu_diag/host_session 是 host-only(libusb 枚举/demo)，
#   绝不进 .so，故只【精确列】eys3d_stream_loop.cpp，不 glob 整个 eys3d/host。
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/camera ${CMAKE_CURRENT_SOURCE_DIR}/eys3d/portable)
file(GLOB CAMERA_SOURCES CONFIGURE_DEPENDS "camera/*.cpp" "camera/berxel_adapter/*.cpp")
file(GLOB EYS3D_PORTABLE_SOURCES CONFIGURE_DEPENDS "eys3d/portable/*.cpp")
file(GLOB EYS3D_ANDROID_SOURCES CONFIGURE_DEPENDS "eys3d/android/*.cpp")
set(EYS3D_STREAM_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/eys3d/host/eys3d_stream_loop.cpp)

# depth/ 子模块（占位）
file(GLOB DEPTH_SOURCES CONFIGURE_DEPENDS "depth/*.cpp")
list(LENGTH DEPTH_SOURCES DEPTH_COUNT)

# fusion/ 子模块（占位）
file(GLOB FUSION_SOURCES CONFIGURE_DEPENDS "fusion/*.cpp")

# reconstruction/ 子模块（占位）
file(GLOB RECON_SOURCES CONFIGURE_DEPENDS "reconstruction/*.cpp")

# lidar/ 子模块（激光双单元车辆外廓几何内核，Eigen-only，复用 reconstruction/icp）
file(GLOB LIDAR_SOURCES CONFIGURE_DEPENDS "lidar/*.cpp")

# vin/ 子模块（占位）
file(GLOB VIN_SOURCES CONFIGURE_DEPENDS "vin/*.cpp")

# calibration/ 子模块（占位）
file(GLOB CALIB_SOURCES CONFIGURE_DEPENDS "calibration/*.cpp")

# JNI 入口
file(GLOB JNI_SOURCES CONFIGURE_DEPENDS "jni/*.cpp")

# 主 .so：app 端只 dlopen 这一个
add_library(gomob_native SHARED
    ${JNI_SOURCES}
    ${DEPTH_SOURCES}
    ${FUSION_SOURCES}
    ${RECON_SOURCES}
    ${LIDAR_SOURCES}
    ${VIN_SOURCES}
    ${CALIB_SOURCES}
    ${BERXEL_SOURCES}
    ${BERXEL_PORTABLE_SOURCES}
    ${CAMERA_SOURCES}
    ${EYS3D_PORTABLE_SOURCES}
    ${EYS3D_ANDROID_SOURCES}
    ${EYS3D_STREAM_SOURCES}
)

target_link_libraries(gomob_native
    log
    android
    GLESv3
    EGL
    usb-1.0
)

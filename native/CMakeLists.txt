cmake_minimum_required(VERSION 3.22.1)

project(gomob_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 全局编译选项 — 严格警告，禁止未声明类型
add_compile_options(-Wall -Wextra -Wpedantic)

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
# 我们的 gomob_native.so 不需要在 C++ 层链接 Berxel 任何 .so。
# 所以这里没有 -DGOMOB_HAS_BERXEL，没有 IMPORTED libberxel —— 故意留空。

# depth/ 子模块（占位）
file(GLOB DEPTH_SOURCES "depth/*.cpp")
list(LENGTH DEPTH_SOURCES DEPTH_COUNT)

# fusion/ 子模块（占位）
file(GLOB FUSION_SOURCES "fusion/*.cpp")

# reconstruction/ 子模块（占位）
file(GLOB RECON_SOURCES "reconstruction/*.cpp")

# vin/ 子模块（占位）
file(GLOB VIN_SOURCES "vin/*.cpp")

# calibration/ 子模块（占位）
file(GLOB CALIB_SOURCES "calibration/*.cpp")

# JNI 入口
file(GLOB JNI_SOURCES "jni/*.cpp")

# 主 .so：app 端只 dlopen 这一个
add_library(gomob_native SHARED
    ${JNI_SOURCES}
    ${DEPTH_SOURCES}
    ${FUSION_SOURCES}
    ${RECON_SOURCES}
    ${VIN_SOURCES}
    ${CALIB_SOURCES}
)

target_link_libraries(gomob_native
    log
    android
    GLESv3
    EGL
)

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

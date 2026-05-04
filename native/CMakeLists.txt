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
)

# Berxel SDK（待用户放入 third_party/berxel-android/jniLibs/<abi>/libberxel.so 后启用）
set(BERXEL_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/berxel-android")
if(EXISTS "${BERXEL_SDK_DIR}/include/BerxelHawkContext.h")
    add_definitions(-DGOMOB_HAS_BERXEL=1)
    include_directories("${BERXEL_SDK_DIR}/include")
    add_library(berxel SHARED IMPORTED)
    set_target_properties(berxel PROPERTIES
        IMPORTED_LOCATION "${BERXEL_SDK_DIR}/jniLibs/${ANDROID_ABI}/libberxel.so")
endif()

# depth/ 子模块（占位）
file(GLOB DEPTH_SOURCES "depth/*.cpp")
list(LENGTH DEPTH_SOURCES DEPTH_COUNT)

# fusion/ 子模块（占位）
file(GLOB FUSION_SOURCES "fusion/*.cpp")

# reconstruction/ 子模块（占位）
file(GLOB RECON_SOURCES "reconstruction/*.cpp")

# JNI 入口
file(GLOB JNI_SOURCES "jni/*.cpp")

# 主 .so：app 端只 dlopen 这一个
add_library(gomob_native SHARED
    ${JNI_SOURCES}
    ${DEPTH_SOURCES}
    ${FUSION_SOURCES}
    ${RECON_SOURCES}
)

target_link_libraries(gomob_native
    log
    android
    GLESv3
    EGL
)

if(EXISTS "${BERXEL_SDK_DIR}/include/BerxelHawkContext.h")
    target_link_libraries(gomob_native berxel)
endif()

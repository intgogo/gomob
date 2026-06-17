# ============================================================================
# LIDAR_PTZ -> Linux/C++ migration — M1 headless pipeline build
# ----------------------------------------------------------------------------
# Builds:  lidar_core  (STATIC core library)  +  lidar_cli  (CLI target).
# Layout:  src/{device,cloud,calib,texture,config,pipeline,app}
# Deps  :  PCL + Eigen + OpenCV(+aruco) + Ceres + yaml-cpp + zstd  (M1 headless)
#          vendored header-only: nlohmann/json, cpp-httplib  (third_party/)
# Master spec: re/SPEC.md ; dep rationale: re/spec_deps_build.md
#
# DESIGN RULE: this file must CONFIGURE even when sources/deps are missing.
#   - Every optional dependency is guarded; a missing dep yields a clear
#     status/warning message and disables the dependent target, it does NOT
#     hard-error the configure step (so the scaffold is usable incrementally).
#   - The headless core links NO Qt / CloudCompare / OpenGL (those are M2/GUI).
# ============================================================================
cmake_minimum_required(VERSION 3.20)
project(lidar_ptz_linux
        VERSION 0.1.0
        DESCRIPTION "Headless Linux/C++ port of QtTrainScan/LIDAR_PTZ vehicle scanner"
        LANGUAGES CXX)

# ---------------------------------------------------------------------------
# Standard / build type
# ---------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
  message(STATUS "[lidar] CMAKE_BUILD_TYPE not set -> defaulting to Release")
endif()

# ---------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------
option(LIDAR_USE_LIBCURL "Use libcurl for the REST client instead of vendored cpp-httplib" OFF)
option(LIDAR_BUILD_TESTS "Build offline parity tests against sample/temp ground truth"      ON)
option(LIDAR_BUILD_GUI   "Build the CloudCompare-style Qt/OpenGL viewer (M2, GPL)"          OFF)
option(LIDAR_WERROR      "Treat warnings as errors in our own sources"                       OFF)

# Where the ground-truth PCDs actually live (NOT under sample/temp/, which only
# has points3D.txt). Overridable so CI can point elsewhere.
set(LIDAR_GROUNDTRUTH_DIR "/root/WindowsR/LIDAR_PTZ/temp"
    CACHE PATH "Directory holding ground-truth temp/*.pcd (c64k/heap/laser3D_1)")

# ---------------------------------------------------------------------------
# Dependency discovery — each guarded, collected into LIDAR_MISSING_DEPS.
# A missing CORE dep disables lidar_core (and everything downstream) but does
# NOT fail configuration.
# ---------------------------------------------------------------------------
set(LIDAR_MISSING_DEPS "")

# --- PCL (core: only the components the original exe imports + header-only ICP)
find_package(PCL 1.8 QUIET COMPONENTS common io filters kdtree search segmentation registration features)
if(PCL_FOUND)
  message(STATUS "[lidar] PCL ${PCL_VERSION} found: ${PCL_DIR}")
else()
  message(WARNING "[lidar] PCL (>=1.8, components common io filters kdtree search segmentation "
                  "registration) NOT found. Install: dnf install pcl-devel (EPEL, 1.12).")
  list(APPEND LIDAR_MISSING_DEPS "PCL")
endif()

# --- OpenCV (core: image IO/undistort/projection + ArUco marker detect)
find_package(OpenCV 4 QUIET COMPONENTS core imgproc imgcodecs calib3d)
if(OpenCV_FOUND)
  message(STATUS "[lidar] OpenCV ${OpenCV_VERSION} found: ${OpenCV_DIR}")
  # ArUco moved main->objdetect at 4.7; in 4.6 it is the contrib 'aruco' module.
  # Probe both; calibration (M1c) REQUIRES one of them.
  set(LIDAR_ARUCO_LIBS "")
  find_package(OpenCV 4 QUIET COMPONENTS aruco)
  if(OpenCV_ARUCO_FOUND OR TARGET opencv_aruco)
    set(LIDAR_ARUCO_LIBS opencv_aruco)
    message(STATUS "[lidar] OpenCV ArUco module: 'aruco' (contrib) present.")
  else()
    find_package(OpenCV 4 QUIET COMPONENTS objdetect)
    if(OpenCV_OBJDETECT_FOUND OR TARGET opencv_objdetect)
      set(LIDAR_ARUCO_LIBS opencv_objdetect)
      message(STATUS "[lidar] OpenCV ArUco module: via 'objdetect' (OpenCV>=4.7).")
    else()
      message(WARNING "[lidar] OpenCV present but NO aruco/objdetect module found. "
                      "Calibration marker detection (M1c) will not build. "
                      "Fix: source-build OpenCV 4.6 + opencv_contrib, or install opencv-contrib.")
      list(APPEND LIDAR_MISSING_DEPS "OpenCV-aruco")
    endif()
  endif()
else()
  message(WARNING "[lidar] OpenCV>=4 NOT found. Install: dnf install opencv-devel (EPEL, 4.6).")
  list(APPEND LIDAR_MISSING_DEPS "OpenCV")
endif()

# --- Ceres (core: AutoDiffCostFunction for P2Plane + Reprojection functors)
find_package(Ceres QUIET)
if(Ceres_FOUND OR CERES_FOUND)
  message(STATUS "[lidar] Ceres ${CERES_VERSION} found (Win build was 1.11; distro 2.1 OK).")
else()
  message(WARNING "[lidar] Ceres NOT found. Install: dnf install ceres-solver-devel (EPEL, 2.1).")
  list(APPEND LIDAR_MISSING_DEPS "Ceres")
endif()

# --- Eigen3 (core: header-only; shared by PCL + Ceres + our geometry)
find_package(Eigen3 3.3 QUIET NO_MODULE)
if(TARGET Eigen3::Eigen)
  message(STATUS "[lidar] Eigen3 ${EIGEN3_VERSION_STRING} found.")
else()
  message(WARNING "[lidar] Eigen3>=3.3 NOT found. Install: dnf install eigen3-devel (CRB, 3.4).")
  list(APPEND LIDAR_MISSING_DEPS "Eigen3")
endif()

# --- yaml-cpp (core: parse config.yaml / config150.yaml)
find_package(yaml-cpp QUIET)
if(yaml-cpp_FOUND OR TARGET yaml-cpp OR TARGET yaml-cpp::yaml-cpp)
  message(STATUS "[lidar] yaml-cpp found.")
  if(TARGET yaml-cpp::yaml-cpp)
    set(LIDAR_YAMLCPP_TARGET yaml-cpp::yaml-cpp)   # >=0.8 namespaced target
  else()
    set(LIDAR_YAMLCPP_TARGET yaml-cpp)             # 0.6.x plain target (el9)
  endif()
else()
  message(WARNING "[lidar] yaml-cpp NOT found. Install: dnf install yaml-cpp-devel (EPEL, 0.6).")
  list(APPEND LIDAR_MISSING_DEPS "yaml-cpp")
endif()

# --- zstd (core: inflate the device scan stream) — config pkg or pkg-config
set(LIDAR_ZSTD_TARGET "")
find_package(zstd QUIET)
if(TARGET zstd::libzstd_shared)
  set(LIDAR_ZSTD_TARGET zstd::libzstd_shared)
elseif(TARGET libzstd::libzstd)
  set(LIDAR_ZSTD_TARGET libzstd::libzstd)
else()
  find_package(PkgConfig QUIET)
  if(PkgConfig_FOUND)
    pkg_check_modules(ZSTD QUIET IMPORTED_TARGET libzstd)
    if(TARGET PkgConfig::ZSTD)
      set(LIDAR_ZSTD_TARGET PkgConfig::ZSTD)
    endif()
  endif()
endif()
if(LIDAR_ZSTD_TARGET)
  message(STATUS "[lidar] zstd found -> target ${LIDAR_ZSTD_TARGET}.")
else()
  message(WARNING "[lidar] zstd NOT found. Install: dnf install libzstd-devel (AppStream, 1.5).")
  list(APPEND LIDAR_MISSING_DEPS "zstd")
endif()

# --- Optional REST client backend
if(LIDAR_USE_LIBCURL)
  find_package(CURL QUIET)
  if(CURL_FOUND)
    message(STATUS "[lidar] libcurl ${CURL_VERSION_STRING} found (REST backend).")
  else()
    message(WARNING "[lidar] LIDAR_USE_LIBCURL=ON but libcurl NOT found. "
                    "Install libcurl-devel, or set -DLIDAR_USE_LIBCURL=OFF to use vendored cpp-httplib.")
    list(APPEND LIDAR_MISSING_DEPS "CURL")
  endif()
endif()

# ---------------------------------------------------------------------------
# Vendored header-only third-party (nlohmann/json, cpp-httplib).
# Tolerate absence (scaffold may predate vendoring) with a clear message.
# ---------------------------------------------------------------------------
add_library(lidar_thirdparty INTERFACE)
foreach(_tp nlohmann httplib)
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${_tp}")
    target_include_directories(lidar_thirdparty INTERFACE
      "${CMAKE_CURRENT_SOURCE_DIR}/third_party/${_tp}")
  else()
    message(STATUS "[lidar] third_party/${_tp} not vendored yet "
                   "(add the single header before building device/config sources).")
  endif()
endforeach()

# ---------------------------------------------------------------------------
# Dependency layering (so the FOUNDATION builds before calib/texture deps land).
#   FOUNDATION deps (always required for lidar_core): PCL, Eigen3, yaml-cpp, zstd
#   calib/   sources additionally need: Ceres + OpenCV(+aruco)
#   texture/ sources additionally need: OpenCV
# A subsystem whose extra dep is missing is simply EXCLUDED from the build (with
# a clear message) instead of disabling the whole core. This lets STEP1-3
# (config/cloud) compile while STEP5/6 (calib/texture) deps are still installing.
# ---------------------------------------------------------------------------
macro(_lidar_has_dep _name _out)
  list(FIND LIDAR_MISSING_DEPS "${_name}" _i)
  if(_i EQUAL -1)
    set(${_out} TRUE)
  else()
    set(${_out} FALSE)
  endif()
endmacro()
_lidar_has_dep("Ceres"        LIDAR_HAVE_CERES)
_lidar_has_dep("OpenCV"       LIDAR_HAVE_OPENCV)
_lidar_has_dep("OpenCV-aruco" LIDAR_HAVE_ARUCO)
set(LIDAR_HAVE_CALIB_DEPS FALSE)
if(LIDAR_HAVE_CERES AND LIDAR_HAVE_OPENCV AND LIDAR_HAVE_ARUCO)
  set(LIDAR_HAVE_CALIB_DEPS TRUE)
endif()

# ---------------------------------------------------------------------------
# Source globbing — collect per-subsystem; skip subsystems whose extra deps are
# missing so configuration never fails on a not-yet-installable subsystem.
# ---------------------------------------------------------------------------
set(LIDAR_CORE_SRC "")
foreach(_sub device cloud config pipeline lib)
  file(GLOB _s CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/${_sub}/*.cpp")
  list(APPEND LIDAR_CORE_SRC ${_s})
endforeach()
file(GLOB _calib_s   CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/calib/*.cpp")
file(GLOB _texture_s CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/texture/*.cpp")
if(_calib_s)
  if(LIDAR_HAVE_CALIB_DEPS)
    list(APPEND LIDAR_CORE_SRC ${_calib_s})
  else()
    message(WARNING "[lidar] src/calib/ present but Ceres/OpenCV-aruco missing "
                    "(${LIDAR_MISSING_DEPS}) -> calib sources EXCLUDED until installed.")
  endif()
endif()
if(_texture_s)
  if(LIDAR_HAVE_OPENCV)
    list(APPEND LIDAR_CORE_SRC ${_texture_s})
  else()
    message(WARNING "[lidar] src/texture/ present but OpenCV missing -> texture sources EXCLUDED.")
  endif()
endif()

# Expected M1 source manifest (for reference / to detect not-yet-created files).
# These are the files the M1 plan creates; missing ones are simply skipped here.
set(LIDAR_EXPECTED_SRC
  src/device/http_client.cpp
  src/device/scan_stream.cpp
  src/cloud/cloud_build.cpp
  src/cloud/fusion.cpp
  src/cloud/io_pcd.cpp
  src/calib/calibration_pipeline.cpp
  src/calib/p2plane_functor.cpp
  src/calib/reprojection_functor.cpp
  src/texture/colorizer.cpp
  src/texture/panorama.cpp
  src/config/config_yaml.cpp
  src/config/calibration_json.cpp)

# ---------------------------------------------------------------------------
# Core static library — declared if the FOUNDATION deps + >=1 source exist.
# (calib/texture sources were already filtered out above if their deps are
# missing, so lidar_core links Ceres/OpenCV only when those sources are in.)
# ---------------------------------------------------------------------------
set(LIDAR_FOUNDATION_OK TRUE)
foreach(_d PCL Eigen3 yaml-cpp zstd)
  list(FIND LIDAR_MISSING_DEPS "${_d}" _idx)
  if(NOT _idx EQUAL -1)
    set(LIDAR_FOUNDATION_OK FALSE)
  endif()
endforeach()

# Does the actual source set pull in calib/texture (hence OpenCV/Ceres)?
set(LIDAR_LINK_OPENCV FALSE)
set(LIDAR_LINK_CERES FALSE)
foreach(_f ${LIDAR_CORE_SRC})
  if(_f MATCHES "/src/texture/" OR _f MATCHES "/src/calib/")
    set(LIDAR_LINK_OPENCV TRUE)
  endif()
  if(_f MATCHES "/src/calib/")
    set(LIDAR_LINK_CERES TRUE)
  endif()
endforeach()

if(LIDAR_CORE_SRC AND LIDAR_FOUNDATION_OK)
  add_library(lidar_core STATIC ${LIDAR_CORE_SRC})

  target_include_directories(lidar_core PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    ${PCL_INCLUDE_DIRS})
  if(LIDAR_LINK_OPENCV)
    target_include_directories(lidar_core PUBLIC ${OpenCV_INCLUDE_DIRS})
  endif()

  target_compile_definitions(lidar_core PUBLIC ${PCL_DEFINITIONS})
  if(LIDAR_USE_LIBCURL)
    target_compile_definitions(lidar_core PUBLIC LIDAR_USE_LIBCURL=1)
  endif()

  find_package(Threads REQUIRED)   # cpp-httplib (device HTTP client/server) uses std::thread
  target_link_libraries(lidar_core PUBLIC
    ${PCL_LIBRARIES}
    Eigen3::Eigen
    ${LIDAR_YAMLCPP_TARGET}
    ${LIDAR_ZSTD_TARGET}
    Threads::Threads
    lidar_thirdparty)
  if(LIDAR_LINK_OPENCV)
    target_link_libraries(lidar_core PUBLIC ${OpenCV_LIBS} ${LIDAR_ARUCO_LIBS})
  endif()
  if(LIDAR_LINK_CERES)
    target_link_libraries(lidar_core PUBLIC Ceres::ceres)
  endif()
  if(LIDAR_USE_LIBCURL AND TARGET CURL::libcurl)
    target_link_libraries(lidar_core PUBLIC CURL::libcurl)
  endif()

  # PCL/Ceres drag in legacy deprecation warnings; keep our own warnings on.
  target_compile_options(lidar_core PRIVATE -Wall -Wextra -Wno-deprecated-declarations)
  if(LIDAR_WERROR)
    target_compile_options(lidar_core PRIVATE -Werror)
  endif()

  message(STATUS "[lidar] lidar_core sources: ${LIDAR_CORE_SRC}")

  # 精简 liblidar_scan.a（M8'：gomob 服务端 cmd/laserworker 经 cgo 链接）——只激光「连接+采集+融合」
  # 子集，仅 PCL 核心，无 OpenCV/Ceres/VTK/Qt。device + cloud(fusion/registration/cloud_build/io_pcd)
  # + config + lib/lidar_scan。验证：Go cgo 回放真机 bin 流式点穿过 cgo（见 re/ 或设计文档 §9.2）。
  set(LIDAR_SCAN_SRC
    src/device/scan_stream.cpp src/device/stream_capture.cpp src/device/http_client.cpp
    src/cloud/cloud_build.cpp src/cloud/fusion.cpp src/cloud/registration.cpp src/cloud/io_pcd.cpp
    src/config/config_yaml.cpp src/config/calibration_json.cpp
    src/lib/lidar_scan.cpp)
  if(LIDAR_HAVE_OPENCV)
    list(APPEND LIDAR_SCAN_SRC src/texture/colorizer.cpp)
  endif()
  add_library(lidar_scan STATIC ${LIDAR_SCAN_SRC})
  target_include_directories(lidar_scan PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/src" ${PCL_INCLUDE_DIRS})
  if(LIDAR_HAVE_OPENCV)
    target_include_directories(lidar_scan PUBLIC ${OpenCV_INCLUDE_DIRS})
    target_compile_definitions(lidar_scan PUBLIC LIDAR_SCAN_HAVE_TEXTURE=1)
  endif()
  target_compile_definitions(lidar_scan PUBLIC ${PCL_DEFINITIONS})
  target_link_libraries(lidar_scan PUBLIC
    pcl_common pcl_io pcl_kdtree pcl_search pcl_octree pcl_filters pcl_registration
    pcl_sample_consensus pcl_features
    Eigen3::Eigen ${LIDAR_YAMLCPP_TARGET} ${LIDAR_ZSTD_TARGET} Threads::Threads lidar_thirdparty)
  if(LIDAR_HAVE_OPENCV)
    target_link_libraries(lidar_scan PUBLIC ${OpenCV_LIBS})
  endif()
  target_compile_options(lidar_scan PRIVATE -Wall -Wextra -Wno-deprecated-declarations)
  message(STATUS "[lidar] lidar_scan (slim, cgo) enabled.")
  message(STATUS "[lidar] lidar_core links OpenCV=${LIDAR_LINK_OPENCV} Ceres=${LIDAR_LINK_CERES}")
else()
  if(NOT LIDAR_CORE_SRC)
    message(STATUS "[lidar] No buildable src/**/*.cpp found yet -> lidar_core target skipped.")
  endif()
  if(NOT LIDAR_FOUNDATION_OK)
    message(WARNING "[lidar] Foundation deps missing (need PCL/Eigen3/yaml-cpp/zstd) -> "
                    "lidar_core DISABLED until installed.")
  endif()
endif()

# ---------------------------------------------------------------------------
# CLI executable (M1 entry point)
# ---------------------------------------------------------------------------
if(TARGET lidar_core AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/src/app/main_cli.cpp")
  add_executable(lidar_cli src/app/main_cli.cpp)
  target_link_libraries(lidar_cli PRIVATE lidar_core)
  message(STATUS "[lidar] lidar_cli target enabled.")
elseif(TARGET lidar_core)
  message(STATUS "[lidar] src/app/main_cli.cpp not present yet -> lidar_cli skipped.")
endif()

# ---------------------------------------------------------------------------
# Offline parity tests (validate against the §6 ground-truth oracles)
# ---------------------------------------------------------------------------
if(LIDAR_BUILD_TESTS AND TARGET lidar_core)
  enable_testing()
  set(_gt "${LIDAR_GROUNDTRUTH_DIR}/laser3D_1.pcd")
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_cloud_build.cpp")
    add_executable(test_cloud_build tests/test_cloud_build.cpp)
    target_link_libraries(test_cloud_build PRIVATE lidar_core)
    target_compile_definitions(test_cloud_build PRIVATE
      LIDAR_SAMPLE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/sample")
    if(EXISTS "${_gt}")
      add_test(NAME cloud_build COMMAND test_cloud_build "${_gt}")
    else()
      message(STATUS "[lidar] ground-truth ${_gt} not found; cloud_build test registered "
                     "without it (set -DLIDAR_GROUNDTRUTH_DIR=...).")
      add_test(NAME cloud_build COMMAND test_cloud_build)
    endif()
  else()
    message(STATUS "[lidar] tests/test_cloud_build.cpp not present yet -> tests skipped.")
  endif()

  # Foundation parity test (config + PCD IO + legacy CALIB3D oracle vs §6 ground truth).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_foundation.cpp")
    add_executable(test_foundation tests/test_foundation.cpp)
    target_link_libraries(test_foundation PRIVATE lidar_core)
    target_compile_definitions(test_foundation PRIVATE
      LIDAR_SAMPLE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/sample"
      LIDAR_GROUNDTRUTH_DIR="${LIDAR_GROUNDTRUTH_DIR}")
    add_test(NAME foundation COMMAND test_foundation)
    message(STATUS "[lidar] test_foundation target enabled.")
  endif()

  # Device scan-stream parser test (CRC + synthetic LDR/PTS frames, offline).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_scan_stream.cpp")
    add_executable(test_scan_stream tests/test_scan_stream.cpp)
    target_link_libraries(test_scan_stream PRIVATE lidar_core)
    add_test(NAME scan_stream COMMAND test_scan_stream)
    message(STATUS "[lidar] test_scan_stream target enabled.")
  endif()

  # Cloud transform/fusion/downsample/crop test (offline).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_fusion.cpp")
    add_executable(test_fusion tests/test_fusion.cpp)
    target_link_libraries(test_fusion PRIVATE lidar_core)
    add_test(NAME fusion COMMAND test_fusion)
    message(STATUS "[lidar] test_fusion target enabled.")
  endif()

  # Texture colorizer test (worldToCamera round-trip + projection + coloring). Needs OpenCV.
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_colorizer.cpp" AND LIDAR_LINK_OPENCV)
    add_executable(test_colorizer tests/test_colorizer.cpp)
    target_link_libraries(test_colorizer PRIVATE lidar_core)
    target_compile_definitions(test_colorizer PRIVATE
      LIDAR_SAMPLE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/sample")
    add_test(NAME colorizer COMMAND test_colorizer)
    message(STATUS "[lidar] test_colorizer target enabled.")
  endif()

  # Two-unit ICP registration test (R5; synthetic known-transform recovery).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_registration.cpp")
    add_executable(test_registration tests/test_registration.cpp)
    target_link_libraries(test_registration PRIVATE lidar_core)
    add_test(NAME registration COMMAND test_registration)
    message(STATUS "[lidar] test_registration target enabled.")
  endif()

  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_two_unit_autoregister.cpp")
    add_executable(test_two_unit_autoregister tests/test_two_unit_autoregister.cpp)
    target_link_libraries(test_two_unit_autoregister PRIVATE lidar_core)
    message(STATUS "[lidar] test_two_unit_autoregister target enabled.")
  endif()

  # C-ABI test (M8'-C1): lidar_scan_replay streaming + fusion on real recorded .bin (for cgo hosting).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_capi.cpp"
     AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/out_live/car_101_pts.bin")
    add_executable(test_capi tests/test_capi.cpp)
    target_link_libraries(test_capi PRIVATE lidar_core)
    add_test(NAME capi COMMAND test_capi
             "${CMAKE_CURRENT_SOURCE_DIR}/out_live/car_101_pts.bin"
             "${CMAKE_CURRENT_SOURCE_DIR}/out_live/car_102_pts.bin")
    message(STATUS "[lidar] test_capi target enabled.")
  endif()

  # Vehicle reconstruction orchestrator test (STEP 10: align->union->downsample->crop->save).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_scan_vehicle.cpp")
    add_executable(test_scan_vehicle tests/test_scan_vehicle.cpp)
    target_link_libraries(test_scan_vehicle PRIVATE lidar_core)
    add_test(NAME scan_vehicle COMMAND test_scan_vehicle)
    message(STATUS "[lidar] test_scan_vehicle target enabled.")
  endif()

  # Ceres calibration test (functor residuals + synthetic-recovery solves). Needs Ceres.
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_calibration.cpp" AND LIDAR_LINK_CERES)
    add_executable(test_calibration tests/test_calibration.cpp)
    target_link_libraries(test_calibration PRIVATE lidar_core)
    add_test(NAME calibration COMMAND test_calibration)
    message(STATUS "[lidar] test_calibration target enabled.")
  endif()

  # 现场共享 ArUco 标记场自标定（aggregate + umeyama 复原已知 B->A，合成数据）。需 calib 源(OpenCV+aruco)。
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_site_marker_calib.cpp" AND LIDAR_LINK_OPENCV)
    add_executable(test_site_marker_calib tests/test_site_marker_calib.cpp)
    target_link_libraries(test_site_marker_calib PRIVATE lidar_core)
    add_test(NAME site_marker_calib COMMAND test_site_marker_calib)
    message(STATUS "[lidar] test_site_marker_calib target enabled.")
  endif()

  # Device HTTP client test (builders/parsers + in-process mock-device round-trip).
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_http_client.cpp" AND
     EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/httplib/httplib.h")
    add_executable(test_http_client tests/test_http_client.cpp)
    target_link_libraries(test_http_client PRIVATE lidar_core)
    add_test(NAME http_client COMMAND test_http_client)
    message(STATUS "[lidar] test_http_client target enabled.")
  endif()

  # Cylindrical panorama test (projection geometry + occlusion). Needs OpenCV.
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_panorama.cpp" AND LIDAR_LINK_OPENCV)
    add_executable(test_panorama tests/test_panorama.cpp)
    target_link_libraries(test_panorama PRIVATE lidar_core)
    add_test(NAME panorama COMMAND test_panorama)
    message(STATUS "[lidar] test_panorama target enabled.")
  endif()

  # End-to-end offline replay pipeline test (config + heap.pcd + cam.jpg). Needs OpenCV.
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_pipeline.cpp" AND LIDAR_LINK_OPENCV)
    add_executable(test_pipeline tests/test_pipeline.cpp)
    target_link_libraries(test_pipeline PRIVATE lidar_core)
    target_compile_definitions(test_pipeline PRIVATE
      LIDAR_SAMPLE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/sample"
      LIDAR_GROUNDTRUTH_DIR="${LIDAR_GROUNDTRUTH_DIR}"
      LIDAR_CAMJPG="${LIDAR_GROUNDTRUTH_DIR}/../cam.jpg")
    add_test(NAME pipeline COMMAND test_pipeline)
    message(STATUS "[lidar] test_pipeline target enabled.")
  endif()
endif()

# ---------------------------------------------------------------------------
# M2 GUI viewer (CloudCompare-style Qt/OpenGL, GPL) — guarded; OFF for M1.
# Kept entirely out of lidar_core so the GPL boundary never touches the core.
# ---------------------------------------------------------------------------
if(LIDAR_BUILD_GUI)
  find_package(Qt5 5.12 QUIET COMPONENTS Core Gui Widgets OpenGL Network)
  if(Qt5_FOUND)
    message(STATUS "[lidar] Qt5 ${Qt5_VERSION} found for M2 GUI. "
                   "Link CloudCompare CCCoreLib + qCC_db into lidar_gui ONLY (GPL).")
    # add_executable(lidar_gui src/app/main_gui.cpp ...)   # M2 — not part of M1
  else()
    message(WARNING "[lidar] LIDAR_BUILD_GUI=ON but Qt5>=5.12 not found "
                    "(dnf install qt5-qtbase-devel). GUI target skipped.")
  endif()
endif()

# ---------------------------------------------------------------------------
# Configure-time summary
# ---------------------------------------------------------------------------
message(STATUS "==================== lidar M1 configure summary ====================")
message(STATUS "  Build type        : ${CMAKE_BUILD_TYPE}")
message(STATUS "  C++ standard      : ${CMAKE_CXX_STANDARD}")
if(LIDAR_USE_LIBCURL)
  message(STATUS "  REST backend      : libcurl")
else()
  message(STATUS "  REST backend      : cpp-httplib (vendored)")
endif()
message(STATUS "  Core sources      : ${LIDAR_CORE_SRC}")
if(LIDAR_MISSING_DEPS)
  message(STATUS "  MISSING deps      : ${LIDAR_MISSING_DEPS}  (targets disabled until installed)")
else()
  message(STATUS "  MISSING deps      : none")
endif()
message(STATUS "  Ground-truth dir  : ${LIDAR_GROUNDTRUTH_DIR}")
message(STATUS "  GUI (M2)          : ${LIDAR_BUILD_GUI}")
message(STATUS "====================================================================")

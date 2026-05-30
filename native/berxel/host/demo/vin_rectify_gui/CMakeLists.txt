cmake_minimum_required(VERSION 3.20)

project(GomobBerxelVinRectifyGui LANGUAGES CXX)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 REQUIRED COMPONENTS Core Gui Widgets)
find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBUSB REQUIRED IMPORTED_TARGET libusb-1.0)

set(HOST_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")

add_executable(gomob_berxel_vin_rectify_gui
    main.cpp
    MainWindow.cpp
    MainWindow.h
    ImageView.cpp
    ImageView.h
    BerxelStreamWorker.cpp
    BerxelStreamWorker.h
    "${HOST_ROOT}/../portable/gomob_berxel_portable.cpp"
    "${HOST_ROOT}/src/gomob_berxel_host_sdk.cpp"
)

target_include_directories(gomob_berxel_vin_rectify_gui PRIVATE
    "${HOST_ROOT}/include"
    "${HOST_ROOT}/../portable"
)

target_link_libraries(gomob_berxel_vin_rectify_gui PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Widgets
    PkgConfig::LIBUSB
)

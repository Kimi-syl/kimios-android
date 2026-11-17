cmake_minimum_required(VERSION 3.22.1)
project(kimios-bridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(kimios-bridge SHARED
    native-lib.cpp
    qmp_client.cpp
    rfb_client.cpp
    shm_fb.cpp
    native_log.cpp
)

if(KIMIOS_ANDROID_LIB_DIR)
    target_link_libraries(kimios-bridge
        "${KIMIOS_ANDROID_LIB_DIR}/liblog.so"
        "${KIMIOS_ANDROID_LIB_DIR}/libandroid.so"
    )
else()
    find_library(log-lib log)
    find_library(android-lib android)
    target_link_libraries(kimios-bridge ${log-lib} ${android-lib})
endif()

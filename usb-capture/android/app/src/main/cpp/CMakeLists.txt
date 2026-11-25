cmake_minimum_required(VERSION 3.22.1)
project(usbcapture C)

add_library(uvcstream SHARED
        uvc_stream.c
        crash_handler.c
        jni_bridge.c)

target_compile_options(uvcstream PRIVATE
        -O3 -Wall -Wextra -Wno-unused-parameter
        -fvisibility=hidden -fno-strict-aliasing)

# Android 15+ devices may use 16 KB memory pages.
target_link_options(uvcstream PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(uvcstream log)

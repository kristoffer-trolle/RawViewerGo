cmake_minimum_required(VERSION 3.22.1)
project("rawviewer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored LibRaw 0.22.2 (github.com/LibRaw/LibRaw), LGPL 2.1 / CDDL 1.0 dual-licensed.
# LibRaw has no officially supported CMake build (see libraw/README.cmake), so this globs
# its default source set (matches LibRaw's own Makefile.dist object list - no RawSpeed,
# DNG SDK, external libjpeg or lcms; only USE_ZLIB, using the NDK's bundled zlib).
set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraw)
file(GLOB_RECURSE LIBRAW_SOURCES ${LIBRAW_DIR}/src/*.cpp)
# *_ph.cpp are "placeholder" stubs for a metadata-only build without postprocessing -
# they duplicate symbols from the real implementation files, so exclude them.
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(rawviewer SHARED
        raw_jni.cpp
        ${LIBRAW_SOURCES})

target_include_directories(rawviewer PRIVATE ${LIBRAW_DIR})
target_compile_definitions(rawviewer PRIVATE USE_ZLIB)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(z-lib z)

target_link_libraries(rawviewer ${log-lib} ${jnigraphics-lib} ${z-lib})

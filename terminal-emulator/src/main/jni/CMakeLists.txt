cmake_minimum_required(VERSION 3.22)
project(termux_ghostty LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options($<$<CONFIG:Debug>:-O2>)

if(NOT DEFINED GHOSTTY_ROOT)
    message(FATAL_ERROR "GHOSTTY_ROOT must contain per-ABI libghostty-vt installs")
endif()
if(NOT DEFINED LIBPNG_SRC)
    message(FATAL_ERROR "LIBPNG_SRC must point to the libpng checkout")
endif()
if(NOT DEFINED FREETYPE_SRC)
    message(FATAL_ERROR "FREETYPE_SRC must point to the FreeType checkout")
endif()
if(NOT DEFINED HARFBUZZ_SRC)
    message(FATAL_ERROR "HARFBUZZ_SRC must point to the HarfBuzz checkout")
endif()

set(PNG_SHARED OFF CACHE BOOL "" FORCE)
set(PNG_STATIC ON CACHE BOOL "" FORCE)
set(PNG_TESTS OFF CACHE BOOL "" FORCE)
set(PNG_TOOLS OFF CACHE BOOL "" FORCE)
set(PNG_FRAMEWORK OFF CACHE BOOL "" FORCE)
set(SKIP_INSTALL_ALL ON CACHE BOOL "" FORCE)
add_subdirectory("${LIBPNG_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/libpng" EXCLUDE_FROM_ALL)

set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
add_subdirectory("${FREETYPE_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/freetype" EXCLUDE_FROM_ALL)

set(HB_BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_SUBSET OFF CACHE BOOL "" FORCE)
set(HB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(HB_HAVE_FREETYPE ON CACHE BOOL "" FORCE)
set(HB_HAVE_GLIB OFF CACHE BOOL "" FORCE)
set(HB_HAVE_ICU OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GRAPHITE2 OFF CACHE BOOL "" FORCE)
add_subdirectory("${HARFBUZZ_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/harfbuzz" EXCLUDE_FROM_ALL)

set(GHOSTTY_PREFIX "${GHOSTTY_ROOT}/${ANDROID_ABI}")
add_library(ghostty-vt STATIC IMPORTED GLOBAL)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION "${GHOSTTY_PREFIX}/lib/libghostty-vt.a"
    INTERFACE_INCLUDE_DIRECTORIES "${GHOSTTY_PREFIX}/include")

add_library(termux SHARED
    termux.c
    ghostty_engine.cpp
    ghostty_renderer.cpp)

target_compile_options(termux PRIVATE
    -Wall
    -Wextra
    -Werror
    -fno-stack-protector)
target_compile_definitions(termux PRIVATE GHOSTTY_STATIC)
target_include_directories(termux PRIVATE
    "${GHOSTTY_PREFIX}/include"
    "${CMAKE_CURRENT_BINARY_DIR}/libpng")
target_link_options(termux PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
target_link_libraries(termux PRIVATE
    ghostty-vt
    png_static
    freetype
    harfbuzz
    android
    vulkan
    log
    m)

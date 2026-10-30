# Adapted from llama.cpp examples/llama.android/lib/src/main/cpp/CMakeLists.txt
# (MIT, Copyright (c) 2023-2026 The ggml authors; Android example by Arm).
cmake_minimum_required(VERSION 3.31.6)

project("lpengine" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI} (only arm64-v8a is built)")
    endif()
endif()

# libmtmd (vision and audio projectors) on its own: LLAMA_BUILD_TOOLS stays off (the default for a
# non-standalone llama.cpp) so no servers or CLIs are built. Video input is not used (it needs ffmpeg).
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

# NPU (Hexagon) variant: engine/build.gradle.kts passes -DLP_HEXAGON=ON -DHEXAGON_SDK_ROOT=... for
# `-PlpHexagon` builds only. The public build leaves it off.
option(LP_HEXAGON "Build the opt-in Hexagon NPU backend and its DSP skels" OFF)
if(LP_HEXAGON)
    if(NOT HEXAGON_SDK_ROOT)
        message(FATAL_ERROR "LP_HEXAGON needs HEXAGON_SDK_ROOT (the unpacked Hexagon SDK)")
    endif()
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    # The SDK's hexagon_fun.cmake picks its prebuilt host libraries from this (llama.cpp's
    # snapdragon preset sets the same); left empty, its string(FIND) calls fail at configure.
    set(PREBUILT_LIB_DIR android_aarch64 CACHE STRING "" FORCE)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        lpengine.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        mtmd
        android
        log)

if(LP_HEXAGON)
    # ggml_backend_load_all_from_path() loads every libggml-<backend>.so it finds, and registering
    # the Hexagon backend starts the DSP and makes it the default offload device. Under another
    # name it is loaded only by nativeInitNpu (ggml_backend_load) when the user picks the NPU.
    # The name must match NPU_LIB in lpengine.cpp and LlamaEngine.kt.
    set_target_properties(ggml-hexagon PROPERTIES OUTPUT_NAME lp-hexagon)

    # The DSP opens its skel as file:///libggml-htp-v<arch>.so from ADSP_LIBRARY_PATH, so the skels
    # (built by the htp-v* ExternalProjects into ggml-hexagon's binary dir) go next to the other
    # native libraries (liblpengine.so lives in the CMAKE_LIBRARY_OUTPUT_DIRECTORY Gradle packages),
    # which end up in nativeLibraryDir.
    set(LP_HTP_ARCHS v73 v75 v79 v81 v85)
    get_target_property(LP_HEXAGON_BIN ggml-hexagon BINARY_DIR)
    set(LP_HTP_SKELS)
    foreach(V IN LISTS LP_HTP_ARCHS)
        add_dependencies(${CMAKE_PROJECT_NAME} htp-${V})
        list(APPEND LP_HTP_SKELS ${LP_HEXAGON_BIN}/libggml-htp-${V}.so)
    endforeach()
    add_dependencies(${CMAKE_PROJECT_NAME} ggml-hexagon)
    add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${LP_HTP_SKELS} $<TARGET_FILE_DIR:${CMAKE_PROJECT_NAME}>
            COMMENT "Copying the Hexagon HTP skels next to the native libraries"
            VERBATIM)
endif()

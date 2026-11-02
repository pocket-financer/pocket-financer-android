cmake_minimum_required(VERSION 3.22.1)
project("pocketfinancer_llm")

# ── llama.cpp ────────────────────────────────────────────────────────────────
# FetchContent pulls llama.cpp at a pinned commit so builds are reproducible.
include(FetchContent)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b9198
    GIT_SHALLOW    TRUE
)

# Prevent llama.cpp from building examples / server / tests (we only need libllama)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "")
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "")
set(LLAMA_STANDALONE     ON  CACHE BOOL "")

# CPU-only build for Android. Metal / CUDA / Vulkan / OpenCL / BLAS / RPC all off.
set(GGML_METAL  OFF CACHE BOOL "")
set(GGML_CUDA   OFF CACHE BOOL "")
set(GGML_VULKAN OFF CACHE BOOL "")
set(GGML_OPENCL OFF CACHE BOOL "")
set(GGML_BLAS   OFF CACHE BOOL "")
set(GGML_RPC    OFF CACHE BOOL "")
set(LLAMA_CURL  OFF CACHE BOOL "")

# Only enable KleidiAI on ARM64 ABI
if (ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "Enabling Arm KleidiAI for arm64-v8a target")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    message(STATUS "Disabling Arm KleidiAI for non-arm64-v8a target: ${ANDROID_ABI}")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(llama_cpp)

# ── JNI bridge ───────────────────────────────────────────────────────────────
add_library(pocketfinancer_llm SHARED llama_jni.cpp)

target_include_directories(pocketfinancer_llm PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/common
)

target_link_libraries(pocketfinancer_llm
    llama
    llama-common
    ggml
    ggml-base
    log
)

target_link_options(pocketfinancer_llm PRIVATE "-Wl,-z,max-page-size=16384")

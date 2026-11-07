cmake_minimum_required(VERSION 3.22.1)
project(muesli_qwen LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# Android Studio's Debug configuration passes no -O flag (i.e. -O0), which
# makes on-device llama.cpp inference several times slower than necessary.
# Optimize the kernels + bridge while keeping debug info (-g stays on).
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O2)
endif()
# Static llama + ggml archives linked into the single shared libmuesli-qwen.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/cd959bae99ff4bed22bef2604f45241c09bbb8c8
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(muesli-qwen SHARED local_qwen_jni.cpp)

if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(muesli-qwen PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_link_libraries(muesli-qwen PRIVATE llama ggml)
if(UNIX AND NOT APPLE)
    target_link_options(muesli-qwen PRIVATE -Wl,--exclude-libs,ALL)
endif()

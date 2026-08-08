cmake_minimum_required(VERSION 3.22)
project(grace_embedding LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Reuse the proven local llama.cpp checkout while Grace's native module is stabilized.
set(LLAMA_SRC "/home/ravi/codex/Ask_Galaxy/third_party/llama.cpp")
add_subdirectory(${LLAMA_SRC} llama-build)

add_library(grace_embedding SHARED grace_embedding_jni.cpp)
target_include_directories(grace_embedding PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(grace_embedding PRIVATE llama log android)

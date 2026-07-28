cmake_minimum_required(VERSION 3.22.1)
project(atlas-native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/llama.cpp")
add_subdirectory("${LLAMA_SRC}" build-llama)

add_library(ai-chat SHARED ai_chat.cpp)
target_include_directories(ai-chat PRIVATE
        "${LLAMA_SRC}"
        "${LLAMA_SRC}/common"
        "${LLAMA_SRC}/include"
        "${LLAMA_SRC}/ggml/include"
        "${LLAMA_SRC}/ggml/src")
target_link_libraries(ai-chat llama llama-common android log)

set(SQLITE_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/sqlite")
set(SQLITE_VEC_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/sqlite-vec")
add_library(atlas-sqlite SHARED
        vector_sqlite.cpp
        "${SQLITE_SRC}/sqlite3.c"
        "${SQLITE_VEC_SRC}/sqlite-vec.c")
target_include_directories(atlas-sqlite PRIVATE "${SQLITE_SRC}" "${SQLITE_VEC_SRC}")
target_compile_definitions(atlas-sqlite PRIVATE
        SQLITE_CORE
        SQLITE_VEC_STATIC
        SQLITE_ENABLE_FTS5
        SQLITE_THREADSAFE=2
        SQLITE_DEFAULT_MEMSTATUS=0
        SQLITE_OMIT_DEPRECATED
        SQLITE_DQS=0)
target_compile_options(atlas-sqlite PRIVATE -O3 -ffast-math)
target_link_libraries(atlas-sqlite android log)

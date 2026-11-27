cmake_minimum_required(VERSION 3.18)
project(MemDiagnosticEngine VERSION 2.1.0 LANGUAGES C CXX ASM)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(UNIX)
    add_compile_definitions(_GNU_SOURCE)
endif()

option(MEMENGINE_BUILD_JNI "Build Android JNI shared library target" ON)

# Architecture & SIMD Detection (ARM64 NEON, ARMv7 NEON, x86 / x86_64)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    message(STATUS "[MemEngine] Target Architecture: ARM64 (AArch64 NEON SIMD + ShadowHook enabled)")
    set(MEMENGINE_CXX_FLAGS -O3 -Wall -Wextra -fno-strict-aliasing)
    set(SH_ARCH "arm64")
    set(HAS_SHADOWHOOK ON)
    add_link_options(-Wl,-z,max-page-size=16384)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "arm|armv7|armeabi-v7a")
    message(STATUS "[MemEngine] Target Architecture: ARMv7 (NEON SIMD + ShadowHook enabled)")
    set(MEMENGINE_CXX_FLAGS -O3 -Wall -Wextra -mfpu=neon -mfloat-abi=softfp -fno-strict-aliasing)
    set(SH_ARCH "arm")
    set(HAS_SHADOWHOOK ON)
    add_link_options(-Wl,-z,max-page-size=16384)
else()
    message(STATUS "[MemEngine] Target Architecture: ${CMAKE_SYSTEM_PROCESSOR} (x86 / x86_64)")
    set(MEMENGINE_CXX_FLAGS -O3 -Wall -Wextra -fno-strict-aliasing -pthread)
    set(HAS_SHADOWHOOK OFF)
    add_link_options(-Wl,-z,max-page-size=16384)
endif()

find_package(Threads REQUIRED)

# ============================================================================
# 1. User-supplied xDL v2.4.0 (pure C17 static library)
# ============================================================================
set(XDL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/xdl")
file(GLOB XDL_SOURCES "${XDL_DIR}/*.c")
add_library(xdl STATIC ${XDL_SOURCES})
target_compile_features(xdl PRIVATE c_std_17)
target_compile_options(xdl PRIVATE -Os -ffunction-sections -fdata-sections -Wno-unused-parameter)
target_include_directories(xdl
    PUBLIC  "${XDL_DIR}/include"
    PRIVATE "${XDL_DIR}"
)

# ============================================================================
# 2. ByteDance ShadowHook (linked cleanly against our external xDL v2.4.0)
#    ShadowHook supports arm64-v8a and armeabi-v7a natively.
# ============================================================================
if(HAS_SHADOWHOOK)
    set(SH_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/shadowhook")
    file(GLOB SH_SOURCES
        "${SH_DIR}/*.c"
        "${SH_DIR}/arch/${SH_ARCH}/*.c"
        "${SH_DIR}/arch/${SH_ARCH}/*.S"
        "${SH_DIR}/common/*.c"
        "${SH_DIR}/third_party/bsd/*.c"
        "${SH_DIR}/third_party/lss/*.c"
    )
    add_library(shadowhook STATIC ${SH_SOURCES})
    target_compile_features(shadowhook PRIVATE c_std_11)
    target_compile_options(shadowhook PRIVATE -Os -ffunction-sections -fdata-sections -Wno-unused-parameter -Wno-sign-compare)
    target_include_directories(shadowhook
        PUBLIC  "${SH_DIR}/include"
        PRIVATE "${SH_DIR}"
                "${SH_DIR}/arch/${SH_ARCH}"
                "${SH_DIR}/common"
                "${SH_DIR}/third_party/bsd"
                "${SH_DIR}/third_party/lss"
    )
    target_link_libraries(shadowhook PUBLIC xdl)
    if(ANDROID)
        find_library(log-lib log)
        target_link_libraries(shadowhook PUBLIC ${log-lib})
    endif()
endif()

# ============================================================================
# 2.5 Embedded Lua 5.5.1 VM (Static C Library with Lua 5.3/5.5 Compat Shims)
# ============================================================================
set(LUA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/lua")
file(GLOB LUA_SOURCES "${LUA_DIR}/*.c")
list(REMOVE_ITEM LUA_SOURCES
    "${LUA_DIR}/lua.c"
    "${LUA_DIR}/luac.c"
    "${LUA_DIR}/onelua.c"
)
add_library(lua_static STATIC ${LUA_SOURCES})
target_compile_features(lua_static PRIVATE c_std_17)
target_compile_definitions(lua_static PUBLIC
    LUA_COMPAT_GLOBAL=1
    LUA_COMPAT_5_3
    LUA_COMPAT_MATHLIB
    LUA_USE_POSIX
    LUA_USE_DLOPEN
)
target_compile_options(lua_static PRIVATE -O2 -ffunction-sections -fdata-sections -Wno-unused-parameter -Wno-sign-compare)
target_include_directories(lua_static PUBLIC "${LUA_DIR}")
target_link_libraries(lua_static PUBLIC m dl)

# ============================================================================
# 3. Core Memory, Debugger, Dynamic Assembly & Embedded Lua 5.5.1 Engine
# ============================================================================
set(MEMENGINE_CORE_SOURCES
    src/maps_parser.cpp
    src/process_enum.cpp
    src/proc_io.cpp
    src/mmap_store.cpp
    src/simd_kernel.cpp
    src/search_engine.cpp
    src/asm_engine.cpp
    src/debugger.cpp
    src/remote_alloc.cpp
    src/lua_engine.cpp
)

add_library(mem_engine_core STATIC ${MEMENGINE_CORE_SOURCES})
target_compile_options(mem_engine_core PRIVATE ${MEMENGINE_CXX_FLAGS})
target_include_directories(mem_engine_core PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<BUILD_INTERFACE:${LUA_DIR}>
)
target_link_libraries(mem_engine_core PUBLIC lua_static Threads::Threads dl m)

# ============================================================================
# 4. JNI Library, Root Daemon Executable & ShadowHook Speedhack Agent
# ============================================================================
if(MEMENGINE_BUILD_JNI)
    # ShadowHook + xDL Agent library (libggnext_agent.so)
    add_library(ggnext_agent SHARED
        src/ggnext_agent.cpp
    )
    target_compile_options(ggnext_agent PRIVATE ${MEMENGINE_CXX_FLAGS})
    target_link_options(ggnext_agent PRIVATE -Wl,--exclude-libs,ALL -Wl,--no-undefined)
    target_link_libraries(ggnext_agent PRIVATE xdl Threads::Threads dl)
    if(HAS_SHADOWHOOK)
        target_link_libraries(ggnext_agent PRIVATE shadowhook)
    endif()

    # Main JNI library (libmem_engine_jni.so)
    # Linked cleanly against c++_static without DT_NEEDED on libggnext_agent.so
    # to avoid leaking/importing libc++ symbols across shared libraries.
    add_library(mem_engine_jni SHARED
        jni/mem_engine_jni.cpp
    )
    target_compile_options(mem_engine_jni PRIVATE ${MEMENGINE_CXX_FLAGS})
    target_link_options(mem_engine_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--no-undefined)
    target_include_directories(mem_engine_jni PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    )
    target_link_libraries(mem_engine_jni PUBLIC mem_engine_core xdl Threads::Threads dl)

    # Root helper daemon executable (packaged as libmem_engine_root.so)
    add_executable(mem_engine_root
        src/root_helper_main.cpp
    )
    target_compile_options(mem_engine_root PRIVATE ${MEMENGINE_CXX_FLAGS} -fPIE)
    target_include_directories(mem_engine_root PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    )
    target_link_options(mem_engine_root PRIVATE -pie)
    target_link_libraries(mem_engine_root PRIVATE mem_engine_core Threads::Threads dl)
    set_target_properties(mem_engine_root PROPERTIES
        OUTPUT_NAME "mem_engine_root.so"
        PREFIX "lib"
    )
    if(DEFINED CMAKE_LIBRARY_OUTPUT_DIRECTORY)
        set_target_properties(mem_engine_root PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
        )
    endif()

    if(ANDROID)
        find_library(log-lib log)
        target_link_libraries(ggnext_agent PRIVATE ${log-lib})
        target_link_libraries(mem_engine_jni PUBLIC ${log-lib})
        target_link_libraries(mem_engine_root PRIVATE ${log-lib})
    endif()
endif()

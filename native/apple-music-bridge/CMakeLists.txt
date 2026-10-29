cmake_minimum_required(VERSION 3.21)
project(dango_apple_music_bridge VERSION 1.0 LANGUAGES CXX)

# Runtime: Windows 10 1809 (17763) or newer. Uses only official read-only SMTC APIs.
# Build with VS 2022 and the Windows SDK 10.0.22621.0:
# cmake -S native/apple-music-bridge -B native/apple-music-bridge/build -A x64 -DCMAKE_SYSTEM_VERSION=10.0.22621.0
# cmake --build native/apple-music-bridge/build --config Release
# ctest --test-dir native/apple-music-bridge/build -C Release --output-on-failure
# cmake --install native/apple-music-bridge/build --config Release --prefix common/core/src/main/resources
# Install BEFORE building the mod; the core resources are shaded into its jar.
# Only protocol tests can run on non-Windows hosts. No external dependencies.
option(DANGO_PROTOCOL_TESTS_ONLY "Build portable protocol tests without Windows SDK" OFF)
include(CTest)

if(NOT DANGO_PROTOCOL_TESTS_ONLY)
    if(NOT WIN32 OR NOT MSVC OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
        message(FATAL_ERROR "The helper requires Windows x64 and MSVC with C++/WinRT SDK headers")
    endif()
    add_executable(dango-apple-music main.cpp)
    target_compile_features(dango-apple-music PRIVATE cxx_std_17)
    target_compile_definitions(dango-apple-music PRIVATE
        WIN32_LEAN_AND_MEAN NOMINMAX WINVER=0x0A00 _WIN32_WINNT=0x0A00)
    target_compile_options(dango-apple-music PRIVATE /W4 /WX /permissive- /utf-8 /EHsc)
    target_link_libraries(dango-apple-music PRIVATE windowsapp)
    # Avoid a separately installed Visual C++ runtime on the user's machine.
    set_property(TARGET dango-apple-music PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    target_link_options(dango-apple-music PRIVATE /Brepro)
    install(TARGETS dango-apple-music RUNTIME DESTINATION native/windows-x64)
endif()

if(BUILD_TESTING)
    add_executable(bridge-protocol-tests protocol_tests.cpp)
    target_compile_features(bridge-protocol-tests PRIVATE cxx_std_17)
    if(MSVC)
        target_compile_options(bridge-protocol-tests PRIVATE /W4 /WX /utf-8)
    else()
        target_compile_options(bridge-protocol-tests PRIVATE -Wall -Wextra -Werror -pedantic)
    endif()
    add_test(NAME bridge-protocol COMMAND bridge-protocol-tests)
endif()

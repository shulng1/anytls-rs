cmake_minimum_required(VERSION 3.22.1)
project(anytls_jni C)

set(CMAKE_C_STANDARD 11)

add_library(anytls_jni SHARED anytls_jni.c)
target_include_directories(anytls_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

# Link libanytls.so by name so DT_NEEDED stays "libanytls.so" instead of an
# absolute build-host path (the library has no SONAME).
target_link_directories(anytls_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
target_link_libraries(anytls_jni PRIVATE anytls)

# 16 KB page size compatibility (Android 15+)
target_link_options(anytls_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")

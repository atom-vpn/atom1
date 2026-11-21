cmake_minimum_required(VERSION 3.22.1)
project(atom_engine_native)

# The extracted repository stores imported native dependencies under vendor/.
set(AETHER_SO "${CMAKE_CURRENT_LIST_DIR}/../vendor/jniLibs/${ANDROID_ABI}/libaether.so")

if(EXISTS "${AETHER_SO}")
    add_library(aether SHARED IMPORTED)
    set_target_properties(aether PROPERTIES
        IMPORTED_LOCATION "${AETHER_SO}"
        IMPORTED_NO_SONAME TRUE)

    add_library(aether_jni SHARED aether_jni.cpp)
    target_link_options(aether_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
    find_library(log-lib log)
    target_link_libraries(aether_jni aether ${log-lib})
endif()

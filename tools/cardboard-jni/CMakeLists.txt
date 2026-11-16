cmake_minimum_required(VERSION 3.22.1)
project(cardboard_sdk_jni CXX)

# 照抄 googlevr/cardboard v1.36.0 的 paperscope/CMakeLists.txt:94-105,123。
#
# 官方 :sdk:assembleDebug 产出的 AAR 只含 libGfxPluginCardboard.so（C API，
# 供 Unity 插件与 NDK demo 用）。Java 层的 Initialize / HeadTracker /
# LensDistortion 需要的 JNI 桥接库 libcardboard_sdk_jni.so 定义在 paperscope
# 这个 demo app 里，sdk AAR 天生不含它；缺它会在 Initialize.<clinit> 抛
# UnsatisfiedLinkError（alpha39 真机崩溃的根因）。
#
# 这里把那个 target 原样摘出来单独构建：源文件 100% 来自官方仓库，
# 没有一行自研 JNI 代码，只是绕开 paperscope 的 freetype / launcher 等无关 target。

set(CARDBOARD_SDK_DIR "" CACHE PATH "official sdk/ directory")
set(CARDBOARD_JAVA_API_DIR "" CACHE PATH "official java_api/ directory")
set(CARDBOARD_LIB "" CACHE FILEPATH "prebuilt libGfxPluginCardboard.so for this ABI")

foreach(required CARDBOARD_SDK_DIR CARDBOARD_JAVA_API_DIR CARDBOARD_LIB)
  if(NOT ${required})
    message(FATAL_ERROR "missing -D${required}=...")
  endif()
endforeach()

find_library(ANDROID_LIB android)
find_library(LOG_LIB log)
find_library(GLESv2_LIB GLESv2)

add_library(cardboard_sdk_jni SHARED
    ${CARDBOARD_JAVA_API_DIR}/src/main/jni/cardboard_jni.cc
    ${CARDBOARD_SDK_DIR}/jni_utils/android/jni_utils.cc
)

target_include_directories(cardboard_sdk_jni PRIVATE ${CARDBOARD_SDK_DIR})

target_link_libraries(cardboard_sdk_jni PRIVATE
    ${CARDBOARD_LIB}
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${GLESv2_LIB}
)

# Android 15+ 的 16KB page size 要求；官方 paperscope 同样设置。
target_link_options(cardboard_sdk_jni PRIVATE "-Wl,-z,max-page-size=16384")

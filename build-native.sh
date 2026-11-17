#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ -f /opt/tenv.sh ]; then
    . /opt/tenv.sh
fi

TOOLCHAIN="${KIMIOS_TOOLCHAIN:-/opt/native-toolchain.cmake}"
STUB_DIR="${KIMIOS_ANDROID_LIB_DIR:-/opt/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26}"
BUILD_DIR=build-native
JNI_DIR=app/src/main/jniLibs/arm64-v8a

cmake -S app/src/main/cpp -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DKIMIOS_ANDROID_LIB_DIR="$STUB_DIR"
cmake --build "$BUILD_DIR" -j"${KIMIOS_BUILD_JOBS:-2}"

mkdir -p "$JNI_DIR"
cp "$BUILD_DIR/libkimios-bridge.so" "$JNI_DIR/"
if [ -f /opt/tusr/lib/libc++_shared.so ]; then
    cp /opt/tusr/lib/libc++_shared.so "$JNI_DIR/"
fi

AAPT2_ARGS=()
if [ -x /opt/aapt2arm/build-tools/aapt2 ]; then
    AAPT2_ARGS+=("-Pandroid.aapt2FromMavenOverride=/opt/aapt2arm/build-tools/aapt2")
fi
JAVA_HOME=${JAVA_HOME:-/opt/jdk17} ./gradlew --no-daemon "${AAPT2_ARGS[@]}" :app:assembleDebug

ls -l app/build/outputs/apk/debug/app-debug.apk

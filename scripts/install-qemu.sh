#!/bin/sh
set -e
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ $# -lt 1 ]; then
    echo "usage: $0 <path-to-android-qemu-system-aarch64> [lib-dir]" >&2
    echo "The binary must run under Android bionic (static or NDK build);" >&2
    echo "the glibc-linked proot/Termux binary will not work." >&2
    echo "It is packaged as jniLibs/arm64-v8a/libqemu.so so Android allows" >&2
    echo "executing it from nativeLibraryDir (W^X restriction)." >&2
    exit 1
fi
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
cp "$1" "$DEST/libqemu.so"
chmod 755 "$DEST/libqemu.so"
if [ $# -ge 2 ]; then
    LIBDEST="$ROOT/app/src/main/assets/qemu/lib"
    mkdir -p "$LIBDEST"
    cp -a "$2"/. "$LIBDEST/"
fi
echo "installed; rebuild with ./build-native.sh"

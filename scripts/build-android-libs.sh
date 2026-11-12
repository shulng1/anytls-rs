#!/usr/bin/env bash
# Cross-compile the AnyTLS FFI shared library for all Android ABIs.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-android-libs.sh
#
# Output: android/app/src/main/jniLibs/<abi>/libanytls.so
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
    echo "error: set ANDROID_NDK_HOME to an Android NDK directory" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=29
OUT="$ROOT/android/app/src/main/jniLibs"

if [[ ! -d "$TC" ]]; then
    echo "error: unsupported host, missing toolchain: $TC" >&2
    exit 1
fi

# Android 15+ requires 16 KB page alignment for native libraries.
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

build_abi() {
    local abi="$1" triple="$2" cc="$3"

    export "CC_${triple//-/_}=$TC/bin/$cc"
    export "CXX_${triple//-/_}=$TC/bin/${cc}++"
    export "AR_${triple//-/_}=$TC/bin/llvm-ar"
    export "CARGO_TARGET_$(echo "$triple" | tr '[:lower:]-' '[:upper:]_')_LINKER=$TC/bin/$cc"

    echo "==> $abi ($triple)"
    cargo build --manifest-path "$ROOT/Cargo.toml" \
        --release --no-default-features --features ffi --target "$triple"

    mkdir -p "$OUT/$abi"
    cp "$ROOT/target/$triple/release/libanytls.so" "$OUT/$abi/libanytls.so"
    "$TC/bin/llvm-strip" --strip-unneeded "$OUT/$abi/libanytls.so"
}

build_abi arm64-v8a aarch64-linux-android "aarch64-linux-android$API-clang"
build_abi armeabi-v7a armv7-linux-androideabi "armv7a-linux-androideabi$API-clang"
build_abi x86 i686-linux-android "i686-linux-android$API-clang"
build_abi x86_64 x86_64-linux-android "x86_64-linux-android$API-clang"

echo "==> done"
ls -lh "$OUT"/*/libanytls.so

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="${ANDROID_NDK_VERSION:-26.3.11579264}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/${NDK_VERSION}}"
NDK_BUILD="${ANDROID_NDK_ROOT}/ndk-build"

# Pin upstream and its gitlink dependencies. Do not follow a moving branch.
HEV_REPOSITORY="https://github.com/heiher/hev-socks5-tunnel.git"
HEV_REVISION="fc3f90040b8d4b537341c96538669a9b2e35d514"

if [[ ! -x "${NDK_BUILD}" ]]; then
  echo "Android ndk-build not found: ${NDK_BUILD}" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

git clone --quiet --recurse-submodules "${HEV_REPOSITORY}" "${WORK_DIR}/hev"
git -C "${WORK_DIR}/hev" checkout --quiet "${HEV_REVISION}"
git -C "${WORK_DIR}/hev" submodule update --init --recursive --quiet

# Keep the upstream build definition intact; only override the JNI class contract.
cp "${WORK_DIR}/hev/Application.mk" "${WORK_DIR}/Application.atom.mk"
cat >> "${WORK_DIR}/Application.atom.mk" <<'EOF'
APP_CFLAGS += -DPKGNAME=com/atomvpn/mobile/engine/tor -DCLSNAME=AtomHevSocks5Tunnel
EOF

mkdir -p "${ROOT}/vendor/jniLibs"
"${NDK_BUILD}" \
  NDK_PROJECT_PATH="${WORK_DIR}/hev" \
  APP_BUILD_SCRIPT="${WORK_DIR}/hev/Android.mk" \
  NDK_APPLICATION_MK="${WORK_DIR}/Application.atom.mk" \
  NDK_OUT="${WORK_DIR}/obj" \
  NDK_LIBS_OUT="${ROOT}/vendor/jniLibs" \
  -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"

for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  library="${ROOT}/vendor/jniLibs/${abi}/libhev-socks5-tunnel.so"
  test -s "${library}" || { echo "Missing HEV output for ${abi}" >&2; exit 1; }
  file "${library}"
done

echo "HEV JNI rebuilt for all four Android ABIs with Atom package binding."

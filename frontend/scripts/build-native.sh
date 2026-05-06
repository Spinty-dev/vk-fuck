#!/usr/bin/env bash
# Builds the Go binaries that vkturn bundles into each distribution.
#
# Outputs go into:
#   composeApp/native/<os-arch>/            for the desktop bundle
#   composeApp/src/androidMain/jniLibs/<abi>/libvkturnclient.so  for APK
#   composeApp/src/androidMain/jniLibs/<abi>/libsingbox.so       for APK
#
# Requires: go (>= 1.22). For Android armeabi-v7a and x86_64: ANDROID_NDK and
# CGO (Go uses NDK clang for external linking on those GOARCH values).
# sing-box is built from module source in a temp dir (cross-compile + go install
# limitations).
#
# Usage:
#   ./scripts/build-native.sh all              # all targets
#   ./scripts/build-native.sh desktop-linux    # only host desktop
#   ./scripts/build-native.sh android          # only android ABIs

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$(cd "$HERE/.." && pwd)"
REPO_DIR="$(cd "$FRONTEND_DIR/.." && pwd)"

VKP_SRC="${VKTURN_PROXY_SRC:-$REPO_DIR/vk-turn-proxy}"
SINGBOX_REPO="${SINGBOX_REPO:-github.com/sagernet/sing-box/cmd/sing-box}"
SINGBOX_VERSION="${SINGBOX_VERSION:-latest}"

NATIVE_DIR="$FRONTEND_DIR/composeApp/native"
JNILIBS_DIR="$FRONTEND_DIR/composeApp/src/androidMain/jniLibs"

require_go() {
    command -v go >/dev/null 2>&1 || { echo "go not in PATH" >&2; exit 1; }
}

build_desktop() {
    local goos=$1 goarch=$2 compose_dir=$3 suffix=${4:-}
    echo "==> desktop: $goos/$goarch -> $compose_dir"
    require_go
    mkdir -p "$NATIVE_DIR/$compose_dir"

    echo "    - vk-turn-proxy client"
    (cd "$VKP_SRC" && CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
        go build -trimpath -ldflags="-s -w" \
        -o "$NATIVE_DIR/$compose_dir/vkturn-client$suffix" ./client)

    echo "    - sing-box"
    CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
        GOBIN="$NATIVE_DIR/$compose_dir" \
        go install -trimpath -ldflags="-s -w" \
        "$SINGBOX_REPO@$SINGBOX_VERSION"
    if [[ -n "$suffix" && -f "$NATIVE_DIR/$compose_dir/sing-box" ]]; then
        mv "$NATIVE_DIR/$compose_dir/sing-box" "$NATIVE_DIR/$compose_dir/sing-box$suffix"
    fi
}

build_android() {
    local goarch=$1 goarm=${2:-} abi=$3
    echo "==> android: $goarch/$goarm -> jniLibs/$abi"
    require_go
    mkdir -p "$JNILIBS_DIR/$abi"

    # android/arm and android/amd64 use external linking — needs CGO + NDK.
    # (android/arm64 links internally with CGO_ENABLED=0.)
    local cgo_enabled=0
    local cc=""
    local cxx=""
    local ndk="${ANDROID_NDK:-}"
    local host_tag="linux-x86_64"
    case "$(uname -s)" in
        Darwin) [[ "$(uname -m)" == "arm64" ]] && host_tag="darwin-arm64" || host_tag="darwin-x86_64" ;;
    esac
    local bindir=""
    [[ -n "$ndk" && -d "$ndk" ]] && bindir="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"

    case "$goarch" in
        arm)
            cgo_enabled=1
            [[ -n "$bindir" ]] || { echo "error: armeabi-v7a requires ANDROID_NDK" >&2; exit 1; }
            cc="$bindir/armv7a-linux-androideabi26-clang"
            cxx="$bindir/armv7a-linux-androideabi26-clang++"
            ;;
        amd64)
            cgo_enabled=1
            [[ -n "$bindir" ]] || { echo "error: android x86_64 requires ANDROID_NDK" >&2; exit 1; }
            cc="$bindir/x86_64-linux-android26-clang"
            cxx="$bindir/x86_64-linux-android26-clang++"
            ;;
    esac
    if [[ "$cgo_enabled" == "1" && ! -x "$cc" ]]; then
        echo "error: missing NDK compiler: $cc" >&2
        exit 1
    fi

    # vk-turn-proxy (anet uses //go:linkname into net; Go 1.23+ needs this)
    (
        cd "$VKP_SRC" || exit 1
        export CGO_ENABLED="$cgo_enabled"
        [[ -n "$cc" ]] && export CC="$cc" CXX="$cxx"
        GOOS=android GOARCH="$goarch" GOARM="$goarm" \
            go build -trimpath -ldflags="-s -w -checklinkname=0" \
            -o "$JNILIBS_DIR/$abi/libvkturnclient.so" ./client
    )

    # sing-box — ephemeral module: `go build path@version` is invalid; `go install`
    # cannot write to GOBIN when cross-compiling.
    local _sb_tmp
    _sb_tmp="$(mktemp -d)"
    (
        cd "$_sb_tmp" || exit 1
        go mod init singbox-android-bundle
        go get "${SINGBOX_REPO}@${SINGBOX_VERSION}"
        export CGO_ENABLED="$cgo_enabled"
        [[ -n "$cc" ]] && export CC="$cc" CXX="$cxx"
        GOOS=android GOARCH="$goarch" GOARM="$goarm" \
            go build -trimpath -ldflags="-s -w -checklinkname=0" \
            -tags="with_quic,with_grpc,with_utls,with_gvisor" \
            -o "$JNILIBS_DIR/$abi/libsingbox.so" \
            "$SINGBOX_REPO"
    )
    rm -rf "$_sb_tmp"
}

build_daemon() {
    local goos=$1 goarch=$2 outname=$3 suffix=${4:-}
    local daemon_src="$REPO_DIR/daemon"
    if [[ ! -d "$daemon_src" ]]; then
        echo "(skip) daemon source tree not found at $daemon_src"
        return 0
    fi
    echo "==> daemon: $goos/$goarch -> $outname$suffix"
    require_go
    (cd "$daemon_src" && CGO_ENABLED=0 GOOS="$goos" GOARCH="$goarch" \
        go build -trimpath -ldflags="-s -w" \
        -o "$NATIVE_DIR/$outname/vkturnd$suffix" .)
}

cmd="${1:-all}"
case "$cmd" in
    desktop-linux)   build_desktop linux   amd64 linux-x64;   build_daemon linux amd64 linux-x64 ;;
    desktop-macos-x64) build_desktop darwin amd64 macos-x64 ;;
    desktop-macos-arm64) build_desktop darwin arm64 macos-arm64 ;;
    desktop-windows) build_desktop windows amd64 windows-x64 .exe; build_daemon windows amd64 windows-x64 .exe ;;
    android)
        build_android arm64 ""  arm64-v8a
        build_android arm   "7" armeabi-v7a
        build_android amd64 ""  x86_64
        ;;
    all)
        "$0" desktop-linux
        "$0" desktop-macos-x64 || true
        "$0" desktop-macos-arm64 || true
        "$0" desktop-windows
        "$0" android
        ;;
    *)
        echo "usage: $0 {desktop-linux|desktop-macos-x64|desktop-macos-arm64|desktop-windows|android|all}" >&2
        exit 2
        ;;
esac

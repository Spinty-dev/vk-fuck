#!/usr/bin/env bash
# Installs vkturnd as a systemd service. Needs to run as root.
#
#   sudo ./install.sh install        # build + install + enable
#   sudo ./install.sh uninstall      # stop, disable, remove files
#   sudo ./install.sh status         # print service status

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN_DIR=/usr/lib/vkturn
UNIT_PATH=/etc/systemd/system/vkturnd.service
GROUP=vkturn

ensure_root() {
    if [[ $EUID -ne 0 ]]; then
        echo "must run as root (try: sudo $0 $*)" >&2
        exit 1
    fi
}

ensure_group() {
    if ! getent group "$GROUP" >/dev/null; then
        echo "==> creating group $GROUP"
        groupadd --system "$GROUP"
    fi
}

build_binary() {
    command -v go >/dev/null 2>&1 || { echo "go not in PATH" >&2; exit 1; }
    echo "==> building vkturnd"
    (cd "$HERE" && go build -trimpath -ldflags="-s -w" -o vkturnd .)
}

install_files() {
    ensure_group
    mkdir -p "$BIN_DIR"
    install -m 0755 "$HERE/vkturnd" "$BIN_DIR/vkturnd"

    # Stage sing-box/vkturn-client copies next to the daemon
    # so it can find them without PATH lookups (needed for ProtectSystem=strict).
    for name in sing-box vkturn-client; do
        if [[ -x "$HERE/bin/$name" ]]; then
            install -m 0755 "$HERE/bin/$name" "$BIN_DIR/$name"
        elif command -v "$name" &>/dev/null; then
            # Copy from system PATH if available
            sys_path="$(command -v "$name")"
            install -m 0755 "$sys_path" "$BIN_DIR/$name"
            echo "==> staged $name from $sys_path to $BIN_DIR/"
        fi
    done

    install -m 0644 "$HERE/vkturnd.service" "$UNIT_PATH"

    systemctl daemon-reload
    systemctl enable vkturnd.service
    systemctl restart vkturnd.service

    cat <<EOF

Installed. To let a desktop user talk to the daemon without sudo:

    sudo usermod -a -G $GROUP "\$USER"
    # then log out / log in, or run:  newgrp $GROUP

The control socket lives at /run/vkturn/control.sock.
EOF
}

uninstall_files() {
    systemctl disable --now vkturnd.service || true
    rm -f "$UNIT_PATH" "$BIN_DIR/vkturnd"
    rmdir --ignore-fail-on-non-empty "$BIN_DIR" || true
    systemctl daemon-reload
    echo "removed."
}

cmd="${1:-install}"
case "$cmd" in
    install)
        ensure_root
        build_binary
        install_files
        ;;
    uninstall)
        ensure_root
        uninstall_files
        ;;
    status)
        systemctl status vkturnd.service --no-pager || true
        ;;
    *)
        echo "usage: $0 {install|uninstall|status}" >&2
        exit 2
        ;;
esac

#!/bin/bash
# makepkg-style installer for vkturnd
# Does everything: build, install to /usr/bin, set up root access
#
# Usage: sudo ./makepkg.sh

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN_DIR=/usr/bin

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

die() { echo -e "${RED}ERROR:${NC} $*" >&2; exit 1; }
info() { echo -e "${GREEN}==>${NC} $*"; }
warn() { echo -e "${YELLOW}WARN:${NC} $*"; }

# Must run as root
[[ $EUID -eq 0 ]] || die "Must run as root (try: sudo $0)"

command -v go &>/dev/null || die "go is required"

info "Building vkturnd..."
cd "$HERE"
go build -trimpath -ldflags="-s -w" -o vkturnd . || die "build vkturnd failed"

go build -trimpath -ldflags="-s -w" -o vkturnctl ./cmd/vkturnctl || die "build vkturnctl failed"

info "Installing to $BIN_DIR..."
install -Dm755 vkturnd "$BIN_DIR/vkturnd"
install -Dm755 vkturnctl "$BIN_DIR/vkturnctl"

# Create the magic wrapper that gives root via capabilities
info "Setting up capabilities (root access without setuid)..."

# Method 1: Linux capabilities (preferred - secure)
if command -v setcap &>/dev/null; then
    setcap cap_net_admin,cap_net_raw+ep "$BIN_DIR/vkturnd" 2>/dev/null && {
        info "Capabilities set: vkturnd can create TUN as non-root"
    } || {
        warn "setcap failed, falling back to setuid"
        chmod u+s "$BIN_DIR/vkturnd"
        info "Setuid bit set: vkturnd runs as root (less secure)"
    }
else
    warn "setcap not found, using setuid"
    chmod u+s "$BIN_DIR/vkturnd"
fi

# Create group
if ! getent group vkturn &>/dev/null; then
    info "Creating vkturn group..."
    groupadd --system vkturn
fi

# Create runtime dirs
mkdir -p /run/vkturn
mkdir -p /var/lib/vkturn
chown root:vkturn /run/vkturn
chmod 775 /run/vkturn

# Passwordless sudo for vkturn group
info "Setting up passwordless sudo for vkturn group..."
cat > /etc/sudoers.d/vkturnd <<EOF
%vkturn ALL=(root) NOPASSWD: $BIN_DIR/vkturnd
%vkturn ALL=(root) NOPASSWD: $BIN_DIR/vkturnctl
EOF
chmod 440 /etc/sudoers.d/vkturnd

# Polkit for GUI apps
info "Setting up polkit rules..."
mkdir -p /etc/polkit-1/rules.d
cat > /etc/polkit-1/rules.d/50-vkturnd.rules <<EOF
polkit.addRule(function(action, subject) {
    if (action.id.indexOf("org.freedesktop.policykit.exec") == 0) {
        if (subject.isInGroup("vkturn")) {
            return polkit.Result.YES;
        }
    }
});
EOF

# Create wrapper with env vars
cat > "$BIN_DIR/vkturnd-run" <<'EOF'
#!/bin/bash
export SINGBOX_BINARY=/usr/bin/sing-box
export VKTURN_CLIENT=/usr/bin/vkturn-client
exec /usr/bin/vkturnd -socket /run/vkturn/control.sock -group vkturn "$@"
EOF
chmod 755 "$BIN_DIR/vkturnd-run"

# Create simple launcher (no pkexec prompts for group members)
cat > "$BIN_DIR/vkturnd" <<'EOF'
#!/bin/bash
# Simple launcher - automatically elevates without password for vkturn group

# Check if already running
if [[ -S /run/vkturn/control.sock ]]; then
    if timeout 1 /usr/bin/vkturnctl -op=hello &>/dev/null; then
        echo "vkturnd is already running"
        exit 0
    fi
fi

# Run with sudo (passwordless for vkturn group)
exec sudo /usr/bin/vkturnd-run "$@"
EOF
chmod 755 "$BIN_DIR/vkturnd"

# Systemd service that doesn't need special perms
info "Creating systemd service..."
cat > /etc/systemd/system/vkturnd.service <<EOF
[Unit]
Description=vkturn daemon
After=network.target

[Service]
Type=simple
ExecStart=$BIN_DIR/vkturnd-run
Restart=on-failure
RestartSec=3
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload 2>/dev/null || true

# Example config
mkdir -p /etc/vkturn
cat > /etc/vkturn/example.json <<'EOF'
{
  "log": { "level": "info" },
  "inbounds": [{ "type": "tun", "tag": "tun-in", "interface_name": "vk-tun",
    "address": ["10.20.30.1/24"], "mtu": 1500, "auto_route": true, "stack": "mixed" }],
  "outbounds": [{ "type": "direct", "tag": "direct" }],
  "route": { "final": "direct" }
}
EOF

echo ""
echo "=========================================="
echo -e "${GREEN}✓ Installation complete!${NC}"
echo "=========================================="
echo ""
echo "Files installed:"
echo "  $BIN_DIR/vkturnd      - daemon (with CAP_NET_ADMIN)"
echo "  $BIN_DIR/vkturnctl    - CLI"
echo "  $BIN_DIR/vkturnd-run  - wrapper with env vars"
echo "  $BIN_DIR/vkturnd      - simple launcher"
echo ""
echo "Quick start:"
echo "  1. Add user to group:  sudo usermod -a -G vkturn \$USER && newgrp vkturn"
echo "  2. Start daemon:       vkturnd         (no password!)"
echo "  3. Check status:       vkturnctl -op=status"
echo "  4. Test TUN:           sudo vkturnctl -op=up -id=test -config=/etc/vkturn/example.json"
echo ""
echo "Or use systemd:         sudo systemctl enable --now vkturnd"
echo ""

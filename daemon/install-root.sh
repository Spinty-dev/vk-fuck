#!/bin/bash
# Simple installer that:
# 1. Builds vkturnd
# 2. Installs to /usr/bin
# 3. Sets up root access (via setcap for capabilities or setuid for full root)
#
# Usage: sudo ./install-root.sh

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN_DIR=/usr/bin
CONFIG_DIR=/etc/vkturn
LOG_DIR=/var/log/vkturn

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

# Must run as root
[[ $EUID -eq 0 ]] || die "Must run as root (try: sudo $0)"

# Build
info "Building vkturnd..."
cd "$HERE"
go build -trimpath -ldflags="-s -w" -o vkturnd . || die "Failed to build vkturnd"

go build -trimpath -ldflags="-s -w" -o vkturnctl ./cmd/vkturnctl || die "Failed to build vkturnctl"

# Install binaries
info "Installing binaries to $BIN_DIR..."
install -Dm755 vkturnd "$BIN_DIR/vkturnd"
install -Dm755 vkturnctl "$BIN_DIR/vkturnctl"

# Create wrapper script that sets env vars
info "Creating wrapper script..."
cat > "$BIN_DIR/vkturnd-run" <<'WRAPPER'
#!/bin/bash
# Wrapper to run vkturnd with proper environment
export SINGBOX_BINARY=/usr/bin/sing-box
export VKTURN_CLIENT=/usr/bin/vkturn-client
export VKTURND_LOG=/var/log/vkturn/vkturnd.log

# Create log dir if needed
mkdir -p /var/log/vkturn

exec /usr/bin/vkturnd -socket /run/vkturn/control.sock -group vkturn -log "$VKTURND_LOG" "$@"
WRAPPER
chmod 755 "$BIN_DIR/vkturnd-run"

# Create directories
info "Creating directories..."
mkdir -p /run/vkturn
mkdir -p /var/lib/vkturn
mkdir -p "$CONFIG_DIR"
mkdir -p "$LOG_DIR"

# Create group
if ! getent group vkturn &>/dev/null; then
    info "Creating vkturn group..."
    groupadd --system vkturn
fi

# Option 1: Use capabilities (recommended - no setuid needed)
info "Setting capabilities (allows TUN without full root)..."
setcap cap_net_admin,cap_net_raw+ep "$BIN_DIR/vkturnd" 2>/dev/null || {
    info "WARNING: setcap failed, will use setuid root instead (less secure)"
    chmod u+s "$BIN_DIR/vkturnd"
}

# Create systemd user service (runs as root via setcap)
info "Creating user service..."
mkdir -p /etc/systemd/user

cat > /etc/systemd/user/vkturnd.service <<EOF
[Unit]
Description=vkturn daemon (user service with elevated TUN capabilities)

[Service]
Type=simple
ExecStart=$BIN_DIR/vkturnd-run
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF

# Desktop autostart entry (polkit-based passwordless)
info "Creating polkit rules for passwordless authentication..."
mkdir -p /etc/polkit-1/rules.d

cat > /etc/polkit-1/rules.d/50-vkturnd.rules <<'POLKIT'
polkit.addRule(function(action, subject) {
    if (action.id == "com.vkturn.vkturnd.run" ||
        action.id.indexOf("org.freedesktop.policykit.exec") == 0) {
        if (subject.isInGroup("vkturn")) {
            return polkit.Result.YES;
        }
    }
});
POLKIT

# Create a simple launcher that uses pkexec but auto-accepts
info "Creating auto-elevate launcher..."
cat > "$BIN_DIR/vkturn-launch" <<'LAUNCHER'
#!/bin/bash
# Launch vkturnd with automatic elevation (no GUI prompt if in vkturn group)

if [[ $EUID -eq 0 ]]; then
    exec /usr/bin/vkturnd-run
fi

# Check if we can connect without elevation
if timeout 1 /usr/bin/vkturnctl -op=hello &>/dev/null; then
    echo "vkturnd is already running"
    exit 0
fi

# Try pkexec (will be passwordless for vkturn group members due to polkit rules)
exec pkexec /usr/bin/vkturnd-run
LAUNCHER
chmod 755 "$BIN_DIR/vkturn-launch"

# Create a sudoers entry (alternative: no password for vkturn group)
info "Creating sudoers entry for passwordless sudo..."
cat > /etc/sudoers.d/vkturnd <<'SUDOERS'
# Allow vkturn group to run vkturnd without password
%vkturn ALL=(root) NOPASSWD: /usr/bin/vkturnd
%vkturn ALL=(root) NOPASSWD: /usr/bin/vkturnd-run
%vkturn ALL=(root) NOPASSWD: /usr/bin/vkturnd-wrapper
SUDOERS
chmod 440 /etc/sudoers.d/vkturnd

# Fix permissions
chown root:vkturn /run/vkturn
chmod 775 /run/vkturn
chown root:vkturn /var/lib/vkturn
chmod 755 /var/lib/vkturn
chown root:vkturn "$LOG_DIR"
chmod 755 "$LOG_DIR"

# Create example config
info "Creating example config..."
cat > "$CONFIG_DIR/example-tun.json" <<'CONFIG'
{
  "log": { "level": "info", "timestamp": true },
  "inbounds": [{
    "type": "tun",
    "tag": "tun-in",
    "interface_name": "vk-test",
    "address": ["10.20.30.1/24"],
    "mtu": 1500,
    "auto_route": true,
    "stack": "mixed"
  }],
  "outbounds": [{ "type": "direct", "tag": "direct" }],
  "route": { "final": "direct" }
}
CONFIG

echo ""
echo "=========================================="
echo "Installation complete!"
echo "=========================================="
echo ""
echo "Binaries installed to:"
echo "  $BIN_DIR/vkturnd      - daemon"
echo "  $BIN_DIR/vkturnctl    - CLI client"
echo "  $BIN_DIR/vkturnd-run  - wrapper with env vars"
echo "  $BIN_DIR/vkturn-launch - auto-elevate launcher"
echo ""
echo "To add user to vkturn group:"
echo "  sudo usermod -a -G vkturn \$USER"
echo "  newgrp vkturn"
echo ""
echo "Start daemon (passwordless for group members):"
echo "  vkturn-launch     # or: sudo systemctl start vkturnd"
echo ""
echo "Use CLI:"
echo "  vkturnctl -op=status"
echo "  sudo vkturnctl -op=up -id=my-tun -config=$CONFIG_DIR/example-tun.json"
echo ""
echo "Logs: tail -f $LOG_DIR/vkturnd.log"
echo ""

#!/usr/bin/env bash
# Diagnose TUN issues on Linux desktop
set -euo pipefail

echo "=== TUN Device Diagnostics ==="
echo "Date: $(date)"
echo "User: $(whoami)"
echo ""

echo "--- 1. Checking if TUN module is loaded ---"
if lsmod | grep -q tun; then
    echo "OK: TUN module is loaded"
else
    echo "WARN: TUN module not loaded, trying to load..."
    sudo modprobe tun 2>/dev/null || echo "ERROR: Failed to load TUN module (may need sudo)"
fi
echo ""

echo "--- 2. Checking /dev/net/tun ---"
if [ -c /dev/net/tun ]; then
    echo "OK: /dev/net/tun exists"
    ls -la /dev/net/tun
    echo ""
    echo "Permissions check:"
    if [ -r /dev/net/tun ] && [ -w /dev/net/tun ]; then
        echo "OK: Current user can read/write /dev/net/tun"
    else
        echo "WARN: Current user may not have access to /dev/net/tun"
        echo "      To fix: sudo chmod 666 /dev/net/tun (temporary)"
        echo "      Or add user to group that owns the device"
    fi
else
    echo "ERROR: /dev/net/tun does not exist!"
    echo "      Try: sudo mkdir -p /dev/net && sudo mknod /dev/net/tun c 10 200 && sudo chmod 666 /dev/net/tun"
fi
echo ""

echo "--- 3. Checking CAP_NET_ADMIN capability ---"
if command -v capsh &>/dev/null; then
    if capsh --print | grep -q "cap_net_admin"; then
        echo "OK: CAP_NET_ADMIN is available"
    else
        echo "WARN: CAP_NET_ADMIN may not be available to this process"
    fi
else
    echo "SKIP: capsh not installed (install libcap-ng-utils or libcap2-bin)"
fi
echo ""

echo "--- 4. Checking sing-box binary ---"
if command -v sing-box &>/dev/null; then
    echo "OK: sing-box found at: $(which sing-box)"
    sing-box version 2>/dev/null || echo "WARN: Could not get sing-box version"
else
    echo "ERROR: sing-box not found in PATH"
    echo "      It should be in /usr/lib/vkturn/ or next to vkturnd"
fi
echo ""

echo "--- 5. Checking vkturnd status ---"
if systemctl is-active --quiet vkturnd 2>/dev/null; then
    echo "OK: vkturnd service is active"
    systemctl status vkturnd --no-pager 2>/dev/null | head -10
elif [ -S /run/vkturn/control.sock ]; then
    echo "OK: vkturnd socket exists at /run/vkturn/control.sock"
    ls -la /run/vkturn/control.sock
else
    echo "WARN: vkturnd does not appear to be running"
    echo "      Start with: sudo systemctl start vkturnd"
    echo "      Or manually: sudo ./vkturnd -log=/var/log/vkturnd.log"
fi
echo ""

echo "--- 6. Checking vkturn group ---"
if getent group vkturn &>/dev/null; then
    echo "OK: vkturn group exists"
    echo "Members: $(getent group vkturn | cut -d: -f4)"
else
    echo "WARN: vkturn group does not exist"
    echo "      Create with: sudo groupadd --system vkturn"
fi
echo ""

echo "--- 7. Testing TUN creation manually ---"
echo "Attempting to create test TUN device..."
if sudo ip tuntap add mode tun dev vkturn-test 2>/dev/null; then
    echo "OK: Can create TUN device"
    sudo ip link del vkturn-test 2>/dev/null || true
else
    echo "ERROR: Failed to create TUN device"
    echo "      This indicates a system-level TUN issue"
fi
echo ""

echo "--- 8. Checking kernel messages for TUN errors ---"
echo "Recent kernel messages related to TUN:"
sudo dmesg 2>/dev/null | grep -i tun | tail -5 || echo "No recent TUN-related kernel messages"
echo ""

echo "=== Diagnostics Complete ==="
echo ""
echo "Next steps to test:"
echo "1. Build binaries: cd /home/spinty/projects/new_vk/daemon && ./build-and-test.sh"
echo "2. Start daemon manually with logs: sudo ./vkturnd -log=/var/log/vkturnd.log"
echo "3. Run test: sudo ./vkturnctl -op=up -id=test -config=test_tun_config.json"
echo "4. Check logs: sudo tail -f /var/log/vkturnd.log"
echo "5. Check interface: ip link show | grep vk-"

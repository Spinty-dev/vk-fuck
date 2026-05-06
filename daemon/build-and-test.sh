#!/usr/bin/env bash
# Build daemon and CLI, then run a quick TUN test
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

echo "==> Building vkturnd (daemon)..."
go build -trimpath -ldflags="-s -w" -o vkturnd .

echo "==> Building vkturnctl (CLI)..."
cd "$HERE/cmd/vkturnctl"
go build -trimpath -ldflags="-s -w" -o ../../vkturnctl .
cd "$HERE"

echo "==> Binaries built:"
ls -la vkturnd vkturnctl

echo ""
echo "==> Usage examples:"
echo ""
echo "1. Check daemon status:"
echo "   sudo ./vkturnctl -op=status"
echo ""
echo "2. Start a test TUN route:"
echo "   sudo ./vkturnctl -op=up -id=test-tun -config=test_tun_config.json"
echo ""
echo "3. Check routes status:"
echo "   sudo ./vkturnctl -op=status"
echo ""
echo "4. Stop the test route:"
echo "   sudo ./vkturnctl -op=down -id=test-tun"
echo ""
echo "5. Follow logs for a route:"
echo "   sudo ./vkturnctl -op=logs -id=test-tun -logs-timeout=30s"
echo ""
echo "6. Check if TUN interface was created:"
echo "   ip link show | grep vk-"
echo ""
echo "==> For manual daemon testing (with full debug output):"
echo "   sudo ./vkturnd -log=/var/log/vkturnd.log"
echo "   # Then in another terminal run the CLI commands"

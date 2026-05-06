#!/bin/bash
# Build Arch package for vkturnd
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DAEMON_DIR="$(dirname "$SCRIPT_DIR")"

echo "==> Building Arch package for vkturnd"
echo "    Daemon dir: $DAEMON_DIR"
echo "    Build dir:  $SCRIPT_DIR"

# Ensure we have dependencies
if ! command -v go &>/dev/null; then
    echo "ERROR: go is required to build"
    exit 1
fi

# Copy source files to build dir
cd "$SCRIPT_DIR"

# Create PKGBUILD if not exists
if [[ ! -f PKGBUILD ]]; then
    echo "ERROR: PKGBUILD not found in $SCRIPT_DIR"
    exit 1
fi

# Update pkgver with timestamp to force fresh build
TIMESTAMP=$(date +%Y%m%d.%H%M%S)
sed -i "s/^pkgver=.*/pkgver=0.1.0.$TIMESTAMP/" PKGBUILD

# Clean old builds completely
rm -rf pkg/ src/ *.pkg.tar.zst .SRCINFO 2>/dev/null || true

# Clean makepkg source cache to force rebuild
cd "$SCRIPT_DIR"
rm -rf src/ *.pkg.tar.zst .SRCINFO 2>/dev/null || true

echo "==> Building with pkgver=0.1.0.$TIMESTAMP"

# Build the package
makepkg -C -f -s --noconfirm

echo ""
echo "==> Build complete!"
echo "    Package: $(ls -1 *.pkg.tar.zst 2>/dev/null | head -1)"
echo ""
echo "==> Installing package..."
sudo pacman -U --noconfirm *.pkg.tar.zst
echo ""
echo "==> Restarting vkturnd service..."
sudo systemctl restart vkturnd || true
echo ""
echo "Done! vkturnd installed and restarted."

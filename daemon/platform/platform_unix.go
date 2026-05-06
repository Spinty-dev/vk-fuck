//go:build linux || darwin

package platform

import (
	"fmt"
	"net"
	"os"
	"os/user"
	"strconv"
	"syscall"
)

func defaultSocketPath() string {
	// /run is tmpfs on Linux (cleared on reboot); on macOS fall back to
	// /var/run which is a symlink to /private/var/run.
	return "/run/vkturn/control.sock"
}

func listenControl(path string) (net.Listener, error) {
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, fmt.Errorf("listen unix %s: %w", path, err)
	}
	return ln, nil
}

func secureSocket(path, groupName string) error {
	if groupName == "" {
		return os.Chmod(path, 0o600)
	}
	g, err := user.LookupGroup(groupName)
	if err != nil {
		return fmt.Errorf("lookup group %q: %w (is the group created?)", groupName, err)
	}
	gid, err := strconv.Atoi(g.Gid)
	if err != nil {
		return fmt.Errorf("parse gid: %w", err)
	}

	// If the group is already correct, skip Chown to avoid errors when
	// running under systemd with restricted permissions or as a non-root user.
	if info, err := os.Stat(path); err == nil {
		if stat, ok := info.Sys().(*syscall.Stat_t); ok {
			if int(stat.Gid) == gid {
				return os.Chmod(path, 0o660)
			}
		}
	}

	if err := os.Chown(path, 0, gid); err != nil {
		return fmt.Errorf("chown: %w", err)
	}
	return os.Chmod(path, 0o660)
}

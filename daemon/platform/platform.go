// Package platform isolates OS-specific bits of the daemon so main.go can
// stay portable. Linux/macOS use UNIX domain sockets; Windows named pipe
// support lives in platform_windows.go (stubbed for the first drop).
package platform

import "net"

// DefaultSocketPath returns the conventional control socket path.
// Override with `-socket /some/other/path` on the command line.
func DefaultSocketPath() string { return defaultSocketPath() }

// ListenControl opens a listener for the control protocol.
func ListenControl(path string) (net.Listener, error) { return listenControl(path) }

// SecureSocket tightens permissions on the freshly-created socket so
// unprivileged users in the given group can reach it but not everyone else.
func SecureSocket(path, group string) error { return secureSocket(path, group) }

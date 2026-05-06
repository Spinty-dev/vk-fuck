//go:build windows

package platform

import (
	"errors"
	"net"
)

// TODO: replace with github.com/Microsoft/go-winio once the Windows service
// wiring is in. Until then, this platform build simply errors out — the
// Kotlin daemon client recognises the absence of a live socket and falls
// back to "TUN requires daemon" UX state.

func defaultSocketPath() string {
	return `\\.\pipe\vkturn-control`
}

func listenControl(_ string) (net.Listener, error) {
	return nil, errors.New("named pipe listener not yet implemented (see github.com/Microsoft/go-winio)")
}

func secureSocket(_, _ string) error { return nil }

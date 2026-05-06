// vkturnd is the privileged helper for the vkturn desktop GUI. It owns
// the TUN device and supervises sing-box / vk-turn-proxy subprocesses so
// the unprivileged UI can stay out of the network stack.
//
// Control surface: a single UNIX socket on Linux/macOS (Windows: named
// pipe, added later). The protocol is newline-delimited JSON.
//
//   {"op":"hello"}                                     -> {"ok":true,"version":...,"caps":[...]}
//   {"op":"up","id":"r1","kind":"sing-box","configJson":"..."}
//   {"op":"up","id":"r2","kind":"vkturn-client","args":[...],"protectSocket":"/run/vkturn/protect-r2.sock"}
//   {"op":"down","id":"r1"}
//   {"op":"status"}                                    -> {"ok":true,"routes":{...}}
//   {"op":"logs","id":"r1"}                            -> streams {"line":"..."} until the connection closes
//
// Every response has `"ok":bool` and, on failure, `"error":string`.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/vkturn/vkturnd/control"
	"github.com/vkturn/vkturnd/platform"
	"github.com/vkturn/vkturnd/tunnel"
)

var version = "0.1.0"

func main() {
	socketPath := flag.String("socket", platform.DefaultSocketPath(), "control socket path")
	groupName := flag.String("group", "vkturn", "group with read/write access to the socket")
	logPath := flag.String("log", "", "optional log file; stderr when empty")
	flag.Parse()

	if *logPath != "" {
		f, err := os.OpenFile(*logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o640)
		if err != nil {
			log.Fatalf("open log: %s", err)
		}
		log.SetOutput(f)
	}
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	log.Printf("vkturnd %s starting: socket=%s group=%s", version, *socketPath, *groupName)

	if err := os.MkdirAll(filepath.Dir(*socketPath), 0o755); err != nil {
		log.Fatalf("mkdir socket parent: %s", err)
	}
	_ = os.Remove(*socketPath)

	ln, err := platform.ListenControl(*socketPath)
	if err != nil {
		log.Fatalf("listen: %s", err)
	}
	defer ln.Close()

	if err := platform.SecureSocket(*socketPath, *groupName); err != nil {
		log.Printf("warn: secure socket: %s", err)
	}

	mgr := tunnel.NewManager()
	defer mgr.StopAll()

	srv := control.NewServer(ln, mgr, version)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sigs
		log.Printf("signal received, shutting down")
		cancel()
	}()

	if err := srv.Serve(ctx); err != nil {
		fmt.Fprintln(os.Stderr, err)
	}
	log.Printf("vkturnd stopped")
}

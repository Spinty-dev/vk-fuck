// Package tunnel owns the actual supervision of external processes —
// sing-box (TUN + routing engine) and vk-turn-proxy-client (the Go TURN
// tunneller). It keeps per-route state and a small pub/sub for log lines.
package tunnel

import (
	"bufio"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

type Spec struct {
	ID            string
	Kind          string
	ConfigJSON    string
	Args          []string
	ProtectSocket string
}

type Status struct {
	Kind     string
	State    string // idle | starting | running | error | stopped
	Message  string
	Pid      int
	InBytes  int64
	OutBytes int64
	UptimeMs int64
}

type route struct {
	spec      Spec
	cmd       *exec.Cmd
	startedAt time.Time
	state     string
	message   string
	subs      map[chan string]struct{}
	subsMu    sync.Mutex
}

type Manager struct {
	mu         sync.Mutex
	routes     map[string]*route
	singboxBin string
	vkturnBin  string
	stageDir   string
	protector  *Protector
}

// NewManager prepares the on-disk layout and picks up any packaged
// binaries that shipped with the daemon.
func NewManager() *Manager {
	singboxBin := findBinary("sing-box", "SINGBOX_BINARY")
	vkturnBin := findBinary("vkturn-client", "VKTURN_CLIENT")

	if singboxBin == "" {
		log.Printf("[tunnel] WARNING: sing-box binary not found. Set SINGBOX_BINARY or place sing-box next to vkturnd.")
	} else {
		log.Printf("[tunnel] Found sing-box: %s", singboxBin)
	}
	if vkturnBin == "" {
		log.Printf("[tunnel] WARNING: vkturn-client binary not found. Set VKTURN_CLIENT or place it next to vkturnd.")
	} else {
		log.Printf("[tunnel] Found vkturn-client: %s", vkturnBin)
	}

	m := &Manager{
		routes:     make(map[string]*route),
		stageDir:   "/var/lib/vkturn",
		singboxBin: singboxBin,
		vkturnBin:  vkturnBin,
		protector:  NewProtector("vkturn-protect", 51820),
	}
	_ = os.MkdirAll(m.stageDir, 0o755)
	if err := m.protector.Start(); err != nil {
		log.Printf("[tunnel] WARNING: failed to start protector: %v", err)
	}
	return m
}

func findBinary(name, env string) string {
	if v := os.Getenv(env); v != "" {
		if _, err := os.Stat(v); err == nil {
			return v
		}
	}
	// Look next to the daemon binary first, then the usual PATH entries.
	self, _ := os.Executable()
	if self != "" {
		local := filepath.Join(filepath.Dir(self), name)
		if _, err := os.Stat(local); err == nil {
			return local
		}
	}
	if path, err := exec.LookPath(name); err == nil {
		return path
	}
	return ""
}

// Up validates the spec, stops any previous runner with the same id and
// spawns a fresh child. Errors are returned synchronously; runtime
// failures land in the route's `message` field and bubble through the
// log subscription.
func (m *Manager) Up(spec Spec) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if spec.ID == "" {
		return fmt.Errorf("empty id")
	}

	log.Printf("[tunnel] Up requested: id=%s kind=%s", spec.ID, spec.Kind)

	if existing, ok := m.routes[spec.ID]; ok {
		log.Printf("[tunnel] Stopping existing route: id=%s", spec.ID)
		m.stopLocked(existing)
	}

	r := &route{spec: spec, state: "starting", subs: map[chan string]struct{}{}}

	switch spec.Kind {
	case "sing-box":
		if m.singboxBin == "" {
			return fmt.Errorf("sing-box binary not found (set SINGBOX_BINARY)")
		}
		cfgPath := filepath.Join(m.stageDir, "singbox-"+spec.ID+".json")
		log.Printf("[tunnel] Writing sing-box config to: %s", cfgPath)
		log.Printf("[tunnel] Config size: %d bytes", len(spec.ConfigJSON))
		if err := os.WriteFile(cfgPath, []byte(spec.ConfigJSON), 0o640); err != nil {
			return fmt.Errorf("stage config: %w", err)
		}
		r.cmd = exec.Command(m.singboxBin, "run", "-c", cfgPath)
		log.Printf("[tunnel] Prepared sing-box command: %s %v", m.singboxBin, []string{"run", "-c", cfgPath})

	case "vkturn-client":
		if m.vkturnBin == "" {
			return fmt.Errorf("vkturn-client binary not found (set VKTURN_CLIENT)")
		}
		args := append([]string{}, spec.Args...)
		if spec.ProtectSocket != "" {
			args = append(args, "-protect-sock", spec.ProtectSocket)
		}
		r.cmd = exec.Command(m.vkturnBin, args...)

	case "wireguard":
		wgBin := findBinary("awg-quick", "AWG_QUICK_BINARY")
		if wgBin == "" {
			wgBin = findBinary("wg-quick", "WG_QUICK_BINARY")
		}
		if wgBin == "" {
			return fmt.Errorf("wg-quick / awg-quick binary not found")
		}

		slug := spec.ID
		var cleanSlug string
		for _, r := range slug {
			if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
				cleanSlug += string(r)
			}
		}
		if cleanSlug == "" {
			cleanSlug = "vk"
		} else if len(cleanSlug) > 12 {
			cleanSlug = cleanSlug[:12]
		}
		ifaceName := "vk" + cleanSlug

		cfgPath := filepath.Join(m.stageDir, ifaceName+".conf")
		log.Printf("[tunnel] Writing wg-quick config to: %s", cfgPath)
		if err := os.WriteFile(cfgPath, []byte(spec.ConfigJSON), 0o600); err != nil {
			return fmt.Errorf("stage wg config: %w", err)
		}

		script := fmt.Sprintf(`
trap '%s down %s; rm -f %s; exit 0' SIGTERM SIGINT
%s up %s || exit 1
echo "WireGuard interface %s is up."
sleep infinity & wait
`, wgBin, cfgPath, cfgPath, wgBin, cfgPath, ifaceName)
		r.cmd = exec.Command("bash", "-c", script)

	default:
		return fmt.Errorf("unsupported kind %q", spec.Kind)
	}

	r.cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	stdout, stdoutErr := r.cmd.StdoutPipe()
	stderr, stderrErr := r.cmd.StderrPipe()
	if stdoutErr != nil {
		log.Printf("[tunnel] Failed to create stdout pipe: %v", stdoutErr)
	}
	if stderrErr != nil {
		log.Printf("[tunnel] Failed to create stderr pipe: %v", stderrErr)
	}

	log.Printf("[tunnel] Starting process for id=%s...", spec.ID)
	if err := r.cmd.Start(); err != nil {
		r.state = "error"
		r.message = err.Error()
		log.Printf("[tunnel] Failed to start process for id=%s: %v", spec.ID, err)
		return fmt.Errorf("start: %w", err)
	}
	r.startedAt = time.Now()
	r.state = "running"
	m.routes[spec.ID] = r
	log.Printf("[tunnel] Process started for id=%s, pid=%d", spec.ID, r.cmd.Process.Pid)

	go m.pump(r, stdout, "stdout")
	go m.pump(r, stderr, "stderr")
	go m.watch(r)
	return nil
}

// Down stops the named route and waits briefly for the child to exit.
func (m *Manager) Down(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if r, ok := m.routes[id]; ok {
		m.stopLocked(r)
		delete(m.routes, id)
	}
}

// StopAll gracefully tears down every live runner. Called from the main
// signal handler.
func (m *Manager) StopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, r := range m.routes {
		m.stopLocked(r)
		delete(m.routes, id)
	}
	if m.protector != nil {
		m.protector.Stop()
	}
}

// StatusAll returns a snapshot of every route's state. Safe to call from
// any goroutine.
func (m *Manager) StatusAll() map[string]Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make(map[string]Status, len(m.routes))
	for id, r := range m.routes {
		pid := 0
		if r.cmd != nil && r.cmd.Process != nil {
			pid = r.cmd.Process.Pid
		}
		uptime := int64(0)
		if !r.startedAt.IsZero() {
			uptime = time.Since(r.startedAt).Milliseconds()
		}
		out[id] = Status{
			Kind:     r.spec.Kind,
			State:    r.state,
			Message:  r.message,
			Pid:      pid,
			UptimeMs: uptime,
		}
	}
	return out
}

// Subscribe opens a channel fed by log lines from the given route. The
// returned cancel function must be called to release resources.
func (m *Manager) Subscribe(id string) (<-chan string, func(), error) {
	m.mu.Lock()
	r, ok := m.routes[id]
	m.mu.Unlock()
	if !ok {
		return nil, nil, fmt.Errorf("no such route %q", id)
	}
	ch := make(chan string, 256)
	r.subsMu.Lock()
	r.subs[ch] = struct{}{}
	r.subsMu.Unlock()
	cancel := func() {
		r.subsMu.Lock()
		delete(r.subs, ch)
		alreadyClosed := false
		select {
		case <-ch:
			alreadyClosed = true // Channel is already closed/drained
		default:
		}
		r.subsMu.Unlock()
		if !alreadyClosed {
			// Protect against double-close (e.g., watch() also closes)
			defer func() { _ = recover() }()
			close(ch)
		}
	}
	return ch, cancel, nil
}

func (m *Manager) stopLocked(r *route) {
	if r.cmd == nil || r.cmd.Process == nil {
		return
	}
	_ = syscall.Kill(-r.cmd.Process.Pid, syscall.SIGTERM)

	done := make(chan struct{})
	go func() {
		_ = r.cmd.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		_ = syscall.Kill(-r.cmd.Process.Pid, syscall.SIGKILL)
		<-done
	}
	r.state = "stopped"
}

func (m *Manager) pump(r *route, rc io.ReadCloser, tag string) {
	if rc == nil {
		log.Printf("[tunnel] pump called with nil reader for route=%s tag=%s", r.spec.ID, tag)
		return
	}
	defer rc.Close()
	sc := bufio.NewScanner(rc)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	log.Printf("[tunnel] Starting log pump for route=%s tag=%s", r.spec.ID, tag)
	lineCount := 0
	for sc.Scan() {
		line := fmt.Sprintf("[%s] %s", tag, sc.Text())
		r.subsMu.Lock()
		for ch := range r.subs {
			select {
			case ch <- line:
			default:
			}
		}
		r.subsMu.Unlock()
		lineCount++
		// Log first 20 lines and any errors/warnings for debugging
		if lineCount <= 20 || strings.Contains(strings.ToLower(line), "error") || strings.Contains(strings.ToLower(line), "warn") || strings.Contains(strings.ToLower(line), "tun") {
			log.Printf("[tunnel] [%s:%s] %s", r.spec.ID, tag, sc.Text())
		}
	}
	if err := sc.Err(); err != nil {
		log.Printf("[tunnel] Scanner error for route=%s tag=%s: %v", r.spec.ID, tag, err)
	}
	log.Printf("[tunnel] Log pump ended for route=%s tag=%s (lines=%d)", r.spec.ID, tag, lineCount)
}

func (m *Manager) watch(r *route) {
	log.Printf("[tunnel] Watch started for route=%s", r.spec.ID)
	err := r.cmd.Wait()
	m.mu.Lock()
	defer m.mu.Unlock()
	if err != nil {
		r.state = "error"
		r.message = err.Error()
		log.Printf("[tunnel] route %s exited with error: %s", r.spec.ID, err)
	} else {
		r.state = "stopped"
		log.Printf("[tunnel] route %s stopped normally", r.spec.ID)
	}
	r.subsMu.Lock()
	for ch := range r.subs {
		// Protect against double-close
		func() {
			defer func() { _ = recover() }()
			close(ch)
		}()
		delete(r.subs, ch)
	}
	r.subsMu.Unlock()
	log.Printf("[tunnel] Watch ended for route=%s", r.spec.ID)
}

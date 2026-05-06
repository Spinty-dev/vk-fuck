package control

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"sync"

	"github.com/vkturn/vkturnd/tunnel"
)

// Server accepts control connections and dispatches requests to the
// tunnel manager. Each accepted connection owns its own goroutine; the
// manager is the only shared piece of mutable state.
type Server struct {
	ln      net.Listener
	mgr     *tunnel.Manager
	version string
}

func NewServer(ln net.Listener, mgr *tunnel.Manager, version string) *Server {
	return &Server{ln: ln, mgr: mgr, version: version}
}

func (s *Server) Serve(ctx context.Context) error {
	go func() {
		<-ctx.Done()
		_ = s.ln.Close()
	}()

	var wg sync.WaitGroup
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			log.Printf("accept: %s", err)
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			s.handle(ctx, conn)
		}()
	}
	wg.Wait()
	return nil
}

func (s *Server) handle(ctx context.Context, conn net.Conn) {
	remoteAddr := conn.RemoteAddr().String()
	log.Printf("[control] New connection from %s", remoteAddr)
	defer func() {
		log.Printf("[control] Connection closed from %s", remoteAddr)
		conn.Close()
	}()

	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 0, 1<<20), 1<<24)
	enc := json.NewEncoder(conn)

	for scanner.Scan() {
		if ctx.Err() != nil {
			log.Printf("[control] Context cancelled, closing connection from %s", remoteAddr)
			return
		}
		var req Request
		if err := json.Unmarshal(scanner.Bytes(), &req); err != nil {
			log.Printf("[control] Bad request from %s: %v", remoteAddr, err)
			_ = enc.Encode(Response{OK: false, Error: fmt.Sprintf("bad request: %s", err)})
			continue
		}
		log.Printf("[control] Request from %s: op=%s id=%s kind=%s", remoteAddr, req.Op, req.ID, req.Kind)
		if err := s.dispatch(ctx, conn, enc, &req); err != nil {
			log.Printf("[control] Dispatch error for op=%s from %s: %v", req.Op, remoteAddr, err)
			_ = enc.Encode(Response{OK: false, Error: err.Error()})
		}
	}
	if err := scanner.Err(); err != nil {
		log.Printf("[control] Scanner error from %s: %v", remoteAddr, err)
	}
}

func (s *Server) dispatch(ctx context.Context, conn net.Conn, enc *json.Encoder, req *Request) error {
	switch req.Op {
	case "hello":
		return enc.Encode(Response{OK: true, Version: s.version, Caps: DefaultCaps})

	case "up":
		if req.ID == "" {
			return fmt.Errorf("`id` is required")
		}
		spec := tunnel.Spec{
			ID:            req.ID,
			Kind:          string(req.Kind),
			ConfigJSON:    req.ConfigJSON,
			Args:          req.Args,
			ProtectSocket: req.ProtectSocket,
		}
		log.Printf("[control] Starting route %s (kind=%s)", req.ID, req.Kind)
		if err := s.mgr.Up(spec); err != nil {
			log.Printf("[control] Failed to start route %s: %v", req.ID, err)
			return err
		}
		// Получаем PID из статуса
		routes := s.mgr.StatusAll()
		pid := 0
		if st, ok := routes[req.ID]; ok {
			pid = st.Pid
		}
		log.Printf("[control] Route %s started successfully (pid=%d)", req.ID, pid)
		return enc.Encode(Response{OK: true, ID: req.ID, State: "starting", Pid: pid})

	case "down":
		if req.ID == "" {
			return fmt.Errorf("`id` is required")
		}
		log.Printf("[control] Stopping route %s", req.ID)
		s.mgr.Down(req.ID)
		log.Printf("[control] Route %s stopped", req.ID)
		return enc.Encode(Response{OK: true, ID: req.ID, State: "stopped"})

	case "status":
		routes := s.mgr.StatusAll()
		log.Printf("[control] Status request: %d routes active", len(routes))
		return enc.Encode(Response{OK: true, Routes: toProtoStatus(routes)})

	case "logs":
		if req.ID == "" {
			return fmt.Errorf("`id` is required")
		}
		return s.streamLogs(ctx, conn, enc, req.ID)

	default:
		return fmt.Errorf("unknown op %q", req.Op)
	}
}

func (s *Server) streamLogs(ctx context.Context, conn net.Conn, enc *json.Encoder, id string) error {
	ch, cancel, err := s.mgr.Subscribe(id)
	if err != nil {
		return err
	}
	defer cancel()
	for {
		select {
		case <-ctx.Done():
			return nil
		case line, ok := <-ch:
			if !ok {
				return nil
			}
			if err := enc.Encode(Response{OK: true, ID: id, Line: line}); err != nil {
				return nil
			}
		}
	}
}

func toProtoStatus(m map[string]tunnel.Status) map[string]Status {
	out := make(map[string]Status, len(m))
	for id, s := range m {
		out[id] = Status{
			Kind:     Kind(s.Kind),
			State:    s.State,
			Message:  s.Message,
			Pid:      s.Pid,
			InBytes:  s.InBytes,
			OutBytes: s.OutBytes,
			Uptime:   s.UptimeMs,
		}
	}
	return out
}

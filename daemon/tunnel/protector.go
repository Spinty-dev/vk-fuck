package tunnel

import (
	"io"
	"log"
	"net"
	"syscall"
)

// Protector listens on an abstract unix socket and implements the FD protection
// protocol used by vk-turn-proxy client. It marks sockets with the
// given fwmark so they bypass the WireGuard tunnel.
type Protector struct {
	name string
	mark int
	ln   *net.UnixListener
}

func NewProtector(name string, mark int) *Protector {
	return &Protector{name: name, mark: mark}
}

func (p *Protector) Start() error {
	addr := &net.UnixAddr{Name: "@" + p.name, Net: "unix"}
	ln, err := net.ListenUnix("unix", addr)
	if err != nil {
		return err
	}
	p.ln = ln
	go p.serve()
	log.Printf("[protect] Listening on abstract socket @%s (mark=%d)", p.name, p.mark)
	return nil
}

func (p *Protector) Stop() {
	if p.ln != nil {
		_ = p.ln.Close()
	}
}

func (p *Protector) serve() {
	for {
		conn, err := p.ln.AcceptUnix()
		if err != nil {
			return
		}
		go p.handle(conn)
	}
}

func (p *Protector) handle(conn *net.UnixConn) {
	defer conn.Close()
	buf := make([]byte, 1)
	oob := make([]byte, 64)
	for {
		n, oobn, _, _, err := conn.ReadMsgUnix(buf, oob)
		if err != nil {
			if err != io.EOF {
				log.Printf("[protect] read error: %v", err)
			}
			return
		}
		if n == 0 {
			return
		}

		msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
		if err != nil {
			log.Printf("[protect] parse control message: %v", err)
			continue
		}

		for _, msg := range msgs {
			fds, err := syscall.ParseUnixRights(&msg)
			if err != nil {
				log.Printf("[protect] parse unix rights: %v", err)
				continue
			}
			for _, fd := range fds {
				// SO_MARK is 36 on Linux
				err := syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, 36, p.mark)
				if err != nil {
					log.Printf("[protect] setsockopt SO_MARK %d on fd %d: %v", p.mark, fd, err)
				}
				_ = syscall.Close(fd)
			}
		}

		// Send ACK (1)
		_, _ = conn.Write([]byte{1})
	}
}

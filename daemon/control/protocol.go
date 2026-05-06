// Package control defines the on-the-wire message shapes between the
// vkturn GUI and the daemon. The protocol is newline-delimited JSON over
// a UNIX socket or Windows named pipe.
//
// Every request has an `op` field naming the operation; every response has
// an `ok` boolean and, on failure, an `error` string. Streaming ops (like
// `logs`) produce multiple JSON envelopes until the caller closes the
// connection.
package control

// Kind enumerates the process kinds the daemon knows how to supervise.
type Kind string

const (
	KindSingBox      Kind = "sing-box"
	KindVKTurnClient Kind = "vkturn-client"
	KindWireguard    Kind = "wireguard"
)

type Request struct {
	Op string `json:"op"`

	// Common to up/down/status/logs — the caller-assigned route id.
	ID string `json:"id,omitempty"`

	// up-only
	Kind          Kind     `json:"kind,omitempty"`
	ConfigJSON    string   `json:"configJson,omitempty"`
	Args          []string `json:"args,omitempty"`
	ProtectSocket string   `json:"protectSocket,omitempty"`

	// logs-only
	Follow bool `json:"follow,omitempty"`
}

type Response struct {
	OK      bool              `json:"ok"`
	Error   string            `json:"error,omitempty"`
	Version string            `json:"version,omitempty"`
	Caps    []string          `json:"caps,omitempty"`
	ID      string            `json:"id,omitempty"`
	State   string            `json:"state,omitempty"`
	Pid     int               `json:"pid,omitempty"`
	Routes  map[string]Status `json:"routes,omitempty"`
	Line    string            `json:"line,omitempty"`
}

type Status struct {
	Kind     Kind   `json:"kind"`
	State    string `json:"state"`
	Message  string `json:"message,omitempty"`
	Pid      int    `json:"pid,omitempty"`
	InBytes  int64  `json:"inBytes,omitempty"`
	OutBytes int64  `json:"outBytes,omitempty"`
	Uptime   int64  `json:"uptimeMs,omitempty"`
}

// Capabilities advertised to the GUI when it opens the connection.
var DefaultCaps = []string{"tun", "sing-box", "vkturn-client", "wireguard", "protect-sock"}

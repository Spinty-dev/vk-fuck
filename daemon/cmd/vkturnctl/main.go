package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"os/exec"
	"time"
)

type Request struct {
	Op            string   `json:"op"`
	ID            string   `json:"id,omitempty"`
	Kind          string   `json:"kind,omitempty"`
	ConfigJSON    string   `json:"configJson,omitempty"`
	Args          []string `json:"args,omitempty"`
	ProtectSocket string   `json:"protectSocket,omitempty"`
}

type Status struct {
	Kind     string `json:"kind"`
	State    string `json:"state"`
	Message  string `json:"message,omitempty"`
	Pid      int    `json:"pid,omitempty"`
	InBytes  int64  `json:"inBytes,omitempty"`
	OutBytes int64  `json:"outBytes,omitempty"`
	Uptime   int64  `json:"uptimeMs,omitempty"`
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

func main() {
	socket := flag.String("socket", "/run/vkturn/control.sock", "vkturnd control socket")
	op := flag.String("op", "hello", "operation: hello|status|up|down|logs|connect")
	wingsvURL := flag.String("wingsv", "", "wingsv:// URL for connect operation")
	routeID := flag.String("id", "", "route ID (for up/down/logs)")
	kind := flag.String("kind", "sing-box", "route kind: sing-box|vkturn-client")
	configFile := flag.String("config", "", "path to sing-box config JSON file (for up)")
	timeout := flag.Duration("timeout", 5*time.Second, "connect/read timeout")
	logsTimeout := flag.Duration("logs-timeout", 10*time.Second, "how long to follow logs (for logs op)")
	flag.Parse()

	// Поддержка: vkturnctl connect 'wingsv://...' (positional command)
	opStr := *op
	if flag.Arg(0) == "connect" {
		opStr = "connect"
		// Убираем "connect" из аргументов чтобы flag.Arg(0) стал URL
		os.Args = append([]string{os.Args[0]}, os.Args[2:]...)
		flag.Parse()
	}

	c, err := net.DialTimeout("unix", *socket, *timeout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "dial %s: %v\n", *socket, err)
		fmt.Fprintln(os.Stderr, "Hint: Is vkturnd running? Try: sudo systemctl start vkturnd")
		os.Exit(2)
	}
	defer c.Close()

	w := bufio.NewWriter(c)
	rdr := bufio.NewReader(c)

	switch opStr {
	case "hello", "status":
		sendRequest(w, rdr, Request{Op: *op}, *timeout)

	case "up":
		if *routeID == "" {
			fmt.Fprintln(os.Stderr, "usage: -op=up -id=<route-id> -config=<path.json>")
			os.Exit(1)
		}
		var configJSON string
		if *configFile != "" {
			data, err := os.ReadFile(*configFile)
			if err != nil {
				fmt.Fprintf(os.Stderr, "read config file: %v\n", err)
				os.Exit(1)
			}
			configJSON = string(data)
		}
		req := Request{Op: "up", ID: *routeID, Kind: *kind, ConfigJSON: configJSON}
		resp := sendRequest(w, rdr, req, *timeout)
		if resp.OK {
			fmt.Printf("Route %s starting... (state: %s)\n", resp.ID, resp.State)
			// Auto-follow logs after starting
			fmt.Println("--- Logs ---")
			followLogs(c, w, rdr, *routeID, *logsTimeout)
		}

	case "down":
		if *routeID == "" {
			fmt.Fprintln(os.Stderr, "usage: -op=down -id=<route-id>")
			os.Exit(1)
		}
		sendRequest(w, rdr, Request{Op: "down", ID: *routeID}, *timeout)

	case "logs":
		if *routeID == "" {
			fmt.Fprintln(os.Stderr, "usage: -op=logs -id=<route-id>")
			os.Exit(1)
		}
		followLogs(c, w, rdr, *routeID, *logsTimeout)

	case "connect":
		if *wingsvURL == "" && flag.NArg() < 1 {
			fmt.Fprintln(os.Stderr, "usage: -op=connect -wingsv='wingsv://...' OR vkturnctl connect 'wingsv://...'")
			os.Exit(1)
		}
		url := *wingsvURL
		if url == "" {
			url = flag.Arg(0)
		}
		if err := runConnect(c, w, rdr, url, *timeout, *logsTimeout); err != nil {
			fmt.Fprintf(os.Stderr, "connect failed: %v\n", err)
			os.Exit(1)
		}

	default:
		fmt.Fprintf(os.Stderr, "unknown operation: %s\n", *op)
		os.Exit(1)
	}
}

func sendRequest(w *bufio.Writer, rdr *bufio.Reader, req Request, timeout time.Duration) Response {
	data, _ := json.Marshal(req)
	if _, err := w.Write(append(data, '\n')); err != nil {
		fmt.Fprintf(os.Stderr, "write: %v\n", err)
		os.Exit(3)
	}
	_ = w.Flush()

	line, err := rdr.ReadString('\n')
	if err != nil {
		fmt.Fprintf(os.Stderr, "read: %v\n", err)
		os.Exit(4)
	}

	var resp Response
	if err := json.Unmarshal([]byte(line), &resp); err != nil {
		fmt.Fprintf(os.Stderr, "parse response: %v\nline: %s\n", err, line)
		os.Exit(4)
	}

	if req.Op == "status" && resp.OK {
		printStatus(resp.Routes)
	} else if req.Op != "logs" {
		// Pretty print response
		out, _ := json.MarshalIndent(resp, "", "  ")
		fmt.Println(string(out))
	}

	if !resp.OK {
		os.Exit(1)
	}
	return resp
}

func followLogs(c net.Conn, w *bufio.Writer, rdr *bufio.Reader, routeID string, duration time.Duration) {
	req := Request{Op: "logs", ID: routeID}
	data, _ := json.Marshal(req)
	if _, err := w.Write(append(data, '\n')); err != nil {
		fmt.Fprintf(os.Stderr, "write logs request: %v\n", err)
		return
	}
	_ = w.Flush()

	done := time.After(duration)
	for {
		select {
		case <-done:
			fmt.Println("\n--- End of logs (timeout) ---")
			return
		default:
			_ = c.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
			line, err := rdr.ReadString('\n')
			if err != nil {
				if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
					continue
				}
				fmt.Fprintf(os.Stderr, "\nlogs read error: %v\n", err)
				return
			}
			var resp Response
			if err := json.Unmarshal([]byte(line), &resp); err == nil && resp.Line != "" {
				fmt.Println(resp.Line)
			}
		}
	}
}

func printStatus(routes map[string]Status) {
	if len(routes) == 0 {
		fmt.Println("No active routes")
		return
	}
	fmt.Printf("%-20s %-12s %-10s %-10s %s\n", "ID", "Kind", "State", "PID", "Message")
	fmt.Println(string(make([]byte, 80)))
	for id, s := range routes {
		msg := s.Message
		if len(msg) > 30 {
			msg = msg[:27] + "..."
		}
		fmt.Printf("%-20s %-12s %-10s %-10d %s\n", id, s.Kind, s.State, s.Pid, msg)
	}
}

func runConnect(c net.Conn, w *bufio.Writer, rdr *bufio.Reader, url string, timeout, logsTimeout time.Duration) error {
	fmt.Println("Декодирую wingsv URL...")

	cmd := exec.Command("wingsvdump", url)
	output, err := cmd.Output()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return fmt.Errorf("wingsvdump failed: %s", string(exitErr.Stderr))
		}
		return fmt.Errorf("wingsvdump error: %w", err)
	}

	var cfg map[string]interface{}
	if err := json.Unmarshal(output, &cfg); err != nil {
		return fmt.Errorf("parse wingsvdump output: %w", err)
	}

	backend, _ := cfg["backend"].(string)
	if backend != "BACKEND_TYPE_VK_TURN_WIREGUARD" && backend != "1" {
		return fmt.Errorf("unsupported backend type: %s", backend)
	}

	turn, _ := cfg["turn"].(map[string]interface{})
	wg, _ := cfg["wg"].(map[string]interface{})
	wgIface, _ := wg["iface"].(map[string]interface{})
	wgPeer, _ := wg["peer"].(map[string]interface{})

	ifaceAddr, _ := wgIface["address"].(string)
	ifacePrivKey, _ := wgIface["privateKey"].(string)
	ifaceMtu := 1420
	if mtu, ok := wgIface["mtu"].(float64); ok {
		ifaceMtu = int(mtu)
	}

	peerPubKey, _ := wgPeer["publicKey"].(string)
	peerPSK, _ := wgPeer["presharedKey"].(string)
	peerKeepalive := 25
	if ka, ok := wgPeer["persistentKeepaliveInterval"].(float64); ok {
		peerKeepalive = int(ka)
	}

	// Генерируем ID для этого подключения
	baseID := fmt.Sprintf("wingsv-%d", time.Now().Unix())
	vkturnID := baseID + "-vkp"
	singboxID := baseID + "-tun"

	// === ШАГ 1: Запускаем vk-turn-proxy-client ===
	fmt.Println("Шаг 1: Запуск vk-turn-proxy-client...")

	vkturnArgs := buildVkturnArgs(turn)
	vkturnReq := Request{Op: "up", ID: vkturnID, Kind: "vkturn-client", Args: vkturnArgs}
	vkturnResp := sendRequest(w, rdr, vkturnReq, timeout)
	if !vkturnResp.OK {
		return fmt.Errorf("failed to start vk-turn-proxy-client: %s", vkturnResp.Error)
	}
	fmt.Printf("vk-turn-proxy-client запущен (PID: %d)\n", vkturnResp.Pid)

	// Ждём чтобы vk-turn-proxy-client поднялся и создал слушающий порт
	fmt.Println("Жду 2 секунды чтобы vk-turn-proxy-client стартовал...")
	time.Sleep(2 * time.Second)

	// === ШАГ 2: Запускаем sing-box с WireGuard ===
	fmt.Println("Шаг 2: Запуск sing-box (TUN + WireGuard)...")

	sbConfig := map[string]interface{}{
		"log": map[string]interface{}{"level": "info", "timestamp": true},
		"inbounds": []map[string]interface{}{
			{
				"type": "tun", "tag": "tun-in", "interface_name": "vkturn0",
				"address": []string{ifaceAddr, "fd00::1/128"}, "mtu": ifaceMtu,
				"auto_route": true, "strict_route": false, "stack": "mixed",
			},
		},
		"outbounds": []map[string]interface{}{
			{
				"type":                          "wireguard",
				"tag":                           "wg-out",
				"server":                        "127.0.0.1:51820",
				"address":                       ifaceAddr,
				"private_key":                   ifacePrivKey,
				"peer_public_key":               peerPubKey,
				"pre_shared_key":                peerPSK,
				"persistent_keepalive_interval": peerKeepalive,
			},
			{"type": "direct", "tag": "direct"},
		},
		"route": map[string]interface{}{"final": "wg-out", "auto_detect_interface": true},
	}

	configJSON, _ := json.Marshal(sbConfig)
	singboxReq := Request{Op: "up", ID: singboxID, Kind: "sing-box", ConfigJSON: string(configJSON)}
	singboxResp := sendRequest(w, rdr, singboxReq, timeout)
	if !singboxResp.OK {
		// Если sing-box не стартовал - убиваем vk-turn-proxy-client
		fmt.Println("Ошибка запуска sing-box, останавливаю vk-turn-proxy-client...")
		sendRequest(w, rdr, Request{Op: "down", ID: vkturnID}, timeout)
		return fmt.Errorf("failed to start sing-box: %s", singboxResp.Error)
	}

	fmt.Printf("sing-box запущен (PID: %d)\n", singboxResp.Pid)
	fmt.Println("\n=== VPN ПОДКЛЮЧЕН ===")
	fmt.Printf("Маршруты: %s (TURN) и %s (TUN)\n", vkturnID, singboxID)
	fmt.Println("\nДля отключения выполни:")
	fmt.Printf("  sudo vkturnctl -op=down -id=%s\n", vkturnID)
	fmt.Printf("  sudo vkturnctl -op=down -id=%s\n", singboxID)

	// Показываем логи обоих процессов
	fmt.Println("\n--- Логи vk-turn-proxy-client ---")
	followLogs(c, w, rdr, vkturnID, logsTimeout)

	return nil
}

// buildVkturnArgs строит аргументы для vk-turn-proxy-client из wingsv конфига
func buildVkturnArgs(turn map[string]interface{}) []string {
	var args []string

	if link, ok := turn["link"].(string); ok && link != "" {
		args = append(args, "-vk-link", link)
	}
	if links, ok := turn["links"].([]interface{}); ok && len(links) > 0 {
		for _, l := range links {
			if s, ok := l.(string); ok {
				args = append(args, "-vk-link", s)
			}
		}
	}

	useUdp := false
	if u, ok := turn["useUdp"].(bool); ok {
		useUdp = u
	}
	if useUdp {
		args = append(args, "-transport", "datagram")
	} else {
		args = append(args, "-transport", "stream")
	}

	if noObf, ok := turn["noObfuscation"].(bool); ok && !noObf {
		args = append(args, "-captcha-solver", "v2")
	} else {
		args = append(args, "-captcha-solver", "v2")
	}

	if gs, ok := turn["credsGroupSize"].(float64); ok && gs > 0 {
		args = append(args, "-creds-group-size", fmt.Sprintf("%d", int(gs)))
	}
	if threads, ok := turn["threads"].(float64); ok && threads > 0 {
		args = append(args, "-threads", fmt.Sprintf("%d", int(threads)))
	}

	// Слушаем на фиксированном порту для wireguard
	args = append(args, "-listen", "127.0.0.1:51820")

	// Peer endpoint из TURN конфига
	if endpoint, ok := turn["endpoint"].(map[string]interface{}); ok {
		host, _ := endpoint["host"].(string)
		port := 3478
		if p, ok := endpoint["port"].(float64); ok {
			port = int(p)
		}
		if host != "" {
			args = append(args, "-peer", fmt.Sprintf("%s:%d", host, port))
		}
	}

	// Сессия в mu/v1 режиме для совместимости
	args = append(args, "-session-mode", "mu")

	return args
}

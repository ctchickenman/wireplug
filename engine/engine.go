// Package engine is WirePlug's networking core: loopback listeners, the
// per-connection router, the direct dialer, the on-demand userspace WireGuard
// tunnel and the status feed. It is bound to Android with gomobile.
package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Version is the engine version reported in the log.
const Version = "1.0.0"

// Host is implemented by the Android side.
type Host interface {
	// BindDirectSocket binds a socket used for a direct (Home mode)
	// connection to the home network, via Network.bindSocket.
	BindDirectSocket(fd int) error
	// BindTunnelSocket binds the WireGuard UDP socket to the current
	// default network.
	BindTunnelSocket(fd int) error
	// Resolve looks a host name up on the current network and returns its
	// addresses separated by commas.
	Resolve(hostname string) (string, error)
	// Log receives one log line. Lines never contain keys or passwords.
	Log(line string)
	// OnStatusChanged receives the Status JSON whenever it changes.
	OnStatusChanged(status string)
}

// Modes accepted by SetMode.
const (
	ModeHome    = "home"
	ModeAway    = "away"
	ModeOffline = "offline"
)

// Engine owns the listeners, routing and tunnel lifetime.
type Engine struct {
	host Host

	mu          sync.Mutex
	cfg         *parsedConfig
	mode        string
	forwards    map[string]*forward
	proxy       *proxyServer
	tun         *tunnel
	tunState    string // down, connecting, up, error
	tunErr      string
	tunSince    time.Time
	tunnelConns int
	idleTimer   *time.Timer
	stopped     bool

	// tunMu serialises tunnel creation and teardown.
	tunMu sync.Mutex

	notifyCh   chan struct{}
	done       chan struct{}
	lastStatus string
}

// NewEngine creates an engine in Offline mode with nothing listening.
func NewEngine(host Host) *Engine {
	e := &Engine{
		host:     host,
		mode:     ModeOffline,
		forwards: map[string]*forward{},
		tunState: "down",
		notifyCh: make(chan struct{}, 1),
		done:     make(chan struct{}),
	}
	go e.notifier()
	e.logf("engine %s started", Version)
	return e
}

func (e *Engine) logf(format string, args ...any) {
	if e.host != nil {
		e.host.Log(fmt.Sprintf(format, args...))
	}
}

func (e *Engine) notify() {
	select {
	case e.notifyCh <- struct{}{}:
	default:
	}
}

func (e *Engine) notifier() {
	for {
		select {
		case <-e.done:
			return
		case <-e.notifyCh:
		}
		s := e.Status()
		if s != e.lastStatus {
			e.lastStatus = s
			if e.host != nil {
				e.host.OnStatusChanged(s)
			}
		}
	}
}

// Configure stores the tunnel settings (JSON, see tunnelConfig) without
// touching the network. A running tunnel is rebuilt on its next use.
func (e *Engine) Configure(configJSON string) error {
	p, err := parseConfig(configJSON)
	if err != nil {
		return err
	}
	e.tunMu.Lock()
	e.mu.Lock()
	old := e.cfg
	e.cfg = p
	t := e.tun
	changed := old == nil || old.raw != p.raw
	if changed && t != nil && e.tunnelConns == 0 {
		e.tun = nil
		e.setTunStateLocked("down", "")
	} else {
		t = nil
	}
	e.mu.Unlock()
	if t != nil {
		t.close()
	}
	e.tunMu.Unlock()
	if changed {
		e.logf("tunnel configured, endpoint %s, allowed %s", p.raw.Endpoint, p.raw.AllowedIPs)
	}
	e.applyKeepUp()
	e.notify()
	return nil
}

// SetMode switches between home, away and offline. Connections already open
// keep their route; new connections use the new one (F4).
func (e *Engine) SetMode(mode string) error {
	switch mode {
	case ModeHome, ModeAway, ModeOffline:
	default:
		return fmt.Errorf("unknown mode %q", mode)
	}
	e.mu.Lock()
	if e.mode == mode {
		e.mu.Unlock()
		return nil
	}
	e.mode = mode
	e.mu.Unlock()
	e.maybeScheduleIdle()
	e.applyKeepUp()
	e.notify()
	return nil
}

// Mode returns the current mode.
func (e *Engine) Mode() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.mode
}

// NetworkChanged re-resolves the endpoint and rebuilds the tunnel's UDP
// socket, so the tunnel keeps working on the new default network (F5).
func (e *Engine) NetworkChanged() {
	e.tunMu.Lock()
	defer e.tunMu.Unlock()
	e.mu.Lock()
	t, cfg := e.tun, e.cfg
	e.mu.Unlock()
	if t == nil || cfg == nil {
		return
	}
	ep, err := e.resolveEndpoint(cfg)
	if err != nil {
		e.logf("network changed: endpoint lookup failed: %v", err)
	} else if ep != t.endpoint {
		if err := t.dev.IpcSet(fmt.Sprintf("public_key=%s\nendpoint=%s\n", cfg.peerHex, ep)); err != nil {
			e.logf("network changed: endpoint update failed: %v", err)
		} else {
			e.logf("tunnel endpoint now %s", ep)
			e.mu.Lock()
			t.endpoint = ep
			e.mu.Unlock()
		}
	}
	if err := t.dev.BindUpdate(); err != nil {
		e.logf("network changed: socket rebuild failed: %v", err)
	} else {
		e.logf("tunnel socket rebuilt")
	}
	e.notify()
}

// Stop closes all listeners, connections and the tunnel.
func (e *Engine) Stop() {
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return
	}
	e.stopped = true
	fwds := make([]*forward, 0, len(e.forwards))
	for _, f := range e.forwards {
		fwds = append(fwds, f)
	}
	e.forwards = map[string]*forward{}
	px := e.proxy
	e.proxy = nil
	if e.idleTimer != nil {
		e.idleTimer.Stop()
	}
	e.mu.Unlock()
	for _, f := range fwds {
		f.close()
	}
	if px != nil {
		px.close()
	}
	e.tunMu.Lock()
	e.mu.Lock()
	t := e.tun
	e.tun = nil
	e.setTunStateLocked("down", "")
	e.mu.Unlock()
	if t != nil {
		t.close()
	}
	e.tunMu.Unlock()
	e.logf("engine stopped")
	e.notify()
	// Give the notifier a moment to deliver the final status.
	time.AfterFunc(200*time.Millisecond, func() { close(e.done) })
}

// --- routing ---

var errOffline = errors.New("offline: no usable network")

// routeName is the route new connections take in the current mode.
func routeName(mode string) string {
	switch mode {
	case ModeHome:
		return "direct"
	case ModeAway:
		return "tunnel"
	}
	return "none"
}

// dial opens a TCP connection to dst by the route for the current mode. The
// returned release function must be called when the connection closes.
func (e *Engine) dial(ctx context.Context, dst netip.AddrPort) (net.Conn, func(), string, error) {
	mode := e.Mode()
	switch mode {
	case ModeHome:
		c, err := e.dialDirect(ctx, dst)
		return c, func() {}, "direct", err
	case ModeAway:
		t, release, err := e.acquireTunnel()
		if err != nil {
			return nil, nil, "tunnel", err
		}
		c, err := t.tnet.DialContextTCPAddrPort(ctx, dst)
		if err != nil {
			release()
			return nil, nil, "tunnel", fmt.Errorf("through tunnel: %w", err)
		}
		e.pollTunnel(t)
		return c, release, "tunnel", nil
	}
	return nil, nil, "none", errOffline
}

func (e *Engine) dialDirect(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	d := net.Dialer{
		Control: func(_, _ string, rc syscall.RawConn) error {
			var berr error
			if err := rc.Control(func(fd uintptr) {
				if e.host != nil {
					berr = e.host.BindDirectSocket(int(fd))
				}
			}); err != nil {
				return err
			}
			if berr != nil {
				return fmt.Errorf("bind to home network: %w", berr)
			}
			return nil
		},
	}
	return d.DialContext(ctx, "tcp", dst.String())
}

// lookup resolves a host name by the route for the current mode: the
// system resolver of the home network at home, the tunnel's DNS away (F11).
func (e *Engine) lookup(ctx context.Context, host string) ([]netip.Addr, func(), error) {
	if a, err := netip.ParseAddr(strings.Trim(host, "[]")); err == nil {
		return []netip.Addr{a}, func() {}, nil
	}
	mode := e.Mode()
	switch mode {
	case ModeHome:
		addrs, err := e.hostResolve(host)
		return addrs, func() {}, err
	case ModeAway:
		t, release, err := e.acquireTunnel()
		if err != nil {
			return nil, nil, err
		}
		if !t.hasDNS {
			release()
			return nil, nil, errors.New("no DNS server set for the tunnel")
		}
		names, err := t.tnet.LookupContextHost(ctx, host)
		if err != nil {
			release()
			return nil, nil, err
		}
		var out []netip.Addr
		for _, n := range names {
			if a, err := netip.ParseAddr(n); err == nil {
				out = append(out, a)
			}
		}
		return out, release, nil
	}
	return nil, nil, errOffline
}

func (e *Engine) hostResolve(name string) ([]netip.Addr, error) {
	if e.host == nil {
		return nil, errors.New("no resolver")
	}
	s, err := e.host.Resolve(name)
	if err != nil {
		return nil, fmt.Errorf("lookup %s: %w", name, err)
	}
	var out []netip.Addr
	for _, f := range splitList(s) {
		if a, err := netip.ParseAddr(f); err == nil {
			out = append(out, a.Unmap())
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("lookup %s: no addresses", name)
	}
	return out, nil
}

// testResult is the JSON TestRoute returns.
type testResult struct {
	OK     bool   `json:"ok"`
	Route  string `json:"route"`
	Millis int64  `json:"ms"`
	Error  string `json:"error,omitempty"`
}

// TestRoute opens and closes a connection to remoteAddr ("ip:port") by the
// route currently in use, and reports the outcome as JSON.
func (e *Engine) TestRoute(remoteAddr string) string {
	res := testResult{Route: routeName(e.Mode())}
	dst, err := netip.ParseAddrPort(remoteAddr)
	if err != nil {
		res.Error = "target must be an IP address and port"
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		start := time.Now()
		c, release, route, err := e.dial(ctx, dst)
		cancel()
		res.Route = route
		res.Millis = time.Since(start).Milliseconds()
		if err != nil {
			res.Error = err.Error()
		} else {
			res.OK = true
			c.Close()
			release()
		}
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// --- status ---

type forwardStatus struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Local       string `json:"local"`
	Remote      string `json:"remote"`
	State       string `json:"state"`
	Error       string `json:"error,omitempty"`
	Route       string `json:"route"`
	Connections int    `json:"connections"`
}

type proxyStatus struct {
	Enabled     bool   `json:"enabled"`
	Local       string `json:"local,omitempty"`
	State       string `json:"state"`
	Connections int    `json:"connections"`
}

type tunnelStatus struct {
	State         string `json:"state"`
	Error         string `json:"error,omitempty"`
	Configured    bool   `json:"configured"`
	Endpoint      string `json:"endpoint,omitempty"`
	Since         int64  `json:"since,omitempty"`
	LastHandshake int64  `json:"lastHandshake,omitempty"`
	Rx            int64  `json:"rx"`
	Tx            int64  `json:"tx"`
	Connections   int    `json:"connections"`
	IdleTimeout   int    `json:"idleTimeout"`
	KeepUp        bool   `json:"keepUp"`
}

type status struct {
	Mode     string          `json:"mode"`
	Running  bool            `json:"running"`
	Tunnel   tunnelStatus    `json:"tunnel"`
	Forwards []forwardStatus `json:"forwards"`
	Proxy    proxyStatus     `json:"proxy"`
}

// Status returns the current state as JSON.
func (e *Engine) Status() string {
	e.mu.Lock()
	s := status{Mode: e.mode, Running: !e.stopped, Forwards: []forwardStatus{}}
	s.Tunnel = tunnelStatus{State: e.tunState, Error: e.tunErr, Configured: e.cfg != nil, Connections: e.tunnelConns}
	if e.cfg != nil {
		s.Tunnel.IdleTimeout = int(e.cfg.raw.IdleTimeout)
		s.Tunnel.KeepUp = e.cfg.raw.KeepUp
	}
	if e.tun != nil {
		s.Tunnel.Endpoint = e.tun.endpoint
		s.Tunnel.Since = e.tunSince.Unix()
		s.Tunnel.LastHandshake = e.tun.lastHandshake
		s.Tunnel.Rx, s.Tunnel.Tx = e.tun.rx, e.tun.tx
	}
	route := routeName(e.mode)
	for _, f := range e.forwards {
		fs := forwardStatus{ID: f.id, Name: f.name, Local: f.local.String(), Route: route}
		f.mu.Lock()
		fs.Remote = f.remote.String()
		fs.Connections = len(f.conns)
		f.mu.Unlock()
		fs.State = "listening"
		s.Forwards = append(s.Forwards, fs)
	}
	if e.proxy != nil {
		s.Proxy = proxyStatus{Enabled: true, Local: e.proxy.local.String(), State: "listening", Connections: e.proxy.connCount()}
	} else {
		s.Proxy = proxyStatus{State: "off"}
	}
	e.mu.Unlock()
	sort.Slice(s.Forwards, func(i, j int) bool { return s.Forwards[i].Local < s.Forwards[j].Local })
	b, _ := json.Marshal(s)
	return string(b)
}

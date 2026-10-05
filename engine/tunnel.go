package engine

import (
	"bufio"
	"errors"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// handshakeTimeout is how long a new tunnel may go without a handshake
// before its state is reported as an error.
const handshakeTimeout = 10 * time.Second

type tunnel struct {
	dev      *device.Device
	tnet     *netstack.Net
	hasDNS   bool
	endpoint string
	created  time.Time
	stop     chan struct{}

	// Updated by the monitor under Engine.mu.
	lastHandshake int64
	rx, tx        int64
}

func (t *tunnel) close() {
	close(t.stop)
	t.dev.Close()
}

func (e *Engine) setTunStateLocked(state, errMsg string) {
	if e.tunState != state {
		e.tunSince = time.Now()
	}
	e.tunState, e.tunErr = state, errMsg
}

func (e *Engine) resolveEndpoint(cfg *parsedConfig) (string, error) {
	var addr netip.Addr
	if a, err := netip.ParseAddr(cfg.endpointHost); err == nil {
		addr = a
	} else {
		addrs, err := e.hostResolve(cfg.endpointHost)
		if err != nil {
			return "", err
		}
		addr = addrs[0]
		for _, a := range addrs {
			if a.Is4() { // prefer IPv4: many mobile networks lack IPv6
				addr = a
				break
			}
		}
	}
	return netip.AddrPortFrom(addr.Unmap(), cfg.endpointPort).String(), nil
}

// acquireTunnel returns the running tunnel, creating it if needed, and
// counts one more open connection. release must be called exactly once.
func (e *Engine) acquireTunnel() (*tunnel, func(), error) {
	e.tunMu.Lock()
	defer e.tunMu.Unlock()
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return nil, nil, errors.New("engine stopped")
	}
	if e.mode != ModeAway {
		mode := e.mode
		e.mu.Unlock()
		if mode == ModeOffline {
			return nil, nil, errOffline
		}
		return nil, nil, errors.New("tunnel not used in Home mode")
	}
	t := e.tun
	e.mu.Unlock()
	if t == nil {
		var err error
		if t, err = e.startTunnel(); err != nil {
			return nil, nil, err
		}
	}
	e.mu.Lock()
	e.tunnelConns++
	if e.idleTimer != nil {
		e.idleTimer.Stop()
		e.idleTimer = nil
	}
	e.mu.Unlock()
	e.notify()
	var once bool
	return t, func() {
		e.mu.Lock()
		if once {
			e.mu.Unlock()
			return
		}
		once = true
		e.tunnelConns--
		e.mu.Unlock()
		e.maybeScheduleIdle()
		e.notify()
	}, nil
}

// startTunnel creates and starts the WireGuard device. Caller holds tunMu.
func (e *Engine) startTunnel() (*tunnel, error) {
	e.mu.Lock()
	cfg := e.cfg
	if cfg == nil {
		e.mu.Unlock()
		return nil, errors.New("tunnel is not configured")
	}
	e.setTunStateLocked("connecting", "")
	e.mu.Unlock()
	e.notify()

	fail := func(err error) (*tunnel, error) {
		e.mu.Lock()
		e.setTunStateLocked("error", err.Error())
		e.mu.Unlock()
		e.logf("tunnel error: %v", err)
		e.notify()
		return nil, err
	}

	ep, err := e.resolveEndpoint(cfg)
	if err != nil {
		return fail(fmt.Errorf("endpoint unreachable: %w", err))
	}
	tdev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.raw.MTU)
	if err != nil {
		return fail(fmt.Errorf("create tunnel: %w", err))
	}
	logger := &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf: func(format string, args ...any) {
			e.logf("wireguard: "+format, args...)
		},
	}
	bind := newBoundBind(func(fd uintptr) error {
		if e.host == nil {
			return nil
		}
		return e.host.BindTunnelSocket(int(fd))
	})
	dev := device.NewDevice(tdev, bind, logger)
	var sb strings.Builder
	fmt.Fprintf(&sb, "private_key=%s\n", cfg.privateHex)
	fmt.Fprintf(&sb, "public_key=%s\n", cfg.peerHex)
	if cfg.pskHex != "" {
		fmt.Fprintf(&sb, "preshared_key=%s\n", cfg.pskHex)
	}
	fmt.Fprintf(&sb, "endpoint=%s\n", ep)
	if cfg.raw.Keepalive > 0 {
		fmt.Fprintf(&sb, "persistent_keepalive_interval=%d\n", cfg.raw.Keepalive)
	}
	for _, p := range cfg.allowed {
		fmt.Fprintf(&sb, "allowed_ip=%s\n", p)
	}
	if err := dev.IpcSet(sb.String()); err != nil {
		dev.Close()
		return fail(fmt.Errorf("bad key or settings: %w", err))
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return fail(fmt.Errorf("start tunnel: %w", err))
	}
	t := &tunnel{dev: dev, tnet: tnet, hasDNS: len(cfg.dns) > 0, endpoint: ep, created: time.Now(), stop: make(chan struct{})}
	e.mu.Lock()
	e.tun = t
	e.mu.Unlock()
	e.logf("tunnel starting, endpoint %s", ep)
	go e.monitor(t)
	return t, nil
}

// monitor polls the device for handshake and traffic counters while the
// tunnel exists. There are no wake-ups while the tunnel is down (P4).
func (e *Engine) monitor(t *tunnel) {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-t.stop:
			return
		case <-tick.C:
		}
		if !e.pollTunnel(t) {
			return
		}
	}
}

// pollTunnel reads the device counters and updates the tunnel state. It
// returns false once t is no longer the engine's tunnel.
func (e *Engine) pollTunnel(t *tunnel) bool {
	hs, rx, tx := readDeviceStats(t.dev)
	e.mu.Lock()
	if e.tun != t {
		e.mu.Unlock()
		return false
	}
	changed := hs != t.lastHandshake || rx != t.rx || tx != t.tx
	t.lastHandshake, t.rx, t.tx = hs, rx, tx
	prev := e.tunState
	switch {
	case hs > 0 && time.Since(time.Unix(hs, 0)) < 3*time.Minute:
		e.setTunStateLocked("up", "")
	case hs > 0:
		// Handshake is stale: WireGuard re-keys on next traffic.
		if e.tunState != "error" {
			e.setTunStateLocked("connecting", "")
		}
	case time.Since(t.created) > handshakeTimeout:
		e.setTunStateLocked("error", "no handshake within 10 s: check the keys and that the endpoint is reachable")
	}
	state := e.tunState
	e.mu.Unlock()
	if state != prev {
		changed = true
		switch state {
		case "up":
			e.logf("tunnel up %s", t.endpoint)
		case "error":
			e.logf("tunnel error: no handshake within 10 s")
		}
	}
	if changed {
		e.notify()
	}
	return true
}

func readDeviceStats(dev *device.Device) (hs, rx, tx int64) {
	s, err := dev.IpcGet()
	if err != nil {
		return
	}
	sc := bufio.NewScanner(strings.NewReader(s))
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), "=")
		if !ok {
			continue
		}
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "last_handshake_time_sec":
			hs = n
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		}
	}
	return
}

// maybeScheduleIdle arranges tunnel teardown when no connections use it:
// after the idle timeout in Away mode, at once in Home or Offline (F3, F4).
func (e *Engine) maybeScheduleIdle() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.tun == nil || e.tunnelConns > 0 || e.cfg == nil {
		return
	}
	if e.mode == ModeAway && e.cfg.raw.KeepUp {
		return
	}
	delay := time.Duration(e.cfg.raw.IdleTimeout * float64(time.Second))
	if e.mode != ModeAway {
		delay = 0
	}
	if e.idleTimer != nil {
		e.idleTimer.Stop()
	}
	e.idleTimer = time.AfterFunc(delay, e.idleTeardown)
}

func (e *Engine) idleTeardown() {
	e.tunMu.Lock()
	defer e.tunMu.Unlock()
	e.mu.Lock()
	t := e.tun
	if t == nil || e.tunnelConns > 0 || (e.mode == ModeAway && e.cfg != nil && e.cfg.raw.KeepUp) {
		e.mu.Unlock()
		return
	}
	e.tun = nil
	e.idleTimer = nil
	e.setTunStateLocked("down", "")
	e.mu.Unlock()
	t.close()
	e.logf("tunnel down (idle)")
	e.notify()
}

// applyKeepUp brings the tunnel up at once in Away mode when the keep-up
// option is set (F1).
func (e *Engine) applyKeepUp() {
	e.mu.Lock()
	want := e.mode == ModeAway && e.cfg != nil && e.cfg.raw.KeepUp && e.tun == nil && !e.stopped
	e.mu.Unlock()
	if !want {
		e.maybeScheduleIdle()
		return
	}
	go func() {
		e.tunMu.Lock()
		defer e.tunMu.Unlock()
		e.mu.Lock()
		still := e.mode == ModeAway && e.tun == nil && !e.stopped
		e.mu.Unlock()
		if still {
			e.startTunnel()
		}
	}()
}

// RetryTunnel discards a tunnel in the error state so the next connection
// (or keep-up) starts a fresh one.
func (e *Engine) RetryTunnel() {
	e.tunMu.Lock()
	e.mu.Lock()
	var t *tunnel
	if e.tunnelConns == 0 {
		t = e.tun
		e.tun = nil
	}
	e.setTunStateLocked("down", "")
	e.mu.Unlock()
	if t != nil {
		t.close()
	}
	e.tunMu.Unlock()
	e.logf("tunnel reset")
	e.applyKeepUp()
	e.notify()
}

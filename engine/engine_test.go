package engine

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// --- helpers ---

type testHost struct {
	t           *testing.T
	directBinds atomic.Int32
	tunnelBinds atomic.Int32
	mu          sync.Mutex
	last        string
}

func (h *testHost) BindDirectSocket(fd int) error { h.directBinds.Add(1); return nil }
func (h *testHost) BindTunnelSocket(fd int) error { h.tunnelBinds.Add(1); return nil }
func (h *testHost) Resolve(name string) (string, error) {
	addrs, err := net.LookupHost(name)
	return strings.Join(addrs, ","), err
}
func (h *testHost) Log(line string) { h.t.Log(line) }
func (h *testHost) OnStatusChanged(s string) {
	h.mu.Lock()
	h.last = s
	h.mu.Unlock()
}

func genKey(t *testing.T) (priv, pub []byte) {
	priv = make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

func freeTCPPort(t *testing.T) int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// homePeer is the home side: a WireGuard peer with an echo server, a
// read-all-then-reply server and an HTTP server on its tunnel address.
type homePeer struct {
	dev        *device.Device
	tnet       *netstack.Net
	port       int
	pubB64     string
	clientPriv string
}

const (
	homeIP    = "10.9.0.1"
	clientIP  = "10.9.0.2"
	echoAddr  = homeIP + ":7000"
	replyAddr = homeIP + ":7001"
	httpAddr  = homeIP + ":8080"
)

func newHomePeer(t *testing.T) *homePeer {
	hpriv, hpub := genKey(t)
	cpriv, cpub := genKey(t)
	tdev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr(homeIP)}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "home: "))
	port := freeUDPPort(t)
	err = dev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=%s/32\n",
		hex.EncodeToString(hpriv), port, hex.EncodeToString(cpub), clientIP))
	if err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(dev.Close)

	echo, err := tnet.ListenTCP(net.TCPAddrFromAddrPort(netip.MustParseAddrPort(echoAddr)))
	if err != nil {
		t.Fatal(err)
	}
	go serveEcho(echo)
	reply, err := tnet.ListenTCP(net.TCPAddrFromAddrPort(netip.MustParseAddrPort(replyAddr)))
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := reply.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				b, _ := io.ReadAll(c)
				// Reply after the client half-closes.
				time.Sleep(100 * time.Millisecond)
				fmt.Fprintf(c, "got %d bytes", len(b))
			}()
		}
	}()
	hl, err := tnet.ListenTCP(net.TCPAddrFromAddrPort(netip.MustParseAddrPort(httpAddr)))
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(hl, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "hello from home")
	}))
	return &homePeer{dev: dev, tnet: tnet, port: port,
		pubB64: base64.StdEncoding.EncodeToString(hpub), clientPriv: base64.StdEncoding.EncodeToString(cpriv)}
}

func serveEcho(l net.Listener) {
	for {
		c, err := l.Accept()
		if err != nil {
			return
		}
		go func() { io.Copy(c, c); c.Close() }()
	}
}

func (h *homePeer) config(idle float64, keepUp bool) string {
	b, _ := json.Marshal(tunnelConfig{
		PrivateKey:    h.clientPriv,
		PeerPublicKey: h.pubB64,
		Endpoint:      fmt.Sprintf("localhost:%d", h.port),
		AllowedIPs:    "10.9.0.0/24",
		Address:       clientIP + "/32",
		IdleTimeout:   idle,
		KeepUp:        keepUp,
	})
	return string(b)
}

func newTestEngine(t *testing.T) (*Engine, *testHost) {
	h := &testHost{t: t}
	e := NewEngine(h)
	t.Cleanup(e.Stop)
	return e, h
}

func getStatus(t *testing.T, e *Engine) status {
	var s status
	if err := json.Unmarshal([]byte(e.Status()), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func waitFor(t *testing.T, what string, timeout time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func echoOnce(t *testing.T, addr, msg string) {
	t.Helper()
	c, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := c.Write([]byte(msg)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len(msg))
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatalf("echo read: %v", err)
	}
	if string(buf) != msg {
		t.Fatalf("echo got %q want %q", buf, msg)
	}
}

func localAddr(port int) string { return fmt.Sprintf("127.0.0.1:%d", port) }

// --- tests ---

func TestAwayTunnelOnDemandAndIdle(t *testing.T) {
	home := newHomePeer(t)
	e, h := newTestEngine(t)
	if err := e.Configure(home.config(1, false)); err != nil {
		t.Fatal(err)
	}
	e.SetMode(ModeAway)
	lp := freeTCPPort(t)
	if err := e.AddForward("f1", "echo", localAddr(lp), echoAddr); err != nil {
		t.Fatal(err)
	}
	// F1: no tunnel before the first connection.
	time.Sleep(200 * time.Millisecond)
	if s := getStatus(t, e); s.Tunnel.State != "down" {
		t.Fatalf("tunnel %s before first connection", s.Tunnel.State)
	}
	start := time.Now()
	echoOnce(t, localAddr(lp), "hello through the tunnel")
	if d := time.Since(start); d > 5*time.Second {
		t.Fatalf("first connection took %v (F2)", d)
	}
	if h.tunnelBinds.Load() == 0 {
		t.Fatal("tunnel socket was not passed to the host for binding")
	}
	waitFor(t, "tunnel up", 3*time.Second, func() bool {
		s := getStatus(t, e)
		return s.Tunnel.State == "up" && s.Tunnel.LastHandshake > 0 && s.Tunnel.Rx > 0
	})
	// F3: torn down after the idle timeout.
	waitFor(t, "idle teardown", 3*time.Second, func() bool { return getStatus(t, e).Tunnel.State == "down" })
	// And comes back on demand.
	echoOnce(t, localAddr(lp), "again")
}

func TestTunnelNotTornDownWhileOpen(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	e.Configure(home.config(0.2, false))
	e.SetMode(ModeAway)
	lp := freeTCPPort(t)
	e.AddForward("f1", "echo", localAddr(lp), echoAddr)
	c, err := net.Dial("tcp", localAddr(lp))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte("x"))
	io.ReadFull(c, make([]byte, 1))
	time.Sleep(800 * time.Millisecond)
	s := getStatus(t, e)
	if s.Tunnel.State == "down" || s.Tunnel.Connections != 1 || s.Forwards[0].Connections != 1 {
		t.Fatalf("unexpected status with open connection: %+v", s)
	}
	c.Close()
	waitFor(t, "teardown after close", 3*time.Second, func() bool { return getStatus(t, e).Tunnel.State == "down" })
}

func TestHomeDirect(t *testing.T) {
	lanEcho, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer lanEcho.Close()
	go serveEcho(lanEcho)
	home := newHomePeer(t)
	e, h := newTestEngine(t)
	e.Configure(home.config(60, false))
	e.SetMode(ModeHome)
	lp := freeTCPPort(t)
	if err := e.AddForward("f1", "lan", localAddr(lp), lanEcho.Addr().String()); err != nil {
		t.Fatal(err)
	}
	echoOnce(t, localAddr(lp), "direct")
	if h.directBinds.Load() == 0 {
		t.Fatal("direct socket was not bound to the home network")
	}
	s := getStatus(t, e)
	if s.Tunnel.State != "down" || s.Forwards[0].Route != "direct" {
		t.Fatalf("home mode status: %+v", s)
	}
}

func TestOfflineFailsFast(t *testing.T) {
	e, _ := newTestEngine(t)
	lp := freeTCPPort(t)
	e.AddForward("f1", "x", localAddr(lp), "10.9.0.1:7000")
	c, err := net.Dial("tcp", localAddr(lp))
	if err != nil {
		t.Fatalf("listener must stay up offline: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(2 * time.Second))
	start := time.Now()
	_, err = c.Read(make([]byte, 1))
	if err == nil || time.Since(start) > time.Second {
		t.Fatalf("offline connection should close at once, err=%v after %v", err, time.Since(start))
	}
}

func TestEnterHomeDrainsTunnel(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	e.Configure(home.config(60, false))
	e.SetMode(ModeAway)
	lp := freeTCPPort(t)
	e.AddForward("f1", "echo", localAddr(lp), echoAddr)
	c, err := net.Dial("tcp", localAddr(lp))
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(10 * time.Second))
	c.Write([]byte("a"))
	io.ReadFull(c, make([]byte, 1))
	e.SetMode(ModeHome)
	if s := getStatus(t, e); s.Tunnel.State == "down" {
		t.Fatal("tunnel torn down with a connection open")
	}
	// The open tunnelled connection keeps working.
	c.Write([]byte("b"))
	buf := make([]byte, 1)
	if _, err := io.ReadFull(c, buf); err != nil || buf[0] != 'b' {
		t.Fatalf("open connection broke on entering Home: %v", err)
	}
	c.Close()
	waitFor(t, "teardown in Home", 2*time.Second, func() bool { return getStatus(t, e).Tunnel.State == "down" })
}

func TestKeepUp(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	e.Configure(home.config(0.2, true))
	e.SetMode(ModeAway)
	lp := freeTCPPort(t)
	e.AddForward("f1", "echo", localAddr(lp), echoAddr)
	echoOnce(t, localAddr(lp), "k")
	time.Sleep(700 * time.Millisecond)
	if s := getStatus(t, e); s.Tunnel.State == "down" {
		t.Fatal("keep-up tunnel was torn down")
	}
}

func TestHalfClose(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	e.Configure(home.config(60, false))
	e.SetMode(ModeAway)
	lp := freeTCPPort(t)
	e.AddForward("f1", "reply", localAddr(lp), replyAddr)
	c, err := net.Dial("tcp", localAddr(lp))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	c.Write([]byte("12345"))
	c.(*net.TCPConn).CloseWrite()
	b, err := io.ReadAll(c)
	if err != nil || string(b) != "got 5 bytes" {
		t.Fatalf("response after half-close: %q, %v", b, err)
	}
}

func TestForwardEditRemoveAndConflicts(t *testing.T) {
	a, _ := net.Listen("tcp", "127.0.0.1:0")
	defer a.Close()
	go serveEcho(a)
	b, _ := net.Listen("tcp", "127.0.0.1:0")
	defer b.Close()
	go func() {
		for {
			c, err := b.Accept()
			if err != nil {
				return
			}
			c.Write([]byte("B"))
			c.Close()
		}
	}()
	e, _ := newTestEngine(t)
	e.SetMode(ModeHome)
	lp := freeTCPPort(t)
	if err := e.AddForward("f1", "one", localAddr(lp), a.Addr().String()); err != nil {
		t.Fatal(err)
	}
	// F13: conflict names the forward.
	err := e.AddForward("f2", "two", localAddr(lp), a.Addr().String())
	if err == nil || !strings.Contains(err.Error(), `"one"`) {
		t.Fatalf("conflict error = %v", err)
	}
	if err := e.AddForward("f2", "two", "0.0.0.0:9", a.Addr().String()); err == nil {
		t.Fatal("non-loopback listener accepted")
	}
	if err := e.AddForward("f2", "two", localAddr(lp+0), "host.example:80"); err == nil {
		t.Fatal("host name target accepted")
	}
	// Open connection, then edit the target (A10).
	open, err := net.Dial("tcp", localAddr(lp))
	if err != nil {
		t.Fatal(err)
	}
	open.Write([]byte("z"))
	io.ReadFull(open, make([]byte, 1))
	if err := e.AddForward("f1", "one", localAddr(lp), b.Addr().String()); err != nil {
		t.Fatal(err)
	}
	c, _ := net.Dial("tcp", localAddr(lp))
	got, _ := io.ReadAll(c)
	c.Close()
	if string(got) != "B" {
		t.Fatalf("new connection after edit got %q", got)
	}
	// F12: removing closes open connections.
	if err := e.RemoveForward("f1"); err != nil {
		t.Fatal(err)
	}
	open.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := open.Read(make([]byte, 1)); err == nil {
		t.Fatal("connection survived forward removal")
	}
	open.Close()
	if _, err := net.Dial("tcp", localAddr(lp)); err == nil {
		t.Fatal("listener still up after removal")
	}
}

func proxyClient(proxyPort int, user, pass string) *http.Client {
	u := &url.URL{Scheme: "http", Host: localAddr(proxyPort)}
	if user != "" {
		u.User = url.UserPassword(user, pass)
	}
	return &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(u)}}
}

func connectVia(t *testing.T, proxyPort int, target, auth string) (net.Conn, int) {
	c, err := net.Dial("tcp", localAddr(proxyPort))
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprintf(c, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n", target, target)
	if auth != "" {
		fmt.Fprintf(c, "Proxy-Authorization: Basic %s\r\n", base64.StdEncoding.EncodeToString([]byte(auth)))
	}
	fmt.Fprint(c, "\r\n")
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	return c, resp.StatusCode
}

func TestProxy(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	e.Configure(home.config(60, false))
	e.SetMode(ModeAway)
	pp := freeTCPPort(t)
	if err := e.SetProxy(localAddr(pp), "wireplug", "s3cret-password-123"); err != nil {
		t.Fatal(err)
	}
	// A9: 407 without credentials.
	resp, err := proxyClient(pp, "", "").Get("http://" + httpAddr + "/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("no credentials: %d", resp.StatusCode)
	}
	resp, _ = proxyClient(pp, "wireplug", "wrong").Get("http://" + httpAddr + "/")
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("wrong password: %d", resp.StatusCode)
	}
	resp.Body.Close()
	// Plain HTTP through the tunnel.
	resp, err = proxyClient(pp, "wireplug", "s3cret-password-123").Get("http://" + httpAddr + "/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(body) != "hello from home" {
		t.Fatalf("proxied GET: %d %q", resp.StatusCode, body)
	}
	// CONNECT through the tunnel.
	c, code := connectVia(t, pp, echoAddr, "wireplug:s3cret-password-123")
	if code != 200 {
		t.Fatalf("CONNECT: %d", code)
	}
	c.Write([]byte("ping"))
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("CONNECT echo: %q %v", buf, err)
	}
	c.Close()
	// 502 outside the allowed IPs, in both modes (F15).
	for _, m := range []string{ModeAway, ModeHome} {
		e.SetMode(m)
		c, code = connectVia(t, pp, "192.0.2.1:443", "wireplug:s3cret-password-123")
		c.Close()
		if code != http.StatusBadGateway {
			t.Fatalf("%s: CONNECT outside allowed IPs: %d", m, code)
		}
	}
	e.DisableProxy()
	if _, err := net.Dial("tcp", localAddr(pp)); err == nil {
		t.Fatal("proxy still listening after disable")
	}
}

func TestPortConflictWithProxy(t *testing.T) {
	e, _ := newTestEngine(t)
	pp := freeTCPPort(t)
	if err := e.SetProxy(localAddr(pp), "u", "p"); err != nil {
		t.Fatal(err)
	}
	err := e.AddForward("f", "x", localAddr(pp), "10.0.0.1:1")
	if err == nil || !strings.Contains(err.Error(), "proxy") {
		t.Fatalf("conflict with proxy: %v", err)
	}
}

func TestErrorWithoutHandshake(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	cfg := home.config(60, false)
	// Wrong peer key: no handshake can complete.
	_, otherPub := genKey(t)
	cfg = strings.Replace(cfg, home.pubB64, base64.StdEncoding.EncodeToString(otherPub), 1)
	e.Configure(cfg)
	e.SetMode(ModeAway)
	res := e.TestRoute(echoAddr)
	if strings.Contains(res, `"ok":true`) {
		t.Fatalf("test route succeeded with a bad key: %s", res)
	}
	waitFor(t, "error state", 13*time.Second, func() bool { return getStatus(t, e).Tunnel.State == "error" })
}

func TestTestRoute(t *testing.T) {
	home := newHomePeer(t)
	e, _ := newTestEngine(t)
	e.Configure(home.config(60, false))
	e.SetMode(ModeAway)
	var r testResult
	json.Unmarshal([]byte(e.TestRoute(echoAddr)), &r)
	if !r.OK || r.Route != "tunnel" {
		t.Fatalf("TestRoute: %+v", r)
	}
}

func TestNetworkChangedRebinds(t *testing.T) {
	home := newHomePeer(t)
	e, h := newTestEngine(t)
	e.Configure(home.config(60, false))
	e.SetMode(ModeAway)
	lp := freeTCPPort(t)
	e.AddForward("f1", "echo", localAddr(lp), echoAddr)
	echoOnce(t, localAddr(lp), "before")
	n := h.tunnelBinds.Load()
	e.NetworkChanged()
	if h.tunnelBinds.Load() <= n {
		t.Fatal("UDP socket not rebuilt")
	}
	echoOnce(t, localAddr(lp), "after")
}

func TestParseConf(t *testing.T) {
	conf := `
[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.0.2/32
DNS = 192.168.1.1
ListenPort = 51820
Table = off

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
Endpoint = vpn.example.org:51820
AllowedIPs = 192.168.1.0/24, 10.8.0.0/24
PersistentKeepalive = 25
`
	js, err := ParseConf(conf)
	if err != nil {
		t.Fatal(err)
	}
	var r importResult
	json.Unmarshal([]byte(js), &r)
	if r.Config.Endpoint != "vpn.example.org:51820" || r.Config.Keepalive != 25 || r.Config.AllowedIPs != "192.168.1.0/24, 10.8.0.0/24" {
		t.Fatalf("parsed %+v", r.Config)
	}
	if len(r.Warnings) != 2 {
		t.Fatalf("warnings %v", r.Warnings)
	}
	cj, _ := json.Marshal(r.Config)
	if err := ValidateConfig(string(cj)); err != nil {
		t.Fatal(err)
	}
	if pub, err := PublicKey(r.Config.PrivateKey); err != nil || len(pub) != 44 {
		t.Fatalf("PublicKey: %q %v", pub, err)
	}
}

func TestStatusFeed(t *testing.T) {
	e, h := newTestEngine(t)
	lp := freeTCPPort(t)
	e.AddForward("f1", "x", localAddr(lp), "10.0.0.1:1")
	waitFor(t, "status callback", time.Second, func() bool {
		h.mu.Lock()
		defer h.mu.Unlock()
		return strings.Contains(h.last, `"id":"f1"`)
	})
}

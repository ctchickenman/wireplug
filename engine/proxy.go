package engine

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

const proxyID = "\x00proxy"

type proxyServer struct {
	e     *Engine
	local netip.AddrPort
	user  []byte
	pass  []byte
	ln    net.Listener
	srv   *http.Server
	rp    *httputil.ReverseProxy

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	tunnel map[net.Conn]struct{} // hijacked CONNECT connections
}

func (p *proxyServer) connCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.conns) + len(p.tunnel)
}

// SetProxy starts the local HTTP/CONNECT proxy, or reconfigures it (F14).
func (e *Engine) SetProxy(localAddr, username, password string) error {
	local, err := parseLoopback(localAddr)
	if err != nil {
		return err
	}
	if username == "" || password == "" {
		return errors.New("the proxy needs a username and password")
	}
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return errors.New("engine stopped")
	}
	if user := e.portUserLocked(local.Port(), proxyID); user != "" {
		e.mu.Unlock()
		return fmt.Errorf("port %d is already used by %s", local.Port(), user)
	}
	old := e.proxy
	if old != nil && old.local == local {
		old.mu.Lock()
		old.user, old.pass = []byte(username), []byte(password)
		old.mu.Unlock()
		e.mu.Unlock()
		return nil
	}
	e.proxy = nil
	e.mu.Unlock()
	if old != nil {
		old.close()
	}
	ln, err := net.Listen("tcp", local.String())
	if err != nil {
		e.notify()
		return fmt.Errorf("cannot listen on %s: %v", local, err)
	}
	p := &proxyServer{e: e, local: local, user: []byte(username), pass: []byte(password), ln: ln,
		conns: map[net.Conn]struct{}{}, tunnel: map[net.Conn]struct{}{}}
	p.rp = &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.Out.URL = r.In.URL
			r.Out.Host = r.In.Host
			r.Out.Header.Del("Proxy-Authorization")
			r.Out.Header.Del("Proxy-Connection")
		},
		Transport: &http.Transport{
			DialContext:           p.dialContext,
			DisableKeepAlives:     true, // pooled conns would hold the tunnel open
			ResponseHeaderTimeout: 60 * time.Second,
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			http.Error(w, "WirePlug: "+err.Error(), http.StatusBadGateway)
		},
	}
	p.srv = &http.Server{
		Handler:           p,
		ReadHeaderTimeout: 30 * time.Second,
		ConnState: func(c net.Conn, s http.ConnState) {
			p.mu.Lock()
			switch s {
			case http.StateNew:
				p.conns[c] = struct{}{}
			case http.StateClosed:
				delete(p.conns, c)
			case http.StateHijacked:
				// Tracked in serveConnect until it ends.
				delete(p.conns, c)
			}
			p.mu.Unlock()
			e.notify()
		},
	}
	e.mu.Lock()
	e.proxy = p
	e.mu.Unlock()
	go p.srv.Serve(ln)
	e.logf("proxy: listening on %s", local)
	e.notify()
	return nil
}

// DisableProxy stops the proxy and closes its connections.
func (e *Engine) DisableProxy() {
	e.mu.Lock()
	p := e.proxy
	e.proxy = nil
	e.mu.Unlock()
	if p != nil {
		p.close()
		e.logf("proxy: stopped")
		e.notify()
	}
}

func (p *proxyServer) close() {
	p.srv.Close()
	p.mu.Lock()
	for c := range p.tunnel {
		c.Close()
	}
	p.mu.Unlock()
}

func (p *proxyServer) authorized(r *http.Request) bool {
	h := r.Header.Get("Proxy-Authorization")
	scheme, cred, ok := strings.Cut(h, " ")
	if !ok || !strings.EqualFold(scheme, "Basic") {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(cred))
	if err != nil {
		return false
	}
	u, pw, ok := strings.Cut(string(raw), ":")
	if !ok {
		return false
	}
	p.mu.Lock()
	wantU, wantP := p.user, p.pass
	p.mu.Unlock()
	// Constant-time comparison of both fields (S3).
	okU := subtle.ConstantTimeCompare([]byte(u), wantU)
	okP := subtle.ConstantTimeCompare([]byte(pw), wantP)
	return okU&okP == 1
}

type badGateway struct{ reason string }

func (b badGateway) Error() string { return b.reason }

// resolveAllowed resolves host and keeps only addresses inside the tunnel's
// allowed IPs, in both modes (F15).
func (p *proxyServer) resolveAllowed(ctx context.Context, host string) ([]netip.Addr, func(), error) {
	p.e.mu.Lock()
	cfg := p.e.cfg
	p.e.mu.Unlock()
	if cfg == nil {
		return nil, nil, badGateway{"tunnel is not configured"}
	}
	addrs, release, err := p.e.lookup(ctx, host)
	if err != nil {
		return nil, nil, badGateway{fmt.Sprintf("cannot resolve %s: %v", host, err)}
	}
	var ok []netip.Addr
	for _, a := range addrs {
		if cfg.allows(a) {
			ok = append(ok, a.Unmap())
		}
	}
	if len(ok) == 0 {
		release()
		return nil, nil, badGateway{fmt.Sprintf("%s is outside the tunnel's allowed IPs", host)}
	}
	return ok, release, nil
}

// releaseConn calls release once when the connection closes.
type releaseConn struct {
	net.Conn
	once    sync.Once
	release func()
}

func (c *releaseConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.release)
	return err
}

func (c *releaseConn) CloseWrite() error {
	if cw, ok := c.Conn.(closeWriter); ok {
		return cw.CloseWrite()
	}
	return c.Close()
}

func (p *proxyServer) dialContext(ctx context.Context, _, addr string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, badGateway{"bad address " + addr}
	}
	port, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return nil, badGateway{"bad port in " + addr}
	}
	addrs, lookupRelease, err := p.resolveAllowed(ctx, host)
	if err != nil {
		return nil, err
	}
	defer lookupRelease()
	var lastErr error
	for _, a := range addrs {
		c, release, _, err := p.e.dial(ctx, netip.AddrPortFrom(a, uint16(port)))
		if err == nil {
			return &releaseConn{Conn: c, release: release}, nil
		}
		lastErr = err
	}
	return nil, badGateway{fmt.Sprintf("cannot connect to %s: %v", addr, lastErr)}
}

func (p *proxyServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !p.authorized(r) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="WirePlug"`)
		http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	if r.Method == http.MethodConnect {
		p.serveConnect(w, r)
		return
	}
	if !r.URL.IsAbs() || r.URL.Scheme != "http" {
		http.Error(w, "WirePlug: only absolute http:// URLs and CONNECT are supported", http.StatusBadRequest)
		return
	}
	p.e.logf("proxy: %s %s", r.Method, r.URL.Host)
	p.rp.ServeHTTP(w, r)
}

func (p *proxyServer) serveConnect(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), connectTimeout)
	rc, err := p.dialContext(ctx, "tcp", r.Host)
	cancel()
	if err != nil {
		p.e.logf("proxy: CONNECT %s failed: %v", r.Host, err)
		http.Error(w, "WirePlug: "+err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		rc.Close()
		http.Error(w, "hijack unsupported", http.StatusInternalServerError)
		return
	}
	c, brw, err := hj.Hijack()
	if err != nil {
		rc.Close()
		return
	}
	p.mu.Lock()
	p.tunnel[c] = struct{}{}
	p.mu.Unlock()
	p.e.notify()
	defer func() {
		p.mu.Lock()
		delete(p.tunnel, c)
		p.mu.Unlock()
		p.e.notify()
	}()
	if _, err := c.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n")); err != nil {
		c.Close()
		rc.Close()
		return
	}
	var client net.Conn = c
	if brw != nil && brw.Reader.Buffered() > 0 {
		client = &bufferedConn{Conn: c, r: brw.Reader}
	}
	p.e.logf("proxy: CONNECT %s open", r.Host)
	pipe(client, rc)
}

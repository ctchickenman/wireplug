package engine

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"
)

// connectTimeout bounds how long a client waits for the route to connect.
const connectTimeout = 15 * time.Second

type forward struct {
	e     *Engine
	id    string
	name  string
	local netip.AddrPort
	ln    net.Listener

	mu     sync.Mutex
	remote netip.AddrPort
	conns  map[net.Conn]struct{}
	closed bool
}

func parseLoopback(localAddr string) (netip.AddrPort, error) {
	ap, err := netip.ParseAddrPort(localAddr)
	if err != nil {
		return ap, fmt.Errorf("local address %q must be 127.0.0.1:port", localAddr)
	}
	if !ap.Addr().IsLoopback() {
		return ap, errors.New("listeners bind to loopback only")
	}
	if ap.Port() == 0 {
		return ap, errors.New("local port must be set")
	}
	return ap, nil
}

// portUserLocked names what already uses port, other than forward id.
func (e *Engine) portUserLocked(port uint16, id string) string {
	for _, f := range e.forwards {
		if f.id != id && f.local.Port() == port {
			return fmt.Sprintf("forward %q", f.name)
		}
	}
	if id != proxyID && e.proxy != nil && e.proxy.local.Port() == port {
		return "the HTTP proxy"
	}
	return ""
}

// AddForward adds a forward or updates the one with the same id. Changing
// only the target or name keeps the listener and open connections (F12).
func (e *Engine) AddForward(id, name, localAddr, remoteAddr string) error {
	local, err := parseLoopback(localAddr)
	if err != nil {
		return err
	}
	remote, err := netip.ParseAddrPort(remoteAddr)
	if err != nil {
		return fmt.Errorf("target %q must be an IP address and port", remoteAddr)
	}
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return errors.New("engine stopped")
	}
	if user := e.portUserLocked(local.Port(), id); user != "" {
		e.mu.Unlock()
		return fmt.Errorf("port %d is already used by %s", local.Port(), user)
	}
	old := e.forwards[id]
	if old != nil && old.local == local {
		old.mu.Lock()
		changed := old.remote != remote || old.name != name
		old.remote = remote
		old.name = name
		old.mu.Unlock()
		e.mu.Unlock()
		if changed {
			e.logf("%s: target %s", name, remote)
			e.notify()
		}
		return nil
	}
	e.mu.Unlock()

	if old != nil {
		// Free the old port first so a forward can move between ports.
		old.close()
	}
	ln, err := net.Listen("tcp", local.String())
	if err != nil {
		e.mu.Lock()
		if e.forwards[id] == old {
			delete(e.forwards, id)
		}
		e.mu.Unlock()
		e.notify()
		return fmt.Errorf("cannot listen on %s: %v", local, err)
	}
	f := &forward{e: e, id: id, name: name, local: local, remote: remote, ln: ln, conns: map[net.Conn]struct{}{}}
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		ln.Close()
		return errors.New("engine stopped")
	}
	e.forwards[id] = f
	e.mu.Unlock()
	go f.serve()
	e.logf("%s: listening on %s -> %s", name, local, remote)
	e.notify()
	return nil
}

// RemoveForward stops a forward and closes its connections.
func (e *Engine) RemoveForward(id string) error {
	e.mu.Lock()
	f := e.forwards[id]
	delete(e.forwards, id)
	e.mu.Unlock()
	if f == nil {
		return fmt.Errorf("no forward %q", id)
	}
	f.close()
	e.logf("%s: removed", f.name)
	e.notify()
	return nil
}

func (f *forward) close() {
	f.mu.Lock()
	f.closed = true
	conns := f.conns
	f.conns = map[net.Conn]struct{}{}
	f.mu.Unlock()
	f.ln.Close()
	for c := range conns {
		c.Close()
	}
}

func (f *forward) serve() {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			return
		}
		f.mu.Lock()
		if f.closed {
			f.mu.Unlock()
			c.Close()
			return
		}
		f.conns[c] = struct{}{}
		remote, name := f.remote, f.name
		f.mu.Unlock()
		f.e.notify()
		go f.handle(c, remote, name)
	}
}

func (f *forward) handle(c net.Conn, remote netip.AddrPort, name string) {
	defer func() {
		c.Close()
		f.mu.Lock()
		delete(f.conns, c)
		f.mu.Unlock()
		f.e.notify()
	}()
	ctx, cancel := context.WithTimeout(context.Background(), connectTimeout)
	rc, release, route, err := f.e.dial(ctx, remote)
	cancel()
	if err != nil {
		f.e.logf("%s: connection failed (%s): %v", name, route, err)
		return
	}
	defer release()
	f.e.logf("%s: connection open (%s)", name, route)
	pipe(c, rc)
	f.e.logf("%s: connection closed", name)
}

type closeWriter interface {
	CloseWrite() error
}

// pipe copies in both directions. When one direction ends cleanly it
// half-closes the other side and waits for the reverse direction, so a
// response is not cut off after the client half-closes (L9). An error in
// either direction closes both connections.
func pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	var once sync.Once
	closeBoth := func() { once.Do(func() { a.Close(); b.Close() }) }
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		_, err := io.Copy(dst, src)
		if err != nil {
			closeBoth()
			return
		}
		if cw, ok := dst.(closeWriter); ok {
			if cw.CloseWrite() != nil {
				closeBoth()
			}
		} else {
			closeBoth()
		}
	}
	wg.Add(2)
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
	closeBoth()
}

// bufferedConn serves bytes already read into a bufio.Reader before reading
// from the connection itself.
type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }

func (c *bufferedConn) CloseWrite() error {
	if cw, ok := c.Conn.(closeWriter); ok {
		return cw.CloseWrite()
	}
	return c.Conn.Close()
}

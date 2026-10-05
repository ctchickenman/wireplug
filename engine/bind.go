package engine

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"syscall"

	"golang.zx2c4.com/wireguard/conn"
)

// boundBind is a minimal conn.Bind whose UDP sockets are passed to a control
// hook before use, so the Android side can bind them to the current default
// Network (P2, L8). The device calls Open again on BindUpdate, which gives a
// fresh socket after a network change (F5).
type boundBind struct {
	control func(fd uintptr) error

	mu sync.Mutex
	v4 *net.UDPConn
	v6 *net.UDPConn
}

func newBoundBind(control func(fd uintptr) error) *boundBind {
	return &boundBind{control: control}
}

type udpEndpoint struct {
	dst netip.AddrPort
}

func (e *udpEndpoint) ClearSrc()           {}
func (e *udpEndpoint) SrcToString() string { return "" }
func (e *udpEndpoint) DstToString() string { return e.dst.String() }
func (e *udpEndpoint) DstToBytes() []byte  { b, _ := e.dst.MarshalBinary(); return b }
func (e *udpEndpoint) DstIP() netip.Addr   { return e.dst.Addr() }
func (e *udpEndpoint) SrcIP() netip.Addr   { return netip.Addr{} }

func (b *boundBind) listen(network string, port uint16) (*net.UDPConn, error) {
	lc := net.ListenConfig{}
	if b.control != nil {
		lc.Control = func(_, _ string, rc syscall.RawConn) error {
			var cerr error
			if err := rc.Control(func(fd uintptr) { cerr = b.control(fd) }); err != nil {
				return err
			}
			return cerr
		}
	}
	addr := netip.AddrPortFrom(netip.IPv4Unspecified(), port)
	if network == "udp6" {
		addr = netip.AddrPortFrom(netip.IPv6Unspecified(), port)
	}
	pc, err := lc.ListenPacket(context.Background(), network, addr.String())
	if err != nil {
		return nil, err
	}
	return pc.(*net.UDPConn), nil
}

func (b *boundBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.v4 != nil || b.v6 != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	v4, err := b.listen("udp4", port)
	if err != nil {
		return nil, 0, err
	}
	actual := uint16(v4.LocalAddr().(*net.UDPAddr).Port)
	b.v4 = v4
	fns := []conn.ReceiveFunc{makeReceive(v4)}
	// IPv6 is best effort: many mobile networks have none.
	if v6, err := b.listen("udp6", actual); err == nil {
		b.v6 = v6
		fns = append(fns, makeReceive(v6))
	}
	return fns, actual, nil
}

func makeReceive(c *net.UDPConn) conn.ReceiveFunc {
	return func(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		n, ap, err := c.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		eps[0] = &udpEndpoint{dst: netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())}
		return 1, nil
	}
}

func (b *boundBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	var err error
	if b.v4 != nil {
		err = b.v4.Close()
		b.v4 = nil
	}
	if b.v6 != nil {
		if e := b.v6.Close(); err == nil {
			err = e
		}
		b.v6 = nil
	}
	return err
}

func (b *boundBind) SetMark(uint32) error { return nil }

func (b *boundBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	e, ok := ep.(*udpEndpoint)
	if !ok {
		return conn.ErrWrongEndpointType
	}
	b.mu.Lock()
	c := b.v4
	if e.dst.Addr().Is6() {
		c = b.v6
	}
	b.mu.Unlock()
	if c == nil {
		return errors.New("no socket for address family")
	}
	for _, buf := range bufs {
		if _, err := c.WriteToUDPAddrPort(buf, e.dst); err != nil {
			return err
		}
	}
	return nil
}

func (b *boundBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return &udpEndpoint{dst: netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())}, nil
}

func (b *boundBind) BatchSize() int { return 1 }

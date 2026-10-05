// Command testpeer runs a userspace WireGuard peer that stands in for the
// home network during development. Inside the tunnel it serves a TCP echo on
// 10.9.0.1:7000 and HTTP on 10.9.0.1:8080, and it prints a client .conf to
// import into WirePlug.
//
//	go run ./tools/testpeer -listen 51820 -endpoint 10.0.2.2:51820
package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func key() (priv, pub []byte) {
	priv = make([]byte, 32)
	rand.Read(priv)
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, _ = curve25519.X25519(priv, curve25519.Basepoint)
	return
}

func main() {
	port := flag.Int("listen", 51820, "UDP port to listen on")
	endpoint := flag.String("endpoint", "10.0.2.2:51820", "endpoint written into the client .conf")
	out := flag.String("conf", "", "write the client .conf to this file as well")
	flag.Parse()

	hpriv, hpub := key()
	cpriv, cpub := key()
	tdev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil, 1280)
	if err != nil {
		log.Fatal(err)
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "peer: "))
	err = dev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.9.0.2/32\n",
		hex.EncodeToString(hpriv), *port, hex.EncodeToString(cpub)))
	if err != nil {
		log.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		log.Fatal(err)
	}

	echo, err := tnet.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 9, 0, 1), Port: 7000})
	if err != nil {
		log.Fatal(err)
	}
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			log.Printf("echo: connection from %s", c.RemoteAddr())
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	hl, err := tnet.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 9, 0, 1), Port: 8080})
	if err != nil {
		log.Fatal(err)
	}
	go http.Serve(hl, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("http: %s %s", r.Method, r.URL)
		fmt.Fprintln(w, "hello from the test peer")
	}))

	conf := fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = 10.9.0.2/32
DNS = 10.9.0.1

[Peer]
PublicKey = %s
Endpoint = %s
AllowedIPs = 10.9.0.0/24
PersistentKeepalive = 25
`, base64.StdEncoding.EncodeToString(cpriv), base64.StdEncoding.EncodeToString(hpub), *endpoint)
	fmt.Print(conf)
	if *out != "" {
		if err := os.WriteFile(*out, []byte(conf), 0o600); err != nil {
			log.Fatal(err)
		}
	}
	log.Printf("listening on UDP %d; echo 10.9.0.1:7000, http 10.9.0.1:8080", *port)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	<-sig
	dev.Close()
}

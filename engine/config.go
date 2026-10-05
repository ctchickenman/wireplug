package engine

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"

	"golang.org/x/crypto/curve25519"
)

// TunnelConfig is the tunnel configuration the Android side stores and passes
// to Engine.Configure as JSON. Keys are standard base64 WireGuard keys.
type tunnelConfig struct {
	PrivateKey    string  `json:"privateKey"`
	PeerPublicKey string  `json:"peerPublicKey"`
	PresharedKey  string  `json:"presharedKey,omitempty"`
	Endpoint      string  `json:"endpoint"`
	AllowedIPs    string  `json:"allowedIPs"`
	Address       string  `json:"address"`
	DNS           string  `json:"dns,omitempty"`
	MTU           int     `json:"mtu,omitempty"`
	Keepalive     int     `json:"keepalive,omitempty"`
	IdleTimeout   float64 `json:"idleTimeout,omitempty"` // seconds
	KeepUp        bool    `json:"keepUp,omitempty"`
}

// parsedConfig is a validated tunnelConfig.
type parsedConfig struct {
	raw          tunnelConfig
	privateHex   string
	peerHex      string
	pskHex       string
	endpointHost string
	endpointPort uint16
	allowed      []netip.Prefix
	addresses    []netip.Addr
	dns          []netip.Addr
}

func keyToHex(name, b64 string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("%s is not a valid WireGuard key", name)
	}
	return hex.EncodeToString(b), nil
}

func splitList(s string) []string {
	var out []string
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' || r == '\t' }) {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}

func parseConfig(js string) (*parsedConfig, error) {
	var c tunnelConfig
	if err := json.Unmarshal([]byte(js), &c); err != nil {
		return nil, fmt.Errorf("bad configuration: %v", err)
	}
	p := &parsedConfig{raw: c}
	var err error
	if p.privateHex, err = keyToHex("private key", c.PrivateKey); err != nil {
		return nil, err
	}
	if p.peerHex, err = keyToHex("peer public key", c.PeerPublicKey); err != nil {
		return nil, err
	}
	if strings.TrimSpace(c.PresharedKey) != "" {
		if p.pskHex, err = keyToHex("preshared key", c.PresharedKey); err != nil {
			return nil, err
		}
	}
	host, portStr, err := net.SplitHostPort(strings.TrimSpace(c.Endpoint))
	if err != nil || host == "" {
		return nil, fmt.Errorf("endpoint must be host:port")
	}
	port, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil || port == 0 {
		return nil, fmt.Errorf("endpoint port is not valid")
	}
	p.endpointHost, p.endpointPort = host, uint16(port)
	for _, s := range splitList(c.AllowedIPs) {
		pfx, err := netip.ParsePrefix(s)
		if err != nil {
			a, err2 := netip.ParseAddr(s)
			if err2 != nil {
				return nil, fmt.Errorf("allowed IP %q is not valid", s)
			}
			pfx = netip.PrefixFrom(a, a.BitLen())
		}
		p.allowed = append(p.allowed, pfx.Masked())
	}
	if len(p.allowed) == 0 {
		return nil, errors.New("allowed IPs are empty")
	}
	for _, s := range splitList(c.Address) {
		if pfx, err := netip.ParsePrefix(s); err == nil {
			p.addresses = append(p.addresses, pfx.Addr())
		} else if a, err := netip.ParseAddr(s); err == nil {
			p.addresses = append(p.addresses, a)
		} else {
			return nil, fmt.Errorf("tunnel address %q is not valid", s)
		}
	}
	if len(p.addresses) == 0 {
		return nil, errors.New("tunnel address is empty")
	}
	for _, s := range splitList(c.DNS) {
		a, err := netip.ParseAddr(s)
		if err != nil {
			// Search domains are allowed in wg-quick files; ignore them.
			continue
		}
		p.dns = append(p.dns, a)
	}
	if c.Keepalive < 0 || c.Keepalive > 65535 {
		return nil, errors.New("keepalive is out of range")
	}
	if c.MTU == 0 {
		p.raw.MTU = 1280
	}
	if c.IdleTimeout <= 0 {
		p.raw.IdleTimeout = 60
	}
	return p, nil
}

// allows reports whether a is inside the tunnel's allowed IPs.
func (p *parsedConfig) allows(a netip.Addr) bool {
	a = a.Unmap()
	for _, pfx := range p.allowed {
		if pfx.Contains(a) {
			return true
		}
	}
	return false
}

// importResult is what ParseConf returns as JSON.
type importResult struct {
	Config   tunnelConfig `json:"config"`
	Warnings []string     `json:"warnings"`
}

// ParseConf parses a standard WireGuard .conf file (as written by wg-quick)
// and returns JSON {"config": {...}, "warnings": [...]}. Unknown or unsupported
// directives are reported as warnings rather than silently dropped (S7).
func ParseConf(text string) (string, error) {
	var res importResult
	res.Warnings = []string{}
	section := ""
	peers := 0
	sc := bufio.NewScanner(strings.NewReader(text))
	line := 0
	for sc.Scan() {
		line++
		l := sc.Text()
		if i := strings.IndexByte(l, '#'); i >= 0 {
			l = l[:i]
		}
		l = strings.TrimSpace(l)
		if l == "" {
			continue
		}
		if strings.HasPrefix(l, "[") && strings.HasSuffix(l, "]") {
			section = strings.ToLower(strings.TrimSpace(l[1 : len(l)-1]))
			if section == "peer" {
				peers++
				if peers > 1 {
					res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: only the first [Peer] is used", line))
				}
			} else if section != "interface" {
				res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: unknown section [%s] ignored", line, section))
			}
			continue
		}
		k, v, ok := strings.Cut(l, "=")
		if !ok {
			res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: not a key = value line", line))
			continue
		}
		k = strings.ToLower(strings.TrimSpace(k))
		v = strings.TrimSpace(v)
		appendList := func(dst *string) {
			if *dst == "" {
				*dst = v
			} else {
				*dst += ", " + v
			}
		}
		switch {
		case section == "interface":
			switch k {
			case "privatekey":
				res.Config.PrivateKey = v
			case "address":
				appendList(&res.Config.Address)
			case "dns":
				appendList(&res.Config.DNS)
			case "mtu":
				n, err := strconv.Atoi(v)
				if err != nil {
					res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: MTU %q is not a number", line, v))
				} else {
					res.Config.MTU = n
				}
			case "listenport":
				res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: ListenPort is not used; a random port is chosen", line))
			default:
				res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: unsupported [Interface] directive %q ignored", line, k))
			}
		case section == "peer" && peers == 1:
			switch k {
			case "publickey":
				res.Config.PeerPublicKey = v
			case "presharedkey":
				res.Config.PresharedKey = v
			case "endpoint":
				res.Config.Endpoint = v
			case "allowedips":
				appendList(&res.Config.AllowedIPs)
			case "persistentkeepalive":
				if v == "off" {
					res.Config.Keepalive = 0
				} else if n, err := strconv.Atoi(v); err == nil {
					res.Config.Keepalive = n
				} else {
					res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: PersistentKeepalive %q is not a number", line, v))
				}
			default:
				res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: unsupported [Peer] directive %q ignored", line, k))
			}
		case section == "":
			res.Warnings = append(res.Warnings, fmt.Sprintf("line %d: directive outside a section ignored", line))
		}
	}
	if res.Config.PrivateKey == "" && res.Config.PeerPublicKey == "" {
		return "", errors.New("no WireGuard [Interface] or [Peer] found")
	}
	b, _ := json.Marshal(res)
	return string(b), nil
}

// PublicKey derives the base64 public key from a base64 private key.
func PublicKey(privateKey string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(privateKey))
	if err != nil || len(b) != 32 {
		return "", errors.New("not a valid WireGuard private key")
	}
	pub, err := curve25519.X25519(b, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(pub), nil
}

// ValidateConfig checks a tunnel configuration JSON without applying it.
func ValidateConfig(js string) error {
	_, err := parseConfig(js)
	return err
}

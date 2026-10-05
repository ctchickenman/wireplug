# WirePlug

An Android app that gives other apps access to services on your home network
through ports on `127.0.0.1`, using a WireGuard tunnel that runs entirely
inside the app. It needs no system VPN, no VPN slot and no root. At home it
connects straight to the LAN; away from home it goes through the tunnel,
brought up on demand and torn down when idle. The local ports are the same in
both places.

See [docs/requirements.md](docs/requirements.md) for the full requirements and
[docs/wireframes/](docs/wireframes/) for the screens.

## Layout

| Path | What |
| --- | --- |
| `engine/` | Go engine (gomobile AAR): listeners, router, direct dialer, on-demand userspace WireGuard, HTTP/CONNECT proxy, status feed, `.conf` parser |
| `app/` | Kotlin / Jetpack Compose app: UI, foreground service, Keystore-encrypted config, home-network detection |
| `tools/testpeer/` | A userspace WireGuard peer for development, serving echo and HTTP inside the tunnel |
| `build.sh` | Runs the engine tests, builds the AAR, then the APK |

## Building

Needs Go (see `go.mod`), JDK 17 or later, the Android SDK (platform 37), the
NDK and network access to the Go and Maven repositories. `gomobile` and
`gobind` are pinned in `go.mod` as tools; `build.sh` builds them.

```sh
./build.sh            # debug APK
./build.sh release    # signed release APK
```

Release signing reads `~/.config/wireplug/keystore.properties` (or the file
named by `$WIREPLUG_KEYSTORE_PROPERTIES`) with `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`. Without it the release APK is unsigned.

## Testing

`go test ./engine` runs the engine against a second in-process WireGuard peer
over loopback UDP: on-demand start, idle teardown, keep-up, Home/Away/Offline
routing, draining on entering Home, half-close, forward edit/remove and port
conflicts, the proxy (407, CONNECT, plain HTTP, 502 outside the allowed IPs),
handshake failure, socket rebinding and `.conf` import.

To try the app on an emulator against a fake home network:

```sh
go run ./tools/testpeer -listen 51820 -endpoint 10.0.2.2:51820 -conf /tmp/wireplug.conf
adb push /tmp/wireplug.conf /sdcard/Download/
```

Import the file in **Settings → Tunnel**, add a forward such as
`17000 → 10.9.0.1:7000`, turn WirePlug on, then
`adb shell 'echo hi | toybox nc -q 2 127.0.0.1 17000'`.

## Implementation notes

- **Foreground service type:** `specialUse`. The service keeps loopback
  listeners and a userspace tunnel alive; it is not a `VpnService`, media,
  location or connected-device service, and `dataSync` is time-limited on
  Android 15. Distribution is by sideloading, so Play policy does not apply.
- **Home check (P1):** the TXT or A query is sent with a small built-in DNS
  client over a UDP socket bound to the current network, straight to that
  network's own DNS servers, so Private DNS and other VPNs do not hide the
  home name and TXT works on API 26–28.
- **DNS in Go (L7):** every host-name lookup outside the tunnel (the endpoint,
  and proxy targets at home) goes through `Host.Resolve`, which uses
  `Network.getAllByName`. Tunnel-side lookups use the tunnel's DNS server.
- **UDP binding (L8):** the engine has its own minimal `conn.Bind` whose
  sockets are passed to `Host.BindTunnelSocket` before use, so the tunnel's UDP
  socket is bound to the current default network. `BindUpdate` rebuilds it on
  network changes (F5).
- **Local network permission:** Android 17 blocks connections to LAN addresses
  for apps targeting API 37 unless they hold `ACCESS_LOCAL_NETWORK`. DNS on
  port 53 is exempt, so the home check passes without it, but direct
  connections time out. WirePlug asks for it when the home check is saved and
  shows a warning while it is missing. Until it is granted, Home falls back to
  the tunnel so forwards keep working.
- **Other VPNs (P5):** when another VPN is the default network, both routes go
  through it. This is known behaviour in v1.
- **Boot receiver:** not exported; the system still delivers `BOOT_COMPLETED`
  to it, so the launcher activity remains the only exported component (S8).

### Still to verify on a real device

The acceptance tests A1 to A12 are run on hardware, on the home network and on
mobile data. The emulator cannot exercise the home DNS check against a real
TXT record, Private DNS in strict mode, or Wi-Fi/cellular handover.

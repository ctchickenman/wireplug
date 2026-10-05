"""Generate the WirePlug wireframes as standalone SVG files, next to this script.

Run: python3 generate.py
"""
import math, os
from xml.sax.saxutils import escape

OUT = os.path.dirname(os.path.abspath(__file__))
INK, MID, LINE, FILL, PAPER = "#202124", "#5f6368", "#9aa0a6", "#f1f3f4", "#ffffff"
FONT = "Roboto, 'Helvetica Neue', Arial, sans-serif"
MONO = "'Roboto Mono', Menlo, Consolas, monospace"
W, H = 360, 760          # phone screen
CW, CH = 400, 840        # one panel (caption + phone)
PAD = 16


def T(x, y, s, size=13, weight=400, fill=INK, anchor="start", mono=False):
    fam = MONO if mono else FONT
    return (f'<text x="{x}" y="{y}" font-family="{fam}" font-size="{size}" '
            f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{escape(s)}</text>')


def R(x, y, w, h, rx=0, fill="none", stroke=LINE, sw=1, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{sw}"{d}/>')


def L(x1, y1, x2, y2, stroke=LINE, sw=1):
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" stroke-width="{sw}"/>'


def switch(x, y, on=True):
    if on:
        return (R(x, y, 44, 24, 12, fill=INK, stroke=INK) +
                f'<circle cx="{x+32}" cy="{y+12}" r="8" fill="{PAPER}"/>')
    return (R(x, y, 44, 24, 12, fill=PAPER, stroke=MID, sw=1.5) +
            f'<circle cx="{x+12}" cy="{y+12}" r="6" fill="{MID}"/>')


def chip(x, y, label, filled=False, anchor="start"):
    w = round(len(label) * 6.2 + 16)
    if anchor == "end":
        x -= w
    f, tf = (INK, PAPER) if filled else (FILL, INK)
    return R(x, y, w, 20, 10, fill=f, stroke=INK if filled else LINE) + \
        T(x + w / 2, y + 14, label, 11, 500, tf, "middle")


def button(x, y, w, label, filled=False, h=36):
    f, tf = (INK, PAPER) if filled else (PAPER, INK)
    return R(x, y, w, h, h / 2, fill=f, stroke=INK, sw=1.2) + \
        T(x + w / 2, y + h / 2 + 4.5, label, 13, 500, tf, "middle")


def field(x, y, w, label, value, mono=False, h=48):
    return (R(x, y, w, h, 4, fill=PAPER, stroke=MID) +
            T(x + 12, y + 17, label, 10.5, 400, MID) +
            T(x + 12, y + 36, value, 14, 400, INK, mono=mono))


def chevron(x, y):
    return (f'<polyline points="{x},{y-5} {x+5},{y} {x},{y+5}" fill="none" '
            f'stroke="{MID}" stroke-width="1.6"/>')


def gear(cx, cy):
    s = f'<circle cx="{cx}" cy="{cy}" r="6.5" fill="none" stroke="{INK}" stroke-width="1.6"/>'
    s += f'<circle cx="{cx}" cy="{cy}" r="2.2" fill="{INK}"/>'
    for i in range(8):
        a = i * math.pi / 4
        s += L(round(cx + 7.5 * math.cos(a), 1), round(cy + 7.5 * math.sin(a), 1),
               round(cx + 10.5 * math.cos(a), 1), round(cy + 10.5 * math.sin(a), 1), INK, 2)
    return s


def copy_icon(x, y):
    return R(x + 4, y, 10, 12, 2, stroke=INK, sw=1.4) + R(x, y + 4, 10, 12, 2, fill=PAPER, stroke=INK, sw=1.4)


def warn_icon(x, y):
    return (f'<polygon points="{x+9},{y} {x+18},{y+16} {x},{y+16}" fill="none" stroke="{INK}" '
            f'stroke-width="1.5" stroke-linejoin="round"/>' + T(x + 9, y + 14, "!", 11, 700, INK, "middle"))


def info_icon(x, y):
    return (f'<circle cx="{x+9}" cy="{y+9}" r="8.5" fill="none" stroke="{INK}" stroke-width="1.4"/>' +
            T(x + 9, y + 13.5, "i", 12, 700, INK, "middle"))


class Screen:
    def __init__(self, caption):
        self.caption, self.p, self.y = caption, [], 0
        # status bar
        self.p.append(T(PAD, 19, "9:41", 11, 500, MID))
        self.p.append(R(W - 38, 10, 20, 10, 2, stroke=MID) + R(W - 60, 10, 14, 10, 2, stroke=MID))
        self.y = 28

    def add(self, s):
        self.p.append(s)

    def appbar(self, title, back=True, action=None):
        y = self.y
        x = PAD
        if back:
            self.add(f'<path d="M{x+16},{y+28} L{x+2},{y+28} M{x+9},{y+21} L{x+2},{y+28} L{x+9},{y+35}" '
                     f'fill="none" stroke="{INK}" stroke-width="1.8"/>')
            x += 32
        self.add(T(x, y + 34, title, 20, 500))
        if action == "gear":
            self.add(gear(W - PAD - 12, y + 28))
        elif action:
            self.add(T(W - PAD, y + 33, action, 14, 500, INK, "end"))
        self.add(L(0, y + 56, W, y + 56))
        self.y = y + 68

    def section(self, label):
        self.add(T(PAD, self.y + 12, label.upper(), 10.5, 500, MID))
        self.y += 22

    def note(self, lines, size=12):
        for ln in lines:
            self.add(T(PAD, self.y + 12, ln, size, 400, MID))
            self.y += 17
        self.y += 6

    def card(self, h, dash=None, fill=PAPER):
        self.add(R(PAD, self.y, W - 2 * PAD, h, 12, fill=fill, stroke=LINE, dash=dash))
        y = self.y
        self.y += h + 10
        return y

    def row(self, title, sub=None, trailing=None, on=None, h=56, divider=True):
        y = self.y
        if sub:
            self.add(T(PAD, y + 23, title, 14.5, 500))
            self.add(T(PAD, y + 41, sub, 12, 400, MID))
        else:
            self.add(T(PAD, y + 33, title, 14.5, 500))
        if on is not None:
            self.add(switch(W - PAD - 44, y + 16, on))
        if trailing == "chevron":
            self.add(chevron(W - PAD - 8, y + 28))
        if divider:
            self.add(L(PAD, y + h, W - PAD, y + h, "#dadce0"))
        self.y = y + h

    def fields(self, items, gap=8):
        """items: list of (label, value[, mono]) laid out in one row."""
        n = len(items)
        w = (W - 2 * PAD - gap * (n - 1)) / n
        for i, it in enumerate(items):
            mono = it[2] if len(it) > 2 else False
            self.add(field(PAD + i * (w + gap), self.y, w, it[0], it[1], mono))
        self.y += 48 + 10

    def body(self):
        return "".join(self.p)


def panel(scr, ox=0, oy=0):
    """One captioned phone at offset."""
    px, py = 20, 44
    g = f'<g transform="translate({ox},{oy})">'
    g += T(px + 4, 28, scr.caption, 14, 600, INK)
    g += R(px - 6, py - 6, W + 12, H + 12, 30, fill=PAPER, stroke=INK, sw=2)
    cid = "clip-" + scr.caption.split(" ")[0]
    g += f'<clipPath id="{cid}"><rect x="{px}" y="{py}" width="{W}" height="{H}" rx="24"/></clipPath>'
    g += f'<g clip-path="url(#{cid})"><g transform="translate({px},{py})">{scr.body()}</g></g>'
    return g + "</g>"


def svg(inner, w, h, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" '
            f'role="img" aria-label="{escape(title)}"><title>{escape(title)}</title>'
            f'<rect width="{w}" height="{h}" fill="{PAPER}"/>{inner}</svg>')


# ---------------------------------------------------------------- shared blocks
FWD = [("Home Assistant", ":8123  →  192.168.1.10:8123", "2 open"),
       ("NAS", ":5001  →  192.168.1.20:5001", "1 open"),
       ("SSH", ":2222  →  192.168.1.5:22", "0 open")]


def hero(s, state, sub, on=True):
    y = s.card(76, fill=FILL)
    s.add(T(PAD + 16, y + 34, state, 20, 600))
    s.add(T(PAD + 16, y + 55, sub, 12, 400, MID))
    s.add(switch(W - PAD - 16 - 44, y + 26, on))


def forwards_card(s, route):
    y = s.card(34 + 50 * len(FWD))
    s.add(T(PAD + 16, y + 23, "Forwards", 13, 600))
    for i, (name, mp, n) in enumerate(FWD):
        ry = y + 34 + 50 * i
        s.add(L(PAD + 16, ry, W - PAD - 16, ry, "#dadce0"))
        s.add(T(PAD + 16, ry + 21, name, 13.5, 500))
        s.add(chip(W - PAD - 16, ry + 7, route, anchor="end"))
        s.add(T(PAD + 16, ry + 39, mp, 10.5, 400, MID, mono=True))
        s.add(T(W - PAD - 16, ry + 39, f"Listening · {n}", 11, 400, MID, "end"))


def proxy_card(s, route):
    y = s.card(50)
    s.add(T(PAD + 16, y + 21, "HTTP proxy", 13, 600))
    s.add(T(PAD + 16, y + 39, "127.0.0.1:8888 · Listening", 11, 400, MID))
    s.add(chip(W - PAD - 16, y + 15, route, anchor="end"))


def log_card(s, lines):
    y = s.card(34 + 16 * len(lines) + 8)
    s.add(T(PAD + 16, y + 23, "Log", 13, 600))
    s.add(T(W - PAD - 16, y + 23, "Copy", 12, 500, INK, "end"))
    s.add(copy_icon(W - PAD - 16 - 50, y + 10))
    for i, ln in enumerate(lines):
        s.add(T(PAD + 16, y + 46 + 16 * i, ln, 9.5, 400, MID, mono=True))


# ---------------------------------------------------------------- screens
def status_away():
    s = Screen("1 · Status — Away, tunnel up")
    s.appbar("WirePlug", back=False, action="gear")
    hero(s, "Away, tunnel up", "On another network · service running")
    y = s.card(46, dash="4 3")
    s.add(warn_icon(PAD + 14, y + 14))
    s.add(T(PAD + 42, y + 28, "Battery restriction may stop the service", 12, 400))
    s.add(T(W - PAD - 14, y + 28, "Fix", 12.5, 600, INK, "end"))
    y = s.card(112)
    s.add(T(PAD + 16, y + 23, "Tunnel", 13, 600))
    s.add(chip(W - PAD - 16, y + 9, "Up", filled=True, anchor="end"))
    for i, (k, v) in enumerate([("Last handshake", "12 s ago"),
                                 ("Sent / received", "1.2 MB / 8.4 MB"),
                                 ("Endpoint", "vpn.example.org:51820")]):
        s.add(T(PAD + 16, y + 50 + 22 * i, k, 12, 400, MID))
        s.add(T(W - PAD - 16, y + 50 + 22 * i, v, 12, 500, INK, "end"))
    forwards_card(s, "Tunnel")
    proxy_card(s, "Tunnel")
    log_card(s, ["12:01:07 mode=away network changed",
                 "12:01:09 tunnel up vpn.example.org:51820",
                 "12:01:09 NAS: connection open (tunnel)",
                 "12:03:40 NAS: connection closed"])
    return s


def status_home():
    s = Screen("2 · Status — Home, direct")
    s.appbar("WirePlug", back=False, action="gear")
    hero(s, "Home, direct", "internal.domain matched (TXT record)")
    y = s.card(58)
    s.add(T(PAD + 16, y + 23, "Tunnel", 13, 600))
    s.add(chip(W - PAD - 16, y + 9, "Down", anchor="end"))
    s.add(T(PAD + 16, y + 44, "Not needed on the home network", 12, 400, MID))
    forwards_card(s, "Direct")
    proxy_card(s, "Direct")
    log_card(s, ["18:22:51 network changed",
                 "18:22:51 home check matched (TXT)",
                 "18:22:51 mode=home",
                 "18:23:55 tunnel down (no open connections)"])
    return s


def settings():
    s = Screen("3 · Settings")
    s.appbar("Settings")
    for t, sub in [("Tunnel", "vpn.example.org:51820"),
                   ("Home network", "internal.domain · TXT record"),
                   ("Forwards", "3 forwards, 2 enabled"),
                   ("HTTP proxy", "On · port 8888"),
                   ("Behaviour", "Start at boot, notification")]:
        s.row(t, sub, trailing="chevron", h=60)
    return s


def tunnel():
    s = Screen("4 · Tunnel")
    s.appbar("Tunnel", action="Save")
    half = (W - 2 * PAD - 8) / 2
    s.add(button(PAD, s.y, half, "Import .conf"))
    s.add(button(PAD + half + 8, s.y, half, "Paste clipboard"))
    s.y += 36 + 14
    s.section("Peer")
    s.fields([("Private key (never shown in full)", "••••••••••••••••••••  Replace")])
    s.fields([("Peer public key", "xTIBA5rboUvnH4htodjb6e…", True)])
    s.fields([("Endpoint (hostname or IP)", "vpn.example.org:51820", True)])
    s.fields([("Allowed IPs", "192.168.1.0/24", True)])
    s.fields([("Tunnel address", "10.8.0.2/32", True), ("DNS server", "192.168.1.1", True)])
    s.fields([("Keepalive", "25 s"), ("Idle timeout", "60 s")])
    s.y += 4
    s.section("On demand")
    s.row("Keep tunnel up", "Otherwise it starts on first connection", on=False, divider=False)
    return s


def home_network():
    s = Screen("5 · Home network")
    s.appbar("Home network", action="Save")
    s.note(["Home mode is active when this host name resolves",
            "with the expected value on the current network.",
            "Use a name only your home DNS server answers."])
    s.fields([("Host name", "internal.domain", True)])
    s.add(T(PAD, s.y + 10, "Record type", 10.5, 400, MID))
    s.y += 18
    half = (W - 2 * PAD) / 2
    s.add(R(PAD, s.y, half, 36, 18, fill=INK, stroke=INK))
    s.add(T(PAD + half / 2, s.y + 22.5, "TXT (preferred)", 13, 500, PAPER, "middle"))
    s.add(R(PAD + half, s.y, half, 36, 18, fill=PAPER, stroke=INK, sw=1.2))
    s.add(T(PAD + half * 1.5, s.y + 22.5, "A record", 13, 500, INK, "middle"))
    s.y += 36 + 12
    s.fields([("Expected TXT value", "True", True)])
    y = s.card(64, fill=FILL)
    s.add(T(PAD + 16, y + 24, "Latest lookup", 12, 400, MID))
    s.add(T(PAD + 16, y + 46, "TXT value matched · 18:22:51", 13, 500))
    s.add(chip(W - PAD - 16, y + 10, "Home", filled=True, anchor="end"))
    s.y += 8
    s.section("Reachability probe (optional)")
    s.note(["Not required. If set, Home also needs a TCP",
            "connect to this LAN address to succeed."])
    cw = W - 2 * PAD
    s.add(field(PAD, s.y, cw * 0.64, "Probe host", "Not set"))
    s.add(field(PAD + cw * 0.64 + 8, s.y, cw * 0.36 - 8, "Port", "—"))
    s.y += 58
    return s


def forwards():
    s = Screen("6 · Forwards")
    s.appbar("Forwards")
    y = s.card(52, dash="4 3")
    s.add(info_icon(PAD + 14, y + 17))
    s.add(T(PAD + 42, y + 23, "Any app on this phone can connect to", 12, 400))
    s.add(T(PAD + 42, y + 40, "an enabled forward.", 12, 400))
    for (name, mp, _), on in zip(FWD, [True, True, False]):
        y = s.y
        s.add(T(PAD, y + 25, name, 14.5, 500, INK if on else MID))
        s.add(T(PAD, y + 44, mp, 11, 400, MID, mono=True))
        s.add(switch(W - PAD - 44, y + 20, on))
        s.add(L(PAD, y + 62, W - PAD, y + 62, "#dadce0"))
        s.y = y + 62
    s.note(["", "Tap a row to edit or delete it."])
    # FAB
    s.add(R(W - PAD - 150, H - 80, 150, 52, 16, fill=INK, stroke=INK))
    s.add(T(W - PAD - 75, H - 48, "+  Add forward", 14, 500, PAPER, "middle"))
    return s


def edit_forward():
    s = Screen("7 · Edit forward")
    s.appbar("Edit forward", action="Save")
    s.fields([("Name", "Home Assistant")])
    s.fields([("Local port (on 127.0.0.1)", "8123", True)])
    cw = W - 2 * PAD
    s.section("Remote target")
    s.add(field(PAD, s.y, cw * 0.64, "IP address", "192.168.1.10", True))
    s.add(field(PAD + cw * 0.64 + 8, s.y, cw * 0.36 - 8, "Port", "8123", True))
    s.y += 58
    s.note(["An IP address, not a host name. The same address",
            "is used at home and through the tunnel."], 11.5)
    s.row("Enabled", on=True, h=52)
    s.y += 14
    s.add(button(PAD, s.y, 96, "Test"))
    s.add(T(PAD + 110, s.y + 16, "Connected through the tunnel", 12, 500))
    s.add(T(PAD + 110, s.y + 32, "in 85 ms", 12, 400, MID))
    s.y += 36 + 22
    s.add(T(PAD, s.y + 14, "Delete forward", 13.5, 500))
    return s


def proxy():
    s = Screen("8 · HTTP proxy")
    s.appbar("HTTP proxy", action="Save")
    s.row("Enable proxy", "HTTP and CONNECT on 127.0.0.1", on=True)
    s.y += 14
    s.fields([("Local port", "8888", True)])
    s.fields([("Username", "wireplug", True)])
    y = s.y
    s.fields([("Password (generated)", "••••••••••••••••")])
    s.add(copy_icon(W - PAD - 30, y + 16))
    s.y += 4
    s.note(["Reaches only destinations inside the tunnel's",
            "allowed IPs, in Home and Away mode alike."])
    return s


def behaviour():
    s = Screen("9 · Behaviour")
    s.appbar("Behaviour")
    s.row("Start at boot", "Only if the main switch was on", on=True, h=60)
    s.row("Notification options", "To be defined", trailing="chevron", h=60)
    s.row("Battery settings", "Opens the system battery settings", trailing="chevron", h=60)
    return s


def notification():
    s = Screen("10 · Notification")
    s.add(R(0, 28, W, H, 0, fill=FILL, stroke="none"))
    s.y = 44
    for state, detail in [("Away · tunnel up", "Handshake 12 s ago · 3 connections open"),
                          ("Home · direct", "Tunnel down · 0 connections open")]:
        y = s.card(104)
        s.add(R(PAD + 14, y + 14, 16, 16, 4, fill=INK, stroke=INK))
        s.add(T(PAD + 38, y + 26, "WirePlug · ongoing", 11, 400, MID))
        s.add(T(PAD + 14, y + 52, state, 14, 600))
        s.add(T(PAD + 14, y + 70, detail, 12, 400, MID))
        s.add(T(PAD + 14, y + 92, "STOP", 12, 600))
        s.y += 4
    s.note(["", "Two example states of the one ongoing",
            "notification. It updates on every change."])
    return s


SCREENS = [("01-status-away", status_away), ("02-status-home", status_home),
           ("03-settings", settings), ("04-tunnel", tunnel), ("05-home-network", home_network),
           ("06-forwards", forwards), ("07-edit-forward", edit_forward), ("08-proxy", proxy),
           ("09-behaviour", behaviour), ("10-notification", notification)]

os.makedirs(OUT, exist_ok=True)
built = []
for name, fn in SCREENS:
    scr = fn()
    built.append(scr)
    open(f"{OUT}/{name}.svg", "w").write(svg(panel(scr), CW, CH, "WirePlug wireframe: " + scr.caption))
    assert scr.y <= H, (name, scr.y)

cols = 5
rows = -(-len(built) // cols)
top = 70
inner = T(24, 44, "WirePlug — UI wireframes", 24, 600)
inner += T(24 + 330, 44, "Low-fidelity layout only; sample data, not final styling.", 13, 400, MID)
for i, scr in enumerate(built):
    inner += panel(scr, (i % cols) * CW, top + (i // cols) * CH)
open(f"{OUT}/00-overview.svg", "w").write(svg(inner, cols * CW, top + rows * CH, "WirePlug UI wireframes overview"))
print("ok", [(n, round(s.y)) for (n, _), s in zip(SCREENS, built)])
